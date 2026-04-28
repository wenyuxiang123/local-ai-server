package com.localai.server.util

import android.os.Environment
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Direct file logger - writes to Download/LocalAI_Logs/direct.log
 * Bypasses logcat entirely for reliable diagnostics
 */
object FileLog {
    private var logFile: File? = null
    private var writer: FileWriter? = null
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    
    fun init() {
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val logDir = File(downloadDir, "LocalAI_Logs")
            if (!logDir.exists()) logDir.mkdirs()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            logFile = File(logDir, "direct_$timestamp.log")
            writer = FileWriter(logFile, true)
            log("FileLog", "Direct file logger initialized: ${logFile?.absolutePath}")
        } catch (e: Exception) {
            // Silently fail - this is a diagnostic tool
        }
    }
    
    fun log(tag: String, message: String) {
        try {
            val ts = dateFormat.format(Date())
            writer?.apply {
                write("$ts $tag: $message\n")
                flush()
            }
        } catch (_: Exception) {}
    }
    
    fun close() {
        try {
            writer?.close()
            writer = null
        } catch (_: Exception) {}
    }
}
