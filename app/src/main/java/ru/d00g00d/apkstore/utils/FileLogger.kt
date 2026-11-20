package ru.d00g00d.apkstore.utils

import android.content.Context
import android.os.Environment
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileLogger(private val context: Context) : Timber.Tree() {

    private val logFile: File? by lazy {
        val documentsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        if (documentsDir != null) {
            val logDir = File(documentsDir, "ApkStoreLogs")
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            File(logDir, "app_log.txt")
        } else {
            null
        }
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val priorityStr = when (priority) {
            android.util.Log.VERBOSE -> "V"
            android.util.Log.DEBUG -> "D"
            android.util.Log.INFO -> "I"
            android.util.Log.WARN -> "W"
            android.util.Log.ERROR -> "E"
            android.util.Log.ASSERT -> "A"
            else -> "?"
        }

        val logMessage = "$timestamp [$priorityStr] ${tag ?: "ApkStore"}: $message\n"

        logFile?.let { file ->
            try {
                FileWriter(file, true).use { writer ->
                    writer.append(logMessage)
                    if (t != null) {
                        writer.append(android.util.Log.getStackTraceString(t))
                        writer.append("\n")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("FileLogger", "Error writing to log file", e)
            }
        }
    }
}
