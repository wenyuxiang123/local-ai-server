package com.localai.server.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.server.data.repository.AIRepositoryImpl
import com.localai.server.domain.model.GenerateConfig
import com.localai.server.domain.model.ModelConfig
import com.localai.server.domain.repository.AIRepository
import com.localai.server.domain.repository.DownloadProgress
import com.localai.server.service.AIService
import com.localai.server.util.ExtractProgress
import com.localai.server.util.ModelExtractor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: AIRepositoryImpl,
    private val modelExtractor: ModelExtractor
) : ViewModel() {
    
    // UI状态
    private val _state = MutableStateFlow(MainState())
    val state: StateFlow<MainState> = _state.asStateFlow()
    
    // 单次事件
    private val _effect = MutableSharedFlow<MainEffect>()
    val effect: SharedFlow<MainEffect> = _effect
    
    init {
        checkServiceStatus()
        loadAvailableModels()
        checkAndExtractModel()
    }
    
    /**
     * 检查模型状态并自动启动服务
     */
    private fun autoStartIfModelReady() {
        viewModelScope.launch {
            if (repository.isBuiltInModelReady()) {
                _state.update { it.copy(modelReady = true) }
                // 模型已就绪，自动启动服务
                startService()
            }
        }
    }
    
    /**
     * 检查并下载/加载模型（三阶段流程）
     */
    private fun checkAndExtractModel() {
        viewModelScope.launch {
            if (repository.isBuiltInModelReady()) {
                _state.update { it.copy(modelReady = true) }
                startService()
                return@launch
            }
            
            // 阶段1：下载
            _state.update { 
                it.copy(
                    loadingPhase = LoadingPhase.DOWNLOADING,
                    progress = 0,
                    logMessages = "准备下载模型..."
                )
            }
            
            try {
                repository.extractBuiltInModel().collect { progress ->
                    // 根据进度百分比判断阶段，错误情况(<0)也走IDLE
                    val phase = when {
                        progress.percent < 0 -> LoadingPhase.IDLE
                        progress.percent < 98 -> LoadingPhase.DOWNLOADING
                        progress.percent == 98 -> LoadingPhase.WAITING
                        progress.percent >= 99 -> LoadingPhase.LOADING
                        else -> LoadingPhase.WAITING
                    }
                    
                    _state.update { state ->
                        // 限制日志行数，只保留最近50行
                        val newLog = (state.logMessages + "\n> " + progress.message)
                            .split("\n")
                            .takeLast(50)
                            .joinToString("\n")
                        
                        state.copy(
                            loadingPhase = phase,
                            progress = if (progress.percent < 0) state.progress else progress.percent,
                            logMessages = newLog,
                            downloadedBytes = progress.downloadedBytes,
                            totalBytes = progress.totalBytes,
                            speedBytesPerSec = progress.speedBytesPerSec
                        )
                    }
                }
                
                // 检查结果
                if (repository.isBuiltInModelReady()) {
                    _state.update { it.copy(
                        loadingPhase = LoadingPhase.IDLE,
                        modelReady = true
                    )}
                    _effect.emit(MainEffect.ShowToast("模型准备完成"))
                    _effect.emit(MainEffect.ExtractComplete)
                    loadAvailableModels()
                    startService()
                } else {
                    // 下载完成但模型加载失败（可能是MNN引擎问题）
                    _state.update { it.copy(
                        loadingPhase = LoadingPhase.IDLE,
                        error = "模型文件下载完成但MNN引擎加载失败"
                    )}
                    _effect.emit(MainEffect.ShowError("MNN引擎加载失败，请查看日志了解详情"))
                }
            } catch (e: Exception) {
                // 根据异常类型区分下载失败和引擎加载失败
                val errorType = when {
                    e.message?.contains("磁盘空间", ignoreCase = true) == true -> "磁盘空间不足"
                    e.message?.contains("网络", ignoreCase = true) == true -> "网络连接失败"
                    e.message?.contains("超时", ignoreCase = true) == true -> "下载超时"
                    e.message?.contains("下载", ignoreCase = true) == true -> "下载失败"
                    e.message?.contains("模型", ignoreCase = true) == true -> "模型加载失败"
                    else -> "未知错误"
                }
                
                val errorDetail = e.message ?: "未知原因"
                _state.update { it.copy(
                    loadingPhase = LoadingPhase.IDLE,
                    error = "$errorType: $errorDetail"
                )}
                _effect.emit(MainEffect.ShowError("$errorType: $errorDetail\n\n部分已下载的文件已保留，下次启动将自动断点续传"))
            }
        }
    }
    
    /**
     * 处理用户意图
     */
    fun onIntent(intent: MainIntent) {
        when (intent) {
            is MainIntent.StartService -> startService()
            is MainIntent.StopService -> stopService()
            is MainIntent.LoadModel -> loadModel(intent.path)
            is MainIntent.DownloadModel -> downloadModel(intent.url, intent.name)
            is MainIntent.DeleteModel -> deleteModel(intent.path)
            is MainIntent.ClearError -> clearError()
        }
    }
    
    private fun checkServiceStatus() {
        viewModelScope.launch {
            AIService.isRunning.collect { running ->
                _state.update { it.copy(serviceRunning = running) }
            }
        }
        
        viewModelScope.launch {
            AIService.modelLoaded.collect { loaded ->
                _state.update { it.copy(modelLoaded = loaded) }
            }
        }
        
        viewModelScope.launch {
            AIService.statusMessage.collect { message ->
                _state.update { it.copy(statusMessage = message) }
            }
        }
        
        // 监听错误信息
        viewModelScope.launch {
            AIService.errorMessage.collect { errorMsg ->
                if (errorMsg != null) {
                    _state.update { it.copy(error = errorMsg, isLoading = false) }
                }
            }
        }
    }
    
    private fun loadAvailableModels() {
        viewModelScope.launch {
            val models = repository.getAvailableModels()
            _state.update { it.copy(availableModels = models) }
        }
    }
    
    private fun startService() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            
            val address = repository.startServer()
            
            _state.update { 
                it.copy(
                    isLoading = false,
                    serverAddress = address,
                    serviceRunning = true
                )
            }
            
            // 自动加载内置模型
            if (repository.isBuiltInModelReady()) {
                val modelPath = repository.getBuiltInModelPath()
                if (modelPath != null) {
                    loadModel(modelPath)
                } else {
                    _state.update { it.copy(isLoading = false) }
                    _effect.emit(MainEffect.ShowError("模型路径获取失败"))
                }
            } else {
                _state.update { it.copy(isLoading = false) }
            }
            
            _effect.emit(MainEffect.ShowToast("服务已启动: $address"))
        }
    }
    
    private fun stopService() {
        viewModelScope.launch {
            repository.stopServer()
            
            _state.update { 
                it.copy(
                    serviceRunning = false,
                    modelLoaded = false,
                    serverAddress = ""
                )
            }
            
            _effect.emit(MainEffect.ShowToast("服务已停止"))
        }
    }
    
    private fun loadModel(path: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            
            repository.loadModel(path)
                .onSuccess { config ->
                    _state.update { 
                        it.copy(
                            isLoading = false,
                            modelLoaded = true,
                            modelConfig = config,
                            selectedModelPath = path
                        )
                    }
                    _effect.emit(MainEffect.ShowToast("模型加载成功: ${config.name}"))
                }
                .onFailure { e ->
                    _state.update { 
                        it.copy(
                            isLoading = false,
                            error = e.message
                        )
                    }
                    _effect.emit(MainEffect.ShowError(e.message ?: "模型加载失败"))
                }
        }
    }
    
    private fun downloadModel(url: String, name: String) {
        viewModelScope.launch {
            _state.update { it.copy(isDownloading = true, downloadProgress = 0) }
            
            repository.downloadModel(url) { progress ->
                _state.update { it.copy(downloadProgress = progress.percent) }
            }
                .onSuccess { file ->
                    _state.update { 
                        it.copy(
                            isDownloading = false,
                            downloadProgress = 100
                        )
                    }
                    loadAvailableModels()
                    _effect.emit(MainEffect.ShowToast("下载完成: ${file.name}"))
                }
                .onFailure { e ->
                    _state.update { 
                        it.copy(
                            isDownloading = false,
                            downloadProgress = 0,
                            error = "下载失败: ${e.message}"
                        )
                    }
                    _effect.emit(MainEffect.ShowError("下载失败: ${e.message}"))
                }
        }
    }
    
    private fun deleteModel(path: String) {
        viewModelScope.launch {
            repository.deleteModel(path)
                .onSuccess {
                    loadAvailableModels()
                    _effect.emit(MainEffect.ShowToast("模型已删除"))
                }
                .onFailure { e ->
                    _effect.emit(MainEffect.ShowError("删除失败: ${e.message}"))
                }
        }
    }
    
    private fun clearError() {
        _state.update { it.copy(error = null) }
    }
}

