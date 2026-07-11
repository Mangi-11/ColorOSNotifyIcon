package com.fankes.coloros.notify.framework

internal class LatestRequestQueue<T, C>(
    private val revisionOf: (T) -> Long,
    private val isEquivalent: (T, T) -> Boolean = { left, right ->
        revisionOf(left) == revisionOf(right)
    },
) {
    data class Request<T, C>(
        var value: T,
        val callbacks: MutableList<C>,
    )

    data class Completion<T, C>(
        val callbacks: List<C>,
        val next: Request<T, C>?,
    )

    private var active: Request<T, C>? = null
    private var pending: Request<T, C>? = null

    @Synchronized
    fun enqueue(value: T, callback: C?): Request<T, C>? {
        val current = active
        if (current == null) {
            return Request(value, callback.asMutableList()).also { active = it }
        }

        val waiting = pending
        if (waiting != null) {
            if (revisionOf(value) >= revisionOf(waiting.value)) waiting.value = value
            if (callback != null) waiting.callbacks += callback
            return null
        }

        val revision = revisionOf(value)
        val activeRevision = revisionOf(current.value)
        if (revision < activeRevision || revision == activeRevision && isEquivalent(value, current.value)) {
            if (callback != null) current.callbacks += callback
        } else {
            pending = Request(value, callback.asMutableList())
        }
        return null
    }

    @Synchronized
    fun complete(
        request: Request<T, C>,
        succeeded: Boolean,
    ): Completion<T, C> {
        check(active === request) { "Completed request is not active" }
        val next = pending
        pending = null
        val callbacks = if (!succeeded && next != null) {
            next.callbacks.addAll(0, request.callbacks)
            emptyList()
        } else {
            request.callbacks.toList()
        }
        active = next
        return Completion(callbacks, next)
    }

    private fun C?.asMutableList(): MutableList<C> =
        if (this == null) mutableListOf() else mutableListOf(this)
}
