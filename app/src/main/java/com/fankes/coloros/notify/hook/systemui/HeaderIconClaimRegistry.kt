package com.fankes.coloros.notify.hook.systemui

import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Tracks a rendered header icon without retaining SystemUI views or their drawables.
 *
 * A claim is valid only while the same target still displays the same content for the
 * same notification. Any mismatch invalidates the stale claim immediately.
 */
internal class HeaderIconClaimRegistry<Target : Any, Content : Any> {

    private data class Claim<Content : Any>(
        val notificationKey: String,
        val content: WeakReference<Content>,
    )

    private val claims = WeakHashMap<Target, Claim<Content>>()

    fun claim(target: Target, notificationKey: String, content: Content) {
        synchronized(claims) {
            claims[target] = Claim(notificationKey, WeakReference(content))
        }
    }

    fun release(target: Target) {
        synchronized(claims) {
            claims.remove(target)
        }
    }

    fun isCurrentClaim(target: Target, notificationKey: String, content: Content): Boolean =
        synchronized(claims) {
            val claim = claims[target] ?: return@synchronized false
            val isCurrent = claim.notificationKey == notificationKey && claim.content.get() === content
            if (!isCurrent) claims.remove(target)
            isCurrent
        }

    fun clear() {
        synchronized(claims) {
            claims.clear()
        }
    }
}
