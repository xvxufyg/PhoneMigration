package com.example.phonemigration

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自愈引擎：记录错误与自动修复动作，落盘到 App 内部目录。
 * 打包出错时自动重试/调参/降级，全过程写日志，主人可在界面点「自愈日志」查看。
 */
object SelfHealer {
    private const val LOG_NAME = "selfheal.log"
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    private fun logFile(ctx: Context) = File(ctx.filesDir, LOG_NAME)

    @Synchronized
    fun record(ctx: Context, where: String, error: String, action: String) {
        try {
            val line = "${fmt.format(Date())} | $where | 错误:$error | 自愈:$action\n"
            logFile(ctx).appendText(line, Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    @Synchronized
    fun read(ctx: Context): String = try {
        val f = logFile(ctx)
        if (f.exists() && f.length() > 0) f.readText(Charsets.UTF_8).takeLast(5000)
        else "（暂无自愈记录，说明一路顺利喵~）"
    } catch (_: Exception) { "（读取日志失败）" }

    @Synchronized
    fun clear(ctx: Context) {
        try { logFile(ctx).delete() } catch (_: Exception) {}
    }

    @Synchronized
    fun healCount(ctx: Context): Int = try {
        logFile(ctx).readLines(Charsets.UTF_8).count { it.contains("自愈:") }
    } catch (_: Exception) { 0 }
}
