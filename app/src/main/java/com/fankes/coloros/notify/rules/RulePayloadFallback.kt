package com.fankes.coloros.notify.rules

internal object RulePayloadFallback {

    data class Candidate(
        val fileName: String?,
        val sha256: String,
        val read: () -> String,
    )

    data class Selection<T>(
        val candidate: Candidate,
        val json: String,
        val value: T,
        val failures: List<Exception>,
    )

    fun <T> load(
        candidates: List<Candidate>,
        validate: (json: String, sha256: String) -> T,
    ): Selection<T> {
        val selection = FallbackSequence.firstValid(
            attempts = candidates.map { candidate ->
                FallbackSequence.Attempt(candidate) {
                    val json = candidate.read()
                    json to validate(json, candidate.sha256)
                }
            },
        )
        return Selection(
            candidate = selection.key,
            json = selection.value.first,
            value = selection.value.second,
            failures = selection.failures,
        )
    }
}
