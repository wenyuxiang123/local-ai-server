package com.localai.server.ui.tabs

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.server.data.local.entity.Conversation
import com.localai.server.data.local.entity.Message
import com.localai.server.data.repository.ChatApiService
import com.localai.server.data.repository.ChatMessage
import com.localai.server.data.repository.ChatRepository
import com.localai.server.domain.repository.AIRepository
import com.localai.server.network.WebSearchService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

/**
 * 附件数据类
 */
data class Attachment(
    val uri: Uri,
    val name: String,
    val type: String  // "image", "video", "document", "file"
)

/**
 * 主页聊天 ViewModel
 * 整合聊天功能和联网搜索功能
 */
@HiltViewModel
class HomeChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val chatApiService: ChatApiService,
    private val aiRepository: AIRepository,
    private val webSearchService: WebSearchService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // UI State
    private val _uiState = MutableStateFlow(HomeChatUiState())
    val uiState: StateFlow<HomeChatUiState> = _uiState.asStateFlow()

    // Conversations
    val conversations: StateFlow<List<Conversation>> = chatRepository.getAllConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Current conversation messages
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    // Selected conversation ID
    private val _currentConversationId = MutableStateFlow<Long?>(null)
    val currentConversationId: StateFlow<Long?> = _currentConversationId.asStateFlow()

    // 联网模式开关
    private val _webSearchEnabled = MutableStateFlow(false)
    val webSearchEnabled: StateFlow<Boolean> = _webSearchEnabled.asStateFlow()

    // 思考模式开关
    private val _thinkModeEnabled = MutableStateFlow(false)
    val thinkModeEnabled: StateFlow<Boolean> = _thinkModeEnabled.asStateFlow()

    // 当前附件
    private val _attachment = MutableStateFlow<Attachment?>(null)
    val attachment: StateFlow<Attachment?> = _attachment.asStateFlow()

    // Effects for one-time events
    private val _effect = MutableSharedFlow<HomeChatEffect>()
    val effect: SharedFlow<HomeChatEffect> = _effect

    init {
        // Observe conversations
        viewModelScope.launch {
            conversations.collect { list ->
                // Auto-select first conversation if none selected
                if (_currentConversationId.value == null && list.isNotEmpty()) {
                    selectConversation(list.first().id)
                }
            }
        }
    }

    /**
     * 切换联网搜索模式
     */
    fun toggleWebSearch() {
        _webSearchEnabled.value = !_webSearchEnabled.value
        com.localai.server.util.FileLog.log("HomeChatVM", "联网搜索开关: " + _webSearchEnabled.value)
    }

    /**
     * 设置联网搜索模式
     */
    fun setWebSearchEnabled(enabled: Boolean) {
        _webSearchEnabled.value = enabled
    }

    /**
     * 切换思考模式
     */
    fun toggleThinkMode() {
        _thinkModeEnabled.value = !_thinkModeEnabled.value
        com.localai.server.util.FileLog.log("HomeChatVM", "思考模式开关: " + _thinkModeEnabled.value)
    }

    /**
     * 设置思考模式
     */
    fun setThinkModeEnabled(enabled: Boolean) {
        _thinkModeEnabled.value = enabled
    }

    /**
     * 设置附件
     */
    fun setAttachment(uri: Uri, name: String?, type: String) {
        _attachment.value = Attachment(uri, name ?: "文件", type)
    }

    /**
     * 清除附件
     */
    fun clearAttachment() {
        _attachment.value = null
    }

    /**
     * 复制 URI 内容到临时文件
     */
    private suspend fun copyUriToTempFile(uri: Uri, fileName: String): File? {
        return try {
            val cacheDir = File(context.cacheDir, "attachments")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            
            val destFile = File(cacheDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            destFile
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 读取文本文件内容
     */
    private suspend fun readTextContent(uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 构建附件描述文本
     */
    private suspend fun buildAttachmentDescription(attachment: Attachment): String {
        return when (attachment.type) {
            "image" -> {
                "[图片附件: ${attachment.name}]"
            }
            "video" -> {
                "[视频附件: ${attachment.name}]"
            }
            "document", "file" -> {
                val content = readTextContent(attachment.uri)
                if (content != null && content.isNotBlank()) {
                    buildString {
                        appendLine("[文档附件: ${attachment.name}]")
                        appendLine("--- 文件内容 ---")
                        appendLine(content.take(2000)) // 限制内容长度
                        if (content.length > 2000) appendLine("...(内容已截断)")
                        appendLine("---")
                    }
                } else {
                    "[文件附件: ${attachment.name}](无法读取文件内容)"
                }
            }
            else -> {
                "[附件: ${attachment.name}]"
            }
        }
    }

    /**
     * 发送带附件的消息
     */
    fun sendMessageWithAttachment(content: String, attachment: Attachment?) {
        val conversationId = _currentConversationId.value ?: run {
            viewModelScope.launch {
                createConversation()
                val newId = _currentConversationId.value
                if (newId != null) {
                    sendMessageWithAttachmentInternal(newId, content, attachment)
                }
            }
            return
        }

        viewModelScope.launch {
            sendMessageWithAttachmentInternal(conversationId, content, attachment)
        }
    }

    private suspend fun sendMessageWithAttachmentInternal(
        conversationId: Long, 
        content: String, 
        attachment: Attachment?
    ) {
        // 如果只有附件没有文字
        if (content.isBlank() && attachment == null) return

        val finalContent = if (attachment != null) {
            val attachmentDesc = buildAttachmentDescription(attachment)
            if (content.isNotBlank()) {
                "$content\n\n$attachmentDesc"
            } else {
                attachmentDesc
            }
        } else {
            content
        }

        // 使用现有的 sendMessage 方法发送
        sendMessageInternal(conversationId, finalContent)
    }

    /**
     * Create new conversation
     */
    fun createConversation() {
        viewModelScope.launch {
            try {
                val title = "新会话 ${System.currentTimeMillis() % 10000}"
                val id = chatRepository.createConversation(title)
                selectConversation(id)
                _effect.emit(HomeChatEffect.ScrollToBottom)
            } catch (e: Exception) {
                _effect.emit(HomeChatEffect.ShowError("创建会话失败: ${e.message}"))
            }
        }
    }

    /**
     * Select conversation and load messages
     */
    fun selectConversation(conversationId: Long) {
        viewModelScope.launch {
            _currentConversationId.value = conversationId

            // Load messages for this conversation
            chatRepository.getMessagesByConversation(conversationId).collect { msgs ->
                _messages.value = msgs
            }
        }
    }

    /**
     * Delete conversation
     */
    fun deleteConversation(conversationId: Long) {
        viewModelScope.launch {
            try {
                chatRepository.deleteConversation(conversationId)

                // If deleting current conversation, select another
                if (_currentConversationId.value == conversationId) {
                    val remaining = conversations.value.filter { it.id != conversationId }
                    if (remaining.isNotEmpty()) {
                        selectConversation(remaining.first().id)
                    } else {
                        _currentConversationId.value = null
                        _messages.value = emptyList()
                    }
                }

                _effect.emit(HomeChatEffect.ShowMessage("会话已删除"))
            } catch (e: Exception) {
                _effect.emit(HomeChatEffect.ShowError("删除会话失败: ${e.message}"))
            }
        }
    }

    /**
     * Send user message and get AI response
     */
    fun sendMessage(content: String) {
        val conversationId = _currentConversationId.value ?: run {
            // 如果没有会话，先创建一个
            viewModelScope.launch {
                createConversation()
                val newId = _currentConversationId.value
                if (newId != null) {
                    sendMessageInternal(newId, content)
                }
            }
            return
        }

        sendMessageInternal(conversationId, content)
    }

    /**
     * 内部发送消息方法
     */
    private fun sendMessageInternal(conversationId: Long, content: String) {
        if (content.isBlank()) return

        viewModelScope.launch {
            try {
                // Update UI state
                _uiState.update { it.copy(isLoading = true, error = null) }

                android.util.Log.i("HomeChatVM", "sendMessageInternal: webSearchEnabled=${_webSearchEnabled.value}, content=$content")

                // 如果启用联网搜索，先搜索再发送
                if (_webSearchEnabled.value) {
                    _uiState.update { it.copy(searchStatus = "正在搜索...") }
                    com.localai.server.util.FileLog.log("HomeChatVM", "联网搜索已启用，开始搜索: " + content)

                    val searchResponse = webSearchService.search(content)

                    if (searchResponse.error != null) {
                        com.localai.server.util.FileLog.log("HomeChatVM", "搜索失败: " + searchResponse.error)
                        _effect.emit(HomeChatEffect.ShowError("搜索失败: ${searchResponse.error}"))
                    } else {
                        val searchResults = searchResponse.results.take(5)
                        // 使用 WebSearchService 的 buildSearchContext 方法确保长度限制在 800 字以内
                        val searchContext = webSearchService.buildSearchContext(searchResults)
                        val statusMsg = if (searchResults.isNotEmpty()) {
                            "找到 " + searchResults.size + " 条结果"
                        } else {
                            "未找到相关结果"
                        }
                        _uiState.update { it.copy(searchStatus = statusMsg) }
                        com.localai.server.util.FileLog.log("HomeChatVM", "搜索完成: " + statusMsg + ", 上下文长度=" + searchContext.length)

                        // 使用搜索上下文发送消息
                        sendMessageWithContext(conversationId, content, searchContext, searchResults.map { it.url })
                        return@launch
                    }
                }

                // 正常发送消息（不启用联网搜索）
                sendNormalMessage(conversationId, content)

            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
                _effect.emit(HomeChatEffect.ShowError("发送消息失败: ${e.message}"))
            }
        }
    }

    /**
     * 使用搜索上下文发送消息
     */
    private suspend fun sendMessageWithContext(
        conversationId: Long,
        userContent: String,
        searchContext: String,
        sources: List<String>
    ) {
        try {
            // Save user message
            val userMessage = if (searchContext.isNotEmpty()) {
                "$userContent\n\n[联网搜索已启用，参考来源: ${sources.joinToString(", ")}]"
            } else {
                userContent
            }
            chatRepository.sendMessage(conversationId, userMessage)

            // Scroll to bottom
            _effect.emit(HomeChatEffect.ScrollToBottom)

            // Always use 127.0.0.1 for internal chat (WiFi IP can cause connection issues)
            val baseUrl = "http://127.0.0.1:8080"

            // Get history messages for context
            val historyMessages = _messages.value.map { msg ->
                ChatMessage(role = msg.role, content = msg.content)
            }

            // 构建带搜索上下文的系统提示
            val thinkTag = if (_thinkModeEnabled.value) " /think" else " /no_think"
            val systemPrompt = if (searchContext.isNotEmpty()) {
                """
                You are a helpful AI assistant. Please answer the user's question based on the search results provided below.
                If the search results don't contain enough information to answer the question, say so and provide what information you can.

                Search Results:
                $searchContext

                Important: 
                - Answer in the same language as the user's question
                - If you use information from the search results, mention the source at the end
                - Be concise but informative
                $thinkTag
                """.trimIndent()
            } else {
                "You are a helpful AI assistant.$thinkTag"
            }

            // Build messages
            val allMessages = mutableListOf<ChatMessage>()
            allMessages.add(ChatMessage("system", systemPrompt))
            allMessages.addAll(historyMessages)
            allMessages.add(ChatMessage("user", userContent))

            // Call AI API
            chatApiService.sendMessage(baseUrl, allMessages)
                .onSuccess { response ->
                    // Save assistant response with source info if available
                    val assistantResponse = if (sources.isNotEmpty()) {
                        "$response\n\n---\n📎 参考来源:\n${sources.joinToString("\n") { "- $it" }}"
                    } else {
                        response
                    }
                    chatRepository.addAssistantMessage(conversationId, assistantResponse)

                    // Update conversation title if first message
                    if (_messages.value.size <= 1) {
                        val title = userContent.take(20).let {
                            if (userContent.length > 20) "$it..." else it
                        }
                        chatRepository.updateConversationTitle(conversationId, title)
                    }

                    _uiState.update { it.copy(isLoading = false, searchStatus = null) }
                    _effect.emit(HomeChatEffect.ScrollToBottom)
                }
                .onFailure { error ->
                    val errorMsg = error.message ?: "未知错误"
                    _uiState.update { it.copy(isLoading = false, error = errorMsg, searchStatus = null) }

                    // Save error as assistant message for visibility
                    chatRepository.addAssistantMessage(
                        conversationId,
                        "[错误] 无法连接到AI服务: $errorMsg"
                    )
                    _effect.emit(HomeChatEffect.ScrollToBottom)
                }

        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, error = e.message, searchStatus = null) }
            _effect.emit(HomeChatEffect.ShowError("发送消息失败: ${e.message}"))
        }
    }

    /**
     * 发送普通消息（不启用联网搜索）
     */
    private suspend fun sendNormalMessage(conversationId: Long, content: String) {
        // Save user message
        chatRepository.sendMessage(conversationId, content)

        // Scroll to bottom
        _effect.emit(HomeChatEffect.ScrollToBottom)

        // Always use 127.0.0.1 for internal chat (WiFi IP can cause connection issues)
        val baseUrl = "http://127.0.0.1:8080"

        // Get history messages for context
        val historyMessages = _messages.value.map { msg ->
            ChatMessage(role = msg.role, content = msg.content)
        }

        // Build messages with system prompt
        val allMessages = chatApiService.buildMessages(content, historyMessages, _thinkModeEnabled.value)

        // Call AI API
        chatApiService.sendMessage(baseUrl, allMessages)
            .onSuccess { response ->
                // Save assistant response
                chatRepository.addAssistantMessage(conversationId, response)

                // Update conversation title if first message
                if (_messages.value.size <= 1) {
                    val title = content.take(20).let {
                        if (content.length > 20) "$it..." else it
                    }
                    chatRepository.updateConversationTitle(conversationId, title)
                }

                _uiState.update { it.copy(isLoading = false) }
                _effect.emit(HomeChatEffect.ScrollToBottom)
            }
            .onFailure { error ->
                val errorMsg = error.message ?: "未知错误"
                _uiState.update { it.copy(isLoading = false, error = errorMsg) }

                // Save error as assistant message for visibility
                chatRepository.addAssistantMessage(
                    conversationId,
                    "[错误] 无法连接到AI服务: $errorMsg"
                )
                _effect.emit(HomeChatEffect.ScrollToBottom)
            }
    }

    /**
     * Clear current messages
     */
    fun clearMessages() {
        val conversationId = _currentConversationId.value ?: return

        viewModelScope.launch {
            try {
                chatRepository.deleteMessages(conversationId)
                _messages.value = emptyList()
            } catch (e: Exception) {
                _effect.emit(HomeChatEffect.ShowError("清空消息失败: ${e.message}"))
            }
        }
    }
}

/**
 * UI State
 */
data class HomeChatUiState(
    val isLoading: Boolean = false,
    val searchStatus: String? = null,  // 联网搜索状态
    val error: String? = null
)

/**
 * One-time effects
 */
sealed class HomeChatEffect {
    data class ShowMessage(val message: String) : HomeChatEffect()
    data class ShowError(val message: String) : HomeChatEffect()
    object ScrollToBottom : HomeChatEffect()
}
