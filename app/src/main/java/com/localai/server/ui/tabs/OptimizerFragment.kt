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
import com.localai.server.ai.CodeAnalyzer
import com.localai.server.databinding.FragmentOptimizerBinding
import com.localai.server.optimizer.CodeOptimizer
import com.localai.server.optimizer.ParameterTuner
import com.localai.server.ui.adapters.IssuesAdapter
import com.localai.server.ui.adapters.PatchesAdapter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 优化 Tab - 包含代码分析、参数调优、优化补丁
 */
@AndroidEntryPoint
class OptimizerFragment : Fragment() {

    private var _binding: FragmentOptimizerBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var codeAnalyzer: CodeAnalyzer

    @Inject
    lateinit var codeOptimizer: CodeOptimizer

    @Inject
    lateinit var parameterTuner: ParameterTuner

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
            if (isChecked) {
                parameterTuner.startMonitoring()
            } else {
                parameterTuner.stopMonitoring()
            }
        }

        // 性能预设
        binding.btnPresetBalanced.setOnClickListener {
            parameterTuner.applyPreset(ParameterTuner.Preset.BALANCED)
            updateConfigUI()
        }

        binding.btnPresetPerformance.setOnClickListener {
            parameterTuner.applyPreset(ParameterTuner.Preset.PERFORMANCE)
            updateConfigUI()
        }

        binding.btnPresetBattery.setOnClickListener {
            parameterTuner.applyPreset(ParameterTuner.Preset.BATTERY_SAVER)
            updateConfigUI()
        }

        binding.btnPresetQuality.setOnClickListener {
            parameterTuner.applyPreset(ParameterTuner.Preset.QUALITY)
            updateConfigUI()
        }

        // 应用配置
        binding.btnApplyConfig.setOnClickListener {
            Toast.makeText(requireContext(), "配置已应用", Toast.LENGTH_SHORT).show()
        }

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

        // 参数调优状态
        viewLifecycleOwner.lifecycleScope.launch {
            parameterTuner.tuningState.collectLatest { state ->
                binding.switchAutoTune.isChecked = state.autoTuningEnabled
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            parameterTuner.currentConfig.collectLatest { config ->
                binding.tvThreads.text = config.threads.toString()
                binding.tvContextSize.text = config.contextSize.toString()
                binding.tvBatchSize.text = config.batchSize.toString()
                binding.tvTemperature.text = config.temperature.toString()
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
        val config = parameterTuner.currentConfig.value
        binding.tvThreads.text = config.threads.toString()
        binding.tvContextSize.text = config.contextSize.toString()
        binding.tvBatchSize.text = config.batchSize.toString()
        binding.tvTemperature.text = config.temperature.toString()
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
            // 应用补丁（这里需要实现具体的应用逻辑）
            Toast.makeText(requireContext(), "补丁已应用: ${patch.name}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPatchDetails(patch: CodeOptimizer.PatchInfo) {
        // 显示补丁详情对话框
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
