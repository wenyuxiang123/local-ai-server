package com.localai.server.di

import android.content.Context
import androidx.room.Room
import com.localai.server.data.local.ChatDatabase
import com.localai.server.data.local.dao.ConversationDao
import com.localai.server.data.local.dao.MessageDao
import com.localai.server.data.repository.AIRepositoryImpl
import com.localai.server.data.repository.ChatRepository
import com.localai.server.data.repository.ChatRepositoryImpl
import com.localai.server.domain.repository.AIRepository
import com.localai.server.engine.LlamaEngine
import com.localai.server.util.ModelExtractor
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context = context
    
    @Provides
    @Singleton
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
    
    @Provides
    @Singleton
    fun provideModelDir(context: Context): File {
        return File(context.filesDir, "models").apply { mkdirs() }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object EngineModule {
    
    @Provides
    @Singleton
    fun provideLlamaEngine(context: Context): LlamaEngine {
        return LlamaEngine(context)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    
    @Provides
    @Singleton
    fun provideAIRepository(
        context: Context,
        engine: LlamaEngine,
        modelExtractor: ModelExtractor
    ): AIRepository {
        return AIRepositoryImpl(context, engine, modelExtractor)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    
    @Provides
    @Singleton
    fun provideChatDatabase(@ApplicationContext context: Context): ChatDatabase {
        return Room.databaseBuilder(
            context,
            ChatDatabase::class.java,
            ChatDatabase.DATABASE_NAME
        ).build()
    }
    
    @Provides
    @Singleton
    fun provideConversationDao(database: ChatDatabase): ConversationDao {
        return database.conversationDao()
    }
    
    @Provides
    @Singleton
    fun provideMessageDao(database: ChatDatabase): MessageDao {
        return database.messageDao()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ChatRepositoryModule {
    
    @Binds
    @Singleton
    abstract fun bindChatRepository(
        chatRepositoryImpl: ChatRepositoryImpl
    ): ChatRepository
}