/**
 * 模型加载阶段
 */
enum class LoadingPhase {
    IDLE,           // 空闲
    DOWNLOADING,    // 下载中
    WAITING,        // 等待加载模型
    LOADING         // 加载中
}

/**
 * UI状态
 */
data class MainState(
    val isLoading: Boolean = false,
    val serviceRunning: Boolean = false,
    val modelLoaded: Boolean = false,
    val modelReady: Boolean = false,
    val serverAddress: String = "",
    val statusMessage: String = "",
    val modelConfig: ModelConfig? = null,
    val availableModels: List<ModelConfig> = emptyList(),
    val selectedModelPath: String = "",
    // 三个阶段的进度状态
    val loadingPhase: LoadingPhase = LoadingPhase.IDLE,
    val progress: Int = 0,
    val logMessages: String = "",
    // 下载专用进度数据
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    // 其他
    val isDownloading: Boolean = false,
    val downloadProgress: Int = 0,
    val error: String? = null
)

/**
 * 用户意图
 */
sealed class MainIntent {
    object StartService : MainIntent()
    object StopService : MainIntent()
    data class LoadModel(val path: String) : MainIntent()
    data class DownloadModel(val url: String, val name: String) : MainIntent()
    data class DeleteModel(val path: String) : MainIntent()
    object ClearError : MainIntent()
}

/**
 * 单次事件
 */
sealed class MainEffect {
    data class ShowToast(val message: String) : MainEffect()
    data class ShowError(val message: String) : MainEffect()
    object ExtractComplete : MainEffect()
}
