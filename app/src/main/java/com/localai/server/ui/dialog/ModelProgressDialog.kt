package com.localai.server.ui.dialog

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.ProgressBar
import com.localai.server.R
import java.text.DecimalFormat

/**
 * 模型进度弹窗管理器
 * 管理三个阶段：下载、等待加载、加载中
 */
class ModelProgressDialog(private val context: Context) {

    private var currentDialog: Dialog? = null
    private var currentType: DialogType? = null
    
    private val decimalFormat = DecimalFormat("#.#")

    enum class DialogType {
        DOWNLOAD,    // 下载模型
        WAITING,     // 等待加载模型
        LOADING      // 加载中
    }

    fun show(type: DialogType) {
        // 如果类型相同且对话框已显示，不重新创建
        if (currentType == type && currentDialog?.isShowing == true) {
            return
        }
        
        dismiss()
        currentType = type
        
        val view = when (type) {
            DialogType.DOWNLOAD -> createDownloadView()
            DialogType.WAITING -> createLoadingView("等待加载模型")
            DialogType.LOADING -> createLoadingView("加载中")
        }
        
        currentDialog = Dialog(context, android.R.style.Theme_Material_Light_Dialog_NoActionBar).apply {
            setContentView(view)
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            show()
        }
    }

    private fun createDownloadView(): View {
        return LayoutInflater.from(context).inflate(R.layout.dialog_model_download, null)
    }

    private fun createLoadingView(title: String): View {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_model_loading, null)
        view.findViewById<TextView>(R.id.tv_title).text = title
        return view
    }

    fun dismiss() {
        currentDialog?.dismiss()
        currentDialog = null
        currentType = null
    }

    /**
     * 更新下载进度
     */
    fun updateDownloadProgress(
        percent: Int,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSec: Long
    ) {
        if (currentType != DialogType.DOWNLOAD) return
        
        currentDialog?.findViewById<TextView>(R.id.tv_percent)?.text = "$percent%"
        currentDialog?.findViewById<ProgressBar>(R.id.progress_bar)?.progress = percent
        
        val downloadedMB = downloadedBytes / (1024 * 1024)
        val totalMB = totalBytes / (1024 * 1024)
        currentDialog?.findViewById<TextView>(R.id.tv_size)?.text = "$downloadedMB MB / $totalMB MB"
        
        val speedMB = decimalFormat.format(speedBytesPerSec / (1024.0 * 1024.0))
        currentDialog?.findViewById<TextView>(R.id.tv_speed)?.text = "速度: $speedMB MB/s"
        
        if (speedBytesPerSec > 0) {
            val remainingBytes = totalBytes - downloadedBytes
            val remainingSec = remainingBytes / speedBytesPerSec
            val remainingMin = remainingSec / 60
            val remainingSecRemain = remainingSec % 60
            currentDialog?.findViewById<TextView>(R.id.tv_remaining)?.text = 
                "剩余: ${remainingMin}分${remainingSecRemain}秒"
        }
    }

    /**
     * 更新等待/加载进度
     */
    fun updateLoadingProgress(percent: Int, logMessage: String) {
        if (currentType != DialogType.WAITING && currentType != DialogType.LOADING) return
        
        currentDialog?.findViewById<TextView>(R.id.tv_percent)?.text = "$percent%"
        currentDialog?.findViewById<ProgressBar>(R.id.progress_bar)?.progress = percent
        
        val tvLog = currentDialog?.findViewById<TextView>(R.id.tv_log)
        tvLog?.let {
            val currentText = it.text.toString()
            if (!currentText.contains(logMessage)) {
                it.text = "$currentText\n> $logMessage"
            }
        }
    }

    /**
     * 追加日志
     */
    fun appendLog(message: String) {
        if (currentType != DialogType.WAITING && currentType != DialogType.LOADING) return
        
        currentDialog?.findViewById<TextView>(R.id.tv_log)?.let {
            val currentText = it.text.toString()
            it.text = "$currentText\n> $message"
        }
    }

    fun isShowing(): Boolean = currentDialog?.isShowing == true
}
