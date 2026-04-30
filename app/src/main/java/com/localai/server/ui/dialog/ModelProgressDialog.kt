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
 * 防止弹窗重复显示和闪烁
 */
class ModelProgressDialog(private val context: Context) {

    private var currentDialog: Dialog? = null
    private var currentType: DialogType? = null
    private var isShowing = false  // 添加状态标志，防止竞态条件
    private var currentModelName: String = ""  // 当前下载的模型名称
    
    private val decimalFormat = DecimalFormat("#.#")

    enum class DialogType {
        DOWNLOAD,    // 下载模型
        WAITING,     // 等待加载模型
        LOADING      // 加载中
    }

    /**
     * 设置要下载的模型名称
     * 必须在调用 show() 之前调用
     */
    fun setModelName(modelName: String) {
        currentModelName = modelName
    }
    
    /**
     * 获取当前设置的模型名称
     */
    fun getModelName(): String = currentModelName

    @Synchronized
    fun show(type: DialogType) {
        // 如果正在显示相同类型，不重新创建
        if (currentType == type && isShowing && currentDialog?.isShowing == true) {
            return
        }
        
        // 防止快速重复调用导致的闪烁
        if (isShowing && currentDialog?.isShowing == true) {
            // 相同类型但正在显示中，直接返回
            if (currentType == type) {
                return
            }
            // 不同类型，先dismiss再重建
            dismissInternal()
        }
        
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
            setOnDismissListener {
                this@ModelProgressDialog.isShowing = false
            }
            setOnCancelListener {
                this@ModelProgressDialog.isShowing = false
            }
            show()
            this@ModelProgressDialog.isShowing = true
        }
    }
    
    @Synchronized
    private fun dismissInternal() {
        try {
            currentDialog?.dismiss()
        } catch (e: Exception) {
            // 忽略已dismiss的异常
        }
        currentDialog = null
        isShowing = false
    }

    @Synchronized
    fun dismiss() {
        dismissInternal()
        currentType = null
    }

    private fun createDownloadView(): View {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_model_download, null)
        // 设置模型名称
        if (currentModelName.isNotEmpty()) {
            view.findViewById<TextView>(R.id.tv_model_name)?.text = currentModelName
        }
        return view
    }

    private fun createLoadingView(title: String): View {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_model_loading, null)
        view.findViewById<TextView>(R.id.tv_title).text = title
        return view
    }

    /**
     * 更新下载进度
     */
    fun updateDownloadProgress(
        percent: Int,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSec: Long,
        modelName: String? = null
    ) {
        if (currentType != DialogType.DOWNLOAD) return
        if (!isShowing || currentDialog?.isShowing != true) return
        
        // 如果传入了新的模型名称，更新显示
        modelName?.let {
            if (it.isNotEmpty()) {
                currentDialog?.findViewById<TextView>(R.id.tv_model_name)?.text = it
            }
        }
        
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
        if (!isShowing || currentDialog?.isShowing != true) return
        
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
        if (!isShowing || currentDialog?.isShowing != true) return
        
        currentDialog?.findViewById<TextView>(R.id.tv_log)?.let {
            val currentText = it.text.toString()
            it.text = "$currentText\n> $message"
        }
    }

    @Synchronized
    fun isShowing(): Boolean = isShowing && currentDialog?.isShowing == true
}
