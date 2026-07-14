package com.fankes.coloros.notify.hook.systemui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusBarIconClaimRegistryTest {

    private val registry = StatusBarIconClaimRegistry<Any, Any>()

    @Test
    fun `normal and sensitive descriptors for one notification keep separate claims`() {
        val normalDescriptor = Any()
        val sensitiveDescriptor = Any()
        val normalView = Any()
        val sensitiveView = Any()
        registry.claimDescriptor(normalDescriptor, "notification", true)
        registry.claimDescriptor(sensitiveDescriptor, "notification", false)

        registry.bind(normalView, normalDescriptor)
        registry.bind(sensitiveView, sensitiveDescriptor)

        assertEquals(true, registry.colorability(normalView, "notification"))
        assertEquals(false, registry.colorability(sensitiveView, "notification"))
    }

    @Test
    fun `binding an unclaimed host descriptor releases the previous view claim`() {
        val moduleDescriptor = Any()
        val view = Any()
        registry.claimDescriptor(moduleDescriptor, "notification", true)
        registry.bind(view, moduleDescriptor)

        registry.bind(view, Any())

        assertNull(registry.colorability(view, "notification"))
    }

    @Test
    fun `rendered claim survives until the view receives another descriptor`() {
        val descriptor = Any()
        val view = Any()
        registry.claimDescriptor(descriptor, "notification", true)
        registry.bind(view, descriptor)

        assertEquals(true, registry.colorability(view, "notification"))
        assertEquals(true, registry.colorability(view, "notification"))
    }

    @Test
    fun `notification mismatch invalidates a reused view claim`() {
        val descriptor = Any()
        val view = Any()
        registry.claimDescriptor(descriptor, "notification-a", true)
        registry.bind(view, descriptor)

        assertNull(registry.colorability(view, "notification-b"))
        assertNull(registry.colorability(view, "notification-a"))
    }

    @Test
    fun `failed bind releases only its own claim`() {
        val firstDescriptor = Any()
        val secondDescriptor = Any()
        val view = Any()
        registry.claimDescriptor(firstDescriptor, "notification", true)
        registry.claimDescriptor(secondDescriptor, "notification", false)
        val firstClaim = registry.bind(view, firstDescriptor)
        registry.bind(view, secondDescriptor)

        registry.release(view, firstClaim)

        assertEquals(false, registry.colorability(view, "notification"))
    }

    @Test
    fun `equal but distinct descriptors never share ownership`() {
        val claimedDescriptor = EqualKey()
        val equalHostDescriptor = EqualKey()
        val view = Any()
        registry.claimDescriptor(claimedDescriptor, "notification", true)

        registry.bind(view, equalHostDescriptor)

        assertNull(registry.colorability(view, "notification"))
    }

    private class EqualKey {
        override fun equals(other: Any?): Boolean = other is EqualKey
        override fun hashCode(): Int = 1
    }
}
