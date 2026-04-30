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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 模型下载管理服务
 * LocalAI-Server v4.0-MNN
 * 
 * 支持 MNN 模型目录下载（包含config.json + 多个.mnn文件）
 * 默认模型：Qwen3.5-4B-Claude蒸馏版
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
        
        // MNN模型文件列表
        data class MnnModelFile(
            val fileName: String,
            val size: Long,
            val url: String
        )
        
        // MNN模型配置
        data class MnnModelConfig(
            val name: String,
            val description: String,
            val files: List<MnnModelFile>,
            val totalSize: Long
        )
        
        // 默认MNN模型 - Qwen3.5-4B-Claude蒸馏版
        // 下载源：ModelScope
        private val MNN_MODELS = mapOf(
            "Qwen3.5-4B-Claude-Distilled" to MnnModelConfig(
                name = "Qwen3.5-4B-Claude-Distilled",
                description = "Qwen3.5-4B-Claude蒸馏版 - MNN格式，支持思考模式",
                totalSize = 2_500_000_000L, // 约2.5GB
                files = listOf(
                    MnnModelFile("config.json", 2048, 
                        "https://modelscope.cn/taobao-mnn/Qwen3.5-4B-Claude-4.6-Opus-Reasoning-Distilled-MNN/resolve/main/config.json"),
                    MnnModelFile("model.mnn", 2_400_000_000L, 
                        "https://modelscope.cn/taobao-mnn/Qwen3.5-4B-Claude-4.6-Opus-Reasoning-Distilled-MNN/resolve/main/model.mnn"),
                    MnnModelFile("tokenizer.json", 1024,
                        "https://modelscope.cn/taobao-mnn/Qwen3.5-4B-Claude-4.6-Opus-Reasoning-Distilled-MNN/resolve/main/tokenizer.json")
                )
            ),
            // 备用：较小的Qwen3-1.8B蒸馏版
            "Qwen3-1.8B-Claude-Distilled" to MnnModelConfig(
                name = "Qwen3-1.8B-Claude-Distilled",
                description = "Qwen3-1.8B-Claude蒸馏版 - MNN格式，更小更快",
                totalSize = 1_200_000_000L, // 约1.2GB
                files = listOf(
                    MnnModelFile("config.json", 2048,
                        "https://modelscope.cn/taobao-mnn/Qwen3-1.8B-Claude-Reasoning-Distilled-MNN/resolve/main/config.json"),
                    MnnModelFile("model.mnn", 1_150_000_000L,
                        "https://modelscope.cn/taobao-mnn/Qwen3-1.8B-Claude-Reasoning-Distilled-MNN/resolve/main/model.mnn"),
                    MnnModelFile("tokenizer.json", 1024,
                        "https://modelscope.cn/taobao-mnn/Qwen3-1.8B-Claude-Reasoning-Distilled-MNN/resolve/main/tokenizer.json")
                )
            )
        )
        
        // 偏好设置
        private const val PREFS_NAME = "model_downloads"
        private const val PREF_DOWNLOADED_MODELS = "downloaded_models"
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
    
    // 下载取消标志
    @Volatile
    private var isCancelled = false
    
    /**
     * 下载状态
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
        val error: String? = null,
        val currentFile: String = ""
    ) {
        val progressPercent: Int
            get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
    }
    
    /**
     * 下载任务
     */
    private data class DownloadTask(
        val modelName: String,
        val config: MnnModelConfig,
        val targetDir: File,
        val tempDir: File,
        var downloadedBytes: Long = 0,
        var currentFileIndex: Int = 0
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
     * 获取模型目录路径
     */
    fun getModelPath(modelName: String): String? {
        val dir = File(modelDir, modelName)
        val configJson = File(dir, "config.json")
        return if (configJson.exists()) dir.absolutePath else null
    }
    
    /**
     * 获取可用的MNN模型列表
     */
    fun getAvailableModels(): List<MnnModelConfig> {
        return MNN_MODELS.values.toList()
    }
    
    /**
     * 开始下载MNN模型
     * MNN模型是一个目录，包含多个文件
     */
    suspend fun downloadModel(modelName: String): Result<File> = withContext(Dispatchers.IO) {
        isCancelled = false
        
        val config = MNN_MODELS[modelName]
        if (config == null) {
            return@withContext Result.failure(Exception("未知的模型: $modelName"))
        }
        
        val targetDir = File(modelDir, modelName)
        val modelTempDir = File(tempDir, modelName).apply { mkdirs() }
        
        // 如果已存在（包含config.json），直接返回
        val configFile = File(targetDir, "config.json")
        if (configFile.exists()) {
            Log.i(TAG, "Model already exists: $targetDir")
            return@withContext Result.success(targetDir)
        }
        
        _downloadState.value = DownloadState(
            status = STATUS_DOWNLOADING,
            modelName = modelName,
            totalBytes = config.totalSize,
            currentSource = "ModelScope"
        )
        
        try {
            // 创建目标目录
            targetDir.mkdirs()
            
            // 下载每个文件
            for ((index, file) in config.files.withIndex()) {
                if (isCancelled) {
                    return@withContext Result.failure(Exception("下载已取消"))
                }
                
                val targetFile = File(targetDir, file.fileName)
                val tempFile = File(modelTempDir, "${file.fileName}.tmp")
                
                _downloadState.value = _downloadState.value.copy(
                    currentFile = file.fileName,
                    currentFileIndex = index
                )
                
                Log.i(TAG, "Downloading ${file.fileName} (${index + 1}/${config.files.size})")
                
                // 下载文件
                val downloadResult = downloadFile(file.url, targetFile, tempFile, file.size)
                if (downloadResult.isFailure) {
                    // 清理已下载的文件
                    targetDir.deleteRecursively()
                    return@withContext Result.failure(
                        downloadResult.exceptionOrNull() ?: Exception("下载失败")
                    )
                }
                
                _downloadState.value = _downloadState.value.copy(
                    downloadedBytes = _downloadState.value.downloadedBytes + file.size
                )
            }
            
            // 清理临时目录
            modelTempDir.deleteRecursively()
            
            // 记录已下载
            val downloadedModels = getDownloadedModels().toMutableList()
            if (!downloadedModels.contains(modelName)) {
                downloadedModels.add(modelName)
                prefs.edit().putString(PREF_DOWNLOADED_MODELS, 
                    downloadedModels.joinToString(",")).apply()
            }
            
            _downloadState.value = DownloadState(
                status = STATUS_COMPLETED,
                modelName = modelName,
                downloadedBytes = config.totalSize,
                totalBytes = config.totalBytes
            )
            
            Log.i(TAG, "Model downloaded successfully: $modelName")
            Result.success(targetDir)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download model", e)
            _downloadState.value = DownloadState(
                status = STATUS_FAILED,
                modelName = modelName,
                error = e.message
            )
            Result.failure(e)
        }
    }
    
    /**
     * 下载单个文件
     */
    private suspend fun downloadFile(
        url: String,
        targetFile: File,
        tempFile: File,
        expectedSize: Long
    ): Result<Unit> = withContext(Dispatchers.IO) {
        
        try {
            val request = Request.Builder()
                .url(url)
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("HTTP ${response.code}: ${response.message}")
                    )
                }
                
                val body = response.body ?: return@withContext Result.failure(
                    Exception("Empty response body")
                )
                
                val totalBytes = body.contentLength()
                
                FileOutputStream(tempFile).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L
                        val startTime = System.currentTimeMillis()
                        
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (isCancelled) {
                                tempFile.delete()
                                return@withContext Result.failure(Exception("下载已取消"))
                            }
                            
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            
                            // 更新进度
                            val elapsed = System.currentTimeMillis() - startTime
                            val speed = if (elapsed > 0) (totalRead * 1000 / elapsed) else 0
                            
                            _downloadState.value = _downloadState.value.copy(
                                downloadedBytes = _downloadState.value.downloadedBytes - 
                                    (_downloadState.value.downloadedBytes % expectedSize) + totalRead,
                                speed = speed,
                                progress = if (totalBytes > 0) 
                                    ((totalRead * 100) / totalBytes).toInt() else 0
                            )
                        }
                    }
                }
                
                // 重命名临时文件
                if (tempFile.renameTo(targetFile)) {
                    Result.success(Unit)
                } else {
                    // 如果重命名失败，尝试复制
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                    Result.success(Unit)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}")
            tempFile.delete()
            Result.failure(e)
        }
    }
    
    /**
     * 取消下载
     */
    fun cancelDownload() {
        isCancelled = true
        _downloadState.value = _downloadState.value.copy(status = STATUS_IDLE)
    }
    
    /**
     * 删除已下载模型
     */
    suspend fun deleteModel(modelName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val modelDir = File(this@ModelDownloadManager.modelDir, modelName)
            val deleted = modelDir.deleteRecursively()
            
            if (deleted) {
                // 更新已下载列表
                val downloadedModels = getDownloadedModels().toMutableList()
                downloadedModels.remove(modelName)
                prefs.edit().putString(PREF_DOWNLOADED_MODELS,
                    downloadedModels.joinToString(",")).apply()
            }
            
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete model", e)
            false
        }
    }
    
    /**
     * 获取模型目录大小
     */
    fun getModelDirSize(modelName: String): Long {
        val dir = File(modelDir, modelName)
        return if (dir.exists()) {
            dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        } else {
            0L
        }
    }
}
