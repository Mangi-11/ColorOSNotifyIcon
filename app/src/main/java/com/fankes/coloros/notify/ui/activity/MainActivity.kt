package com.fankes.coloros.notify.ui.activity

import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.content.res.ColorStateList
import com.fankes.coloros.notify.BuildConfig
import com.fankes.coloros.notify.R
import com.fankes.coloros.notify.const.PackageName
import com.fankes.coloros.notify.data.ConfigData
import com.fankes.coloros.notify.databinding.ActivityMainBinding
import com.fankes.coloros.notify.utils.tool.FrameworkServiceBridge
import com.fankes.coloros.notify.utils.tool.IconRuleManagerTool
import com.fankes.coloros.notify.utils.tool.RemoteConfigSyncTool
import com.fankes.coloros.notify.utils.tool.SystemUiControl
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import io.github.libxposed.service.XposedService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var service: XposedService? = null
    private val frameworkListener = object : FrameworkServiceBridge.Listener {
        override fun onServiceChanged(service: XposedService?) {
            this@MainActivity.service = service
            runOnUiThread {
                if (service != null && ConfigData.hasPendingRemoteSync) {
                    mirrorToRemoteStore(showErrors = false) {
                        updateStatus()
                    }
                } else {
                    updateStatus()
                }
            }
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
        binding.iconEnhancementSwitch.isChecked = ConfigData.isModuleEnabled && ConfigData.isIconEnhancementEnabled
        binding.versionText.text = "模块版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        binding.deviceText.text = "目标环境：ColorOS 16 / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        refreshRuleState()
        refreshSettingState()
    }

    private fun bindActions() {
        binding.iconEnhancementSwitch.setOnCheckedChangeListener { _, isChecked ->
            ConfigData.isModuleEnabled = isChecked
            ConfigData.isIconEnhancementEnabled = isChecked
            refreshSettingState()
            mirrorToRemoteStore(showErrors = false)
            updateStatus()
            showRestartHint()
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
            showRestartConfirmDialog()
        }
    }

    private fun showRestartConfirmDialog() {
        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_OStatus_Dialog)
            .setTitle(R.string.dialog_restart_title)
            .setMessage(R.string.dialog_restart_message)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_confirm_restart) { _, _ ->
                performRestartSystemUi()
            }
            .create()
        val background = MaterialShapeDrawable(
            ShapeAppearanceModel.builder(
                this,
                0,
                R.style.ShapeAppearanceOverlay_OStatus_Dialog
            ).build()
        ).apply {
            fillColor = ColorStateList.valueOf(
                ContextCompat.getColor(this@MainActivity, R.color.colorSurfaceCard)
            )
            initializeElevationOverlay(this@MainActivity)
        }
        dialog.window?.setBackgroundDrawable(background)
        dialog.show()
    }

    private fun performRestartSystemUi() {
        binding.restartSystemUiButton.isEnabled = false
        if (service == null && ConfigData.hasPendingRemoteSync) {
            binding.restartSystemUiButton.isEnabled = true
            showMessage("当前未连接 modern Xposed 框架，设置已保存到本地；待框架恢复后会自动同步，再重启一次 SystemUI 即可")
            updateStatus()
            return
        }
        if (service == null) {
            restartSystemUiDirectly()
            return
        }
        mirrorToRemoteStore(showErrors = false) { mirrorResult ->
            mirrorResult.onSuccess {
                restartSystemUiDirectly()
            }.onFailure {
                binding.restartSystemUiButton.isEnabled = true
                showMessage("配置尚未同步到框架：${it.message ?: it.javaClass.simpleName}")
                updateStatus()
            }
        }
    }

    private fun restartSystemUiDirectly() {
        SystemUiControl.restartSystemUi { result ->
            binding.restartSystemUiButton.isEnabled = true
            result.onSuccess {
                showMessage("已请求重启 SystemUI")
            }.onFailure {
                showMessage("重启失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun mirrorToRemoteStore(
        showErrors: Boolean = true,
        onResult: ((Result<RemoteConfigSyncTool.SyncResult>) -> Unit)? = null,
    ) {
        val currentService = service
        if (currentService == null) {
            val error = IllegalStateException(
                if (ConfigData.hasPendingRemoteSync) {
                    "未连接 modern Xposed 框架，本地修改待自动同步"
                } else {
                    "未连接 modern Xposed 框架"
                }
            )
            if (showErrors) showMessage(error.message ?: "配置镜像失败")
            onResult?.invoke(Result.failure(error))
            updateStatus()
            return
        }
        RemoteConfigSyncTool.syncAsync(currentService) { result ->
            result.onFailure {
                if (showErrors) showMessage("配置镜像失败：${it.message ?: it.javaClass.simpleName}")
            }
            updateStatus()
            onResult?.invoke(result)
        }
    }

    private fun refreshSettingState() {
        binding.iconEnhancementSwitch.isEnabled = true
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
                "模块已激活，设置页暂未连上框架服务"
            currentService == null && cachedFramework.hasConnectionRecord ->
                "模块上次已连接框架，但当前仍缺少作用域：${missingScopes.joinToString()}"
            currentService == null -> "未连接 modern Xposed 框架"
            missingScopes.isNotEmpty() -> "模块已连接，但仍缺少作用域：${missingScopes.joinToString()}"
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

        val isReady = ConfigData.isModuleEnabled && missingScopes.isEmpty() &&
            (currentService != null || cachedFramework.hasConnectionRecord)
        binding.moduleStatusIndicator.backgroundTintList = android.content.res.ColorStateList.valueOf(
            getColor(if (isReady) com.fankes.coloros.notify.R.color.colorStatusReady else com.fankes.coloros.notify.R.color.colorStatusIdle)
        )
    }

    private fun showRestartHint() {
        val message = if (service == null) {
            "设置已保存到本地；框架服务恢复后会自动同步，再重启一次 SystemUI 即可"
        } else {
            "设置已保存，正在同步到框架；完成后重启 SystemUI 生效"
        }
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun showMessage(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        FrameworkServiceBridge.addListener(frameworkListener)
        service?.takeIf { ConfigData.hasPendingRemoteSync }?.let {
            mirrorToRemoteStore(showErrors = false)
        }
    }

    override fun onStop() {
        FrameworkServiceBridge.removeListener(frameworkListener)
        super.onStop()
    }
}
