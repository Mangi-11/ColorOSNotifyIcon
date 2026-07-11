package com.fankes.coloros.notify.rules

internal object FallbackSequence {

    data class Attempt<K, V>(
        val key: K,
        val load: () -> V,
    )

    data class Selection<K, V>(
        val key: K,
        val value: V,
        val failures: List<Exception>,
    )

    fun <K, V> firstValid(
        attempts: List<Attempt<K, V>>,
    ): Selection<K, V> {
        val failures = mutableListOf<Exception>()
        for (attempt in attempts) {
            try {
                return Selection(attempt.key, attempt.load(), failures.toList())
            } catch (exception: Exception) {
                failures += exception
            }
        }
        val failure = failures.firstOrNull()
            ?: IllegalStateException("No fallback attempt is available")
        failures.drop(1).forEach(failure::addSuppressed)
        throw failure
    }
}
