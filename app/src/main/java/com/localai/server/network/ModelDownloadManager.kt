package com.localai.server.network

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 模型下载管理服务
 * 支持断点续传、多源下载、下载进度显示
 */
@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ModelDownloadManager"
        
        // 下载状态
        const val STATUS_IDLE = 0
        const val STATUS_DOWNLOADING = 1
        const val STATUS_PAUSED = 2
        const val STATUS_COMPLETED = 3
        const val STATUS_FAILED = 4
        
        // 备用下载源
        private val MODEL_SOURCES = mapOf(
            "DeepSeek-R1-Distill-Qwen-1.5B-Q8_0" to listOf(
                "https://modelscope.cn/models/unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/master/DeepSeek-R1-Distill-Qwen-1.5B-Q8_0.gguf",
                "https://huggingface.co/unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q8_0.gguf"
            ),
            "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M" to listOf(
                "https://modelscope.cn/models/unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/master/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
                "https://huggingface.co/unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf"
            ),
            "DeepSeek-R1-Distill-Qwen-7B-Q4_K_M" to listOf(
                "https://modelscope.cn/models/unsloth/DeepSeek-R1-Distill-Qwen-7B-GGUF/resolve/master/DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf",
                "https://huggingface.co/unsloth/DeepSeek-R1-Distill-Qwen-7B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf"
            ),
            "Qwen3-1.7B-Q4_K_M" to listOf(
                "https://modelscope.cn/api/v1/models/unsloth/Qwen3-1.7B-GGUF/resolve/master/Qwen3-1.7B-Q4_K_M.gguf",
                "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"
            ),
            "Qwen3-0.6B-Q4_K_M" to listOf(
                "https://modelscope.cn/api/v1/models/unsloth/Qwen3-0.6B-GGUF/resolve/master/Qwen3-0.6B-Q4_K_M.gguf",
                "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf"
            ),
            "Qwen3-4B-Q4_K_M" to listOf(
                "https://modelscope.cn/api/v1/models/unsloth/Qwen3-4B-GGUF/repo?Revision=master&FilePath=Qwen3-4B-Q4_K_M.gguf",
                "https://huggingface.co/unsloth/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf"
            )
        )
        
        // 偏好设置文件名
        private const val PREFS_NAME = "model_downloads"
        private const val PREF_DOWNLOADED_MODELS = "downloaded_models"
        private const val PREF_LAST_SOURCE = "last_source_"
    }
    
    private val modelDir: File by lazy {
        File(context.filesDir, "models").apply { mkdirs() }
    }
    
    private val tempDir: File by lazy {
        File(context.cacheDir, "download_temp").apply { mkdirs() }
    }
    
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    
    // 下载状态流
    private val _downloadState = MutableStateFlow(DownloadState())
    val downloadState: Flow<DownloadState> = _downloadState.asStateFlow()
    
    // 当前下载任务
    @Volatile
    private var currentDownload: DownloadTask? = null
    
    // 下载任务取消标志
    @Volatile
    private var isCancelled = false
    
    /**
     * 下载状态数据类
     */
    data class DownloadState(
        val status: Int = STATUS_IDLE,
        val modelName: String = "",
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
        val speed: Long = 0,
        val progress: Int = 0,
        val currentSource: String = "",
        val sourceIndex: Int = 0,
        val error: String? = null
    ) {
        val progressPercent: Int
            get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
    }
    
    /**
     * 下载任务
     */
    private data class DownloadTask(
        val modelName: String,
        val url: String,
        val targetFile: File,
        val tempFile: File,
        var downloadedBytes: Long = 0,
        var totalBytes: Long = 0,
        var currentSourceIndex: Int = 0
    )
    
    /**
     * 获取已下载模型列表
     */
    fun getDownloadedModels(): List<String> {
        val modelsStr = prefs.getString(PREF_DOWNLOADED_MODELS, "") ?: ""
        return if (modelsStr.isEmpty()) emptyList() else modelsStr.split(",")
    }
    
    /**
     * 检查模型是否已下载
     */
    fun isModelDownloaded(modelName: String): Boolean {
        return getDownloadedModels().contains(modelName)
    }
    
    /**
     * 获取模型文件路径
     */
    fun getModelPath(modelName: String): String? {
        val fileName = "$modelName.gguf"
        val file = File(modelDir, fileName)
        return if (file.exists()) file.absolutePath else null
    }
    
    /**
     * 开始下载模型
     * @param modelName 模型名称（不含.gguf后缀）
     */
    suspend fun downloadModel(modelName: String): Result<File> = withContext(Dispatchers.IO) {
        isCancelled = false
        
        val sources = MODEL_SOURCES[modelName]
        if (sources == null) {
            return@withContext Result.failure(Exception("未知的模型: $modelName"))
        }
        
        val fileName = "$modelName.gguf"
        val targetFile = File(modelDir, fileName)
        val tempFile = File(tempDir, "$fileName.tmp")
        
        // 如果已存在，直接返回
        if (targetFile.exists()) {
            Log.i(TAG, "Model already exists: $targetFile")
            return@withContext Result.success(targetFile)
        }
        
        // 尝试从每个源下载
        for ((index, url) in sources.withIndex()) {
            if (isCancelled) {
                return@withContext Result.failure(Exception("下载已取消"))
            }
            
            _downloadState.value = DownloadState(
                status = STATUS_DOWNLOADING,
                modelName = modelName,
                currentSource = getSourceName(url),
                sourceIndex = index
            )
            
            Log.i(TAG, "Trying source $index: ${getSourceName(url)}")
            
            val result = downloadFromUrl(url, targetFile, tempFile, index)
            
            if (result.isSuccess) {
                // 保存已下载记录
                saveDownloadedModel(modelName)
                _downloadState.value = DownloadState(status = STATUS_COMPLETED, modelName = modelName)
                return@withContext result
            }
            
            Log.w(TAG, "Source $index failed: ${result.exceptionOrNull()?.message}")
        }
        
        val error = "所有下载源均失败"
        _downloadState.value = DownloadState(status = STATUS_FAILED, modelName = modelName, error = error)
        Result.failure(Exception(error))
    }
    
    /**
     * 从自定义 URL 下载模型
     * @param modelName 模型名称（用于保存文件名）
     * @param url 下载 URL
     */
    suspend fun downloadFromCustomUrl(modelName: String, url: String): Result<File> = withContext(Dispatchers.IO) {
        isCancelled = false
        
        val fileName = "$modelName.gguf"
        val targetFile = File(modelDir, fileName)
        val tempFile = File(tempDir, "$fileName.tmp")
        
        // 如果已存在，直接返回
        if (targetFile.exists()) {
            Log.i(TAG, "Model already exists: $targetFile")
            return@withContext Result.success(targetFile)
        }
        
        _downloadState.value = DownloadState(
            status = STATUS_DOWNLOADING,
            modelName = modelName,
            currentSource = "Custom",
            sourceIndex = 0
        )
        
        val result = downloadFromUrl(url, targetFile, tempFile, 0)
        
        if (result.isSuccess) {
            saveDownloadedModel(modelName)
            _downloadState.value = DownloadState(status = STATUS_COMPLETED, modelName = modelName)
        } else {
            val error = result.exceptionOrNull()?.message ?: "下载失败"
            _downloadState.value = DownloadState(status = STATUS_FAILED, modelName = modelName, error = error)
        }
        result
    }
    
    /**
     * 从指定URL下载
     */
    private suspend fun downloadFromUrl(
        url: String,
        targetFile: File,
        tempFile: File,
        sourceIndex: Int
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            // 检查断点续传支持
            val existingSize = if (tempFile.exists()) tempFile.length() else 0
            
            val requestBuilder = Request.Builder().url(url)
            
            // 如果有已下载的部分，从断点开始下载
            if (existingSize > 0) {
                requestBuilder.addHeader("Range", "bytes=$existingSize-")
                Log.i(TAG, "Resuming download from byte $existingSize")
            }
            
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    // 206 = Partial Content (断点续传成功)
                    return@withContext Result.failure(Exception("HTTP ${response.code}"))
                }
                
                val totalBytes = getContentLength(response, existingSize)
                val isPartial = response.code == 206
                
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(tempFile, isPartial).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = existingSize
                        var lastUpdateTime = System.currentTimeMillis()
                        var lastReadBytes = 0L
                        
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (isCancelled) {
                                // 保存断点
                                output.flush()
                                return@withContext Result.failure(Exception("下载已取消"))
                            }
                            
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            
                            // 更新进度（每500ms更新一次）
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastUpdateTime >= 500) {
                                val timeDiff = currentTime - lastUpdateTime
                                val bytesDiff = totalRead - lastReadBytes
                                val speed = (bytesDiff * 1000 / timeDiff)
                                
                                _downloadState.value = _downloadState.value.copy(
                                    downloadedBytes = totalRead,
                                    totalBytes = totalBytes,
                                    speed = speed,
                                    progress = if (totalBytes > 0) ((totalRead * 100) / totalBytes).toInt() else 0
                                )
                                
                                lastUpdateTime = currentTime
                                lastReadBytes = totalRead
                            }
                        }
                    }
                }
            }
            
            // 下载完成，重命名临时文件
            if (tempFile.renameTo(targetFile)) {
                Log.i(TAG, "Download completed: $targetFile")
                Result.success(targetFile)
            } else {
                // 重命名失败，尝试直接复制
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
                Result.success(targetFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * 获取内容长度
     */
    private fun getContentLength(response: Response, existingSize: Long): Long {
        return try {
            // 优先使用 Content-Range 头
            val contentRange = response.header("Content-Range")
            if (contentRange != null) {
                val match = Regex("bytes \\d+-(\\d+)/(\\d+)").find(contentRange)
                if (match != null) {
                    val totalSize = match.groupValues[2].toLong()
                    return totalSize
                }
            }
            
            // 使用 Content-Length 头
            val contentLength = response.body?.contentLength() ?: -1L
            if (contentLength > 0) {
                return existingSize + contentLength
            }
            
            -1L
        } catch (e: Exception) {
            -1L
        }
    }
    
    /**
     * 暂停下载
     */
    fun pauseDownload() {
        isCancelled = true
        _downloadState.value = _downloadState.value.copy(status = STATUS_PAUSED)
    }
    
    /**
     * 取消下载
     */
    fun cancelDownload() {
        isCancelled = true
        _downloadState.value = DownloadState(status = STATUS_IDLE)
    }
    
    /**
     * 删除模型
     */
    fun deleteModel(modelName: String): Result<Unit> {
        return try {
            val fileName = "$modelName.gguf"
            val file = File(modelDir, fileName)
            
            if (file.exists()) {
                file.delete()
            }
            
            // 移除下载记录
            val downloaded = getDownloadedModels().toMutableList()
            downloaded.remove(modelName)
            prefs.edit().putString(PREF_DOWNLOADED_MODELS, downloaded.joinToString(",")).apply()
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 获取模型大小信息
     */
    fun getModelSize(modelName: String): Long {
        val fileName = "$modelName.gguf"
        val file = File(modelDir, fileName)
        return if (file.exists()) file.length() else 0
    }
    
    /**
     * 格式化文件大小
     */
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
    
    /**
     * 获取源名称
     */
    private fun getSourceName(url: String): String {
        return when {
            url.contains("modelscope") -> "ModelScope"
            url.contains("huggingface") -> "HuggingFace"
            url.contains("hf-mirror") -> "HF Mirror"
            else -> "Unknown"
        }
    }
    
    /**
     * 保存已下载模型记录
     */
    private fun saveDownloadedModel(modelName: String) {
        val downloaded = getDownloadedModels().toMutableSet()
        downloaded.add(modelName)
        prefs.edit().putString(PREF_DOWNLOADED_MODELS, downloaded.joinToString(",")).apply()
    }
    
    /**
     * 获取所有可用模型信息
     */
    fun getAvailableModels(): List<ModelInfo> {
        return listOf(
            ModelInfo("DeepSeek-R1-Distill-Qwen-1.5B-Q8_0", "~1.6GB", "推理模型，展示思考过程，适合手机"),
            ModelInfo("DeepSeek-R1-Distill-Qwen-7B-Q4_K_M", "~4.3GB", "推理模型，更强推理能力"),
            ModelInfo("Qwen3-4B-Q4_K_M", "~2.3GB", "通用对话模型，支持思考模式，性能更强"),
            ModelInfo("Qwen3-1.7B-Q4_K_M", "~1.1GB", "通用对话模型，速度快"),
            ModelInfo("Qwen3-0.6B-Q4_K_M", "~400MB", "超轻量级，极速响应")
        )
    }
    
    /**
     * 模型信息
     */
    data class ModelInfo(
        val name: String,
        val size: String,
        val description: String
    )
}
