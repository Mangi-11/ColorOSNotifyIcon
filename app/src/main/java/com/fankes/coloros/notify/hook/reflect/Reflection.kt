package com.fankes.coloros.notify.hook.reflect

import java.lang.reflect.Field
import java.lang.reflect.Method

internal object Reflection {

    fun loadClassOrNull(
        name: String,
        classLoader: ClassLoader,
        onMissing: (Throwable) -> Unit,
    ): Class<*>? = try {
        Class.forName(name, false, classLoader)
    } catch (exception: ClassNotFoundException) {
        onMissing(exception)
        null
    }

    fun findField(
        clazz: Class<*>,
        name: String,
        expectedType: Class<*>? = null,
    ): Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            current.declaredFields.firstOrNull {
                it.name == name && (expectedType == null || expectedType.isAssignableFrom(it.type))
            }?.let {
                it.isAccessible = true
                return it
            }
            current = current.superclass
        }
        return null
    }

    fun findMethod(
        clazz: Class<*>,
        name: String,
        vararg params: Class<*>,
    ): Method? = findMethodReturning(clazz, name, null, *params)

    fun findMethodReturning(
        clazz: Class<*>,
        name: String,
        expectedReturnType: Class<*>?,
        vararg params: Class<*>,
    ): Method? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            current.declaredMethods.firstOrNull { method ->
                method.name == name &&
                    method.parameterTypes.contentEquals(params) &&
                    (expectedReturnType == null || expectedReturnType.isAssignableFrom(method.returnType))
            }?.let {
                it.isAccessible = true
                return it
            }
            current = current.superclass
        }
        return null
    }
}
