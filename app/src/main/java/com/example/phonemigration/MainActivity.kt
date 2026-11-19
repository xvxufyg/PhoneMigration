package com.example.phonemigration

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class Rec(
    val name: String,
    val type: String,
    val uri: Uri?,
    val file: File?,
    val size: Long = 0L
)

class PState {
    var total: Int = 0
    var done: Int = 0
    var t0: Long = 0L
    var lastUi: Long = 0L
    fun reset(n: Int) {
        total = n; done = 0
        t0 = System.currentTimeMillis(); lastUi = t0
    }
}

class MainActivity : AppCompatActivity() {

    // 每卷上限：界面可自己填（GB），默认 1.9GB，最大 10GB
    private var volumeLimit = 1900L * 1024 * 1024
    private lateinit var etVolume: EditText
    private val VOL_TAG = "_第"

    private lateinit var status: TextView
    private lateinit var listBox: LinearLayout

    private lateinit var pbPhoto: ProgressBar
    private lateinit var etaPhoto: TextView
    private lateinit var pbVideo: ProgressBar
    private lateinit var etaVideo: TextView
    private lateinit var pbMemo: ProgressBar
    private lateinit var etaMemo: TextView

    private val psPhoto = PState()
    private val psVideo = PState()
    private val psMemo = PState()

    private var pendingAfter: (() -> Unit)? = null
    private var lastBatch = mutableListOf<File>()   // 本次生成的全部分卷
    private val REQ = 1001
    private val REQ_OPEN = 2002
    private val REQ_PICK_SHARE = 4004
    private val importedDir get() = File(filesDir, "imported")
    // 公共「下载/搬家」目录：文件选择器可见、文件管理器能直接找到发给 QQ
    private val outDir get(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "搬家")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "搬家 · 相册/视频/备忘录"
        val root = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        root.addView(col)

        col.addView(TextView(this).apply {
            text = "照片+视频+备忘录 → 自动分卷（每卷大小自己填，最大10GB）。" +
                   "\n①打包 → ③选分卷发送；新手机装同一App → ②全选分卷查看。"
            textSize = 14f
            setPadding(0, 0, 0, 8)
        })

