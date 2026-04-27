package com.localai.server.data.repository

import com.localai.server.data.local.entity.Conversation
import com.localai.server.data.local.entity.Message
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    // Conversation operations
    fun getAllConversations(): Flow<List<Conversation>>
    fun getConversationById(id: Long): Flow<Conversation?>
    suspend fun createConversation(title: String): Long
    suspend fun updateConversationTitle(id: Long, title: String)
    suspend fun deleteConversation(id: Long)
    
    // Message operations
    fun getMessagesByConversation(conversationId: Long): Flow<List<Message>>
    suspend fun sendMessage(conversationId: Long, content: String): Long
    suspend fun addAssistantMessage(conversationId: Long, content: String): Long
    suspend fun deleteMessages(conversationId: Long)
}
