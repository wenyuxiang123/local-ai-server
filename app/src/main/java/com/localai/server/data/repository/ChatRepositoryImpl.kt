package com.localai.server.data.repository

import com.localai.server.data.local.dao.ConversationDao
import com.localai.server.data.local.dao.MessageDao
import com.localai.server.data.local.entity.Conversation
import com.localai.server.data.local.entity.Message
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) : ChatRepository {
    
    override fun getAllConversations(): Flow<List<Conversation>> {
        return conversationDao.getAllConversations()
    }
    
    override fun getConversationById(id: Long): Flow<Conversation?> {
        return conversationDao.getConversationByIdFlow(id)
    }
    
    override suspend fun createConversation(title: String): Long {
        val conversation = Conversation(title = title)
        return conversationDao.insertConversation(conversation)
    }
    
    override suspend fun updateConversationTitle(id: Long, title: String) {
        conversationDao.updateTitle(id, title)
    }
    
    override suspend fun deleteConversation(id: Long) {
        conversationDao.deleteConversationById(id)
    }
    
    override fun getMessagesByConversation(conversationId: Long): Flow<List<Message>> {
        return messageDao.getMessagesByConversation(conversationId)
    }
    
    override suspend fun sendMessage(conversationId: Long, content: String): Long {
        val message = Message(
            conversationId = conversationId,
            role = "user",
            content = content
        )
        val messageId = messageDao.insertMessage(message)
        conversationDao.updateTimestamp(conversationId)
        return messageId
    }
    
    override suspend fun addAssistantMessage(conversationId: Long, content: String): Long {
        val message = Message(
            conversationId = conversationId,
            role = "assistant",
            content = content
        )
        val messageId = messageDao.insertMessage(message)
        conversationDao.updateTimestamp(conversationId)
        return messageId
    }
    
    override suspend fun deleteMessages(conversationId: Long) {
        messageDao.deleteMessagesByConversation(conversationId)
    }
}
