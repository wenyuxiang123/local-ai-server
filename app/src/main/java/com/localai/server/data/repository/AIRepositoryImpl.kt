package com.localai.server.data.repository

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.util.Log
import com.localai.server.domain.model.ModelConfig
import com.localai.server.domain.model.ServerStatus
import com.localai.server.domain.repository.AIRepository
import com.localai.server.domain.repository.DownloadProgress
import com.localai.server.engine.LlamaEngine
import com.localai.server.service.AIService
import com.localai.server.util.FileLog
import com.localai.server.util.ModelExtractor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AIRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: LlamaEngine,
    private val modelExtractor: ModelExtractor
) : AIRepository {
    
    companion object {
        private const val TAG = "AIRepositoryImpl"
    }
    
    private val modelDir: File by lazy {
        File(context.filesDir, "models").apply { mkdirs() }
    }
    
    // 内置模型文件名 - Qwen3-4B-Q3_K_M
    private val builtInModelName = modelExtractor.getModelFileName()
    
    override fun isBuiltInModelReady(): Boolean {
        // 使用 ModelExtractor 检查模型是否已解压
        return modelExtractor.isModelExtracted()
    }
    
    override fun getBuiltInModelPath(): String? {
        // 使用 ModelExtractor 获取模型路径
        return modelExtractor.getModelPath()
    }
    
    /**
     * 解压内置模型
     * @return Flow<ExtractProgress> 解压进度
     */
    suspend fun extractBuiltInModel(): Flow<com.localai.server.util.ExtractProgress> {
        return modelExtractor.extractModel()
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    override suspend fun getAvailableModels(): List<ModelConfig> = withContext(Dispatchers.IO) {
        // MNN模型是目录结构，包含config.json
        // 查找包含config.json的目录
        modelDir.listFiles()
            ?.filter { it.isDirectory && File(it, "config.json").exists() }
            ?.map { dir ->
                // 计算目录总大小
                val totalSize = dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
                ModelConfig(
                    name = dir.name,
                    path = dir.absolutePath,
                    sizeBytes = totalSize
                )
            }
            ?.sortedByDescending { it.sizeBytes }
            ?: emptyList()
    }
    
    override suspend fun downloadModel(url: String, progress: (DownloadProgress) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        try {
            val fileName = url.substringAfterLast("/")
            val targetFile = File(modelDir, fileName)
            
            // 如果文件已存在，直接返回
            if (targetFile.exists()) {
                Log.i(TAG, "Model already exists: ${targetFile.absolutePath}")
                return@withContext Result.success(targetFile)
            }
            
            Log.i(TAG, "Starting download: $url")
            val request = Request.Builder().url(url).build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("下载失败: HTTP ${response.code}"))
                }
                
                val total = response.body?.contentLength() ?: -1L
                var downloaded = 0L
                var lastUpdateTime = System.currentTimeMillis()
                var lastDownloaded = 0L
                
                response.body?.byteStream()?.use { input ->
                    targetFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            
                            // 每500ms更新一次进度
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastUpdateTime >= 500) {
                                val timeDiff = currentTime - lastUpdateTime
                                val bytesDiff = downloaded - lastDownloaded
                                val speed = (bytesDiff * 1000 / timeDiff) // bytes per second
                                
                                val percent = if (total > 0) (downloaded * 100 / total).toInt() else 0
                                progress(DownloadProgress(percent, speed, downloaded, total))
                                
                                lastUpdateTime = currentTime
                                lastDownloaded = downloaded
                            }
                        }
                        
                        // 最终进度更新
                        val finalSpeed = if (downloaded > 0) downloaded * 1000 / (System.currentTimeMillis() - lastUpdateTime).coerceAtLeast(1) else 0
                        progress(DownloadProgress(100, finalSpeed, downloaded, total))
                    }
                }
            }
            
            Log.i(TAG, "Download completed: ${targetFile.absolutePath}")
            Result.success(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            Result.failure(e)
        }
    }
    
    override suspend fun copyModelFromUri(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        try {
            // MNN模型是目录结构
            // 假设用户选择一个目录（需要支持SAF的选择目录功能）
            val modelName = "model_${System.currentTimeMillis()}"
            val targetDir = File(modelDir, modelName)
            targetDir.mkdirs()
            
            // 从URI复制文件
            context.contentResolver.openInputStream(uri)?.use { input ->
                // 复制到目标目录的config.json
                val targetFile = File(targetDir, "config.json")
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure(Exception("无法打开文件"))
            
            Result.success(targetDir)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun loadModel(path: String): Result<ModelConfig> = withContext(Dispatchers.Default) {
        try {
            val file = File(path)
            if (!file.exists()) {
                return@withContext Result.failure(Exception("模型文件不存在"))
            }
            
            // 从 SharedPreferences 读取所有配置参数
            val prefs = context.getSharedPreferences("ai_config", Context.MODE_PRIVATE)
            val nThreads = prefs.getInt("n_threads", 4)
            val nBatch = prefs.getInt("n_batch", 512)
            val nCtx = prefs.getInt("n_ctx", 2048)
            val nGpuLayers = prefs.getInt("n_gpu_layers", 0)
            
            Log.i(TAG, "Loading model with config from SharedPreferences: nThreads=$nThreads, nCtx=$nCtx, nBatch=$nBatch, nGpuLayers=$nGpuLayers")
            FileLog.log(TAG, "Loading model with config: nThreads=$nThreads, nCtx=$nCtx, nBatch=$nBatch, nGpuLayers=$nGpuLayers")
            
            val success = engine.loadModel(
                path = path, 
                nCtx = nCtx, 
                nThreads = nThreads,
                nBatch = nBatch,
                flashAttn = true,
                cacheType = "f16",
                nGpuLayers = nGpuLayers
            )
            
            if (success) {
                // 更新 AIService 的模型加载状态
                AIService.updateModelLoaded(true)
                
                val config = ModelConfig(
                    name = file.nameWithoutExtension,
                    path = path,
                    threads = nThreads,
                    sizeBytes = file.length(),
                    optimizationParams = com.localai.server.domain.model.OptimizationParams(
                        nBatch = nBatch,
                        flashAttn = true,
                        cacheType = "f16"
                    )
                )
                Result.success(config)
            } else {
                Result.failure(Exception("模型加载失败"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun deleteModel(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            if (file.exists()) {
                val deleted = if (file.isDirectory) {
                    file.deleteRecursively()
                } else {
                    file.delete()
                }
                if (deleted) Result.success(Unit) else Result.failure(Exception("删除失败"))
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override fun startServer(): String {
        AIService.start(context)
        
        // 获取设备IP地址
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val wifiInfo = wifiManager.connectionInfo
        val ipAddress = wifiInfo.ipAddress
        
        return if (ipAddress != 0) {
            String.format(
                "http://%d.%d.%d.%d:8080",
                ipAddress and 0xff,
                ipAddress shr 8 and 0xff,
                ipAddress shr 16 and 0xff,
                ipAddress shr 24 and 0xff
            )
        } else {
            "http://localhost:8080"
        }
    }
    
    override fun stopServer() {
        AIService.stop(context)
    }
    
    override fun getServerStatus(): ServerStatus {
        // 计算服务地址
        val serverAddress = if (AIService.isRunning.value) {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipAddress = wifiInfo.ipAddress
            
            if (ipAddress != 0) {
                String.format(
                    "http://%d.%d.%d.%d:8080",
                    ipAddress and 0xff,
                    ipAddress shr 8 and 0xff,
                    ipAddress shr 16 and 0xff,
                    ipAddress shr 24 and 0xff
                )
            } else {
                "http://localhost:8080"
            }
        } else {
            null
        }
        
        return ServerStatus(
            isRunning = AIService.isRunning.value,
            modelLoaded = AIService.modelLoaded.value,
            loadedModel = engine.getLoadedModelInfo()["name"] as? String ?: "",
            address = serverAddress,
            uptime = 0
        )
    }
}
