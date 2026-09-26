package com.readboy.otadownloader

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 自带日志系统
 *
 * 特性：
 * - 三个输出：Logcat / 内存环形缓冲（供界面实时查看）/ 按天滚动的文件
 * - 文件位置：应用外部私有目录 logs/ota-yyyyMMdd.log，自动清理超出保留数量的旧文件
 * - 全局崩溃捕获：未捕获异常写入 last_crash.txt，下次启动自动加载到日志
 * - 全程不抛异常（日志失败仅退回 Logcat）
 */
object AppLogger {

    private const val LOGCAT_TAG = "ZaralynOTA"
    private const val MAX_MEMORY_LINES = 1500
    private const val MAX_LOG_FILES = 10
    private const val MAX_LOG_FILE_BYTES = 2 * 1024 * 1024L

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val dateFmt = SimpleDateFormat("yyyyMMdd", Locale.US)

    private val lock = Any()
    private val memory = ArrayDeque<String>()

    @Volatile
    private var baseDir: File? = null

    @Volatile
    private var logDir: File? = null

    @Volatile
    private var crashFile: File? = null

    @Volatile
    private var initialized = false

    // ==================== 初始化 ====================

    fun init(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            baseDir = base
            logDir = File(base, "logs").also { if (!it.exists()) it.mkdirs() }
            crashFile = File(base, "last_crash.txt")
            initialized = true
            cleanOldLogs()
        }

        installCrashHandler()

        i("======== 应用启动 ========")
        i("日志目录: ${logDir?.absolutePath}")
        i("设备: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        i("APK 版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")

        lastCrash()?.let {
            e("检测到上次崩溃记录:\n$it")
            markCrashRead()
        }
    }

    fun logDirectory(): File? = logDir

    fun baseDirectory(): File? = baseDir

    // ==================== 记录 API ====================

    fun d(msg: String) = write('D', LOGCAT_TAG, msg, null)

    fun i(msg: String) = write('I', LOGCAT_TAG, msg, null)

    fun w(msg: String) = write('W', LOGCAT_TAG, msg, null)

    fun e(msg: String, throwable: Throwable? = null) = write('E', LOGCAT_TAG, msg, throwable)

    fun d(tag: String, msg: String) = write('D', tag, msg, null)

    fun i(tag: String, msg: String) = write('I', tag, msg, null)

    fun w(tag: String, msg: String) = write('W', tag, msg, null)

    fun w(tag: String, msg: String, throwable: Throwable?) = write('W', tag, msg, throwable)

    fun e(tag: String, msg: String, throwable: Throwable? = null) = write('E', tag, msg, throwable)

    /** 仅写入日志文件（不进 Logcat/内存环）——用于属性快照这类批量内容 */
    fun fileOnly(msg: String) {
        val now = Date()
        val time = synchronized(timeFmt) { timeFmt.format(now) }
        synchronized(lock) {
            runCatching { appendToFile(now, "$time F/SNAPSHOT $msg") }
                .onFailure { Log.e(LOGCAT_TAG, "写日志文件失败", it) }
        }
    }

    /** 捕获异常并记录（返回异常本身便于上层继续处理） */
    fun caught(tag: String, action: String, throwable: Throwable): Throwable {
        write('E', tag, "$action 失败: ${throwable.javaClass.simpleName}: ${throwable.message}", throwable)
        return throwable
    }

    // ==================== 读取/清理 ====================

    /** 内存日志快照（供界面展示） */
    fun snapshot(): String = synchronized(lock) {
        if (memory.isEmpty()) "(暂无日志)" else memory.joinToString("\n")
    }

    /** 全部日志文件（按时间倒序） */
    fun logFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".log") } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }
    }

    fun clear() {
        synchronized(lock) { memory.clear() }
        runCatching { logFiles().forEach { it.delete() } }
        i("日志已清空")
    }

    fun readAllFiles(): String {
        val sb = StringBuilder()
        logFiles().forEach { f ->
            runCatching {
                sb.append("===== ").append(f.name).append(" =====\n")
                sb.append(f.readText())
                sb.append("\n")
            }
        }
        return if (sb.isEmpty()) "(暂无日志文件)" else sb.toString()
    }

    private fun lastCrash(): String? = runCatching {
        val f = crashFile ?: return null
        if (f.exists()) f.readText().ifBlank { null } else null
    }.getOrNull()

    private fun markCrashRead() = runCatching { crashFile?.writeText("") }

    // ==================== 内部实现 ====================

    private fun write(level: Char, tag: String, msg: String, throwable: Throwable?) {
        val now = Date()
        val time = synchronized(timeFmt) { timeFmt.format(now) }
        val thread = Thread.currentThread().name

        val line = buildString {
            append(time).append(' ').append(level).append('/').append(tag)
            append(" [").append(thread).append("] ").append(msg)
            if (throwable != null) {
                append('\n').append(stackTrace(throwable))
            }
        }

        // 1) Logcat
        runCatching {
            when (level) {
                'D' -> Log.d(tag, msg, throwable)
                'I' -> Log.i(tag, msg, throwable)
                'W' -> Log.w(tag, msg, throwable)
                else -> Log.e(tag, msg, throwable)
            }
        }

        // 2) 内存 + 3) 文件
        synchronized(lock) {
            memory.addLast(line)
            while (memory.size > MAX_MEMORY_LINES) memory.removeFirst()
            runCatching { appendToFile(now, line) }
                .onFailure { Log.e(LOGCAT_TAG, "写日志文件失败", it) }
        }
    }

    private fun appendToFile(now: Date, line: String) {
        val dir = logDir ?: return
        if (!dir.exists()) dir.mkdirs()
        val name = "ota-" + synchronized(dateFmt) { dateFmt.format(now) } + ".log"
        val file = File(dir, name)
        if (file.exists() && file.length() > MAX_LOG_FILE_BYTES) {
            // 单文件超限：改名归档，避免无限增长
            runCatching {
                val archived = File(dir, name.replace(".log", "-" + System.currentTimeMillis() + ".log"))
                file.renameTo(archived)
            }
            cleanOldLogs()
        }
        file.appendText(line + "\n")
    }

    private fun cleanOldLogs() {
        runCatching {
            val files = logFiles()
            if (files.size > MAX_LOG_FILES) {
                files.drop(MAX_LOG_FILES).forEach { it.delete() }
            }
        }
    }

    private fun stackTrace(throwable: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        return sw.toString().trimEnd()
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val detail = buildString {
                    append("崩溃时间: ").append(Date()).append('\n')
                    append("线程: ").append(thread.name).append('\n')
                    append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    append(" / Android ").append(Build.VERSION.RELEASE).append('\n')
                    append("版本: ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n\n")
                    append(stackTrace(throwable))
                }
                crashFile?.writeText(detail)
                // 崩溃也要进入内存日志与文件日志
                write('E', LOGCAT_TAG, "应用发生未捕获异常\n$detail", null)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
