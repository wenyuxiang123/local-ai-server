package com.localai.server.ui.tabs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.localai.server.databinding.FragmentWebBinding
import com.localai.server.network.AutoUpdateService
import com.localai.server.network.ModelDownloadManager
import com.localai.server.network.WebEnhancedQA
import com.localai.server.ui.adapters.ModelInfo
import com.localai.server.ui.adapters.ModelsAdapter
import com.localai.server.ui.adapters.SearchResultsAdapter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 联网 Tab - 包含联网搜索、模型下载、应用更新
 */
@AndroidEntryPoint
class WebFragment : Fragment() {

    private var _binding: FragmentWebBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var webEnhancedQA: WebEnhancedQA

    @Inject
    lateinit var modelDownloadManager: ModelDownloadManager

    @Inject
    lateinit var autoUpdateService: AutoUpdateService

    private lateinit var searchResultsAdapter: SearchResultsAdapter
    private lateinit var modelsAdapter: ModelsAdapter
    private var selectedModel: ModelInfo? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWebBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupAdapters()
        setupViews()
        observeStates()
        loadAvailableModels()
    }

    private fun setupAdapters() {
        // 搜索结果适配器
        searchResultsAdapter = SearchResultsAdapter { result ->
            // 点击搜索结果
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
            intent.data = android.net.Uri.parse(result.url)
            startActivity(intent)
        }
        binding.rvSearchResults.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = searchResultsAdapter
        }

        // 模型列表适配器
        modelsAdapter = ModelsAdapter(
            onDownloadClick = { model ->
                downloadModel(model)
            },
            onDeleteClick = { model ->
                deleteModel(model)
            }
        )
        binding.rvModels.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = modelsAdapter
        }
    }

    private fun loadAvailableModels() {
        // 加载可用模型列表
        val models = listOf(
            ModelInfo(
                name = "Qwen2.5-1.5B-Instruct-Q4_K_M",
                size = "1.1 GB",
                url = "https://modelscope.cn/models/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/master/qwen2.5-1.5b-instruct-q4_k_m.gguf"
            ),
            ModelInfo(
                name = "Qwen2.5-3B-Instruct-Q4_K_M",
                size = "2.0 GB",
                url = "https://modelscope.cn/models/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/master/qwen2.5-3b-instruct-q4_k_m.gguf"
            ),
            ModelInfo(
                name = "Phi-3.5-mini-instruct-Q4_K_M",
                size = "2.2 GB",
                url = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf"
            )
        )
        modelsAdapter.submitList(models)
    }

    private fun setupViews() {
        // 联网搜索
        binding.btnWebSearch.setOnClickListener {
            val query = binding.etWebQuery.text?.toString()?.trim()
            if (query.isNullOrEmpty()) {
                Toast.makeText(requireContext(), "请输入问题", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            val mode = when (binding.rgQaMode.checkedRadioButtonId) {
                binding.rbWebSearch.id -> WebEnhancedQA.MODE_WEB_SEARCH
                binding.rbWebAnswer.id -> WebEnhancedQA.MODE_WEB_ANSWER
                binding.rbWebSummary.id -> WebEnhancedQA.MODE_WEB_SUMMARY
                else -> WebEnhancedQA.MODE_WEB_ANSWER
            }
            
            performWebSearch(query, mode)
        }

        // 检查更新
        binding.btnCheckUpdate.setOnClickListener {
            checkForUpdate()
        }

        // 下载更新
        binding.btnDownloadUpdate.setOnClickListener {
            val state = autoUpdateService.updateState.value
            if (state.downloadUrl.isNotEmpty()) {
                lifecycleScope.launch {
                    autoUpdateService.downloadUpdate(state.downloadUrl)
                }
            }
        }

        // 安装更新
        binding.btnInstallUpdate.setOnClickListener {
            autoUpdateService.updateState.value.downloadedFile?.let { file ->
                autoUpdateService.installUpdate(file)
            }
        }
    }

    private fun observeStates() {
        // 观察联网问答状态
        viewLifecycleOwner.lifecycleScope.launch {
            webEnhancedQA.qaState.collectLatest { state ->
                binding.progressWeb.isVisible = state.isProcessing
                binding.tvWebStatus.isVisible = state.isProcessing
                
                if (state.isProcessing) {
                    binding.tvWebStatus.text = "正在搜索..."
                }

                // 显示搜索结果
                if (state.searchResults.isNotEmpty()) {
                    binding.cardWebResults.isVisible = true
                    searchResultsAdapter.submitList(state.searchResults)
                }

                // 显示回答
                if (state.answer.isNotEmpty()) {
                    binding.cardWebAnswer.isVisible = true
                    binding.tvWebAnswer.text = state.answer
                    binding.tvSources.text = state.sources.joinToString("\n")
                }
            }
        }

        // 观察模型下载状态
        viewLifecycleOwner.lifecycleScope.launch {
            modelDownloadManager.downloadState.collectLatest { state ->
                when (state.status) {
                    ModelDownloadManager.STATUS_DOWNLOADING -> {
                        // 更新下载进度（简化显示）
                        Toast.makeText(requireContext(), "下载中... ${state.progress}%", Toast.LENGTH_SHORT).show()
                    }
                    ModelDownloadManager.STATUS_COMPLETED -> {
                        Toast.makeText(requireContext(), "模型下载完成", Toast.LENGTH_SHORT).show()
                        // 更新模型列表
                        loadAvailableModels()
                    }
                    ModelDownloadManager.STATUS_FAILED -> {
                        Toast.makeText(requireContext(), "下载失败: ${state.error}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // 观察更新状态
        viewLifecycleOwner.lifecycleScope.launch {
            autoUpdateService.updateState.collectLatest { state ->
                binding.tvCurrentVersion.text = "当前版本: v${state.currentVersion}"
                
                when (state.status) {
                    AutoUpdateService.STATUS_CHECKING -> {
                        binding.tvUpdateStatus.isVisible = true
                        binding.tvUpdateStatus.text = "检查中..."
                    }
                    AutoUpdateService.STATUS_DOWNLOADING -> {
                        binding.progressUpdate.isVisible = true
                        binding.progressUpdate.progress = state.progress
                        binding.tvUpdateStatus.isVisible = true
                        binding.tvUpdateStatus.text = "下载中... ${state.progress}%"
                    }
                    AutoUpdateService.STATUS_DOWNLOADED -> {
                        binding.progressUpdate.isVisible = false
                        binding.btnDownloadUpdate.isVisible = false
                        binding.btnInstallUpdate.isVisible = true
                        binding.btnInstallUpdate.isEnabled = true
                        Toast.makeText(requireContext(), "下载完成，可以安装了", Toast.LENGTH_SHORT).show()
                    }
                    AutoUpdateService.STATUS_FAILED -> {
                        binding.progressUpdate.isVisible = false
                        binding.tvUpdateStatus.isVisible = true
                        binding.tvUpdateStatus.text = state.error
                    }
                }
            }
        }
    }

    private fun performWebSearch(query: String, mode: String) {
        lifecycleScope.launch {
            binding.btnWebSearch.isEnabled = false
            binding.btnWebSearch.text = "搜索中..."
            
            val result = webEnhancedQA.askWithWeb(query, mode)
            
            binding.btnWebSearch.isEnabled = true
            binding.btnWebSearch.text = "🔍 联网搜索"
            
            if (result.searchResults.isNotEmpty()) {
                binding.cardWebResults.isVisible = true
                binding.cardWebAnswer.isVisible = true
                binding.tvWebAnswer.text = result.answer
                binding.tvSources.text = result.sources.joinToString("\n") { "• $it" }
            } else {
                binding.cardWebResults.isVisible = false
                binding.cardWebAnswer.isVisible = false
                Toast.makeText(requireContext(), "未找到相关结果", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkForUpdate() {
        lifecycleScope.launch {
            binding.btnCheckUpdate.isEnabled = false
            binding.btnCheckUpdate.text = "检查中..."
            
            autoUpdateService.checkForUpdate().fold(
                onSuccess = { versionInfo ->
                    binding.btnCheckUpdate.isEnabled = true
                    binding.btnCheckUpdate.text = "检查更新"
                    
                    if (versionInfo != null) {
                        binding.tvLatestVersion.text = "最新版本: v${versionInfo.version}"
                        binding.btnDownloadUpdate.isVisible = true
                        binding.btnDownloadUpdate.isEnabled = true
                        Toast.makeText(requireContext(), "发现新版本: v${versionInfo.version}", Toast.LENGTH_SHORT).show()
                    } else {
                        binding.tvLatestVersion.text = "最新版本: 已是最新"
                        Toast.makeText(requireContext(), "已是最新版本", Toast.LENGTH_SHORT).show()
                    }
                },
                onFailure = { e ->
                    binding.btnCheckUpdate.isEnabled = true
                    binding.btnCheckUpdate.text = "检查更新"
                    Toast.makeText(requireContext(), "检查失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun downloadModel(model: ModelInfo) {
        selectedModel = model
        lifecycleScope.launch {
            // 直接使用 URL 下载
            modelDownloadManager.downloadModel(model.name).fold(
                onSuccess = {
                    Toast.makeText(requireContext(), "下载完成: ${model.name}", Toast.LENGTH_SHORT).show()
                },
                onFailure = { e ->
                    Toast.makeText(requireContext(), "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun deleteModel(model: ModelInfo) {
        lifecycleScope.launch {
            val file = java.io.File(requireContext().filesDir, "models/${model.name}.gguf")
            if (file.exists()) {
                if (file.delete()) {
                    Toast.makeText(requireContext(), "已删除: ${model.name}", Toast.LENGTH_SHORT).show()
                    loadAvailableModels()
                } else {
                    Toast.makeText(requireContext(), "删除失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
