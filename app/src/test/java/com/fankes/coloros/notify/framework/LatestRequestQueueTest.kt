package com.fankes.coloros.notify.framework

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatestRequestQueueTest {

    @Test
    fun olderRevisionNeverQueuesBehindNewerActiveRevision() {
        val queue = LatestRequestQueue<Int, String>(revisionOf = Int::toLong)
        val active = queue.enqueue(5, "active")!!

        assertNull(queue.enqueue(4, "older"))
        val completion = queue.complete(active, succeeded = true)

        assertEquals(listOf("active", "older"), completion.callbacks)
        assertNull(completion.next)
    }

    @Test
    fun pendingRequestConflatesToLatestRevision() {
        val queue = LatestRequestQueue<Int, String>(revisionOf = Int::toLong)
        val active = queue.enqueue(1, "one")!!
        queue.enqueue(2, "two")
        queue.enqueue(3, "three")

        val completion = queue.complete(active, succeeded = true)

        assertEquals(listOf("one"), completion.callbacks)
        assertEquals(3, completion.next?.value)
        assertEquals(listOf("two", "three"), completion.next?.callbacks)
    }

    @Test
    fun supersededFailureIsDeliveredOnlyAfterLatestRequestSucceeds() {
        val queue = LatestRequestQueue<Int, String>(revisionOf = Int::toLong)
        val active = queue.enqueue(1, "one")!!
        queue.enqueue(2, "two")

        val failed = queue.complete(active, succeeded = false)
        assertEquals(emptyList<String>(), failed.callbacks)
        val next = failed.next!!
        assertEquals(listOf("one", "two"), next.callbacks)

        val succeeded = queue.complete(next, succeeded = true)
        assertEquals(listOf("one", "two"), succeeded.callbacks)
        assertNull(succeeded.next)
    }

    @Test
    fun equalEquivalentRevisionSharesActiveRequest() {
        val queue = LatestRequestQueue<Int, String>(revisionOf = Int::toLong)
        val active = queue.enqueue(7, "first")!!
        assertNull(queue.enqueue(7, "second"))

        val completion = queue.complete(active, succeeded = true)
        assertEquals(listOf("first", "second"), completion.callbacks)
    }
}
