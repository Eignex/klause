package com.eignex.klause.solver.search

internal enum class LearnedKind { Clause, Pb, Integer, SourceRefutation }

internal enum class LearnedStage { Constructed, Admitted, Asserted, Consumed, Retired }

internal class LearnedLifecycle {
    private val counts = LongArray(LearnedKind.entries.size * LearnedStage.entries.size)

    fun note(kind: LearnedKind, stage: LearnedStage) {
        counts[kind.ordinal * LearnedStage.entries.size + stage.ordinal]++
    }

    fun snapshot(): Map<String, Long> = buildMap {
        for (kind in LearnedKind.entries) for (stage in LearnedStage.entries) {
            val count = counts[kind.ordinal * LearnedStage.entries.size + stage.ordinal]
            if (count != 0L) put("learning${kind.name}${stage.name}", count)
        }
    }
}
