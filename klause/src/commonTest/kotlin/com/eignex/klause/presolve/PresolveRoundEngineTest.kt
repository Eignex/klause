package com.eignex.klause.presolve

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.result.PresolveLane
import com.eignex.klause.solver.result.PresolveSizeStats
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals

class PresolveRoundEngineTest {
    @Test
    fun `a skipped clause pass should become eligible when live dimensions cross its cap`() {
        val problem = Problem(3, 0, emptyArray(), listOf(Clause(intArrayOf(0, 2)), Clause(intArrayOf(0, 2, 4))))
        val outcomes = ArrayList<PassOutcome>()
        val host = object : PresolveRoundEngine.RoundHost {
            var liveFactors = 200_001
            var changed = 0L

            override fun runPass(pass: PresolvePass, slice: Cancellation?): PassOutcome {
                if (pass == PresolvePass.REMOVE_REDUNDANT) {
                    if (liveFactors <= 2) return PassOutcome.UNCHANGED
                    changed += liveFactors - 2
                    liveFactors = 2
                    return PassOutcome.CHANGED
                }
                val outcome = if (pass.namespaceEligible(problem, liveFactors)) {
                    val delta = pass.applySource(problem, PresolveContext.EMPTY)
                    changed += delta.droppedIndices.size
                    liveFactors -= delta.droppedIndices.size
                    PassOutcome.CHANGED
                } else PassOutcome.SKIPPED
                outcomes.add(outcome)
                return outcome
            }

            override fun complexity(): Long = liveFactors.toLong()
            override fun modelSize(): Long = liveFactors.toLong() + 3
            override fun changedUnits(): Long = changed
        }

        PresolveRoundEngine.run(listOf(PresolvePass.SUBSUME_CLAUSES, PresolvePass.REMOVE_REDUNDANT),
            2, Cancellation.Never, null, host)

        assertEquals(listOf(PassOutcome.SKIPPED, PassOutcome.CHANGED), outcomes)
        assertEquals(1, host.liveFactors)
    }

    private class ScriptedHost(
        private val size: Long,
        private val changes: Map<PresolvePass, List<Long>>,
        private val decreasingComplexity: Boolean = false,
        val budget: PresolveBudget? = null,
        private val work: Map<PresolvePass, Long> = emptyMap(),
        private val interrupted: Set<PresolvePass> = emptySet(),
        private val narrowed: Boolean = false,
        private val fixed: Boolean = false,
    ) : PresolveRoundEngine.RoundHost {
        val runs = ArrayList<PresolvePass>()
        private var units = 0L

        override fun runPass(pass: PresolvePass, slice: Cancellation?): PassOutcome = budget.observe(
            pass, PresolveLane.SOURCE, { PresolveSizeStats(size.toInt(), 0, 0, size) },
        ) { invocation ->
            val change = changes[pass]?.getOrNull(runs.count { it == pass }) ?: 0L
            runs.add(pass)
            budget?.charge(work[pass] ?: 0L)
            invocation?.let {
                when {
                    fixed -> {
                        it.fixedColumns = change.toInt()
                        it.outputOccurrences = size + change
                    }
                    narrowed -> it.narrowedColumns = change.toInt()
                    else -> it.outputOccurrences = size - change
                }
                it.interrupted = pass in interrupted
            }
            units += change
            val outcome = when {
                change > 0L -> PassOutcome.CHANGED
                pass in interrupted -> PassOutcome.INTERRUPTED
                else -> PassOutcome.UNCHANGED
            }
            invocation.record(outcome)
            outcome
        }

        override fun complexity(): Long = if (decreasingComplexity) size - units else size

        override fun modelSize(): Long = size

        override fun changedUnits(): Long = units
    }

    private fun runs(passes: List<PresolvePass>, host: ScriptedHost): List<PresolvePass> {
        PresolveRoundEngine.run(passes, MAX_PRESOLVE_ROUNDS, Cancellation.Never, host.budget, host)
        return host.runs
    }

    @Test
    fun `a lower abort fraction should retain productive rounds`() {
        val pass = PresolvePass.STRENGTHEN_COEFFICIENTS
        val stopped = ScriptedHost(10_000, mapOf(pass to listOf(5L, 5L)), decreasingComplexity = true)
        val continued = ScriptedHost(10_000, mapOf(pass to listOf(5L, 5L)), decreasingComplexity = true)

        PresolveRoundEngine.run(listOf(pass), 16, Cancellation.Never, null, stopped, 0.001)
        PresolveRoundEngine.run(listOf(pass), 16, Cancellation.Never, null, continued, 0.0001)

        assertEquals(1, stopped.runs.size)
        assertEquals(3, continued.runs.size)
    }

