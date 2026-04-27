package com.localai.server.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.localai.server.databinding.ItemModelBinding

/**
 * 模型信息
 */
data class ModelInfo(
    val name: String,
    val size: String,
    val url: String,
    val isDownloaded: Boolean = false,
    val isDownloading: Boolean = false,
    val progress: Int = 0
)

/**
 * 模型列表适配器
 */
class ModelsAdapter(
    private val onDownloadClick: (ModelInfo) -> Unit,
    private val onDeleteClick: (ModelInfo) -> Unit
) : ListAdapter<ModelInfo, ModelsAdapter.ModelViewHolder>(ModelDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelViewHolder {
        return ModelViewHolder(
            ItemModelBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
        holder.bind(getItem(position), onDownloadClick, onDeleteClick)
    }

    class ModelViewHolder(private val binding: ItemModelBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            model: ModelInfo,
            onDownloadClick: (ModelInfo) -> Unit,
            onDeleteClick: (ModelInfo) -> Unit
        ) {
            binding.apply {
                tvModelName.text = model.name
                tvModelSize.text = model.size
                
                if (model.isDownloaded) {
                    btnDownload.text = "已下载"
                    btnDownload.isEnabled = false
                    btnDelete.visibility = android.view.View.VISIBLE
                    progressDownload.visibility = android.view.View.GONE
                } else if (model.isDownloading) {
                    btnDownload.text = "下载中..."
                    btnDownload.isEnabled = false
                    progressDownload.visibility = android.view.View.VISIBLE
                    progressDownload.progress = model.progress
                    btnDelete.visibility = android.view.View.GONE
                } else {
                    btnDownload.text = "下载"
                    btnDownload.isEnabled = true
                    progressDownload.visibility = android.view.View.GONE
                    btnDelete.visibility = android.view.View.GONE
                }
                
                btnDownload.setOnClickListener { onDownloadClick(model) }
                btnDelete.setOnClickListener { onDeleteClick(model) }
            }
        }
    }

    class ModelDiffCallback : DiffUtil.ItemCallback<ModelInfo>() {
        override fun areItemsTheSame(oldItem: ModelInfo, newItem: ModelInfo): Boolean {
            return oldItem.name == newItem.name
        }

        override fun areContentsTheSame(oldItem: ModelInfo, newItem: ModelInfo): Boolean {
            return oldItem == newItem
        }
    }
}
