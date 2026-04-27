package com.localai.server.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.localai.server.ai.CodeAnalyzer
import com.localai.server.databinding.ItemIssueBinding

/**
 * 代码分析问题列表适配器
 */
class IssuesAdapter : ListAdapter<CodeAnalyzer.AnalysisResult, IssuesAdapter.IssueViewHolder>(IssueDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IssueViewHolder {
        return IssueViewHolder(
            ItemIssueBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(holder: IssueViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class IssueViewHolder(private val binding: ItemIssueBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(issue: CodeAnalyzer.AnalysisResult) {
            binding.apply {
                // 严重程度图标和颜色
                val severityIcon = when (issue.severity) {
                    CodeAnalyzer.Severity.CRITICAL -> "🔴"
                    CodeAnalyzer.Severity.ERROR -> "🟠"
                    CodeAnalyzer.Severity.WARNING -> "🟡"
                    CodeAnalyzer.Severity.INFO -> "🔵"
                }
                val severityColor = when (issue.severity) {
                    CodeAnalyzer.Severity.CRITICAL -> 0xFFF44336.toInt()
                    CodeAnalyzer.Severity.ERROR -> 0xFFFF9800.toInt()
                    CodeAnalyzer.Severity.WARNING -> 0xFFFFEB3B.toInt()
                    CodeAnalyzer.Severity.INFO -> 0xFF2196F3.toInt()
                }
                
                tvSeverity.text = severityIcon
                tvSeverity.setTextColor(severityColor)
                tvMessage.text = issue.message
                tvCategory.text = issue.category.name
                tvLocation.text = "${issue.file}:${issue.line}"
                
                // 点击展开详情
                root.setOnClickListener {
                    tvDetails.text = issue.suggestion
                    tvDetails.visibility = if (tvDetails.visibility == android.view.View.GONE) {
                        android.view.View.VISIBLE
                    } else {
                        android.view.View.GONE
                    }
                }
            }
        }
    }

    class IssueDiffCallback : DiffUtil.ItemCallback<CodeAnalyzer.AnalysisResult>() {
        override fun areItemsTheSame(oldItem: CodeAnalyzer.AnalysisResult, newItem: CodeAnalyzer.AnalysisResult): Boolean {
            return oldItem.file == newItem.file && oldItem.line == newItem.line && oldItem.message == newItem.message
        }

        override fun areContentsTheSame(oldItem: CodeAnalyzer.AnalysisResult, newItem: CodeAnalyzer.AnalysisResult): Boolean {
            return oldItem == newItem
        }
    }
}
