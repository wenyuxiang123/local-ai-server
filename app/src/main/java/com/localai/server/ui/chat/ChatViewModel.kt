package com.localai.server.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.server.data.local.entity.Conversation
import com.localai.server.data.local.entity.Message
import com.localai.server.data.repository.ChatApiService
import com.localai.server.data.repository.ChatMessage
import com.localai.server.data.repository.ChatRepository
import com.localai.server.data.repository.StreamResult
import com.localai.server.domain.repository.AIRepository
import com.localai.server.network.WebSearchService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val chatApiService: ChatApiService,
    private val aiRepository: AIRepository,
    private val webSearchService: WebSearchService
) : ViewModel() {
    
    private val TAG = "ChatViewModel"
    
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

    // 联网搜索开关
    private val _webSearchEnabled = MutableStateFlow(true)
    val webSearchEnabled: StateFlow<Boolean> = _webSearchEnabled.asStateFlow()

    // 生成阶段状态：Idle -> Thinking -> Outputting
    private val _generationPhase = MutableStateFlow(GenerationPhase.Idle)
    val generationPhase: StateFlow<GenerationPhase> = _generationPhase

    // Effects for one-time events
    private val _effect = MutableSharedFlow<ChatEffect>()
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
     * 切换联网搜索
     */
    fun toggleWebSearch() {
        _webSearchEnabled.value = !_webSearchEnabled.value
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
     * Send user message and get AI response - 流式输出版本
     * 实现边思考边输出token，打字机效果
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
                _effect.emit(ChatEffect.ScrollToBottom)
                
                // 进入思考阶段
                _generationPhase.value = GenerationPhase.Thinking
                
                // Always use 127.0.0.1 for internal chat
                val baseUrl = "http://127.0.0.1:8080"
                
                // Get history messages for context
                val historyMessages = _messages.value.map { msg ->
                    ChatMessage(role = msg.role, content = msg.content)
                }
                
                // ====== 联网搜索逻辑 ======
                var systemPrompt = "You are a helpful assistant. "
                if (_webSearchEnabled.value && needWebSearch(content)) {
                    Log.d(TAG, "触发联网搜索: ${content.take(30)}")
                    
                    try {
                        // 执行联网搜索
                        val searchResponse = webSearchService.search(content)
                        val searchResults = searchResponse.results
                        
                        if (searchResults.isNotEmpty()) {
                            // 把搜索结果加入系统提示词
                            systemPrompt += "\n\n【联网搜索结果】\n"
                            searchResults.take(3).forEachIndexed { i, result ->
                                systemPrompt += "${i+1}. ${result.title}\n   ${result.snippet}\n"
                            }
                            systemPrompt += "\n请基于以上搜索结果回答用户问题，确保信息准确。"
                            Log.d(TAG, "已加入${searchResults.size}条搜索结果到上下文")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "联网搜索失败: ${e.message}")
                    }
                }
                
                // 思考模式标签
                if (_thinkModeEnabled.value) {
                    systemPrompt += " /think"
                } else {
                    systemPrompt += " /no_think"
                }
                
                // Build messages
                val allMessages = chatApiService.buildMessagesWithSystemPrompt(
                    userContent = content,
                    historyMessages = historyMessages,
                    systemPrompt = systemPrompt
                )
                
                // 进入输出阶段
                _generationPhase.value = GenerationPhase.Outputting
                
                // 创建一个临时的assistant消息，用于流式更新
                val tempMessageId = chatRepository.addAssistantMessage(conversationId, "")
                var currentResponse = ""
                
                // 调用流式API - 边生成边输出
                chatApiService.sendMessageStream(baseUrl, allMessages)
                    .collect { streamResult ->
                        when (streamResult) {
                            is StreamResult.Token -> {
                                // 收到新token，追加到当前响应
                                currentResponse += streamResult.token
                                
                                // 过滤思考内容，移除<think>标签
                                val displayContent = if (_thinkModeEnabled.value) {
                                    currentResponse
                                } else {
                                    filterThinkContent(currentResponse)
                                }
                                
                                // 实时更新数据库中的消息内容
                                chatRepository.updateMessageContent(tempMessageId, displayContent)
                                
                                // 通知UI滚动到底部
                                _effect.emit(ChatEffect.ScrollToBottom)
                            }
                            
                            is StreamResult.Error -> {
                                // 错误处理
                                val errorMsg = streamResult.message
                                _uiState.update { it.copy(isLoading = false, error = errorMsg) }
                                chatRepository.updateMessageContent(
                                    tempMessageId, 
                                    "[错误] 无法连接到AI服务: $errorMsg"
                                )
                                _effect.emit(ChatEffect.ScrollToBottom)
                            }
                            
                            StreamResult.Complete -> {
                                // 生成完成
                                _uiState.update { it.copy(isLoading = false) }
                                _generationPhase.value = GenerationPhase.Idle
                                
                                // 更新会话标题（如果是第一条消息）
                                if (_messages.value.size <= 1) {
                                    val title = content.take(20).let { 
                                        if (content.length > 20) "$it..." else it 
                                    }
                                    chatRepository.updateConversationTitle(conversationId, title)
                                }
                            }
                        }
                    }
                
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
                _generationPhase.value = GenerationPhase.Idle
                _effect.emit(ChatEffect.ShowError("发送消息失败: ${e.message}"))
            }
        }
    }
    
    /**
     * 判断用户问题是否需要联网搜索
     */
    private fun needWebSearch(query: String): Boolean {
        val lowerQuery = query.lowercase()
        
        // 搜索触发关键词
        val searchTriggers = listOf(
            "搜索", "查一下", "百度", "谷歌", "最新", "今天", "近日", "现在",
            "股价", "行情", "天气", "新闻", "怎么用", "如何", "是什么",
            "多少钱", "哪里", "怎么", "为什么", "最近", "查询"
        )
        
        return searchTriggers.any { lowerQuery.contains(it) }
    }
    
    /**
     * 过滤思考内容，移除 <think> 到 </think> 之间的所有内容
     */
    private fun filterThinkContent(raw: String): String {
        // 正则匹配完整的 <think>...</think> 块（支持跨行）
        val thinkPattern = Regex("<think>[\\s\\S]*?</think>", RegexOption.DOT_MATCHES_ALL)
        var result = thinkPattern.replace(raw, "")
        
        // 如果有未闭合的 <think> 标签，也移除到行尾
        val unclosedPattern = Regex("<think>[\\s\\S]*$", RegexOption.DOT_MATCHES_ALL)
        result = unclosedPattern.replace(result, "")
        
        return result.trim()
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
 * 生成阶段
 */
enum class GenerationPhase {
    Idle,        // 空闲
    Thinking,    // 思考中（联网搜索、模型加载token）
    Outputting   // 输出中
}

sealed class ChatEffect {
    data class ShowMessage(val message: String) : ChatEffect()
    data class ShowError(val message: String) : ChatEffect()
    object ScrollToBottom : ChatEffect()
}
