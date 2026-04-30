package com.localai.server.ui.tabs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.localai.server.ai.CodeAnalyzer
import com.localai.server.databinding.FragmentOptimizerBinding
import com.localai.server.optimizer.CodeOptimizer
import com.localai.server.ui.adapters.IssuesAdapter
import com.localai.server.ui.adapters.PatchesAdapter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 优化 Tab - 包含代码分析、参数调优、优化补丁
 * 
 * v4.0-MNN: GPU Layers和Batch Size已被隐藏，MNN不使用这些参数
 */
@AndroidEntryPoint
class OptimizerFragment : Fragment() {

    private var _binding: FragmentOptimizerBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var codeAnalyzer: CodeAnalyzer

    @Inject
    lateinit var codeOptimizer: CodeOptimizer

    private lateinit var issuesAdapter: IssuesAdapter
    private lateinit var patchesAdapter: PatchesAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOptimizerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
        observeStates()
    }

    private fun setupViews() {
        // 初始化适配器
        setupAdapters()
        
        // 代码分析
        binding.btnAnalyzeCode.setOnClickListener {
            analyzeCode()
        }

        // 自动调优开关
        binding.switchAutoTune.setOnCheckedChangeListener { _, isChecked ->
            Toast.makeText(
                requireContext(),
                if (isChecked) "自动调优已开启" else "自动调优已关闭",
                Toast.LENGTH_SHORT
            ).show()
        }

        // 性能预设
        binding.btnPresetBalanced.setOnClickListener {
            Toast.makeText(requireContext(), "均衡模式", Toast.LENGTH_SHORT).show()
            updateConfigUI()
        }

        binding.btnPresetPerformance.setOnClickListener {
            Toast.makeText(requireContext(), "性能模式", Toast.LENGTH_SHORT).show()
            updateConfigUI()
        }

        binding.btnPresetBattery.setOnClickListener {
            Toast.makeText(requireContext(), "省电模式", Toast.LENGTH_SHORT).show()
            updateConfigUI()
        }

        binding.btnPresetQuality.setOnClickListener {
            Toast.makeText(requireContext(), "质量模式", Toast.LENGTH_SHORT).show()
            updateConfigUI()
        }

        // 应用配置 - v4.0-MNN: 不再保存n_gpu_layers和n_batch到SharedPreferences
        binding.btnApplyConfig.setOnClickListener {
            Toast.makeText(requireContext(), "配置已应用", Toast.LENGTH_SHORT).show()
        }
        
        // 线程数 SeekBar
        binding.seekbarThreads.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val threads = progress + 2  // 范围2-8
                binding.tvThreads.text = threads.toString()
                if (fromUser) {
                    val prefs = requireContext().getSharedPreferences("ai_config", android.content.Context.MODE_PRIVATE)
                    prefs.edit().putInt("n_threads", threads).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // 上下文大小 SeekBar
        binding.seekbarContextSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val ctxSize = 512 + progress * 512  // 范围512-8192，步进512
                binding.tvContextSize.text = ctxSize.toString()
                if (fromUser) {
                    val prefs = requireContext().getSharedPreferences("ai_config", android.content.Context.MODE_PRIVATE)
                    prefs.edit().putInt("n_ctx", ctxSize).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // 温度 SeekBar
        binding.seekbarTemperature.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val temp = progress / 10f  // 范围0.0-2.0，步进0.1
                binding.tvTemperature.text = String.format("%.1f", temp)
                if (fromUser) {
                    val prefs = requireContext().getSharedPreferences("ai_config", android.content.Context.MODE_PRIVATE)
                    prefs.edit().putFloat("temperature", temp).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 从 SharedPreferences 读取初始值
        val prefs = requireContext().getSharedPreferences("ai_config", android.content.Context.MODE_PRIVATE)
        
        // 线程数 SeekBar
        val savedThreads = prefs.getInt("n_threads", 4)
        val threadsProgress = (savedThreads - 2).coerceIn(0, 6)  // 线程范围2-8
        binding.seekbarThreads.progress = threadsProgress
        binding.tvThreads.text = savedThreads.toString()
        
        // 上下文大小 SeekBar
        val savedCtx = prefs.getInt("n_ctx", 2048)
        val ctxProgress = ((savedCtx - 512) / 512).coerceIn(0, 15)  // 范围512-8192，步进512
        binding.seekbarContextSize.progress = ctxProgress
        binding.tvContextSize.text = savedCtx.toString()
        
        // 温度 SeekBar
        val savedTemp = prefs.getFloat("temperature", 0.7f)
        val tempProgress = (savedTemp * 10).toInt().coerceIn(0, 20)  // 范围0.0-2.0，步进0.1
        binding.seekbarTemperature.progress = tempProgress
        binding.tvTemperature.text = String.format("%.1f", savedTemp)

        // 生成补丁
        binding.btnGeneratePatches.setOnClickListener {
            generatePatches()
        }
    }

    private fun setupAdapters() {
        // 问题列表适配器
        issuesAdapter = IssuesAdapter()
        binding.rvIssues.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = issuesAdapter
        }

        // 补丁列表适配器
        patchesAdapter = PatchesAdapter(
            onApplyPatch = { patch ->
                applyPatch(patch)
            },
            onViewPatch = { patch ->
                showPatchDetails(patch)
            }
        )
        binding.rvPatches.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = patchesAdapter
        }
    }

    private fun observeStates() {
        // 代码分析状态
        viewLifecycleOwner.lifecycleScope.launch {
            codeAnalyzer.analysisState.collectLatest { state ->
                binding.progressAnalyze.isVisible = state.isAnalyzing
                binding.tvAnalyzeStatus.isVisible = state.isAnalyzing

                if (state.isAnalyzing) {
                    binding.tvAnalyzeStatus.text = "分析中... ${state.currentFile}"
                    binding.progressAnalyze.progress = state.progress
                }

                // 显示分析结果
                state.summary?.let { summary ->
                    binding.cardAnalysisSummary.isVisible = true
                    binding.tvCodeScore.text = "代码评分: ${summary.overallScore}/100"
                    binding.tvTotalIssues.text = "问题数: ${summary.totalIssues}"
                    binding.tvTotalFiles.text = "文件数: ${summary.totalFiles}"
                    binding.tvTotalLines.text = "代码行数: ${summary.totalLines}"
                    binding.tvRecommendations.text = summary.recommendations.joinToString("\n")
                }

                // 显示问题列表
                if (state.results.isNotEmpty()) {
                    binding.cardIssues.isVisible = true
                    issuesAdapter.submitList(state.results)
                }
            }
        }

        // 优化补丁状态
        viewLifecycleOwner.lifecycleScope.launch {
            codeOptimizer.optimizationState.collectLatest { state ->
                if (state.availablePatches.isNotEmpty()) {
                    patchesAdapter.submitList(state.availablePatches)
                }
            }
        }
    }

    private fun analyzeCode() {
        lifecycleScope.launch {
            binding.btnAnalyzeCode.isEnabled = false
            binding.btnAnalyzeCode.text = "分析中..."

            val report = codeAnalyzer.analyzeCode()

            binding.btnAnalyzeCode.isEnabled = true
            binding.btnAnalyzeCode.text = "开始分析"

            Toast.makeText(
                requireContext(),
                "分析完成，发现 ${report.summary.totalIssues} 个问题",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateConfigUI() {
        // 从 SharedPreferences 读取配置
        val prefs = requireContext().getSharedPreferences("ai_config", android.content.Context.MODE_PRIVATE)
        
        // 读取所有参数并更新UI
        val nThreads = prefs.getInt("n_threads", 4)
        val nCtx = prefs.getInt("n_ctx", 2048)
        val temp = prefs.getFloat("temperature", 0.7f)
        
        // v4.0-MNN: 不再读取n_gpu_layers和n_batch用于UI
        
        binding.tvThreads.text = nThreads.toString()
        binding.seekbarThreads.progress = (nThreads - 2).coerceIn(0, 6)
        
        binding.tvContextSize.text = nCtx.toString()
        binding.seekbarContextSize.progress = ((nCtx - 512) / 512).coerceIn(0, 15)
        
        binding.tvTemperature.text = String.format("%.1f", temp)
        binding.seekbarTemperature.progress = (temp * 10).toInt().coerceIn(0, 20)
    }

    private fun generatePatches() {
        lifecycleScope.launch {
            binding.btnGeneratePatches.isEnabled = false
            binding.btnGeneratePatches.text = "生成中..."

            val patches = codeOptimizer.generatePatches()

            binding.btnGeneratePatches.isEnabled = true
            binding.btnGeneratePatches.text = "生成优化补丁"

            Toast.makeText(
                requireContext(),
                "生成了 ${patches.size} 个补丁",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun applyPatch(patch: CodeOptimizer.PatchInfo) {
        lifecycleScope.launch {
            Toast.makeText(requireContext(), "补丁已应用: ${patch.name}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPatchDetails(patch: CodeOptimizer.PatchInfo) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(patch.name)
            .setMessage("""
文件: ${patch.file}:${patch.line}
类别: ${patch.category}
影响: ${patch.impact}

${patch.description}

原始代码:
${patch.originalCode}

优化后:
${patch.optimizedCode}
            """.trimIndent())
            .setPositiveButton("关闭", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
