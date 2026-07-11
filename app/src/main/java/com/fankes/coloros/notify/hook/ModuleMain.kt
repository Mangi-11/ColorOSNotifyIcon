package com.fankes.coloros.notify.hook

import com.fankes.coloros.notify.core.SystemPackages
import com.fankes.coloros.notify.diagnostics.DiagnosticEvent
import com.fankes.coloros.notify.diagnostics.DiagnosticLevel
import com.fankes.coloros.notify.diagnostics.Diagnostics
import com.fankes.coloros.notify.diagnostics.OccurrencePolicy
import com.fankes.coloros.notify.hook.systemserver.SystemServerHooks
import com.fankes.coloros.notify.hook.systemui.SystemUiRuntime
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

class ModuleMain : XposedModule() {

    private lateinit var target: ProcessTarget
    private lateinit var processName: String
    private lateinit var diagnostics: Diagnostics
    private lateinit var hooks: HookRegistrar

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        diagnostics = Diagnostics(XposedDiagnosticSink(this, processName))
        hooks = HookRegistrar(this, diagnostics, processName)
        target = resolveProcessTarget(param.isSystemServer, processName)

        diagnostics.report(
            level = DiagnosticLevel.Info,
            event = DiagnosticEvent.ModuleLoaded,
            message = "模块入口已路由",
            attributes = mapOf(
                "api" to apiVersion,
                "framework" to frameworkName,
                "target" to target.diagnosticName,
            ),
            occurrence = OccurrencePolicy.Once(processName),
        )
        if (target == ProcessTarget.Ignore) {
            diagnostics.report(
                level = DiagnosticLevel.Info,
                event = DiagnosticEvent.ProcessIgnored,
                message = "当前进程不承载任何模块功能",
                occurrence = OccurrencePolicy.Once(processName),
            )
            detach()
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        if (target != ProcessTarget.SystemServer) return
        try {
            SystemServerHooks(
                hooks = hooks,
                diagnostics = diagnostics,
            ).install(param.classLoader)
        } catch (exception: Exception) {
            diagnostics.installationFailure(
                scope = "system_server:initialize",
                message = "system_server 功能初始化失败",
                cause = exception,
            )
        }
        detachAfterInitialization()
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (target != ProcessTarget.SystemUiMain) return
        if (param.packageName != SystemPackages.SYSTEM_UI || !param.isFirstPackage) return

        try {
            SystemUiRuntime(
                xposed = this,
                hooks = hooks,
                diagnostics = diagnostics,
                processName = processName,
            ).install(param.classLoader)
        } catch (exception: Exception) {
            diagnostics.installationFailure(
                scope = "systemui:initialize",
                message = "SystemUI 运行时初始化失败",
                cause = exception,
            )
        }
        detachAfterInitialization()
    }

    /**
     * Installed hooks, preference callbacks and delayed UI work have an explicit process lifetime.
     * A partial generation swap cannot safely retire them, so hot reload remains unsupported.
     */
    override fun onHotReloading(param: HotReloadingParam): Boolean = false

    private fun detachAfterInitialization() {
        diagnostics.report(
            level = DiagnosticLevel.Info,
            event = DiagnosticEvent.LifecycleDetached,
            message = "初始化完成，停止接收后续生命周期回调",
            occurrence = OccurrencePolicy.Once("initialized:$processName"),
        )
        detach()
    }

}

internal enum class ProcessTarget(val diagnosticName: String) {
    SystemServer("system_server"),
    SystemUiMain("systemui_main"),
    Ignore("ignore"),
}

internal fun resolveProcessTarget(
    isSystemServer: Boolean,
    processName: String,
): ProcessTarget = when {
    isSystemServer -> ProcessTarget.SystemServer
    processName == SystemPackages.SYSTEM_UI -> ProcessTarget.SystemUiMain
    else -> ProcessTarget.Ignore
}
