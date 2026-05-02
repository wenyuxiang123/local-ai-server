package com.localai.server.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.server.data.local.entity.Conversation
import com.localai.server.data.local.entity.Message
import com.localai.server.data.repository.ChatApiService
import com.localai.server.data.repository.ChatMessage
import com.localai.server.data.repository.ChatRepository
import com.localai.server.domain.repository.AIRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val chatApiService: ChatApiService,
    private val aiRepository: AIRepository
) : ViewModel() {
    
    // UI State
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    
    // Conversations
    val conversations: StateFlow<List<Conversation>> = chatRepository.getAllConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    // Current conversation messages
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()
    
    // Selected conversation ID
    private val _currentConversationId = MutableStateFlow<Long?>(null)
    val currentConversationId: StateFlow<Long?> = _currentConversationId.asStateFlow()

    // 思考模式开关
    private val _thinkModeEnabled = MutableStateFlow(false)
    val thinkModeEnabled: StateFlow<Boolean> = _thinkModeEnabled.asStateFlow()

    // Effects for one-time events
    private val _effect = MutableSharedFlow<ChatEffect>()
    private val _generationPhase = MutableStateFlow(GenerationPhase.Idle)
    val generationPhase: StateFlow<GenerationPhase> = _generationPhase
    val effect: SharedFlow<ChatEffect> = _effect
    
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
     * Create new conversation
     */
    fun createConversation() {
        viewModelScope.launch {
            try {
                val title = "新会话 ${System.currentTimeMillis() % 10000}"
                val id = chatRepository.createConversation(title)
                selectConversation(id)
                _effect.emit(ChatEffect.ScrollToBottom)
            } catch (e: Exception) {
                _effect.emit(ChatEffect.ShowError("创建会话失败: ${e.message}"))
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
                
                _effect.emit(ChatEffect.ShowMessage("会话已删除"))
            } catch (e: Exception) {
                _effect.emit(ChatEffect.ShowError("删除会话失败: ${e.message}"))
            }
        }
    }

    /**
     * 切换思考模式
     */
    fun toggleThinkMode() {
        _thinkModeEnabled.value = !_thinkModeEnabled.value
    }

    /**
     * 设置思考模式
     */
    fun setThinkModeEnabled(enabled: Boolean) {
        _thinkModeEnabled.value = enabled
    }
    
    /**
     * Rename conversation
     */
    fun renameConversation(conversationId: Long, newTitle: String) {
        viewModelScope.launch {
            try {
                chatRepository.updateConversationTitle(conversationId, newTitle)
            } catch (e: Exception) {
                _effect.emit(ChatEffect.ShowError("重命名失败: ${e.message}"))
            }
        }
    }
    
    /**
     * Send user message and get AI response
     */
    fun sendMessage(content: String) {
        val conversationId = _currentConversationId.value ?: return
        
        if (content.isBlank()) return
        
        viewModelScope.launch {
            try {
                // Update UI state
                _uiState.update { it.copy(isLoading = true, error = null) }
                
                // Save user message
                chatRepository.sendMessage(conversationId, content)
                
                // Scroll to bottom
                _effect.emit(ChatEffect.ScrollToBottom)
                
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
                        _effect.emit(ChatEffect.ScrollToBottom)
                    }
                    .onFailure { error ->
                        val errorMsg = error.message ?: "未知错误"
                        _uiState.update { it.copy(isLoading = false, error = errorMsg) }
                        
                        // Save error as assistant message for visibility
                        chatRepository.addAssistantMessage(
                            conversationId, 
                            "[错误] 无法连接到AI服务: $errorMsg"
                        )
                        _effect.emit(ChatEffect.ScrollToBottom)
                    }
                
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
                _effect.emit(ChatEffect.ShowError("发送消息失败: ${e.message}"))
            }
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
                _effect.emit(ChatEffect.ShowError("清空消息失败: ${e.message}"))
            }
        }
    }
    
    /**
     * Toggle drawer
     */
    fun toggleDrawer() {
        _uiState.update { it.copy(isDrawerOpen = !it.isDrawerOpen) }
    }
    
    fun openDrawer() {
        _uiState.update { it.copy(isDrawerOpen = true) }
    }
    
    fun closeDrawer() {
        _uiState.update { it.copy(isDrawerOpen = false) }
    }
}

/**
 * UI State
 */
data class ChatUiState(
    val isLoading: Boolean = false,
    val isDrawerOpen: Boolean = false,
    val error: String? = null
)

/**
 * One-time effects
 */
enum class GenerationPhase {
    Idle,
    Thinking,
    Outputting
}

sealed class ChatEffect {
    data class ShowMessage(val message: String) : ChatEffect()
    data class ShowError(val message: String) : ChatEffect()
    object ScrollToBottom : ChatEffect()

    /**
     * 过滤思考内容，移除 <think> 到 </think> 之间的所有内容
     */
    private fun filterThinkContent(raw: String): String {
        // 正则匹配完整的 <think>...</think> 块（支持跨行）
        val thinkPattern = Regex("<think>[\\s\\S]*?</think>", RegexOption.DOT_MATCHES_ALL)
        var result = thinkPattern.replace(raw, "")
        
        // 如果有未闭合的 <think> 标签，也移除到行尾
        val unclosedPattern = Regex("<think>[\\s\\S]*\$", RegexOption.DOT_MATCHES_ALL)
        result = unclosedPattern.replace(result, "")
        
        return result.trim()
    }
}
