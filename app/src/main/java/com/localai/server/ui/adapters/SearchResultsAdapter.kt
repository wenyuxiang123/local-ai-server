package com.localai.server.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.localai.server.network.WebSearchService
import com.localai.server.databinding.ItemSearchResultBinding

/**
 * 搜索结果列表适配器
 */
class SearchResultsAdapter(
    private val onResultClick: (WebSearchService.SearchResult) -> Unit
) : ListAdapter<WebSearchService.SearchResult, SearchResultsAdapter.ResultViewHolder>(ResultDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder {
        return ResultViewHolder(
            ItemSearchResultBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {
        holder.bind(getItem(position), onResultClick)
    }

    class ResultViewHolder(private val binding: ItemSearchResultBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(result: WebSearchService.SearchResult, onResultClick: (WebSearchService.SearchResult) -> Unit) {
            binding.apply {
                tvTitle.text = result.title
                tvSnippet.text = result.snippet
                tvUrl.text = result.url
                tvSource.text = result.source
                
                root.setOnClickListener { onResultClick(result) }
            }
        }
    }

    class ResultDiffCallback : DiffUtil.ItemCallback<WebSearchService.SearchResult>() {
        override fun areItemsTheSame(oldItem: WebSearchService.SearchResult, newItem: WebSearchService.SearchResult): Boolean {
            return oldItem.url == newItem.url
        }

        override fun areContentsTheSame(oldItem: WebSearchService.SearchResult, newItem: WebSearchService.SearchResult): Boolean {
            return oldItem == newItem
        }
    }
}
