package com.eignex.klause.solver.result

import com.eignex.klause.lp.engine.CutFamily
import kotlin.time.TimeSource

internal enum class LpCutRecipe { ORDINARY, STRUCTURAL, COMPONENT }

internal class LpCutStats(private val record: (String, LpPhaseStats) -> Unit) {
    var recipe: LpRoute = LpRoute.NODE
    var kind: LpCutRecipe? = null
    var families: Set<CutFamily> = emptySet()
    private fun key(family: CutFamily, stage: String): String =
        "CUT_${recipe.name}_${kind?.let { "${it.name}_" }.orEmpty()}${family.name}_$stage"

    fun event(family: CutFamily, stage: String, outcome: String, count: Long = 1L) {
        record(key(family, stage),
            LpPhaseStats(calls = count, outcomes = mapOf(outcome to count)))
    }

    fun shared(stage: String, delta: LpPhaseStats) {
        for (family in families) record(key(family, stage), delta)
    }

    fun <T> measure(family: CutFamily, stage: String, block: () -> T): T {
        val started = TimeSource.Monotonic.markNow()
        var outcome = "FAILED"
        try {
            return block().also { outcome = "COMPLETED" }
        } finally {
            record(key(family, stage), LpPhaseStats(calls = 1L,
                nanos = started.elapsedNow().inWholeNanoseconds, outcomes = mapOf(outcome to 1L)))
        }
    }

    fun <T> measureShared(stage: String, block: () -> T): T {
        val started = TimeSource.Monotonic.markNow()
        var outcome = "FAILED"
        try {
            return block().also { outcome = if (it == null) "DECLINED" else "COMPLETED" }
        } finally {
            shared(stage, LpPhaseStats(calls = 1L, nanos = started.elapsedNow().inWholeNanoseconds,
                outcomes = mapOf(outcome to 1L)))
        }
    }
}
