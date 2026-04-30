package com.localai.server.ui.tabs

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.localai.server.R
import com.localai.server.databinding.FragmentHomeBinding
import com.localai.server.ui.chat.MessageAdapter
import com.localai.server.ui.dialog.ModelProgressDialog
import com.localai.server.ui.main.LoadingPhase
import com.localai.server.ui.main.MainViewModel
import com.localai.server.util.ModelExtractor
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * 首页 Tab - 聊天界面
 * 扣子风格界面：顶部显示AI生成提示，消息列表，底部输入框
 */
@AndroidEntryPoint
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    
    private var modelProgressDialog: ModelProgressDialog? = null

    // 主页聊天 ViewModel
    private val chatViewModel: HomeChatViewModel by viewModels()

    // 主 ViewModel（用于服务状态）
    private val mainViewModel: MainViewModel by activityViewModels()

    // ModelExtractor 用于获取模型名称 (Hilt 注入)
    @Inject lateinit var modelExtractor: ModelExtractor

    private lateinit var messageAdapter: MessageAdapter

    // 当前选中的附件
    private var currentAttachmentUri: Uri? = null
    private var currentAttachmentName: String? = null

    // 文件选择器 - 照片
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleFileSelected(it, "image") }
    }

    // 文件选择器 - 视频
    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleFileSelected(it, "video") }
    }

    // 文件选择器 - 文档
    private val pickDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleFileSelected(it, "document") }
    }

    // 文件选择器 - 其他文件
    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleFileSelected(it, "file") }
    }

    // 权限请求
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Toast.makeText(requireContext(), "需要存储权限才能上传文件", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
        observeChatState()
        observeMainState()
        observeEffects()
        observeAttachment()
    }

    private fun setupViews() {
        // Setup messages RecyclerView
        messageAdapter = MessageAdapter()
        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = false
            }
            adapter = messageAdapter
        }

        // Send button
        binding.btnSend.setOnClickListener {
            sendMessage()
        }

        // Input field - send on enter
        binding.etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }

        // 联网搜索按钮
        binding.btnWebSearch.setOnClickListener {
            com.localai.server.util.FileLog.log("HomeFragment", "联网搜索按钮被点击")
            chatViewModel.toggleWebSearch()
        }

        // 文件上传按钮
        binding.btnUpload.setOnClickListener {
            showUploadMenu(it)
        }

        // 移除附件按钮
        binding.btnRemoveAttachment.setOnClickListener {
            removeAttachment()
        }
    }

    private fun showUploadMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_upload, popup.menu)
        
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_photo -> {
                    checkPermissionsAndPick { pickImageLauncher.launch("image/*") }
                    true
                }
                R.id.menu_video -> {
                    checkPermissionsAndPick { pickVideoLauncher.launch("video/*") }
                    true
                }
                R.id.menu_document -> {
                    checkPermissionsAndPick { 
                        pickDocumentLauncher.launch("application/pdf,text/plain,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                    }
                    true
                }
                R.id.menu_file -> {
                    checkPermissionsAndPick { pickFileLauncher.launch("*/*") }
                    true
                }
                else -> false
            }
        }
        
        popup.show()
    }

    private fun checkPermissionsAndPick(action: () -> Unit) {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            action()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    private fun handleFileSelected(uri: Uri, type: String) {
        currentAttachmentUri = uri
        currentAttachmentName = getFileName(uri)
        showAttachmentPreview(currentAttachmentName ?: "文件", type)
        chatViewModel.setAttachment(uri, currentAttachmentName, type)
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        try {
            val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        name = it.getString(nameIndex)
                    }
                }
            }
        } catch (e: Exception) {
            name = uri.lastPathSegment
        }
        return name
    }

    private fun showAttachmentPreview(fileName: String, type: String) {
        binding.attachmentPreview.isVisible = true
        binding.tvAttachmentName.text = fileName
        
        // 根据类型设置图标
        val iconRes = when (type) {
            "image" -> android.R.drawable.ic_menu_gallery
            "video" -> android.R.drawable.ic_menu_camera
            "document" -> R.drawable.ic_document
            else -> R.drawable.ic_attachment
        }
        binding.ivAttachmentIcon.setImageResource(iconRes)
    }

    private fun removeAttachment() {
        currentAttachmentUri = null
        currentAttachmentName = null
        binding.attachmentPreview.isVisible = false
        chatViewModel.clearAttachment()
    }

    private fun observeAttachment() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                chatViewModel.attachment.collect { attachment ->
                    if (attachment != null) {
                        showAttachmentPreview(attachment.name, attachment.type)
                    } else {
                        binding.attachmentPreview.isVisible = false
                    }
                }
            }
        }
    }

    private fun sendMessage() {
        val content = binding.etMessage.text.toString().trim()
        val attachment = chatViewModel.attachment.value
        
        if (content.isEmpty() && attachment == null) {
            return
        }
        
        chatViewModel.sendMessageWithAttachment(content, attachment)
        binding.etMessage.text?.clear()
        
        // 清空附件
        removeAttachment()
    }

    private fun observeChatState() {
        // 每个 Flow 需要独立的协程，否则第一个 collect 会阻塞后续的
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                chatViewModel.messages.collect { messages ->
                    messageAdapter.submitList(messages) {
                        if (messages.isNotEmpty()) {
                            binding.rvMessages.smoothScrollToPosition(messages.size - 1)
                        }
                    }
                    binding.emptyStateContainer.isVisible = messages.isEmpty()
                    binding.rvMessages.isVisible = messages.isNotEmpty()
                }
            }
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                chatViewModel.webSearchEnabled.collect { enabled ->
                    updateWebSearchButtonState(enabled)
                    if (enabled) {
                        binding.etMessage.hint = "🌐 联网搜索模式..."
                    } else {
                        binding.etMessage.hint = getString(R.string.chat_hint)
                    }
                }
            }
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                chatViewModel.uiState.collect { state ->
                    if (state.searchStatus != null) {
                        Toast.makeText(requireContext(), state.searchStatus, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    
    /**
     * 更新联网搜索按钮状态
     */
    private fun updateWebSearchButtonState(enabled: Boolean) {
        com.localai.server.util.FileLog.log("HomeFragment", "联网按钮状态更新: enabled=" + enabled)
        val tintColor = if (enabled) {
            // 高亮：绿色
            ContextCompat.getColor(requireContext(), R.color.status_online)
        } else {
            // 灰色
            ContextCompat.getColor(requireContext(), R.color.text_secondary)
        }
        binding.btnWebSearch.setColorFilter(tintColor)
    }

    private fun observeMainState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainViewModel.state.collect { state ->
                    // 处理三阶段加载弹窗
                    when (state.loadingPhase) {
                        LoadingPhase.DOWNLOADING -> {
                            if (modelProgressDialog == null) {
                                modelProgressDialog = ModelProgressDialog(requireContext())
                                // 设置要下载的模型名称
                                val modelName = modelExtractor.getModelFileName().removeSuffix(".gguf")
                                modelProgressDialog?.setModelName(modelName)
                            }
                            modelProgressDialog?.show(ModelProgressDialog.DialogType.DOWNLOAD)
                            modelProgressDialog?.updateDownloadProgress(
                                state.progress,
                                state.downloadedBytes,
                                state.totalBytes,
                                state.downloadSpeed
                            )
                        }
                        LoadingPhase.WAITING -> {
                            if (modelProgressDialog == null) {
                                modelProgressDialog = ModelProgressDialog(requireContext())
                                // 设置要下载的模型名称
                                val modelName = modelExtractor.getModelFileName().removeSuffix(".gguf")
                                modelProgressDialog?.setModelName(modelName)
                            }
                            modelProgressDialog?.show(ModelProgressDialog.DialogType.WAITING)
                            modelProgressDialog?.updateLoadingProgress(state.progress, state.logMessages)
                        }
                        LoadingPhase.LOADING -> {
                            if (modelProgressDialog == null) {
                                modelProgressDialog = ModelProgressDialog(requireContext())
                                // 设置要下载的模型名称
                                val modelName = modelExtractor.getModelFileName().removeSuffix(".gguf")
                                modelProgressDialog?.setModelName(modelName)
                            }
                            modelProgressDialog?.show(ModelProgressDialog.DialogType.LOADING)
                            modelProgressDialog?.updateLoadingProgress(state.progress, state.logMessages)
                        }
                        LoadingPhase.IDLE -> {
                            modelProgressDialog?.dismiss()
                        }
                    }
                    
                    // Update service status bar
                    when {
                        state.isLoading -> {
                            binding.serviceStatusBar.isVisible = true
                            binding.tvServiceStatus.text = getString(R.string.service_starting)
                        }
                        state.serviceRunning -> {
                            binding.serviceStatusBar.isVisible = false
                        }
                        else -> {
                            binding.serviceStatusBar.isVisible = true
                            binding.tvServiceStatus.text = getString(R.string.service_not_started)
                        }
                    }
                }
            }
        }
    }

    private fun observeEffects() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                chatViewModel.effect.collect { effect ->
                    when (effect) {
                        is HomeChatEffect.ShowError -> {
                            Toast.makeText(requireContext(), effect.message, Toast.LENGTH_LONG).show()
                        }
                        is HomeChatEffect.ShowMessage -> {
                            Toast.makeText(requireContext(), effect.message, Toast.LENGTH_SHORT).show()
                        }
                        is HomeChatEffect.ScrollToBottom -> {
                            // Scroll to bottom
                            val messages = chatViewModel.messages.value
                            if (messages.isNotEmpty()) {
                                binding.rvMessages.smoothScrollToPosition(messages.size - 1)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        modelProgressDialog?.dismiss()
        modelProgressDialog = null
        _binding = null
        super.onDestroyView()
    }
}
