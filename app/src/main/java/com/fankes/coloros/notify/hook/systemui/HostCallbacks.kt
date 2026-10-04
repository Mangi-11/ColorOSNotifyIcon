package com.fankes.coloros.notify.hook.systemui

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * Wraps a SystemUI-side callback so [after] runs once the host callback returns.
 *
 * SystemUI ships its own Kotlin stdlib, so its `Function1` cannot be implemented by a module-side
 * object; a dynamic proxy of the host interface is used instead.
 */
internal fun wrapHostCallback(callbackType: Class<*>, original: Any, after: () -> Unit): Any =
    Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "ColorOSNotifyIcon\$HostCallback($original)"
            else -> {
                val result = try {
                    method.invoke(original, *(args ?: emptyArray()))
                } catch (exception: InvocationTargetException) {
                    throw exception.targetException
                }
                after()
                result
            }
        }
    }
