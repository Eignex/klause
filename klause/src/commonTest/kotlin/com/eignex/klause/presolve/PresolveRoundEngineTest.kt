package com.eignex.klause.presolve

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals

class PresolveRoundEngineTest {

    /** A host whose passes change the scripted number of units on each successive run. */
    private class ScriptedHost(private val size: Long, private val changes: Map<PresolvePass, List<Long>>) :
        PresolveRoundEngine.RoundHost {
        val runs = ArrayList<PresolvePass>()
        private var units = 0L

        override fun runPass(pass: PresolvePass, slice: Cancellation?): PassOutcome {
            val change = changes[pass]?.getOrNull(runs.count { it == pass }) ?: 0L
            runs.add(pass)
            if (change == 0L) return PassOutcome.UNCHANGED
            units += change
            return PassOutcome.CHANGED
        }

        override fun complexity(): Long = size

        override fun modelSize(): Long = size

        override fun changedUnits(): Long = units
    }

    private fun runs(passes: List<PresolvePass>, host: ScriptedHost): List<PresolvePass> {
        PresolveRoundEngine.run(passes, MAX_PRESOLVE_ROUNDS, Cancellation.Never, null, host)
        return host.runs
    }

    @Test
    fun `a cheap pass reruns after its own change`() {
        val host = ScriptedHost(1_000, mapOf(PresolvePass.STRENGTHEN_COEFFICIENTS to listOf(2L)))

        val runs = runs(listOf(PresolvePass.STRENGTHEN_COEFFICIENTS), host)

        assertEquals(2, runs.size)
    }

    @Test
    fun `an expensive pass does not rerun after its own change`() {
        val host = ScriptedHost(1_000, mapOf(PresolvePass.AGGREGATE_SUB_SUMS to listOf(500L)))

        val runs = runs(listOf(PresolvePass.STRENGTHEN_COEFFICIENTS, PresolvePass.AGGREGATE_SUB_SUMS), host)

        assertEquals(1, runs.count { it == PresolvePass.AGGREGATE_SUB_SUMS })
    }

    @Test
    fun `an expensive pass skips a rerun after a small change elsewhere`() {
        val host = ScriptedHost(1_000, mapOf(PresolvePass.STRENGTHEN_COEFFICIENTS to listOf(3L)))

        val runs = runs(listOf(PresolvePass.AGGREGATE_SUB_SUMS, PresolvePass.STRENGTHEN_COEFFICIENTS), host)

        assertEquals(1, runs.count { it == PresolvePass.AGGREGATE_SUB_SUMS })
    }

    @Test
    fun `an expensive pass reruns once others change a large enough share of the model`() {
        val host = ScriptedHost(1_000, mapOf(PresolvePass.STRENGTHEN_COEFFICIENTS to listOf(10L)))

        val runs = runs(listOf(PresolvePass.AGGREGATE_SUB_SUMS, PresolvePass.STRENGTHEN_COEFFICIENTS), host)

        assertEquals(2, runs.count { it == PresolvePass.AGGREGATE_SUB_SUMS })
    }
}
