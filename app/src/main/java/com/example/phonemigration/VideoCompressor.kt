package com.example.phonemigration

import android.content.ContentResolver
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

/**
 * 视频压缩：MediaCodec 硬解 -> Surface -> 硬编码(H.264) -> MediaMuxer 封装。
 * 目标：长边 <= 1280x720，码率 ~1.2Mbps，音频直接 copy 不重编码。
 * 单个视频硬超时 45 秒，超时/异常返回 false，由调用方回退复制原文件。
 */
object VideoCompressor {

    // 极速参数：360p + 低码率 + 单视频硬超时 2.5 秒，追求 1 分钟内完成
    private const val TARGET_BITRATE = 320_000
    private const val MAX_W = 640
    private const val MAX_H = 360
    private const val TIMEOUT_US = 1_200L
    private const val MAX_MS_PER_VIDEO = 2_000L

    fun compress(
        resolver: ContentResolver,
        uri: Uri,
        outFile: File,
        onProgress: (Long, Long) -> Unit,
        deep: Boolean = false
    ): Boolean {
        // deep：给超大视频用的单路深度压缩，低码率 + 长超时，压到分卷上限内
        val deadline = System.currentTimeMillis() + if (deep) 180_000L else MAX_MS_PER_VIDEO
        val targetBitrate = if (deep) 200_000 else TARGET_BITRATE
        val limitW = if (deep) 480 else MAX_W
        val limitH = if (deep) 270 else MAX_H
        val waitUs = if (deep) 5_000L else TIMEOUT_US
        var vPfd: ParcelFileDescriptor? = null
        var aPfd: ParcelFileDescriptor? = null
        var extractor: MediaExtractor? = null
        var aExtractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var surface: Surface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            vPfd = resolver.openFileDescriptor(uri, "r") ?: return false
            extractor = MediaExtractor().apply { setDataSource(vPfd!!.fileDescriptor) }

            var vi = -1
            var ai = -1
            for (i in 0 until extractor!!.trackCount) {
                val mime = extractor!!.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") && vi < 0) vi = i
                if (mime.startsWith("audio/") && ai < 0) ai = i
            }
            if (vi < 0) return false

            val vfmt = extractor!!.getTrackFormat(vi)
            val durationUs = if (vfmt.containsKey(MediaFormat.KEY_DURATION))
                vfmt.getLong(MediaFormat.KEY_DURATION) else 0L

            var width = vfmt.getInteger(MediaFormat.KEY_WIDTH)
            var height = vfmt.getInteger(MediaFormat.KEY_HEIGHT)
            if (width <= 0 || height <= 0) return false
            if (width > limitW || height > limitH) {
                val r = minOf(limitW.toFloat() / width, limitH.toFloat() / height)
                width = ((width * r).toInt() ushr 1) shl 1
                height = ((height * r).toInt() ushr 1) shl 1
                if (width < 2) width = 2
                if (height < 2) height = 2
            }
            val origBitrate = if (vfmt.containsKey(MediaFormat.KEY_BIT_RATE))
                vfmt.getInteger(MediaFormat.KEY_BIT_RATE) else 4_000_000
            val bitrate = minOf(origBitrate / 3, targetBitrate)
                .coerceAtLeast(if (deep) 80_000 else 250_000)
            val fps = if (vfmt.containsKey(MediaFormat.KEY_FRAME_RATE))
                vfmt.getInteger(MediaFormat.KEY_FRAME_RATE).coerceIn(1, 60) else 30
            val srcMime = vfmt.getString(MediaFormat.KEY_MIME)!!

            // 编码器（H.264）
            val encFmt = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder!!.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = encoder!!.createInputSurface()
            encoder!!.start()

            // 解码器 -> Surface（系统自动缩放与格式转换）
            decoder = MediaCodec.createDecoderByType(srcMime)
            decoder!!.configure(vfmt, surface, null, 0)
            decoder!!.start()
            extractor!!.selectTrack(vi)

            if (outFile.exists()) outFile.delete()
            muxer = MediaMuxer(outFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vTrack = muxer!!.addTrack(encoder!!.outputFormat)

            // 音频：独立 extractor，支持的格式直接 copy
            var aTrack = -1
            if (ai >= 0) {
                try {
                    aPfd = resolver.openFileDescriptor(uri, "r")
                    aExtractor = MediaExtractor().apply {
                        setDataSource(aPfd!!.fileDescriptor)
                        selectTrack(ai)
                    }
                    val afmt = aExtractor!!.getTrackFormat(ai)
                    val aMime = afmt.getString(MediaFormat.KEY_MIME) ?: ""
                    if (aMime.contains("mp4a") || aMime.contains("aac") ||
                        aMime.contains("amr") || aMime.contains("mpeg4")) {
                        aTrack = muxer!!.addTrack(afmt)
                    }
                } catch (_: Exception) {
                    aTrack = -1
                }
            }

            muxer!!.start()
            muxerStarted = true

            val info = MediaCodec.BufferInfo()
            var inputEos = false
            var decoderEos = false
            var videoDone = false
            var lastPts = 0L

            // 主循环：带硬超时，任何一路卡住都会被 deadline 兜住
            while (!videoDone && System.currentTimeMillis() < deadline) {
                // 1) 喂解码器
                if (!inputEos) {
                    val inIdx = decoder!!.dequeueInputBuffer(waitUs)
                    if (inIdx >= 0) {
                        val buf = decoder!!.getInputBuffer(inIdx) ?: break
                        val size = extractor!!.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder!!.queueInputBuffer(
                                inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEos = true
                        } else {
                            lastPts = extractor!!.sampleTime
                            decoder!!.queueInputBuffer(inIdx, 0, size, lastPts, 0)
                            extractor!!.advance()
                        }
                    }
                }

                // 2) 解码输出 -> 渲染到 Surface（触发编码）；检测 decoder EOS
                val di = decoder!!.dequeueOutputBuffer(info, waitUs)
                if (di >= 0) {
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    decoder!!.releaseOutputBuffer(di, true)
                    if (durationUs > 0) {
                        onProgress(minOf(lastPts, durationUs), durationUs)
                    }
                    if (eos && !decoderEos) {
                        decoderEos = true
                        encoder!!.signalEndOfInputStream()
                    }
                }

                // 3) 编码输出 -> muxer；检测 encoder EOS
                val oi = encoder!!.dequeueOutputBuffer(info, waitUs)
                if (oi >= 0) {
                    val buf = encoder!!.getOutputBuffer(oi)
                    if (buf != null && info.size > 0 &&
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        muxer!!.writeSampleData(vTrack, buf, info)
                    }
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    encoder!!.releaseOutputBuffer(oi, false)
                    if (eos) videoDone = true
                }
            }

            // 超时兜底：返回 false，调用方回退复制原文件
            if (!videoDone || System.currentTimeMillis() >= deadline) return false

            // 音频直接 copy
            if (aTrack >= 0 && aExtractor != null) {
                try {
                    val aBuf = ByteBuffer.allocateDirect(256 * 1024)
                    val aInfo = MediaCodec.BufferInfo()
                    while (System.currentTimeMillis() < deadline) {
                        val size = aExtractor!!.readSampleData(aBuf, 0)
                        if (size < 0) break
                        aInfo.set(0, size, aExtractor!!.sampleTime, 0)
                        muxer!!.writeSampleData(aTrack, aBuf, aInfo)
                        aExtractor!!.advance()
                    }
                } catch (_: Exception) {
                    // 音频 copy 失败保留无声视频
                }
            }

            onProgress(durationUs, durationUs)
            return outFile.exists() && outFile.length() > 0
        } catch (_: Exception) {
            return false
        } finally {
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
            try { surface?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
            try { aExtractor?.release() } catch (_: Exception) {}
            try { vPfd?.close() } catch (_: Exception) {}
            try { aPfd?.close() } catch (_: Exception) {}
            try { if (outFile.exists() && outFile.length() == 0L) outFile.delete() } catch (_: Exception) {}
        }
    }
}
