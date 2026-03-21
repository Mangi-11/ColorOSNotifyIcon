package com.fankes.coloros.notify.ui.activity

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.fankes.coloros.notify.BuildConfig
import com.fankes.coloros.notify.const.ModuleInfo
import com.fankes.coloros.notify.const.PackageName
import com.fankes.coloros.notify.data.ConfigData
import com.fankes.coloros.notify.databinding.ActivityMainBinding
import com.fankes.coloros.notify.utils.tool.FrameworkServiceBridge
import com.fankes.coloros.notify.utils.tool.IconRuleManagerTool
import com.fankes.coloros.notify.utils.tool.SystemUiControl
import com.google.android.material.snackbar.Snackbar
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedService.OnScopeEventListener
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private data class RemoteMirrorResult(
        val rulesCount: Int,
        val payloadBytes: Int,
    )

    private lateinit var binding: ActivityMainBinding
    private var service: XposedService? = null
    private val remoteSyncExecutor = Executors.newSingleThreadExecutor()
    private val frameworkListener = object : FrameworkServiceBridge.Listener {
        override fun onServiceChanged(service: XposedService?) {
            this@MainActivity.service = service
            runOnUiThread {
                if (service != null) mirrorToRemoteStore(showErrors = false)
                updateStatus()
            }
        }
    }

    private val scopeCallback = object : OnScopeEventListener {
        override fun onScopeRequestApproved(approved: List<String>) {
            runOnUiThread {
                showMessage("作用域授权成功：${approved.joinToString()}")
                updateStatus()
            }
        }

        override fun onScopeRequestFailed(message: String) {
            runOnUiThread { showMessage("作用域授权失败：$message") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConfigData.initialize(applicationContext)
        service = FrameworkServiceBridge.getCurrentService()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindLocalState()
        bindActions()
        updateStatus()
    }

    private fun bindLocalState() {
        binding.moduleEnableSwitch.isChecked = ConfigData.isModuleEnabled
        binding.iconEnhancementSwitch.isChecked = ConfigData.isIconEnhancementEnabled
        binding.versionText.text = "模块版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        binding.deviceText.text = "目标环境：ColorOS 16 / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        refreshRuleState()
        refreshSettingState()
    }

    private fun bindActions() {
        binding.moduleEnableSwitch.setOnCheckedChangeListener { _, isChecked ->
            ConfigData.isModuleEnabled = isChecked
            refreshSettingState()
            mirrorToRemoteStore()
            updateStatus()
            showRestartHint()
        }
        binding.iconEnhancementSwitch.setOnCheckedChangeListener { _, isChecked ->
            ConfigData.isIconEnhancementEnabled = isChecked
            refreshSettingState()
            mirrorToRemoteStore()
            updateStatus()
            showRestartHint()
        }
        binding.requestScopeButton.setOnClickListener {
            val currentService = service
            if (currentService == null) {
                showMessage("未连接 modern Xposed 框架")
                return@setOnClickListener
            }
            currentService.requestScope(listOf(PackageName.SYSTEM_SCOPE, PackageName.SYSTEM_UI), scopeCallback)
        }
        binding.syncRulesButton.setOnClickListener {
            binding.syncRulesButton.isEnabled = false
            binding.syncRulesButton.text = "同步中..."
            IconRuleManagerTool.syncRules { result ->
                result.onSuccess { syncResult ->
                    refreshRuleState()
                    binding.syncRulesButton.text = "镜像中..."
                    mirrorToRemoteStore(showErrors = false) { mirrorResult ->
                        binding.syncRulesButton.isEnabled = true
                        binding.syncRulesButton.text = "同步规则"
                        mirrorResult.onSuccess {
                            showMessage("规则同步完成，共 ${syncResult.count} 条")
                            showRestartHint()
                        }.onFailure {
                            showMessage("本地规则已同步，但镜像到框架失败：${it.message ?: it.javaClass.simpleName}")
                        }
                    }
                }.onFailure {
                    binding.syncRulesButton.isEnabled = true
                    binding.syncRulesButton.text = "同步规则"
                    showMessage("规则同步失败：${it.message ?: it.javaClass.simpleName}")
                }
            }
        }
        binding.restartSystemUiButton.setOnClickListener {
            binding.restartSystemUiButton.isEnabled = false
            mirrorToRemoteStore(showErrors = false) { mirrorResult ->
                mirrorResult.onSuccess {
                    SystemUiControl.restartSystemUi { result ->
                        binding.restartSystemUiButton.isEnabled = true
                        result.onSuccess {
                            showMessage("已请求重启 SystemUI")
                        }.onFailure {
                            showMessage("重启失败：${it.message ?: it.javaClass.simpleName}")
                        }
                    }
                }.onFailure {
                    binding.restartSystemUiButton.isEnabled = true
                    showMessage("重启前配置镜像失败：${it.message ?: it.javaClass.simpleName}")
                }
            }
        }
        binding.openGithubButton.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ModuleInfo.PROJECT_URL)))
        }
    }

    private fun mirrorToRemoteStore(
        showErrors: Boolean = true,
        onResult: ((Result<RemoteMirrorResult>) -> Unit)? = null,
    ) {
        val currentService = service
        if (currentService == null) {
            val error = IllegalStateException("未连接 modern Xposed 框架")
            if (showErrors) showMessage(error.message ?: "配置镜像失败")
            onResult?.invoke(Result.failure(error))
            return
        }
        remoteSyncExecutor.execute {
            val result = runCatching {
                val remotePrefs = currentService.getRemotePreferences(ConfigData.GROUP_CONFIG)
                val committed = ConfigData.mirrorTo(remotePrefs)
                check(committed) { "远程偏好设置提交失败" }
                val payload = ConfigData.rulesJson.toByteArray(Charsets.UTF_8)
                currentService.openRemoteFile(ConfigData.RULES_FILE_NAME).use { pfd ->
                    FileOutputStream(pfd.fileDescriptor).use { output ->
                        output.channel.truncate(0)
                        output.write(payload)
                        output.flush()
                        output.fd.sync()
                    }
                }
                val mirroredCount = remotePrefs.getInt(ConfigData.KEY_RULES_COUNT, -1)
                check(mirroredCount == ConfigData.rulesCount) {
                    "远程规则数量不一致：local=${ConfigData.rulesCount}, remote=$mirroredCount"
                }
                val mirroredJson = currentService.openRemoteFile(ConfigData.RULES_FILE_NAME).use { pfd ->
                    FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
                }
                check(mirroredJson == ConfigData.rulesJson) { "远程规则文件校验失败" }
                RemoteMirrorResult(
                    rulesCount = mirroredCount,
                    payloadBytes = payload.size,
                )
            }
            runOnUiThread {
                result.onFailure {
                    if (showErrors) showMessage("配置镜像失败：${it.message ?: it.javaClass.simpleName}")
                }
                onResult?.invoke(result)
            }
        }
    }

    private fun refreshSettingState() {
        val moduleEnabled = binding.moduleEnableSwitch.isChecked
        binding.iconEnhancementSwitch.isEnabled = moduleEnabled
    }

    private fun refreshRuleState() {
        binding.rulesStatusText.text = "本地规则：${ConfigData.rulesCount} 条"
        binding.lastSyncText.text = if (ConfigData.rulesUpdatedAt > 0L) {
            val formatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(ConfigData.rulesUpdatedAt))
            "最后同步：$formatted"
        } else {
            "最后同步：从未同步"
        }
    }

    private fun updateStatus() {
        val currentService = service
        val cachedFramework = ConfigData.readFrameworkSnapshot()
        val requiredScopes = setOf(PackageName.SYSTEM_SCOPE, PackageName.SYSTEM_UI)
        val grantedScopes = (currentService?.scope ?: cachedFramework.scopes).toSet()
        val missingScopes = requiredScopes - grantedScopes

        binding.moduleStatusText.text = when {
            !ConfigData.isModuleEnabled -> "模块已关闭"
            currentService == null && cachedFramework.hasConnectionRecord && missingScopes.isEmpty() ->
                "模块已激活，当前只是设置页没有连上框架服务"
            currentService == null && cachedFramework.hasConnectionRecord ->
                "模块上次已连接框架，但作用域仍不完整：${missingScopes.joinToString()}"
            currentService == null -> "未连接 modern Xposed 框架"
            missingScopes.isNotEmpty() -> "模块已连接，但缺少作用域：${missingScopes.joinToString()}"
            else -> "模块已连接，重启 SystemUI 后按当前配置生效"
        }

        binding.frameworkStatusText.text = if (currentService == null) {
            if (cachedFramework.hasConnectionRecord) {
                "框架状态：上次连接 ${cachedFramework.frameworkName} ${cachedFramework.frameworkVersion} / API ${cachedFramework.apiVersion}"
            } else {
                "框架状态：未连接"
            }
        } else {
            "框架状态：${currentService.frameworkName} ${currentService.frameworkVersion} / API ${currentService.apiVersion}"
        }

        binding.scopeStatusText.text = if (currentService == null) {
            if (cachedFramework.hasConnectionRecord) {
                val label = if (cachedFramework.scopes.isEmpty()) "无" else cachedFramework.scopes.joinToString()
                "授权作用域：上次检测为 $label"
            } else {
                "授权作用域：未获取"
            }
        } else {
            "授权作用域：${if (grantedScopes.isEmpty()) "无" else grantedScopes.joinToString()}"
        }

        binding.requestScopeButton.isEnabled = currentService != null && missingScopes.isNotEmpty()
    }

    private fun showRestartHint() {
        Snackbar.make(binding.root, "设置已保存，重启 SystemUI 后生效", Snackbar.LENGTH_SHORT).show()
    }

    private fun showMessage(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        remoteSyncExecutor.shutdown()
    }

    override fun onStart() {
        super.onStart()
        FrameworkServiceBridge.addListener(frameworkListener)
        service?.let {
            mirrorToRemoteStore(showErrors = false)
        }
    }

    override fun onStop() {
        FrameworkServiceBridge.removeListener(frameworkListener)
        super.onStop()
    }
}
