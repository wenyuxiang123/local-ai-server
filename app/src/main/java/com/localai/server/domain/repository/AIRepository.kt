package com.localai.server.domain.repository

import android.net.Uri
import com.localai.server.domain.model.ModelConfig
import com.localai.server.domain.model.ServerStatus
import java.io.File

data class DownloadProgress(
    val percent: Int,
    val speed: Long, // bytes per second
    val downloaded: Long,
    val total: Long
)

interface AIRepository {
    /**
     * 获取可用模型列表
     */
    suspend fun getAvailableModels(): List<ModelConfig>
    
    /**
     * 下载模型
     */
    suspend fun downloadModel(url: String, progress: (DownloadProgress) -> Unit): Result<File>
    
    /**
     * 检查内置模型是否存在
     */
    fun isBuiltInModelReady(): Boolean
    
    /**
     * 获取内置模型路径
     */
    fun getBuiltInModelPath(): String?
    
    /**
     * 从Uri复制模型文件到应用目录
     */
    suspend fun copyModelFromUri(uri: Uri): Result<File>
    
    /**
     * 加载模型
     */
    suspend fun loadModel(path: String): Result<ModelConfig>
    
    /**
     * 删除模型
     */
    suspend fun deleteModel(path: String): Result<Unit>
    
    /**
     * 启动服务器
     */
    fun startServer(): String
    
    /**
     * 停止服务器
     */
    fun stopServer()
    
    /**
     * 获取服务器状态
     */
    fun getServerStatus(): ServerStatus
}
