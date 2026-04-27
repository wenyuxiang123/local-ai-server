package com.localai.server.ui.tabs

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.localai.server.compiler.AppCompiler
import com.localai.server.compiler.ModelQuantizer
import com.localai.server.databinding.FragmentCompileBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 编译 Tab - 包含模型量化、应用编译、APK 管理
 */
@AndroidEntryPoint
class CompileFragment : Fragment() {

    private var _binding: FragmentCompileBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var modelQuantizer: ModelQuantizer

    @Inject
    lateinit var appCompiler: AppCompiler

    private var selectedQuantType: String = "Q4_K_M"
    private var selectedModel: String? = null
    private var generatedApk: java.io.File? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCompileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
        observeStates()
        checkEnvironment()
    }

    private fun setupViews() {
        // 量化类型选择
        binding.rgQuantType.setOnCheckedChangeListener { _, checkedId ->
            selectedQuantType = when (checkedId) {
                binding.rbQ2K.id -> "Q2_K"
                binding.rbQ3K.id -> "Q3_K_M"
                binding.rbQ4K.id -> "Q4_K_M"
                binding.rbQ5K.id -> "Q5_K_M"
                binding.rbQ6K.id -> "Q6_K"
                binding.rbQ8.id -> "Q8_0"
                else -> "Q4_K_M"
            }
            updateSizeEstimate()
        }

        // 开始量化
        binding.btnQuantize.setOnClickListener {
            selectedModel?.let { model ->
                quantizeModel(model)
            } ?: Toast.makeText(requireContext(), "请先选择模型", Toast.LENGTH_SHORT).show()
        }

        // 编译方式选择
        binding.rgCompileType.setOnCheckedChangeListener { _, checkedId ->
            // 选择变化时的处理
        }

        // 开始编译
        binding.btnCompile.setOnClickListener {
            val compileType = when (binding.rgCompileType.checkedRadioButtonId) {
                binding.rbLocal.id -> AppCompiler.CompileType.LOCAL
                binding.rbCloud.id -> AppCompiler.CompileType.CLOUD
                binding.rbGithub.id -> AppCompiler.CompileType.GITHUB_ACTIONS
                else -> AppCompiler.CompileType.CLOUD
            }
            startCompile(compileType)
        }

        // 安装 APK
        binding.btnInstallApk.setOnClickListener {
            generatedApk?.let { apk ->
                installApk(apk)
            }
        }

        // 分享 APK
        binding.btnShareApk.setOnClickListener {
            generatedApk?.let { apk ->
                shareApk(apk)
            }
        }
    }

    private fun observeStates() {
        // 量化状态
        viewLifecycleOwner.lifecycleScope.launch {
            modelQuantizer.quantizeState.collectLatest { state ->
                binding.progressQuantize.isVisible = state.isQuantizing
                if (state.isQuantizing) {
                    binding.progressQuantize.progress = state.progress
                    binding.btnQuantize.isEnabled = false
                    binding.btnQuantize.text = "量化中... ${state.progress}%"
                } else {
                    binding.btnQuantize.isEnabled = true
                    binding.btnQuantize.text = "开始量化"
                }

                if (state.outputPath.isNotEmpty()) {
                    Toast.makeText(requireContext(), "量化完成: ${state.outputPath}", Toast.LENGTH_SHORT).show()
                }

                if (state.error != null) {
                    Toast.makeText(requireContext(), "量化失败: ${state.error}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 编译状态
        viewLifecycleOwner.lifecycleScope.launch {
            appCompiler.compileState.collectLatest { state ->
                binding.progressCompile.isVisible = state.isCompiling
                binding.tvCompileStatus.isVisible = state.isCompiling
                binding.tvCompileStep.isVisible = true

                if (state.isCompiling) {
                    binding.progressCompile.progress = state.progress
                    binding.tvCompileStatus.text = "进度: ${state.progress}%"
                    binding.tvCompileStep.text = state.currentStep
                    binding.btnCompile.isEnabled = false
                    binding.btnCompile.text = "编译中..."
                } else {
                    binding.btnCompile.isEnabled = true
                    binding.btnCompile.text = "🚀 开始编译"
                }

                // 更新日志
                if (state.log.isNotEmpty()) {
                    binding.tvBuildLog.text = state.log
                }

                // 显示 APK 信息
                state.outputApk?.let { apk ->
                    generatedApk = apk
                    binding.tvApkInfo.text = "${apk.name} (${formatSize(apk.length())})"
                    binding.btnInstallApk.isEnabled = true
                    binding.btnShareApk.isEnabled = true
                }

                if (state.error != null) {
                    binding.tvCompileStatus.text = "编译失败: ${state.error}"
                }
            }
        }
    }

    private fun checkEnvironment() {
        val envCheck = appCompiler.checkCompileEnvironment()

        // 更新环境状态
        binding.tvTermuxStatus.text = if (envCheck.hasTermux) "✅ 已安装" else "❌ 未安装"
        binding.tvNdkStatus.text = if (envCheck.hasNDK) "✅ 可用" else "❌ 不可用"
        binding.tvCloudStatus.text = if (envCheck.hasCloudAccess) "✅ 可用" else "❌ 不可用"

        // 推荐方式
        val recommendedMethod = when (envCheck.recommendedMethod) {
            AppCompiler.CompileType.LOCAL -> "本地编译"
            AppCompiler.CompileType.CLOUD -> "云端编译"
            AppCompiler.CompileType.GITHUB_ACTIONS -> "GitHub Actions"
        }
        binding.tvRecommendedMethod.text = recommendedMethod

        // 自动选择
        when (envCheck.recommendedMethod) {
            AppCompiler.CompileType.LOCAL -> binding.rbLocal.isChecked = true
            AppCompiler.CompileType.CLOUD -> binding.rbCloud.isChecked = true
            AppCompiler.CompileType.GITHUB_ACTIONS -> binding.rbGithub.isChecked = true
        }
    }

    private fun updateSizeEstimate() {
        // 模拟原大小
        val originalSize = 1_200_000_000L // 假设 1.2GB
        val quantizedSize = modelQuantizer.estimateQuantizedSize(originalSize, selectedQuantType)

        binding.tvOriginalSize.text = formatSize(originalSize)
        binding.tvQuantizedSize.text = formatSize(quantizedSize)
    }

    private fun quantizeModel(modelPath: String) {
        lifecycleScope.launch {
            val outputPath = modelPath.replace(".gguf", "-${selectedQuantType}.gguf")
            val config = ModelQuantizer.QuantizeConfig(
                inputPath = modelPath,
                outputPath = outputPath,
                quantType = selectedQuantType
            )

            modelQuantizer.quantizeModel(config)
        }
    }

    private fun startCompile(type: AppCompiler.CompileType) {
        lifecycleScope.launch {
            appCompiler.startCompile(type)
        }
    }

    private fun installApk(apk: java.io.File) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "安装失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareApk(apk: java.io.File) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                apk
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(Intent.createChooser(intent, "分享 APK"))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024))
            bytes >= 1024L -> String.format("%.2f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
