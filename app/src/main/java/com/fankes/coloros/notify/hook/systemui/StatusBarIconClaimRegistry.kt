package com.fankes.coloros.notify.hook.systemui

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/**
 * Carries module ownership from a returned status-bar descriptor to the view rendering it.
 *
 * Both maps use weak keys so SystemUI owns every lifecycle. A rendered claim deliberately
 * survives configuration publication: it describes the content currently held by the view and
 * is replaced only when [bind] observes the next descriptor passed to that view.
 */
internal class StatusBarIconClaimRegistry<Descriptor : Any, Target : Any> {

    internal class Claim(
        val notificationKey: String,
        val isColorable: Boolean,
    )

    private val lock = Any()
    private val descriptorClaims = WeakIdentityMap<Descriptor, Claim>()
    private val targetClaims = WeakIdentityMap<Target, Claim>()

    fun claimDescriptor(
        descriptor: Descriptor,
        notificationKey: String,
        isColorable: Boolean,
    ) {
        synchronized(lock) {
            descriptorClaims[descriptor] = Claim(notificationKey, isColorable)
        }
    }

    /** Binds the exact descriptor about to be rendered, releasing any previous target claim. */
    fun bind(target: Target, descriptor: Descriptor): Claim? = synchronized(lock) {
        descriptorClaims[descriptor]
            ?.also { targetClaims[target] = it }
            ?: run {
                targetClaims.remove(target)
                null
            }
    }

    /** Releases only the claim installed by the corresponding bind attempt. */
    fun release(target: Target, expected: Claim?) {
        synchronized(lock) {
            if (targetClaims[target] === expected) targetClaims.remove(target)
        }
    }

    fun colorability(target: Target, notificationKey: String): Boolean? = synchronized(lock) {
        val claim = targetClaims[target] ?: return@synchronized null
        if (claim.notificationKey == notificationKey) {
            claim.isColorable
        } else {
            targetClaims.remove(target)
            null
        }
    }
}

/** A minimal weak map whose key contract is reference identity rather than [Any.equals]. */
private class WeakIdentityMap<Key : Any, Value> {

    private val staleKeys = ReferenceQueue<Key>()
    private val entries = HashMap<IdentityWeakReference<Key>, Value>()

    operator fun get(key: Key): Value? {
        removeStaleKeys()
        return entries[IdentityWeakReference(key)]
    }

    operator fun set(key: Key, value: Value) {
        removeStaleKeys()
        entries[IdentityWeakReference(key, staleKeys)] = value
    }

    fun remove(key: Key): Value? {
        removeStaleKeys()
        return entries.remove(IdentityWeakReference(key))
    }

    private fun removeStaleKeys() {
        while (true) {
            @Suppress("UNCHECKED_CAST")
            val stale = staleKeys.poll() as? IdentityWeakReference<Key> ?: return
            entries.remove(stale)
        }
    }
}

private class IdentityWeakReference<Value : Any>(
    value: Value,
    queue: ReferenceQueue<Value>? = null,
) : WeakReference<Value>(value, queue) {

    private val identityHashCode = System.identityHashCode(value)

    override fun hashCode(): Int = identityHashCode

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdentityWeakReference<*>) return false
        val value = get() ?: return false
        return value === other.get()
    }
}
