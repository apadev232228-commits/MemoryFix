package com.apadev.memoryfix.logging

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.apadev.memoryfix.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {

    private const val LOG_DIR_NAME = "logs"
    private const val LOG_FILE_NAME = "diagnostics.log"
    private const val MAX_LOG_SIZE_BYTES = 256 * 1024L // 256 KB max

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        writeLogEntry("INFO", tag, message, null)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        writeLogEntry("WARN", tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        writeLogEntry("ERROR", tag, message, throwable)
    }

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        synchronized(lock) {
            val file = getLogFile(context)
            if (!file.exists()) {
                writeHeader(context, file)
            }
        }
    }

    fun getLogFile(context: Context): File {
        val dir = File(context.filesDir, LOG_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, LOG_FILE_NAME)
    }

    private fun writeLogEntry(level: String, tag: String, message: String, throwable: Throwable?) {
        val ctx = appContext ?: return
        synchronized(lock) {
            try {
                val file = getLogFile(ctx)
                if (file.length() > MAX_LOG_SIZE_BYTES) {
                    pruneLogFile(file)
                }

                val timestamp = dateFormat.format(Date())
                val stackTraceStr = throwable?.let {
                    val sw = StringWriter()
                    it.printStackTrace(PrintWriter(sw))
                    "\n$sw"
                } ?: ""

                val entry = "[$timestamp] [$level] [$tag] $message$stackTraceStr\n"
                FileOutputStream(file, true).use { fos ->
                    fos.write(entry.toByteArray(Charsets.UTF_8))
                }
            } catch (e: Exception) {
                // Ignore logging failures
            }
        }
    }

    private fun writeHeader(context: Context, file: File) {
        val header = "========================================\n" +
                "MemoryFix Diagnostic Log\n" +
                getSystemReport(context) + "\n" +
                "========================================\n\n"
        FileOutputStream(file, false).use { fos ->
            fos.write(header.toByteArray(Charsets.UTF_8))
        }
    }

    private fun pruneLogFile(file: File) {
        try {
            val lines = file.readLines(Charsets.UTF_8)
            val keepLines = lines.takeLast(lines.size / 2)
            file.writeText(keepLines.joinToString("\n") + "\n", Charsets.UTF_8)
        } catch (e: Exception) {
            file.delete()
        }
    }

    fun getSystemReport(context: Context): String {
        val stat = StatFs(Environment.getDataDirectory().path)
        val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
        val freeMB = freeBytes / (1024 * 1024)

        return """
            App: MemoryFix v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
            Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})
            OS: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
            Free Internal Storage: ~${freeMB} MB
        """.trimIndent()
    }

    fun getRecentLogs(context: Context, maxLines: Int = 100): String {
        return synchronized(lock) {
            val file = getLogFile(context)
            if (!file.exists()) return "No logs recorded yet."
            try {
                val lines = file.readLines(Charsets.UTF_8)
                lines.takeLast(maxLines).joinToString("\n")
            } catch (e: Exception) {
                "Unable to read log file: ${e.message}"
            }
        }
    }

    fun clearLogs(context: Context) {
        synchronized(lock) {
            val file = getLogFile(context)
            if (file.exists()) {
                file.delete()
            }
            writeHeader(context, file)
        }
    }
}
