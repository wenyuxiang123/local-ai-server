package com.localai.server.ui.chat

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.navigation.NavigationView
import com.localai.server.databinding.ActivityChatBinding
import com.localai.server.databinding.NavConversationsContentBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ChatActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityChatBinding
    private lateinit var conversationsBinding: NavConversationsContentBinding
    private val viewModel: ChatViewModel by viewModels()
    
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var conversationAdapter: ConversationAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupDrawer()
        setupViews()
        observeState()
        observeEffects()
    }
    
    private fun setupDrawer() {
        // Inflate conversations content
        val navView = binding.navView
        val headerView = navView.getHeaderView(0)
        conversationsBinding = NavConversationsContentBinding.bind(headerView.parent as android.view.View)
        
        // Setup conversations RecyclerView
        conversationAdapter = ConversationAdapter(
            onItemClick = { conversation ->
                viewModel.selectConversation(conversation.id)
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            },
            onDeleteClick = { conversation ->
                showDeleteConfirmation(conversation.id, conversation.title)
            }
        )
        
        conversationsBinding.rvConversations.apply {
            layoutManager = LinearLayoutManager(this@ChatActivity)
            adapter = conversationAdapter
        }
        
        // New conversation button
        conversationsBinding.btnNewConversation.setOnClickListener {
            viewModel.createConversation()
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
    }
    
    private fun setupViews() {
        // Setup messages RecyclerView
        messageAdapter = MessageAdapter()
        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(this@ChatActivity).apply {
                stackFromEnd = false
            }
            adapter = messageAdapter
        }
        
        // Menu button - open drawer
        binding.btnMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        
        // New chat button
        binding.btnNewChat.setOnClickListener {
            viewModel.createConversation()
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
    }
    
    private fun sendMessage() {
        val content = binding.etMessage.text.toString().trim()
        if (content.isNotEmpty()) {
            viewModel.sendMessage(content)
            binding.etMessage.text?.clear()
        }
    }
    
    private fun showDeleteConfirmation(conversationId: Long, title: String) {
        AlertDialog.Builder(this)
            .setTitle("删除会话")
            .setMessage("确定要删除 \"$title\" 吗？此操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                viewModel.deleteConversation(conversationId)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe conversations
                launch {
                    viewModel.conversations.collect { conversations ->
                        conversationAdapter.submitList(conversations)
                        conversationsBinding.tvConversationCount.text = "${conversations.size} 个会话"
                    }
                }
                
                // Observe current conversation
                launch {
                    viewModel.currentConversationId.collect { id ->
                        binding.tvTitle.text = viewModel.conversations.value
                            .find { it.id == id }?.title
                            ?: "聊天"
                    }
                }
                
                // Observe messages
                launch {
                    viewModel.messages.collect { messages ->
                        messageAdapter.submitList(messages) {
                            // Scroll to bottom after updating list
                            if (messages.isNotEmpty()) {
                                binding.rvMessages.smoothScrollToPosition(messages.size - 1)
                            }
                        }
                    }
                }
                
                // Observe UI state
                launch {
                    viewModel.uiState.collect { state ->
                        binding.progressLoading.isVisible = state.isLoading
                        binding.btnSend.isEnabled = !state.isLoading
                    }
                }
            }
        }
    }
    
    private fun observeEffects() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effect.collect { effect ->
                    when (effect) {
                        is ChatEffect.ShowMessage -> {
                            Toast.makeText(this@ChatActivity, effect.message, Toast.LENGTH_SHORT).show()
                        }
                        is ChatEffect.ShowError -> {
                            Toast.makeText(this@ChatActivity, effect.message, Toast.LENGTH_LONG).show()
                        }
                        is ChatEffect.ScrollToBottom -> {
                            val messages = viewModel.messages.value
                            if (messages.isNotEmpty()) {
                                binding.rvMessages.smoothScrollToPosition(messages.size - 1)
                            }
                        }
                    }
                }
            }
        }
    }
    
    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
}
