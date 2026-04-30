package com.localai.server.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.localai.server.App
import com.localai.server.MainActivity
import com.localai.server.R
import com.localai.server.engine.LlamaEngine
import com.localai.server.server.AiHttpServer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class AIService : Service() {

    companion object {
        private const val TAG = "AIService"
        const val NOTIFICATION_ID = 1001
        const val SERVER_PORT = 8080
        
        const val ACTION_START = "com.localai.server.action.START"
        const val ACTION_STOP = "com.localai.server.action.STOP"
        const val ACTION_LOAD_MODEL = "com.localai.server.action.LOAD_MODEL"
        const val ACTION_UNLOAD_MODEL = "com.localai.server.action.UNLOAD_MODEL"
        const val EXTRA_MODEL_PATH = "model_path"
        const val EXTRA_N_CTX = "n_ctx"
        const val EXTRA_N_THREADS = "n_threads"
        const val EXTRA_N_BATCH = "n_batch"  // [Deprecated] MNN不使用此参数
        const val EXTRA_N_GPU_LAYERS = "n_gpu_layers"  // [Deprecated] MNN不使用此参数
        
        // 服务状态
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning
        
        private val _modelLoaded = MutableStateFlow(false)
        val modelLoaded: StateFlow<Boolean> = _modelLoaded
        
        private val _statusMessage = MutableStateFlow("")
        val statusMessage: StateFlow<String> = _statusMessage
        
        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage
        
        fun start(context: Context) {
            val intent = Intent(context, AIService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, AIService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
        
        fun loadModel(context: Context, path: String, nCtx: Int = 2048, nThreads: Int = 4, nBatch: Int = 512, nGpuLayers: Int = 0) {
            val intent = Intent(context, AIService::class.java).apply {
                action = ACTION_LOAD_MODEL
                putExtra(EXTRA_MODEL_PATH, path)
                putExtra(EXTRA_N_CTX, nCtx)
                putExtra(EXTRA_N_THREADS, nThreads)
                putExtra(EXTRA_N_BATCH, nBatch)
                putExtra(EXTRA_N_GPU_LAYERS, nGpuLayers)
            }
            context.startService(intent)
        }
        
        fun updateModelLoaded(loaded: Boolean) {
            _modelLoaded.value = loaded
        }
    }
    
    @Inject
    lateinit var engine: LlamaEngine
    
    private val binder = LocalBinder()
    private lateinit var notificationBuilder: NotificationCompat.Builder
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var httpServer: AiHttpServer? = null
    
    inner class LocalBinder : Binder() {
        fun getService(): AIService = this@AIService
        fun getEngine(): LlamaEngine = engine
    }
    
    override fun onCreate() {
        super.onCreate()
        setupNotification()
    }
    
    override fun onBind(intent: Intent): IBinder {
        return binder
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                _isRunning.value = true
                _statusMessage.value = "服务已启动"
                _errorMessage.value = null
                startHttpServer()
                updateNotification("服务运行中")
            }
            ACTION_STOP -> {
                stopService()
            }
            ACTION_LOAD_MODEL -> {
                val path = intent.getStringExtra(EXTRA_MODEL_PATH)
                val nCtx = intent.getIntExtra(EXTRA_N_CTX, 2048)
                val nThreads = intent.getIntExtra(EXTRA_N_THREADS, 4)
                val nBatch = intent.getIntExtra(EXTRA_N_BATCH, 512)  // [Deprecated] MNN不使用
                val nGpuLayers = intent.getIntExtra(EXTRA_N_GPU_LAYERS, 0)  // [Deprecated] MNN不使用
                Log.w(TAG, "loadModel: nBatch=$nBatch, nGpuLayers=$nGpuLayers - [Deprecated] MNN不使用这些参数")
                path?.let { loadModelInternal(it, nCtx, nThreads, nBatch, nGpuLayers) }
            }
        }
        
        return START_STICKY
    }
    
    private fun setupNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        notificationBuilder = NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("服务已启动，等待加载模型")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
        
        startForeground(NOTIFICATION_ID, notificationBuilder.build())
    }
    
    private fun updateNotification(text: String) {
        notificationBuilder.setContentText(text)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notificationBuilder.build())
        _statusMessage.value = text
    }
    
    private fun startHttpServer() {
        try {
            if (httpServer == null) {
                httpServer = AiHttpServer(engine, SERVER_PORT)
                httpServer?.start(30000, false)  // 30s socket timeout, non-daemon thread
                Log.i(TAG, "HTTP Server started on port $SERVER_PORT (non-daemon)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP server", e)
            _errorMessage.value = "HTTP服务启动失败: ${e.message}"
        }
    }
    
    private fun stopHttpServer() {
        try {
            httpServer?.stop()
            httpServer = null
            Log.i(TAG, "HTTP Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping HTTP server", e)
        }
    }
    
    private fun loadModelInternal(path: String, nCtx: Int, nThreads: Int, nBatch: Int, nGpuLayers: Int = 0) {
        serviceScope.launch {
            updateNotification("正在加载模型...")
            _errorMessage.value = null
            
            val success = withContext(Dispatchers.Default) {
                engine.loadModel(path, nCtx, nThreads, nBatch, flashAttn = true, cacheType = "f16", nGpuLayers = nGpuLayers)
            }
            
            if (success) {
                _modelLoaded.value = true
                updateNotification("模型已加载，服务就绪")
                Log.i(TAG, "Model loaded successfully: $path")
            } else {
                val error = LlamaEngine.getLoadError() ?: "未知错误"
                _modelLoaded.value = false
                _errorMessage.value = "模型加载失败: $error"
                updateNotification("模型加载失败")
                Log.e(TAG, "Failed to load model: $path, error: $error")
            }
        }
    }
    
    private fun stopService() {
        serviceScope.launch {
            stopHttpServer()
            engine.unloadModel()
            _modelLoaded.value = false
            _isRunning.value = false
            _errorMessage.value = null
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            stopSelf()
        }
    }
    
    override fun onDestroy() {
        stopHttpServer()
        engine.unloadModel()
        _modelLoaded.value = false
        _isRunning.value = false
        serviceScope.cancel()
        super.onDestroy()
    }
}
