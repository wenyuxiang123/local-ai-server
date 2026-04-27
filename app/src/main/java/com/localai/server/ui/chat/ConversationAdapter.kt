package com.localai.server.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.localai.server.data.local.entity.Conversation
import com.localai.server.databinding.ItemConversationBinding
import java.text.SimpleDateFormat
import java.util.*

class ConversationAdapter(
    private val onItemClick: (Conversation) -> Unit,
    private val onDeleteClick: (Conversation) -> Unit
) : ListAdapter<Conversation, ConversationAdapter.ConversationViewHolder>(ConversationDiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationViewHolder {
        val binding = ItemConversationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ConversationViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: ConversationViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    inner class ConversationViewHolder(
        private val binding: ItemConversationBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        
        fun bind(conversation: Conversation) {
            binding.tvTitle.text = conversation.title
            binding.tvTime.text = dateFormat.format(Date(conversation.updatedAt))
            
            binding.root.setOnClickListener {
                onItemClick(conversation)
            }
            
            binding.btnDelete.setOnClickListener {
                onDeleteClick(conversation)
            }
        }
    }
    
    private class ConversationDiffCallback : DiffUtil.ItemCallback<Conversation>() {
        override fun areItemsTheSame(oldItem: Conversation, newItem: Conversation): Boolean {
            return oldItem.id == newItem.id
        }
        
        override fun areContentsTheSame(oldItem: Conversation, newItem: Conversation): Boolean {
            return oldItem == newItem
        }
    }
}