    @Test
    fun `a round cap should stop a productive schedule`() {
        val pass = PresolvePass.STRENGTHEN_COEFFICIENTS
        val host = ScriptedHost(10_000, mapOf(pass to listOf(100L, 100L, 100L)), decreasingComplexity = true)

        PresolveRoundEngine.run(listOf(pass), 2, Cancellation.Never, null, host, 0.0)

        assertEquals(2, host.runs.size)
    }

    @Test
    fun `a cheap pass should rerun after its own change`() {
        val host = ScriptedHost(1_000, mapOf(PresolvePass.STRENGTHEN_COEFFICIENTS to listOf(2L)))

        val runs = runs(listOf(PresolvePass.STRENGTHEN_COEFFICIENTS), host)

        assertEquals(2, runs.size)
    }

    @Test
    fun `an expensive pass should rerun once others change a large enough share of the model`() {
        val host = ScriptedHost(1_000, mapOf(PresolvePass.STRENGTHEN_COEFFICIENTS to listOf(10L)))

        val runs = runs(listOf(PresolvePass.AGGREGATE_SUB_SUMS, PresolvePass.STRENGTHEN_COEFFICIENTS), host)

        assertEquals(2, runs.count { it == PresolvePass.AGGREGATE_SUB_SUMS })
    }

    @Test
    fun `an expensive empty analysis should require more change as its measured work rises`() {
        val expensive = PresolvePass.AGGREGATE_SUB_SUMS
        val cheap = PresolvePass.STRENGTHEN_COEFFICIENTS
        for ((work, expected) in listOf(11L to 2, 1_100L to 1)) {
            val host = ScriptedHost(
                1_000, mapOf(cheap to listOf(10L)), budget = PresolveBudget(Long.MAX_VALUE),
                work = mapOf(expensive to work),
            )

            val runs = runs(listOf(expensive, cheap), host)

            assertEquals(expected, runs.count { it == expensive })
        }
    }

    @Test
    fun `retained row reductions and narrowed columns should both credit an expensive rerun`() {
        val expensive = PresolvePass.AGGREGATE_SUB_SUMS
        val cheap = PresolvePass.STRENGTHEN_COEFFICIENTS
        for (narrowed in listOf(false, true)) {
            for ((yield, expected) in listOf(1L to 1, 50L to 2)) {
                val host = ScriptedHost(
                    1_000, mapOf(expensive to listOf(yield), cheap to listOf(10L)),
                    budget = PresolveBudget(Long.MAX_VALUE), work = mapOf(expensive to 1_100L), narrowed = narrowed,
                )

                val runs = runs(listOf(expensive, cheap), host)

                assertEquals(expected, runs.count { it == expensive })
            }
        }
    }

    @Test
    fun `Boolean pin credit should distinguish five and 289 change rerun thresholds`() {
        val expensive = PresolvePass.DUAL_FIX
        val cheap = PresolvePass.STRENGTHEN_COEFFICIENTS
        for ((pins, changes, expected) in listOf(
            Triple(0L, 288L, 1), Triple(0L, 289L, 2), Triple(64L, 4L, 1), Triple(64L, 5L, 2),
        )) {
            val host = ScriptedHost(
                100, mapOf(expensive to listOf(pins), cheap to listOf(changes)),
                budget = PresolveBudget(Long.MAX_VALUE), work = mapOf(expensive to 2_881L), fixed = true,
            )

            val runs = runs(listOf(expensive, cheap), host)

            assertEquals(expected, runs.count { it == expensive })
        }
    }

    @Test
    fun `an interrupted parity analysis should retry rather than park after another pass changes the model`() {
        val parity = PresolvePass.DERIVE_XOR_UNITS
        val cheap = PresolvePass.STRENGTHEN_COEFFICIENTS
        val host = ScriptedHost(
            1_000, mapOf(cheap to listOf(10L)), budget = PresolveBudget(Long.MAX_VALUE),
            interrupted = setOf(parity),
        )

        val runs = runs(listOf(parity, cheap), host)

        assertEquals(2, runs.count { it == parity })
    }

    @Test
    fun `an expensive interrupted analysis should retry after sufficient accumulated change`() {
        val expensive = PresolvePass.BREAK_SYMMETRIES
        val cheap = PresolvePass.STRENGTHEN_COEFFICIENTS
        val host = ScriptedHost(
            1_000, mapOf(cheap to listOf(50L, 50L)), budget = PresolveBudget(Long.MAX_VALUE),
            work = mapOf(expensive to 1_600L), interrupted = setOf(expensive),
        )

        val runs = runs(listOf(expensive, cheap), host)

        assertEquals(listOf(expensive, cheap, cheap, expensive, cheap), runs)
    }
}
