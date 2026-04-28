package com.localai.server.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * LogCollectorService - 自动日志收集服务
 * 
 * 功能：
 * 1. APP启动时自动开始收集 logcat 日志到文件（循环buffer，最大10MB）
 * 2. 提供隐藏入口：在设置页面连续点击版本号5次触发上传
 * 3. 上传到云电脑的日志服务器
 */
class LogCollectorService : Service() {
    companion object {
        private const val TAG = "LogCollector"
        private const val LOG_DIR = "runtime_logs"
        private const val MAX_LOG_SIZE = 10 * 1024 * 1024 // 10MB
        private const val UPLOAD_URL = "http://115.190.127.67:9090/upload"
        private const val AUTH_TOKEN = "localai_log_2024"
        
        // Filter tags - 只收集相关TAG的日志
        private val FILTER_TAGS = setOf(
            "LocalAI", "LlamaEngine", "AiHttpServer", "AIService", 
            "InferenceEngine", "InferenceEngineImpl", "ModelExtractor",
            "MainActivity", "HomeChatViewModel", "ChatViewModel",
            "ChatApiService", "AIRepositoryImpl", "LogCollector",
            "nanohttpd", "NanoHTTPD"
        )
        
        fun start(context: android.content.Context) {
            val intent = Intent(context, LogCollectorService::class.java)
            context.startService(intent)
        }
        
        fun uploadLogs(context: android.content.Context) {
            val intent = Intent(context, LogCollectorService::class.java).apply {
                action = "ACTION_UPLOAD"
            }
            context.startService(intent)
        }
    }
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var logProcess: Process? = null
    private var logWriter: FileWriter? = null
    private var currentLogFile: File? = null
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "LogCollectorService created")
        startLogCollection()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_UPLOAD" -> uploadCurrentLog()
        }
        return START_STICKY
    }
    
    override fun onDestroy() {
        Log.i(TAG, "LogCollectorService destroyed")
        stopLogCollection()
        super.onDestroy()
    }
    
    private fun startLogCollection() {
        serviceScope.launch {
            try {
                val logDir = File(filesDir, LOG_DIR)
                if (!logDir.exists()) {
                    logDir.mkdirs()
                    Log.i(TAG, "Created log directory: ${logDir.absolutePath}")
                }
                
                val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                val logFile = File(logDir, "log_${dateFormat.format(Date())}.log")
                currentLogFile = logFile
                logWriter = FileWriter(logFile, true)
                
                Log.i(TAG, "Starting log collection to: ${logFile.absolutePath}")
                
                // Build logcat command with tag filters
                val tagFilter = FILTER_TAGS.joinToString(":V ") + ":V *:S"
                val command = arrayOf("logcat", "-v", "threadtime", tagFilter)
                Log.d(TAG, "Logcat command: ${command.joinToString(" ")}")
                
                logProcess = Runtime.getRuntime().exec(command)
                val reader = BufferedReader(InputStreamReader(logProcess!!.inputStream))
                
                var line: String?
                var lineCount = 0
                while (reader.readLine().also { line = it } != null) {
                    logWriter?.apply {
                        write(line)
                        write("\n")
                        flush()
                    }
                    lineCount++
                    
                    // Rotate log if too large
                    if (logFile.length() > MAX_LOG_SIZE) {
                        Log.i(TAG, "Log file too large (${logFile.length()} bytes), rotating...")
                        logWriter?.close()
                        
                        val newFile = File(logDir, "log_${dateFormat.format(Date())}.log")
                        currentLogFile = newFile
                        logWriter = FileWriter(newFile, true)
                        
                        // Delete old log files (keep last 3)
                        logDir.listFiles()
                            ?.filter { it.name.endsWith(".log") && it != newFile }
                            ?.sortedByDescending { it.lastModified() }
                            ?.drop(2)
                            ?.forEach { 
                                Log.d(TAG, "Deleting old log file: ${it.name}")
                                it.delete() 
                            }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Log collection error", e)
            }
        }
    }
    
    private fun stopLogCollection() {
        try {
            logProcess?.destroy()
            logProcess = null
            logWriter?.close()
            logWriter = null
            Log.i(TAG, "Log collection stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping log collection", e)
        }
    }
    
    private fun uploadCurrentLog() {
        serviceScope.launch {
            try {
                val logFile = currentLogFile
                if (logFile == null || !logFile.exists()) {
                    Log.w(TAG, "No log file to upload")
                    return@launch
                }
                
                // Flush the writer before uploading
                logWriter?.flush()
                
                Log.i(TAG, "Uploading log file: ${logFile.name} (${logFile.length()} bytes)")
                
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
                
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file", logFile.name,
                        logFile.asRequestBody("text/plain".toMediaType())
                    )
                    .addFormDataPart("device", android.os.Build.MODEL)
                    .addFormDataPart("app_version", packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown")
                    .addFormDataPart("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                    .build()
                
                val request = Request.Builder()
                    .url(UPLOAD_URL)
                    .addHeader("Authorization", "Bearer $AUTH_TOKEN")
                    .post(requestBody)
                    .build()
                
                client.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string() ?: "empty response"
                    if (response.isSuccessful) {
                        Log.i(TAG, "Log uploaded successfully: $bodyStr")
                    } else {
                        Log.e(TAG, "Log upload failed: ${response.code} $bodyStr")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Log upload error", e)
            }
        }
    }
}