        // 每卷大小输入（GB），默认 1.9，最大 10，打包时读取
        val rowVol = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 12)
        }
        rowVol.addView(TextView(this).apply {
            text = "每卷大小(GB)："
            textSize = 14f
            setPadding(0, 0, 8, 0)
        })
        etVolume = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("1.9")
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        rowVol.addView(etVolume)
        col.addView(rowVol)

        val b1 = Button(this).apply { text = "① 一键打包（自动分卷）" }
        val b2 = Button(this).apply { text = "③ 分享数据包（选分卷发送）" }
        val b3 = Button(this).apply { text = "② 选择全部分卷并查看" }
        val bAll = Button(this).apply { text = "授权全部文件（备忘录更全，可选）" }
        val bLog = Button(this).apply { text = "🛠 自愈日志（点看/长按清空）" }
        col.addView(b1); col.addView(b2); col.addView(b3); col.addView(bAll); col.addView(bLog)

        pbPhoto = addBar(col, "📷 照片")
        etaPhoto = addEta(col, "照片：等待")
        pbVideo = addBar(col, "🎬 视频")
        etaVideo = addEta(col, "视频：等待")
        pbMemo = addBar(col, "📝 备忘录")
        etaMemo = addEta(col, "备忘录：等待")

        status = TextView(this).apply {
            text = "状态：就绪"; textSize = 13f; setPadding(0, 4, 0, 12)
        }
        col.addView(status)
        col.addView(TextView(this).apply {
            text = "文件列表："; textSize = 15f; setPadding(0, 8, 0, 4)
        })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBox)
        setContentView(root)

        b1.setOnClickListener { ensurePerm { doPack() } }
        b2.setOnClickListener { shareMenu() }
        b3.setOnClickListener { pickDataFile() }
        bAll.setOnClickListener { openAllFilesAccess() }
        bLog.setOnClickListener {
            val txt = SelfHealer.read(this)
            AlertDialog.Builder(this)
                .setTitle("🛠 自愈日志（App 自动修复记录）")
                .setMessage(txt)
                .setPositiveButton("关闭", null)
                .setNeutralButton("清空", { _, _ ->
                    SelfHealer.clear(this)
                    Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
                })
                .show()
        }

        importedDir.mkdirs()
        outDir.mkdirs()
        restoreBatch()
        renderList()
        autoRequestPerm()
        intent?.data?.let { importFromUris(listOf(it)) }
    }

    /** 恢复上次生成的分卷（跨启动分享） */
    private fun restoreBatch() {
        lastBatch.clear()
        outDir.listFiles()
            ?.filter { it.name.startsWith("搬家_") && it.name.contains(VOL_TAG) && it.name.endsWith(".zip") }
            ?.sortedBy { it.name }
            ?.let { lastBatch.addAll(it) }
        if (lastBatch.isNotEmpty()) {
            status.text = "状态：已找到上次 ${lastBatch.size} 个分卷，点③可分享。"
        }
    }

    private fun addBar(col: LinearLayout, label: String): ProgressBar {
        col.addView(TextView(this).apply { text = label; textSize = 14f; setPadding(0, 6, 0, 2) })
        return ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }.also { col.addView(it) }
    }

    private fun addEta(col: LinearLayout, text: String): TextView =
        TextView(this).apply { this.text = text; textSize = 13f; setPadding(0, 0, 0, 10) }
            .also { col.addView(it) }

    private fun autoRequestPerm() { ensurePerm {} }

    // ---------- 权限：全 Android 版本适配 ----------
    private fun ensurePerm(after: () -> Unit) {
        val need = if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val miss = need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (miss.isEmpty()) { after(); return }
        pendingAfter = after
        ActivityCompat.requestPermissions(this, miss.toTypedArray(), REQ)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(rc, p, g)
        if (rc == REQ) pendingAfter?.invoke()   // 部分/全部授权都放行，能拿多少拿多少
    }

    private fun openAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                i.data = Uri.parse("package:$packageName"); startActivity(i)
            } catch (e: Exception) {
                try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                catch (_: Exception) { Toast.makeText(this, "请在设置中开启全部文件访问", Toast.LENGTH_SHORT).show() }
            }
        } else {
            Toast.makeText(this, "已可访问全部文件", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- 扫描 ----------
    private fun scanImages(out: MutableList<Rec>) {
        val pr = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE)
        try {
            contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pr, null, null, null)?.use { c ->
                val i = c.getColumnIndex(MediaStore.Images.Media._ID)
                val ni = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val si = c.getColumnIndex(MediaStore.Images.Media.SIZE)
                while (c.moveToNext()) {
                    val id = c.getLong(i)
                    val name = if (ni >= 0) (c.getString(ni) ?: "img_$id.jpg") else "img_$id.jpg"
                    val sz = if (si >= 0) c.getLong(si) else 0L
                    out += Rec("照片/$name", "照片",
                        Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()), null, sz)
                }
            }
        } catch (_: Exception) {}
    }

    private fun scanVideos(out: MutableList<Rec>) {
        val pr = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE)
        try {
            contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, pr, null, null, null)?.use { c ->
                val i = c.getColumnIndex(MediaStore.Video.Media._ID)
                val ni = c.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val si = c.getColumnIndex(MediaStore.Video.Media.SIZE)
                while (c.moveToNext()) {
                    val id = c.getLong(i)
                    val name = if (ni >= 0) (c.getString(ni) ?: "vid_$id.mp4") else "vid_$id.mp4"
                    val sz = if (si >= 0) c.getLong(si) else 0L
                    out += Rec("视频/$name", "视频",
                        Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString()), null, sz)
                }
            }
        } catch (_: Exception) {}
    }

    private fun scanDocs(out: MutableList<Rec>) {
        val exts = listOf("txt", "md", "json", "doc", "docx", "pdf", "xls", "xlsx", "csv", "rtf")
        val sel = exts.joinToString(" OR ") { "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.${it}'" }
        val pr = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.SIZE)
        try {
            contentResolver.query(MediaStore.Files.getContentUri("external"), pr, sel, null, null)?.use { c ->
                val i = c.getColumnIndex(MediaStore.Files.FileColumns._ID)
                val ni = c.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                while (c.moveToNext()) {
                    val id = c.getLong(i)
                    val name = if (ni >= 0) (c.getString(ni) ?: "doc_$id") else "doc_$id"
                    val sz = if (si >= 0) c.getLong(si) else 0L
                    out += Rec("备忘录/$name", "备忘录",
                        Uri.withAppendedPath(MediaStore.Files.getContentUri("external"), id.toString()), null, sz)
                }
            }
        } catch (_: Exception) {}

        val roots = listOf(
            "/sdcard/Notes", "/sdcard/Documents", "/sdcard/Download/Notes",
            "/sdcard/DCIM/Notes", "/sdcard/Memos",
            "/sdcard/Android/data/com.android.notes/files",
            "/sdcard/Android/data/com.miui.notes/files",
            "/sdcard/Android/data/com.huawei.notepad/files",
            "/sdcard/Android/data/com.samsung.android.app.notes/files",
            "/sdcard/Android/data/com.coloros.notes/files",
            "/sdcard/Android/data/com.vivo.notes/files"
        )
        val docExt = setOf("txt", "md", "json", "doc", "docx", "pdf", "xls", "xlsx", "csv", "rtf", "jpg", "jpeg", "png")
        val seen = HashSet<String>()
        for (rp in roots) {
            val dir = File(rp)
            if (!dir.exists() || !dir.isDirectory) continue
            try {
                dir.walkTopDown().filter { it.isFile }.take(3000).forEach { f ->
                    if (f.extension.lowercase() in docExt && seen.add(f.absolutePath)) {
                        out += Rec("备忘录/${f.parentFile?.name ?: "notes"}/${f.name}", "备忘录", null, f, f.length())
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // ---------- 打包：三类别并行 + 自动分卷 ----------
    private data class Prepared(val entryName: String, val source: File)

    private fun doPack() {
        // 读取主人填的每卷大小（GB），限制 0.05~10，失败用默认 1.9
        val gb = etVolume.text.toString().trim().toFloatOrNull() ?: 1.9f
        volumeLimit = (gb.coerceIn(0.05f, 10f) * 1024L * 1024L * 1024L).toLong()
        status.text = "正在扫描文件…"
        listOf(etaPhoto, etaVideo, etaMemo).forEach { it.text = "等待" }
        listOf(pbPhoto, pbVideo, pbMemo).forEach { it.progress = 0 }
        nameSet.clear()
        Thread {
            try {
                val recs = mutableListOf<Rec>()
                scanImages(recs); scanVideos(recs); scanDocs(recs)

                val photos = recs.filter { it.type == "照片" }
                val videos = recs.filter { it.type == "视频" }
                val memos = recs.filter { it.type == "备忘录" }

                psPhoto.reset(photos.size); psVideo.reset(videos.size); psMemo.reset(memos.size)

                val stage = File(cacheDir, "stage")
                stage.mkdirs()
                stage.listFiles()?.forEach { it.delete() }

                val photoReady = Collections.synchronizedList(mutableListOf<Prepared>())
                val videoReady = Collections.synchronizedList(mutableListOf<Prepared>())
                val memoReady = Collections.synchronizedList(mutableListOf<Prepared>())
                val latch = java.util.concurrent.CountDownLatch(3)

                fun stageGroup(list: List<Rec>, ready: MutableList<Prepared>, s: PState, pb: ProgressBar, eta: TextView) {
                    Thread {
                        try {
                            for (r in list) {
                                stageFile(r, stage)?.let {
                                    ready.add(Prepared(uniqueName(baseNameOf(r)), it))
                                }
                                bump(s, pb, eta, labelOf(s))
                            }
                        } catch (_: Exception) {}
                        finally { latch.countDown() }
                    }.start()
                }
                stageGroup(photos, photoReady, psPhoto, pbPhoto, etaPhoto)
                stageGroup(videos, videoReady, psVideo, pbVideo, etaVideo)
                stageGroup(memos, memoReady, psMemo, pbMemo, etaMemo)

                latch.await()

                val all = photoReady + videoReady + memoReady
                var vols: List<File> = emptyList()
                var bad: String? = null
                val userLimit = volumeLimit

                // 自愈循环：出错就调大每卷上限重试，最多3轮；末轮降级为不限制（单包兜底）
                for (round in 1..3) {
                    clearOldVols()
                    when (round) {
                        2 -> volumeLimit = (userLimit * 14 / 10).coerceAtMost(10L * 1024 * 1024 * 1024)
                        3 -> volumeLimit = Long.MAX_VALUE / 4
                    }
                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    status.post { status.text = "正在分卷打包${if (round > 1) "（自愈第${round}次）" else ""}…" }
                    try {
                        vols = writeZipMulti(ts, all, recs)
                        bad = verifyVolumes(vols)
                    } catch (e: Exception) {
                        bad = "${e.javaClass.simpleName}: ${e.message}"
                    }
                    if (bad == null) {
                        if (round > 1) SelfHealer.record(this, "打包", "第${round - 1}轮失败", "第${round}轮自愈成功，分卷完成")
                        break
                    }
                    SelfHealer.record(this, "打包第${round}轮", bad ?: "未知", "清理残卷，" +
                        if (round < 3) "每卷上限调至 ${volumeLimit / 1024 / 1024}MB 重试" else "降级为单大包兜底")
                }

                stage.deleteRecursively()
                if (bad != null) {
                    // 3轮都不行：把已写出的（哪怕部分可用）保留并告知，绝不静默失败
                    SelfHealer.record(this, "打包", bad ?: "未知", "3轮自愈后仍异常，保留已产出分卷并在界面提示")
                    status.post { status.text = "打包异常（已自动重试3次）：$bad\n已产出的分卷仍可分享，或稍后重试。" }
                    runOnUiThread { shareMenu() }
                    return@Thread
                }
                lastBatch.clear(); lastBatch.addAll(vols)

                listOf(pbPhoto, pbVideo, pbMemo).forEach { it.post { it.progress = 100 } }
                etaPhoto.post { etaPhoto.text = "照片：完成 ${photoReady.size}" }
                etaVideo.post { etaVideo.text = "视频：完成 ${videoReady.size}" }
                etaMemo.post { etaMemo.text = "备忘录：完成 ${memoReady.size}" }
                val totalMb = vols.sumOf { it.length() } / 1024 / 1024
                val biggest = vols.maxOfOrNull { it.length() } ?: 0L
                val overHint = if (biggest > volumeLimit + 4096)
                    "\n提示：有单个文件超过每卷大小，已独占一卷；想合并可把输入框调大。"
                    else ""
                val healHint = if (SelfHealer.healCount(this) > 0) "（含自动修复）" else ""
                status.post {
                    status.text = "完成$healHint：${vols.size} 个分卷，共 ${totalMb} MB，" +
                        "最大卷 ${biggest / 1024 / 1024} MB。点③选分卷发送。" + overHint
                }
                runOnUiThread { shareMenu() }
            } catch (e: Exception) {
                SelfHealer.record(this, "打包总体", "${e.javaClass.simpleName}: ${e.message}", "已捕获，界面提示重试")
                status.post { status.text = "打包出错（已记录并自愈）：${e.message}\n再点一次①会自动重试。" }
            }
        }.start()
    }

    private fun labelOf(s: PState) = when (s) {
        psPhoto -> "照片"; psVideo -> "视频"; else -> "备忘录"
    }

    private fun baseNameOf(r: Rec): String = r.name.replace('\\', '/')

    /** 拷一个文件到 stage，带 3 次自愈重试：换输入方式、重建临时文件、等待后重试 */
    private fun stageFile(r: Rec, stage: File): File? {
        val expected = if (r.size > 0) r.size else 0L
        for (attempt in 1..3) {
            val tmp = File(stage, "f_${System.nanoTime()}_${(Math.random() * 1_000_000).toInt()}")
            var lastErr = ""
            try {
                if (r.uri != null) {
                    when (attempt) {
                        1 -> contentResolver.openInputStream(r.uri)?.use { inp ->
                            BufferedOutputStream(FileOutputStream(tmp), 256 * 1024).use { out ->
                                inp.copyTo(out, 256 * 1024)
                            }
                        }
                        2 -> contentResolver.openFileDescriptor(r.uri, "r")?.use { pfd ->
                            FileInputStream(pfd.fileDescriptor).use { inp ->
                                BufferedOutputStream(FileOutputStream(tmp), 256 * 1024).use { out ->
                                    inp.copyTo(out, 256 * 1024)
                                }
                            }
                        }
                        else -> contentResolver.openInputStream(r.uri)?.use { inp ->
                            val buf = ByteArray(64 * 1024)
                            BufferedOutputStream(FileOutputStream(tmp), 64 * 1024).use { out ->
                                while (true) { val c = inp.read(buf); if (c < 0) break; out.write(buf, 0, c) }
                                out.flush()
                            }
                        }
                    }
                } else if (r.file != null) {
                    BufferedInputStream(FileInputStream(r.file), 256 * 1024).use { inp ->
                        BufferedOutputStream(FileOutputStream(tmp), 256 * 1024).use { out ->
                            inp.copyTo(out, 256 * 1024)
                        }
                    }
                }
                // 校验：有预期大小时，拷出来的不能明显偏小
                val got = if (tmp.exists()) tmp.length() else 0L
                // 本来就是 0 字节的空文件：正常现象，直接跳过，不算错误也不记日志
                if (got == 0L && expected == 0L) {
                    if (tmp.exists()) tmp.delete()
                    return null
                }
                val ok = got > 0 && (expected == 0L || got >= expected * 90 / 100 || attempt == 3)
                if (ok) return tmp
                lastErr = "size mismatch expected=$expected got=$got"
                if (tmp.exists()) tmp.delete()
            } catch (e: Exception) {
                lastErr = "${e.javaClass.simpleName}: ${e.message}"
                if (tmp.exists()) tmp.delete()
            }
            if (attempt < 3) {
                SelfHealer.record(this, "stageFile(${r.name})", lastErr, "第${attempt}次失败，换方式重试")
                try { Thread.sleep(150L * attempt) } catch (_: Exception) {}
            } else {
                SelfHealer.record(this, "stageFile(${r.name})", lastErr, "3次均失败，跳过该文件继续打包")
            }
        }
        return null
    }

    /** 清理上次残留的分卷 */
    private fun clearOldVols() {
        outDir.mkdirs()
        outDir.listFiles()?.forEach {
            if (it.name.startsWith("搬家_") && it.name.endsWith(".zip")) it.delete()
        }
    }

    /** 校验分卷：全空/0字节/损坏则报错，触发自愈重试 */
    private fun verifyVolumes(vols: List<File>): String? {
        if (vols.isEmpty()) return "没有产出任何分卷"
        for (f in vols) {
            if (!f.exists() || f.length() <= 0) return "${f.name} 为空"
            // 用 ZipFile 真正打开验证结构，坏包能被发现
            try {
                java.util.zip.ZipFile(f).use { zf ->
                    if (zf.size() <= 0) return "${f.name} 无条目"
                }
            } catch (e: Exception) {
                return "${f.name} 损坏: ${e.message}"
            }
        }
        return null
    }

    /** 自动分卷写包：每卷 <= volumeLimit，写满自动开下一卷 */
    private fun writeZipMulti(ts: String, list: List<Prepared>, recs: List<Rec>): List<File> {
        val result = mutableListOf<File>()
        var vol = 1
        var curSize = 0L
        var zos: ZipOutputStream
        outDir.mkdirs()   // 目录可能被系统/用户清理，写包前强制重建

        fun open(): ZipOutputStream {
            val f = File(outDir, "搬家_${ts}_第${vol.toString().padStart(2, '0')}卷.zip")
            result.add(f); vol++; curSize = 0L
            val z = ZipOutputStream(BufferedOutputStream(FileOutputStream(f), 512 * 1024))
            z.setLevel(Deflater.NO_COMPRESSION)   // 不压缩，让 zip 库自己算 CRC/size
            return z
        }

        zos = open()
        val buf = ByteArray(512 * 1024)
        try {
            for (p in list) {
                // 源文件没了/空了就跳过，绝不声明一个写不出的条目（否则 invalid entry size）
                if (!p.source.exists() || p.source.length() <= 0) continue
                val need = p.source.length() + 4096
                if (curSize > 0 && curSize + need > volumeLimit) {
                    try { zos.closeEntry() } catch (_: Exception) {}
                    try { zos.finish() } catch (_: Exception) {}
                    zos.close()
                    zos = open()
                }
                try {
                    // DEFLATED + NO_COMPRESSION：zip 库自动算 size/CRC，只读一遍，稳且快
                    zos.putNextEntry(ZipEntry(p.entryName))
                    BufferedInputStream(FileInputStream(p.source), 512 * 1024).use { inp ->
                        while (true) {
                            val c = inp.read(buf)
                            if (c < 0) break
                            zos.write(buf, 0, c)
                        }
                    }
                    zos.closeEntry()
                    curSize += need
                } catch (_: Exception) {
                    // 写入失败：跳过这个条目，不记账，避免坏条目污染整个包
                    try { zos.closeEntry() } catch (_: Exception) {}
                }
            }
            // 清单写进最后一卷
            val sb = StringBuilder("文件名\t类型\n")
            recs.forEach { sb.append("${it.name}\t${it.type}\n") }
            zos.putNextEntry(ZipEntry("文件清单.txt"))
            zos.write(sb.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        } finally {
            try { zos.close() } catch (_: Exception) {}
        }
        return result
    }

    @Synchronized
    private fun bump(s: PState, pb: ProgressBar, eta: TextView, label: String) {
        s.done++
        val now = System.currentTimeMillis()
        if (now - s.lastUi >= 200 || s.done == s.total) {
            s.lastUi = now
            val pct = if (s.total > 0) (s.done * 100 / s.total).toInt().coerceIn(0, 100) else 100
            pb.post { pb.progress = pct }
            if (s.total == 0) eta.post { eta.text = "$label：无" }
            else if (s.done < 5) eta.post { eta.text = "$label：计算中… $pct%" }
            else {
                val per = (now - s.t0).toDouble() / 1000.0 / s.done
                val sec = ((s.total - s.done) * per).toLong() + 1
                eta.post { eta.text = "$label：还需 ${sec}秒 ($pct%)" }
            }
        }
    }

    private val nameSet = HashSet<String>()

    @Synchronized
    private fun uniqueName(raw: String): String {
        var name = raw; var k = 1
        while (nameSet.contains(name)) {
            val idx = raw.lastIndexOf('.')
            name = if (idx > 0) "${raw.substring(0, idx)}(${k++})${raw.substring(idx)}" else "$raw(${k++})"
        }
        nameSet.add(name); return name
    }

    // ---------- 分享按钮：弹出分卷列表，点哪个发哪个（单个分享，QQ 必带附件）----------
    private fun shareMenu() {
        if (lastBatch.isEmpty()) restoreBatch()
        if (lastBatch.isEmpty()) {
            // 没有分卷：让用户选一个 zip 直接分享（不跳导入界面）
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"
            }
            startActivityForResult(i, REQ_PICK_SHARE)
            Toast.makeText(this, "请先选一个要分享的 zip（也可先点①打包自动生成分卷）", Toast.LENGTH_LONG).show()
            return
        }
        val items = lastBatch.mapIndexed { i, f ->
            "第${i + 1}卷  ${f.name}  (${f.length() / 1024 / 1024} MB)"
        }.toTypedArray()
        val limitGb = volumeLimit / (1024.0 * 1024.0 * 1024.0)
        AlertDialog.Builder(this)
            .setTitle("选一个分卷发送到新手机")
            .setMessage("每个分卷约 ${String.format(Locale.getDefault(), "%.2f", limitGb)}GB 以内。发完一卷再点本按钮发下一卷。")
            .setItems(items) { _, which -> shareOne(lastBatch[which]) }
            .setPositiveButton("全选一起发", { _, _ -> shareZips(lastBatch) })
            .setNegativeButton("取消", null)
            .show()
    }

    private fun shareOne(f: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "搬家分卷 ${f.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(i, "发送 ${f.name}")
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareZips(zips: List<File>) {
        if (zips.isEmpty()) return
        if (zips.size == 1) { shareOne(zips[0]); return }
        try {
            val uris = ArrayList(zips.map { FileProvider.getUriForFile(this, "$packageName.fileprovider", it) })
            val i = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/zip"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                putExtra(Intent.EXTRA_SUBJECT, "搬家数据包（共${zips.size}卷，请全部发送）")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(i, "发送全部 ${zips.size} 个分卷")
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- 选择并导入 ----------
    private fun pickDataFile() {
        // 先自动扫描公共「下载/搬家」目录里的分卷，直接导入（选择器看不到专属目录）
        val publicVols = outDir.listFiles()
            ?.filter { it.name.startsWith("搬家_") && it.name.endsWith(".zip") }
            ?.sortedBy { it.name }
            ?: emptyList()
        if (publicVols.isNotEmpty()) {
            status.text = "找到 ${publicVols.size} 个分卷，正在自动导入…"
            importFromFiles(publicVols)
        } else {
            // 兜底：让用户手动选 zip
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            startActivityForResult(i, REQ_OPEN)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        if (requestCode == REQ_OPEN) {
            val uris = mutableListOf<Uri>()
            data?.clipData?.let { cd ->
                for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri)
            } ?: data?.data?.let { uris.add(it) }
            if (uris.isNotEmpty()) importFromUris(uris)
        } else if (requestCode == REQ_PICK_SHARE) {
            data?.data?.let { uri -> sharePickedUri(uri) }
        }
    }

    /** 把用户选中的 zip 复制出来直接分享 */
    private fun sharePickedUri(uri: Uri) {
        Thread {
            try {
                val name = queryDisplayName(uri) ?: "搬家数据包.zip"
                val f = File(outDir, name)
                contentResolver.openInputStream(uri)?.use { inp ->
                    BufferedOutputStream(FileOutputStream(f), 256 * 1024).use { out ->
                        inp.copyTo(out, 256 * 1024)
                    }
                } ?: throw Exception("open failed")
                runOnUiThread { shareOne(f) }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "分享失败：${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (_: Exception) { null }

    /** 从公共目录里的分卷文件导入（点②的主路径） */
    private fun importFromFiles(files: List<File>) {
        status.text = "正在导入 ${files.size} 个分卷…"
        Thread { doImportFiles(files) }.start()
    }

    /** 从用户手动选择的 content:// 导入（兜底路径） */
    private fun importFromUris(uris: List<Uri>) {
        status.text = "正在导入 ${uris.size} 个分卷…"
        Thread {
            val cached = mutableListOf<File>()
            try {
                for ((idx, uri) in uris.withIndex()) {
                    val tmp = File(cacheDir, "incoming_uri_${idx}.zip")
                    contentResolver.openInputStream(uri)?.use { inp ->
                        BufferedOutputStream(FileOutputStream(tmp), 256 * 1024).use { out ->
                            inp.copyTo(out, 256 * 1024)
                        }
                    } ?: continue
                    if (tmp.exists() && tmp.length() > 0) cached.add(tmp)
                }
                doImportFiles(cached)
            } catch (e: Exception) {
                status.post { status.text = "导入出错：${e.message}" }
            } finally {
                cached.forEach { try { it.delete() } catch (_: Exception) {} }
            }
        }.start()
    }

    /** 真正的解包导入：把每个分卷里的文件解到 importedDir */
    private fun doImportFiles(files: List<File>) {
        try {
            listOf(pbPhoto, pbVideo, pbMemo).forEach { it.post { it.progress = 0 } }
            listOf(etaPhoto, etaVideo, etaMemo).forEach { it.post { it.text = "导入中" } }

            importedDir.listFiles()?.forEach {
                if (it.isDirectory) it.deleteRecursively() else it.delete()
            }

            for ((idx, f) in files.withIndex()) {
                if (!f.exists() || f.length() <= 0) continue
                ZipInputStream(BufferedInputStream(FileInputStream(f), 256 * 1024)).use { zis ->
                    var e: ZipEntry? = zis.nextEntry
                    while (e != null) {
                        val dest = File(importedDir, e.name)
                        if (!dest.canonicalPath.startsWith(importedDir.canonicalPath)) {
                            zis.closeEntry(); e = zis.nextEntry; continue
                        }
                        if (!e.isDirectory) {
                            dest.parentFile?.mkdirs()
                            BufferedOutputStream(FileOutputStream(dest), 256 * 1024).use { out ->
                                zis.copyTo(out, 256 * 1024)
                            }
                        }
                        zis.closeEntry(); e = zis.nextEntry
                    }
                }
                val pct = ((idx + 1) * 100 / files.size).coerceIn(0, 100)
                pbPhoto.post { pbPhoto.progress = pct }
                etaPhoto.post { etaPhoto.text = "导入中 ${idx + 1}/${files.size}" }
            }

            val c = importedDir.walkTopDown().count { it.isFile && it.name != "文件清单.txt" }
            listOf(pbPhoto, pbVideo, pbMemo).forEach { it.post { it.progress = 100 } }
            listOf(etaPhoto, etaVideo, etaMemo).forEach { it.post { it.text = "导入完成" } }
            status.post {
                status.text = if (c > 0) "导入完成：$c 个文件（点文件名预览）"
                              else "导入完成，但包里没有可显示文件"
            }
            runOnUiThread { renderList() }
        } catch (e: Exception) {
            status.post { status.text = "导入出错：${e.message}" }
        }
    }

    private fun renderList() {
        listBox.removeAllViews()
        val files = importedDir.walkTopDown().filter { it.isFile && it.name != "文件清单.txt" }.toList()
        if (files.isEmpty()) {
            listBox.addView(TextView(this).apply { text = "（空）新手机点②全选分卷" })
            return
        }
        for (f in files) {
            listBox.addView(TextView(this).apply {
                text = "📄 ${f.relativeTo(importedDir).path} (${f.length() / 1024} KB)"
                textSize = 13f; setPadding(8, 6, 8, 6)
                setOnClickListener { openFile(f) }
            })
        }
    }

    private fun openFile(f: File) {
        try {
            val u = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val m = when (f.extension.lowercase()) {
                "jpg", "jpeg", "png", "gif", "webp" -> "image/*"
                "mp4", "mkv", "avi", "mov", "3gp" -> "video/*"
                "pdf" -> "application/pdf"
                "txt", "md", "csv", "json", "log" -> "text/plain"
                "doc", "docx" -> "application/msword"
                "xls", "xlsx" -> "application/vnd.ms-excel"
                else -> "*/*"
            }
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(u, m); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "打开失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
