package local.checklist

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 手机上没有 logcat 时唯一的诊断渠道。
 * 写入 filesDir/app.log，超过 1 MB 轮转一份，`/api/status` 读尾部。
 * 任何写日志的失败都必须被吞掉——日志不能反过来把主流程搞挂。
 */
object AppLog {
    private const val MAX_BYTES = 1_000_000L

    @Volatile
    private var logFile: File? = null

    fun init(context: Context) {
        if (logFile == null) {
            logFile = File(context.filesDir, "app.log")
        }
    }

    @Synchronized
    fun w(tag: String, message: String) {
        val f = logFile ?: return
        try {
            if (f.length() > MAX_BYTES) {
                val old = File(f.parentFile, "app.1.log")
                if (old.exists()) old.delete()
                f.renameTo(old)
            }
            val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText("$ts [$tag] $message\n")
        } catch (_: Exception) {
            // 故意忽略
        }
    }

    fun tail(maxLines: Int = 40): List<String> {
        val f = logFile ?: return emptyList()
        if (!f.exists()) return emptyList()
        return try {
            val lines = f.readLines()
            if (lines.size <= maxLines) lines else lines.subList(lines.size - maxLines, lines.size)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 仅供诊断：最近一次异常的可读形式，避免把整个堆栈塞进通知。 */
    fun brief(t: Throwable): String = "${t.javaClass.simpleName}: ${t.message ?: ""}"

    fun reset() {
        try {
            logFile?.delete()
        } catch (_: Exception) {
        }
    }
}
