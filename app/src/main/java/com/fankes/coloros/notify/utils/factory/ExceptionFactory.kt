package com.fankes.coloros.notify.utils.factory

inline fun <T> safeOf(default: T, block: () -> T): T = try {
    block()
} catch (_: Throwable) {
    default
}

inline fun <T> safeOfNull(block: () -> T): T? = safeOf(null, block)
inline fun safeOfFalse(block: () -> Boolean): Boolean = safeOf(false, block)
