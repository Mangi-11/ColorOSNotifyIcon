package com.fankes.coloros.notify.hook.reflect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ReflectionTest {

    @Test
    fun `method lookup never falls back to parameter arity`() {
        assertNull(
            Reflection.findMethod(
                Fixture::class.java,
                "select",
                Boolean::class.javaPrimitiveType!!,
            )
        )
    }

    @Test
    fun `method lookup matches exact parameters and compatible return type`() {
        val method = Reflection.findMethodReturning(
            Fixture::class.java,
            "select",
            CharSequence::class.java,
            String::class.java,
        )

        assertEquals("string", method?.invoke(Fixture(), "value"))
        assertNull(
            Reflection.findMethodReturning(
                Fixture::class.java,
                "select",
                Number::class.java,
                String::class.java,
            )
        )
    }

    @Test
    fun `field lookup validates its declared type`() {
        val fixture = Fixture()
        val field = Reflection.findField(Fixture::class.java, "payload", CharSequence::class.java)

        assertSame(fixture.payload, field?.get(fixture))
        assertNull(Reflection.findField(Fixture::class.java, "payload", Number::class.java))
    }

    private class Fixture {
        val payload: String = "payload"

        @Suppress("unused")
        fun select(value: String): String = "string"

        @Suppress("unused")
        fun select(value: Int): String = "int"
    }
}
