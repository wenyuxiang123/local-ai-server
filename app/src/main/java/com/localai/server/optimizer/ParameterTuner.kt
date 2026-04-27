package com.localai.server.optimizer

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 参数自动调优服务
 * 监控设备状态，动态调整推理参数
 */
@Singleton
class ParameterTuner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ParameterTuner"
        
        // 监控采样间隔
        private const val MONITOR_INTERVAL_MS = 2000L
        
        // 温度阈值
        private const val TEMP_NORMAL = 35.0f
        private const val TEMP_WARNING = 40.0f
        private const val TEMP_CRITICAL = 45.0f
        
        // 内存使用阈值
        private const val MEMORY_WARNING_RATIO = 0.7f
        private const val MEMORY_CRITICAL_RATIO = 0.85f
        
        // 保存配置的文件名
        private const val CONFIG_FILE = "optimal_config.json"
    }
    
    private val configDir: File by lazy {
        File(context.filesDir, "config").apply { mkdirs() }
    }
    
    private val configFile: File by lazy {
        File(configDir, CONFIG_FILE)
    }
    
    private var monitorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // 调优状态
    private val _tuningState = MutableStateFlow(TuningState())
    val tuningState: StateFlow<TuningState> = _tuningState.asStateFlow()
    
    // 当前最优配置
    private val _currentConfig = MutableStateFlow(InferenceConfig())
    val currentConfig: StateFlow<InferenceConfig> = _currentConfig.asStateFlow()
    
    // 设备信息
    private val activityManager: ActivityManager by lazy {
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    }
    
    /**
     * 调优状态
     */
    data class TuningState(
        val isMonitoring: Boolean = false,
        val cpuUsage: Float = 0f,
        val memoryUsage: Float = 0f,
        val memoryAvailable: Long = 0,
        val temperature: Float = 0f,
        val temperatureLevel: TempLevel = TempLevel.NORMAL,
        val recommendedThreads: Int = 4,
        val recommendedContextSize: Int = 2048,
        val recommendedBatchSize: Int = 1,
        val autoTuningEnabled: Boolean = false
    )
    
    /**
     * 温度级别
     */
    enum class TempLevel {
        NORMAL,    // 正常 (< 35°C)
        WARM,      // 温热 (35-40°C)
        HOT,       // 发热 (40-45°C)
        CRITICAL   // 过热 (> 45°C)
    }
    
    /**
     * 推理配置
     */
    data class InferenceConfig(
        val threads: Int = 4,
        val contextSize: Int = 2048,
        val batchSize: Int = 1,
        val gpuEnabled: Boolean = false,
        val useMmap: Boolean = true,
        val useMlock: Boolean = false,
        val temperature: Float = 0.7f,
        val maxTokens: Int = 512,
        val topK: Int = 40,
        val topP: Float = 0.9f,
        val repeatPenalty: Float = 1.1f,
        val lastUpdated: Long = System.currentTimeMillis()
    )
    
    /**
     * 设备信息
     */
    data class DeviceInfo(
        val cores: Int,
        val totalMemory: Long,
        val isLowRamDevice: Boolean,
        val socModel: String,
        val architecture: String
    )
    
    init {
        // 加载保存的配置
        loadConfig()
        
        // 获取设备信息
        val deviceInfo = getDeviceInfo()
        Log.i(TAG, "Device info: $deviceInfo")
        
        // 根据设备信息初始化配置
        initializeConfigForDevice(deviceInfo)
    }
    
    /**
     * 获取设备信息
     */
    fun getDeviceInfo(): DeviceInfo {
        val cores = Runtime.getRuntime().availableProcessors()
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        return DeviceInfo(
            cores = cores,
            totalMemory = memInfo.totalMem,
            isLowRamDevice = activityManager.isLowRamDevice,
            socModel = getSocModel(),
            architecture = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        )
    }
    
    /**
     * 获取 SoC 型号（简化版）
     */
    private fun getSocModel(): String {
        return try {
            val cpuInfo = File("/proc/cpuinfo").readText()
            val hardware = Regex("Hardware\\s*:\\s*(.+)").find(cpuInfo)?.groupValues?.get(1)
                ?: Regex("model name\\s*:\\s*(.+)").find(cpuInfo)?.groupValues?.get(1)
                ?: "Unknown"
            hardware.trim()
        } catch (e: Exception) {
            "Unknown"
        }
    }
    
    /**
     * 初始化设备配置
     */
    private fun initializeConfigForDevice(deviceInfo: DeviceInfo) {
        val current = _currentConfig.value
        
        // 根据核心数设置线程
        val threads = when {
            deviceInfo.cores >= 8 -> 6
            deviceInfo.cores >= 6 -> 4
            deviceInfo.cores >= 4 -> 3
            else -> 2
        }
        
        // 根据内存设置上下文大小
        val contextSize = when {
            deviceInfo.totalMemory >= 8L * 1024 * 1024 * 1024 -> 4096  // >= 8GB
            deviceInfo.totalMemory >= 6L * 1024 * 1024 * 1024 -> 3072  // >= 6GB
            deviceInfo.totalMemory >= 4L * 1024 * 1024 * 1024 -> 2048  // >= 4GB
            else -> 1024  // < 4GB
        }
        
        // 低内存设备减少配置
        val adjustedContextSize = if (deviceInfo.isLowRamDevice) {
            (contextSize * 0.5).toInt().coerceAtLeast(512)
        } else {
            contextSize
        }
        
        _currentConfig.value = current.copy(
            threads = threads,
            contextSize = adjustedContextSize
        )
        
        _tuningState.value = _tuningState.value.copy(
            recommendedThreads = threads,
            recommendedContextSize = adjustedContextSize
        )
    }
    
    /**
     * 开始监控
     */
    fun startMonitoring() {
        if (monitorJob?.isActive == true) return
        
        _tuningState.value = _tuningState.value.copy(isMonitoring = true, autoTuningEnabled = true)
        
        monitorJob = scope.launch {
            while (isActive) {
                try {
                    updateDeviceState()
                    autoAdjustIfNeeded()
                    delay(MONITOR_INTERVAL_MS)
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Monitor error", e)
                }
            }
        }
        
        Log.i(TAG, "Monitoring started")
    }
    
    /**
     * 停止监控
     */
    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        _tuningState.value = _tuningState.value.copy(isMonitoring = false, autoTuningEnabled = false)
        Log.i(TAG, "Monitoring stopped")
    }
    
    /**
     * 更新设备状态
     */
    private fun updateDeviceState() {
        // 获取内存信息
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        val memoryUsed = memInfo.totalMem - memInfo.availMem
        val memoryUsageRatio = memoryUsed.toFloat() / memInfo.totalMem
        
        // 估算温度（Android 没有统一 API，这里使用启发式方法）
        val temperature = estimateTemperature()
        val tempLevel = when {
            temperature >= TEMP_CRITICAL -> TempLevel.CRITICAL
            temperature >= TEMP_WARNING -> TempLevel.HOT
            temperature >= TEMP_NORMAL -> TempLevel.WARM
            else -> TempLevel.NORMAL
        }
        
        _tuningState.value = _tuningState.value.copy(
            cpuUsage = getCpuUsage(),
            memoryUsage = memoryUsageRatio,
            memoryAvailable = memInfo.availMem,
            temperature = temperature,
            temperatureLevel = tempLevel
        )
    }
    
    /**
     * 估算设备温度
     */
    private fun estimateTemperature(): Float {
        return try {
            // 尝试读取系统温度
            val tempPaths = listOf(
                "/sys/class/thermal/thermal_zone0/temp",
                "/sys/devices/virtual/thermal/thermal_zone0/temp"
            )
            
            for (path in tempPaths) {
                val tempFile = File(path)
                if (tempFile.exists()) {
                    val tempStr = tempFile.readText().trim()
                    val tempMilliCelsius = tempStr.toFloatOrNull() ?: continue
                    return tempMilliCelsius / 1000f
                }
            }
            
            // 如果无法读取，使用 CPU 使用率估算
            val cpuUsage = getCpuUsage()
            TEMP_NORMAL + (cpuUsage * 0.2f)
        } catch (e: Exception) {
            TEMP_NORMAL
        }
    }
    
    /**
     * 获取 CPU 使用率
     */
    private fun getCpuUsage(): Float {
        return try {
            val statFile = File("/proc/stat")
            val lines = statFile.readLines()
            
            if (lines.isEmpty()) return 0f
            
            val firstLine = lines.first()
            val values = firstLine.split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
            
            if (values.size < 4) return 0f
            
            val user = values[1]
            val nice = values[2]
            val system = values[3]
            val idle = values[4]
            val iowait = values.getOrElse(5) { 0 }
            val irq = values.getOrElse(6) { 0 }
            val softirq = values.getOrElse(7) { 0 }
            
            val total = user + nice + system + idle + iowait + irq + softirq
            val busy = total - idle - iowait
            
            (busy.toFloat() / total.toFloat() * 100f).coerceIn(0f, 100f)
        } catch (e: Exception) {
            0f
        }
    }
    
    /**
     * 根据状态自动调整参数
     */
    private fun autoAdjustIfNeeded() {
        val state = _tuningState.value
        val current = _currentConfig.value
        
        var newConfig = current.copy(lastUpdated = System.currentTimeMillis())
        var hasChange = false
        
        // 温度过高时减少负载
        when (state.temperatureLevel) {
            TempLevel.CRITICAL -> {
                // 严重过热，大幅降低
                if (current.threads > 1) {
                    newConfig = newConfig.copy(threads = current.threads - 2)
                    hasChange = true
                }
                if (current.contextSize > 512) {
                    newConfig = newConfig.copy(contextSize = (current.contextSize * 0.5).toInt())
                    hasChange = true
                }
            }
            TempLevel.HOT -> {
                // 发热，适当降低
                if (current.threads > 2) {
                    newConfig = newConfig.copy(threads = current.threads - 1)
                    hasChange = true
                }
                if (current.contextSize > 1024) {
                    newConfig = newConfig.copy(contextSize = (current.contextSize * 0.75).toInt())
                    hasChange = true
                }
            }
            else -> {}
        }
        
        // 内存压力过高时减少配置
        when {
            state.memoryUsage > MEMORY_CRITICAL_RATIO -> {
                if (current.contextSize > 512) {
                    newConfig = newConfig.copy(contextSize = (current.contextSize * 0.5).toInt())
                    hasChange = true
                }
                if (current.batchSize > 1) {
                    newConfig = newConfig.copy(batchSize = 1)
                    hasChange = true
                }
            }
            state.memoryUsage > MEMORY_WARNING_RATIO -> {
                if (current.contextSize > 1024) {
                    newConfig = newConfig.copy(contextSize = (current.contextSize * 0.75).toInt())
                    hasChange = true
                }
            }
        }
        
        // CPU 使用率高时优化
        if (state.cpuUsage > 90f && current.threads > 2) {
            newConfig = newConfig.copy(threads = current.threads - 1)
            hasChange = true
        }
        
        if (hasChange) {
            _currentConfig.value = newConfig
            _tuningState.value = _tuningState.value.copy(
                recommendedThreads = newConfig.threads,
                recommendedContextSize = newConfig.contextSize,
                recommendedBatchSize = newConfig.batchSize
            )
            saveConfig()
            Log.i(TAG, "Auto-adjusted config: threads=${newConfig.threads}, context=${newConfig.contextSize}")
        }
    }
    
    /**
     * 手动设置参数
     */
    fun setConfig(config: InferenceConfig) {
        _currentConfig.value = config.copy(lastUpdated = System.currentTimeMillis())
        saveConfig()
        Log.i(TAG, "Manual config set: $config")
    }
    
    /**
     * 应用性能预设
     */
    fun applyPreset(preset: Preset) {
        val config = when (preset) {
            Preset.BALANCED -> InferenceConfig(
                threads = 4,
                contextSize = 2048,
                batchSize = 1,
                temperature = 0.7f,
                maxTokens = 512
            )
            Preset.PERFORMANCE -> InferenceConfig(
                threads = 6,
                contextSize = 4096,
                batchSize = 2,
                temperature = 0.8f,
                maxTokens = 1024
            )
            Preset.BATTERY_SAVER -> InferenceConfig(
                threads = 2,
                contextSize = 1024,
                batchSize = 1,
                temperature = 0.6f,
                maxTokens = 256
            )
            Preset.QUALITY -> InferenceConfig(
                threads = 4,
                contextSize = 3072,
                batchSize = 1,
                temperature = 0.9f,
                maxTokens = 768
            )
        }
        
        setConfig(config)
        _tuningState.value = _tuningState.value.copy(
            recommendedThreads = config.threads,
            recommendedContextSize = config.contextSize,
            recommendedBatchSize = config.batchSize
        )
    }
    
    /**
     * 保存配置
     */
    private fun saveConfig() {
        try {
            val config = _currentConfig.value
            val json = """
                {
                    "threads": ${config.threads},
                    "contextSize": ${config.contextSize},
                    "batchSize": ${config.batchSize},
                    "gpuEnabled": ${config.gpuEnabled},
                    "useMmap": ${config.useMmap},
                    "useMlock": ${config.useMlock},
                    "temperature": ${config.temperature},
                    "maxTokens": ${config.maxTokens},
                    "topK": ${config.topK},
                    "topP": ${config.topP},
                    "repeatPenalty": ${config.repeatPenalty},
                    "lastUpdated": ${config.lastUpdated}
                }
            """.trimIndent()
            configFile.writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save config", e)
        }
    }
    
    /**
     * 加载配置
     */
    private fun loadConfig() {
        try {
            if (!configFile.exists()) return
            
            val json = configFile.readText()
            val parts = json.split("\n")
            
            val threads = parts.find { it.contains("threads") }?.split(":")?.get(1)?.trim()?.trim(',')?.toIntOrNull() ?: 4
            val contextSize = parts.find { it.contains("contextSize") }?.split(":")?.get(1)?.trim()?.trim(',')?.toIntOrNull() ?: 2048
            val batchSize = parts.find { it.contains("batchSize") }?.split(":")?.get(1)?.trim()?.trim(',')?.toIntOrNull() ?: 1
            val temperature = parts.find { it.contains("temperature") }?.split(":")?.get(1)?.trim()?.trim(',')?.toFloatOrNull() ?: 0.7f
            val maxTokens = parts.find { it.contains("maxTokens") }?.split(":")?.get(1)?.trim()?.trim(',')?.toIntOrNull() ?: 512
            
            _currentConfig.value = InferenceConfig(
                threads = threads,
                contextSize = contextSize,
                batchSize = batchSize,
                temperature = temperature,
                maxTokens = maxTokens
            )
            
            Log.i(TAG, "Config loaded from file")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load config", e)
        }
    }
    
    /**
     * 性能预设
     */
    enum class Preset {
        BALANCED,      // 平衡模式
        PERFORMANCE,   // 性能模式
        BATTERY_SAVER, // 省电模式
        QUALITY        // 质量模式
    }
    
    /**
     * 获取调优状态报告
     */
    fun getStatusReport(): String {
        val state = _tuningState.value
        val config = _currentConfig.value
        val deviceInfo = getDeviceInfo()
        
        val tempEmoji = when (state.temperatureLevel) {
            TempLevel.NORMAL -> "✅"
            TempLevel.WARM -> "🌡️"
            TempLevel.HOT -> "🔥"
            TempLevel.CRITICAL -> "☀️"
        }
        
        val memPercent = (state.memoryUsage * 100).toInt()
        val memEmoji = when {
            memPercent > 85 -> "🔴"
            memPercent > 70 -> "🟡"
            else -> "🟢"
        }
        
        return buildString {
            appendLine("📊 设备状态监控")
            appendLine("=".repeat(40))
            appendLine()
            appendLine("🖥️ 设备信息:")
            appendLine("  • CPU 核心: ${deviceInfo.cores}")
            appendLine("  • 总内存: ${formatBytes(deviceInfo.totalMemory)}")
            appendLine("  • SoC: ${deviceInfo.socModel}")
            appendLine("  • 架构: ${deviceInfo.architecture}")
            appendLine()
            appendLine("📈 当前状态:")
            appendLine("  $tempEmoji 温度: ${String.format("%.1f", state.temperature)}°C")
            appendLine("  $memEmoji 内存: $memPercent%")
            appendLine("  • CPU: ${state.cpuUsage.toInt()}%")
            appendLine()
            appendLine("⚙️ 当前配置:")
            appendLine("  • 线程数: ${config.threads}")
            appendLine("  • 上下文: ${config.contextSize}")
            appendLine("  • 批大小: ${config.batchSize}")
            appendLine("  • Temperature: ${config.temperature}")
            appendLine()
            appendLine("🔧 自动调优: ${if (state.autoTuningEnabled) "已启用" else "已禁用"}")
        }
    }
    
    /**
     * 格式化字节数
     */
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.1f KB", bytes / 1024.0)
        }
    }
}
