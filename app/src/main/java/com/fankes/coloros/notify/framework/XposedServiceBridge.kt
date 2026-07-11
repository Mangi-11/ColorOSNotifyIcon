package com.fankes.coloros.notify.framework

import com.fankes.coloros.notify.diagnostics.AppDiagnostics
import com.fankes.coloros.notify.diagnostics.DiagnosticEvent
import com.fankes.coloros.notify.diagnostics.DiagnosticLevel
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet

object XposedServiceBridge {

    data class ServiceSnapshot(
        val frameworkName: String,
        val frameworkVersion: String,
        val apiVersion: Int,
        val scopes: Set<String>,
    )

    /** Receives conflated service changes asynchronously on the main thread. */
    interface Listener {
        fun onServiceChanged(service: XposedService?)
    }

    private data class ServiceState(
        val service: XposedService?,
        val generation: Long,
    )

    @Volatile
    private var initialized = false

    @Volatile
    private var currentService: XposedService? = null

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val connectedServices = LinkedHashSet<XposedService>()
    private var serviceGeneration = 0L

    fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val registered = try {
                XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                    override fun onServiceBind(service: XposedService) {
                        val state = synchronized(this@XposedServiceBridge) {
                            connectedServices.remove(service)
                            connectedServices += service
                            currentService = service
                            ServiceState(service, ++serviceGeneration)
                        }
                        AppDiagnostics.logger.report(
                            level = DiagnosticLevel.Info,
                            event = DiagnosticEvent.ServiceConnected,
                            message = "Xposed service connected",
                            attributes = mapOf("scope" to "connect"),
                        )
                        notifyListeners(state)
                    }

                    override fun onServiceDied(service: XposedService) {
                        disconnectIfCurrent(
                            service = service,
                            level = DiagnosticLevel.Warning,
                            event = DiagnosticEvent.ServiceDisconnected,
                            message = "Xposed service disconnected",
                            scope = "disconnect",
                        )
                    }
                })
                true
            } catch (exception: Exception) {
                AppDiagnostics.logger.report(
                    level = DiagnosticLevel.Error,
                    event = DiagnosticEvent.ServiceQueryFailed,
                    message = "Unable to register Xposed service listener",
                    cause = exception,
                    attributes = mapOf("scope" to "register"),
                )
                false
            }
            initialized = registered
        }
    }

    fun getCurrentService(): XposedService? = currentService

    fun snapshot(service: XposedService?): ServiceSnapshot? {
        if (service == null) return null
        return try {
            ServiceSnapshot(
                frameworkName = service.frameworkName,
                frameworkVersion = service.frameworkVersion,
                apiVersion = service.apiVersion,
                scopes = service.scope.toSet(),
            )
        } catch (exception: Exception) {
            disconnectIfCurrent(
                service = service,
                level = DiagnosticLevel.Error,
                event = DiagnosticEvent.ServiceQueryFailed,
                message = "Unable to read Xposed service state",
                cause = exception,
                scope = "query",
            )
            null
        }
    }

    fun addListener(listener: Listener, dispatchCurrent: Boolean = true) {
        listeners += listener
        if (dispatchCurrent) {
            val state = synchronized(this) {
                ServiceState(currentService, serviceGeneration)
            }
            dispatch(listener, state)
        }
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    private fun disconnectIfCurrent(
        service: XposedService,
        level: DiagnosticLevel,
        event: DiagnosticEvent,
        message: String,
        scope: String,
        cause: Exception? = null,
    ) {
        val state = synchronized(this) {
            connectedServices.remove(service)
            if (currentService !== service) return@synchronized null
            currentService = connectedServices.lastOrNull()
            ServiceState(currentService, ++serviceGeneration)
        }
        if (state == null) return
        AppDiagnostics.logger.report(
            level = level,
            event = event,
            message = message,
            cause = cause,
            attributes = mapOf("scope" to scope),
        )
        notifyListeners(state)
    }

    private fun notifyListeners(state: ServiceState) {
        listeners.forEach { listener -> dispatch(listener, state) }
    }

    private fun dispatch(listener: Listener, state: ServiceState) {
        MainThreadCallbacks.dispatch("xposed_service") {
            if (listener !in listeners) return@dispatch
            val isCurrent = synchronized(this) {
                state.generation == serviceGeneration && state.service === currentService
            }
            if (isCurrent) listener.onServiceChanged(state.service)
        }
    }
}
