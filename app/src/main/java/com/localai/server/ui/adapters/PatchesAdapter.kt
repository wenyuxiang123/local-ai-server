package com.localai.server.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.localai.server.optimizer.CodeOptimizer
import com.localai.server.databinding.ItemPatchBinding

/**
 * 优化补丁列表适配器
 */
class PatchesAdapter(
    private val onApplyPatch: (CodeOptimizer.PatchInfo) -> Unit,
    private val onViewPatch: (CodeOptimizer.PatchInfo) -> Unit
) : ListAdapter<CodeOptimizer.PatchInfo, PatchesAdapter.PatchViewHolder>(PatchDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PatchViewHolder {
        return PatchViewHolder(
            ItemPatchBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(holder: PatchViewHolder, position: Int) {
        holder.bind(getItem(position), onApplyPatch, onViewPatch)
    }

    class PatchViewHolder(private val binding: ItemPatchBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            patch: CodeOptimizer.PatchInfo,
            onApplyPatch: (CodeOptimizer.PatchInfo) -> Unit,
            onViewPatch: (CodeOptimizer.PatchInfo) -> Unit
        ) {
            binding.apply {
                tvPatchTitle.text = patch.name
                tvPatchDescription.text = patch.description
                tvPatchFile.text = patch.file
                tvPatchType.text = patch.category.name
                
                // 影响程度颜色
                val impactColor = when (patch.impact) {
                    CodeOptimizer.Impact.LOW -> 0xFF4CAF50.toInt()
                    CodeOptimizer.Impact.MEDIUM -> 0xFFFF9800.toInt()
                    CodeOptimizer.Impact.HIGH -> 0xFFF44336.toInt()
                }
                viewRiskIndicator.setBackgroundColor(impactColor)
                
                btnViewPatch.setOnClickListener { onViewPatch(patch) }
                btnApplyPatch.setOnClickListener { onApplyPatch(patch) }
            }
        }
    }

    class PatchDiffCallback : DiffUtil.ItemCallback<CodeOptimizer.PatchInfo>() {
        override fun areItemsTheSame(oldItem: CodeOptimizer.PatchInfo, newItem: CodeOptimizer.PatchInfo): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: CodeOptimizer.PatchInfo, newItem: CodeOptimizer.PatchInfo): Boolean {
            return oldItem == newItem
        }
    }
}
