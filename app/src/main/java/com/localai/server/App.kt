package com.localai.server

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.localai.server.engine.LlamaEngine
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class App : Application() {
    
    companion object {
        const val CHANNEL_ID = "ai_service_channel"
        const val CHANNEL_NAME = "AI服务"
        const val CHANNEL_DESC = "本地AI推理服务通知"
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // 初始化LlamaEngine
        LlamaEngine.initialize(this)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = CHANNEL_DESC
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
