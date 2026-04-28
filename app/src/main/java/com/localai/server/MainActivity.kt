package com.localai.server

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayoutMediator
import com.localai.server.databinding.ActivityMainBinding
import com.localai.server.domain.model.AVAILABLE_MODELS
import com.localai.server.engine.LlamaEngine
import com.localai.server.ui.chat.ChatActivity
import com.localai.server.ui.main.MainEffect
import com.localai.server.ui.main.MainIntent
import com.localai.server.ui.main.MainState
import com.localai.server.ui.main.MainViewModel
import com.localai.server.ui.tabs.MainPagerAdapter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var selectedModelUri: Uri? = null
    
    // 隐藏日志上传入口 - 连续点击toolbar标题5次
    private var titleClickCount = 0
    private var lastTitleClickTime = 0L
    private val TITLE_CLICK_TIMEOUT = 1000L // 1秒内点击5次
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (!allGranted) {
            showToast("需要权限才能正常使用")
        }
    }
    
    private val selectModelLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            selectedModelUri = it
            val fileName = it.lastPathSegment?.substringAfterLast("/") ?: "选中文件"
            showToast("已选择: $fileName")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // 检查native库是否可用
        checkNativeLibraries()
        
        checkPermissions()
        setupToolbar()
        setupDrawer()
        setupTabs()
        observeState()
        observeEffects()
    }
    
    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        
        // 隐藏日志上传入口：连续点击toolbar标题5次触发
        binding.toolbar.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTitleClickTime < TITLE_CLICK_TIMEOUT) {
                titleClickCount++
                if (titleClickCount >= 5) {
                    titleClickCount = 0
                    Log.i(TAG, "Secret log upload triggered!")
                    showToast("正在上传运行日志...")
                    com.localai.server.service.LogCollectorService.uploadLogs(this)
                }
            } else {
                titleClickCount = 1
            }
            lastTitleClickTime = now
        }
    }
    
    private fun setupDrawer() {
        binding.navView.setNavigationItemSelectedListener(this)
    }
    
    override fun onNavigationItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_schedule -> showToast("日程功能开发中")
            R.id.menu_files -> showToast("文件功能开发中")
            R.id.menu_email -> showToast("邮箱功能开发中")
            R.id.menu_device -> showToast("设备功能开发中")
            R.id.menu_channels -> showToast("渠道功能开发中")
            R.id.menu_skills -> showToast("技能功能开发中")
            R.id.menu_agent_world -> showToast("Agent World功能开发中")
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }
    
    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
    
    private fun checkNativeLibraries() {
        try {
            val loaded = LlamaEngine.loadLibraries()
            if (!loaded) {
                val error = LlamaEngine.getLoadError() ?: "Unknown error"
                Log.e(TAG, "Native library check failed: $error")
                showToast("Native库加载失败: $error")
            } else {
                Log.i(TAG, "Native libraries loaded successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check native libraries", e)
            showToast("检查Native库时出错: ${e.message}")
        }
    }
    
    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }
    
    private fun setupTabs() {
        // 设置 ViewPager
        val adapter = MainPagerAdapter(this)
        binding.viewPager.adapter = adapter
        
        // 连接 TabLayout 和 ViewPager
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "主页"
                1 -> "优化"
                2 -> "编译"
                else -> ""
            }
        }.attach()
    }
    
    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    // 状态更新会在各个 Fragment 中处理
                }
            }
        }
    }
    
    private fun observeEffects() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effect.collect { effect ->
                    when (effect) {
                        is MainEffect.ShowToast -> showToast(effect.message)
                        is MainEffect.ShowError -> showError(effect.message)
                        is MainEffect.ExtractComplete -> { /* handled in fragment */ }
                    }
                }
            }
        }
    }
    
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    private fun showError(message: String) {
        Toast.makeText(this, "错误: $message", Toast.LENGTH_LONG).show()
    }
}
