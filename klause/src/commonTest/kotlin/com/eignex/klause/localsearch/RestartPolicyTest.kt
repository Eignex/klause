package com.eignex.klause.localsearch

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.localsearch.movesource.ConfiguredSource
import com.eignex.klause.localsearch.movesource.ViolatedRepairs
import com.eignex.klause.localsearch.schedule.AdaptivePolicy
import com.eignex.klause.localsearch.schedule.RoundLog
import com.eignex.klause.localsearch.schedule.ScheduleBundle
import com.eignex.klause.localsearch.strategy.FeasibleDescent
import com.eignex.klause.localsearch.strategy.SourceDrivenStrategy
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RestartPolicyTest {

    @Test
    fun `fixed cadence triggers at boundary`() {
        val p = FixedCadenceRestart(maxFlipsBeforeRestart = 100)
        assertEquals(false, p.shouldRestart(0))
        assertEquals(false, p.shouldRestart(99))
        assertEquals(true, p.shouldRestart(100))
        assertEquals(true, p.shouldRestart(1_000_000))
    }

    @Test
    fun `adaptive perturbation falls back when no best`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
            ),
        )
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        state.restart()

        state.assignment.setBool(0, false)
        state.assignment.setBool(1, false)
        state.assignment.setBool(2, false)

        AdaptivePerturbationRestart().restart(state, bestSoFar = null)

        val countTrue = (0..2).count { state.assignment.boolValue(it) }
        val expectedHard = if (countTrue == 1) 0L else 1L
        assertEquals(expectedHard, state.cost)
    }

    @Test
    fun `adaptive perturbation anchors to best then perturbs`() {
        val problem = Problem(6, 0, emptyArray(), emptyList())
        val state = LocalSearchState(problem, Random(0))
        state.restart()
        for (b in 0..5) state.assignment.setBool(b, false)

        val best = Sample(bools = booleanArrayOf(true, true, true, true, true, true), ints = longArrayOf())
        val policy = AdaptivePerturbationRestart(perturbationStrength = 2)
        policy.restart(state, bestSoFar = best)

        val differences = (0..5).count { state.assignment.boolValue(it) != best.bools[it] }
        assertTrue(
            differences in 0..2,
            "perturbed assignment differs from bestSoFar in $differences positions, expected 0..2",
        )
    }

    @Test
    fun `adaptive perturbation restart integrates with local search optimizer`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(AllDifferent(vars = intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 4)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1L, 2L, 3L, 4L))

        val fixed = LocalSearchSolver(problem.bake(), restartPolicy = FixedCadenceRestart())
        val adaptive = LocalSearchSolver(problem.bake(), restartPolicy = AdaptivePerturbationRestart())

        val a = fixed.minimize(objective, LocalSearchParams(maxFlips = 8_000L, randomSeed = 1L)).assignment
        val b = adaptive.minimize(objective, LocalSearchParams(maxFlips = 8_000L, randomSeed = 1L)).assignment
        assertNotNull(a)
        assertNotNull(b)
        assertEquals(objective.evaluate(a), objective.evaluate(b))
    }

    @Test
    fun `luby triggers at cadence boundary`() {
        val p = LubyRestart(unit = 100)
        assertEquals(false, p.shouldRestart(0))
        assertEquals(false, p.shouldRestart(99))
        assertEquals(true, p.shouldRestart(100))
        assertEquals(true, p.shouldRestart(1_000_000))
    }

    @Test
    fun `luby sequence matches knuth`() {
        val p = LubyRestart(unit = 1)
        val problem = Problem(1, 0, emptyArray(), emptyList())
        val state = LocalSearchState(problem, Random(0))
        state.restart()

        val emitted = mutableListOf<Int>()
        repeat(15) {
            var n = 1
            while (!p.shouldRestart(n)) n++
            emitted += n
            p.restart(state, bestSoFar = null)
        }

        assertEquals(listOf(1, 1, 2, 1, 1, 2, 4, 1, 1, 2, 1, 1, 2, 4, 8), emitted)
    }

    @Test
    fun `luby integrates with local search solver`() {
        val clauses = listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
        )
        val problem = Problem(3, 0, emptyArray(), clauses)
        val solver = LocalSearchSolver(problem.bake(), restartPolicy = LubyRestart(unit = 50))
        val sample = solver.sample(LocalSearchParams(maxFlips = 20_000L, randomSeed = 9L)).assignment
        assertNotNull(sample)
    }

    private fun round(bestCost: Double): RoundLog =
        RoundLog(proposed = 1, accepted = 1, costMean = 0.0, costVariance = 0.0, bestCost = bestCost, temperature = 1.0)

    @Test
    fun `stagnation restart fires after patience rounds without improvement`() {
        val p = StagnationRestart(patience = 3, maxFlipsBeforeRestart = 1_000_000)
        p.observe(round(5.0)) // first round establishes the watermark
        p.observe(round(5.0)) // no improvement: 1
        p.observe(round(5.0)) // no improvement: 2
        assertFalse(p.shouldRestart(0), "must not restart before patience elapses")
        p.observe(round(5.0)) // no improvement: 3 → trigger
        assertTrue(p.shouldRestart(0), "patience consecutive flat rounds must trigger a restart")
    }

    @Test
    fun `stagnation restart resets its counter on a strict improvement`() {
        val p = StagnationRestart(patience = 3, maxFlipsBeforeRestart = 1_000_000)
        p.observe(round(5.0))
        p.observe(round(5.0)) // flat: 1
        p.observe(round(4.0)) // improvement resets the counter
        p.observe(round(4.0)) // flat: 1
        p.observe(round(4.0)) // flat: 2
        assertFalse(p.shouldRestart(0), "an improvement must reset the no-progress counter")
    }

    @Test
    fun `stagnation restart honours the hard flip ceiling and clears on restart`() {
        val p = StagnationRestart(patience = 100, maxFlipsBeforeRestart = 500)
        assertFalse(p.shouldRestart(499))
        assertTrue(p.shouldRestart(500), "the ceiling must force a restart even without stagnation")
        repeat(101) { p.observe(round(5.0)) } // 1 watermark round + 100 flat rounds (patience)
        assertTrue(p.shouldRestart(0), "stagnation must trigger after patience flat rounds")
        val state = LocalSearchState(Problem(1, 0, emptyArray(), emptyList()), Random(0))
        p.restart(state, bestSoFar = null)
        assertFalse(p.shouldRestart(0), "restart must clear the pending trigger")
    }

    @Test
    fun `engine uses the restart policy from the strategy schedule bundle`() {
        // When the strategy's ScheduleBundle declares a restart policy, the engine must use it over the
        // solver-level param. The spy never restarts but counts queries; if the solver param were used
        // the spy would never be touched.
        val spy = object : RestartPolicy {
            var queried = 0
            override fun shouldRestart(stepsSinceLastRestart: Int): Boolean {
                queried++
                return false
            }
            override fun restart(state: LocalSearchState, bestSoFar: Sample?) = state.restart()
        }
        val problem = Problem(
            numBoolVars = 6,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Cardinality.exactlyOne(intArrayOf(Lit.make(1, true), Lit.make(2, true))),
                Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
            ),
        )
        val strategy = SourceDrivenStrategy(
            sources = listOf(ConfiguredSource(ViolatedRepairs(sampleCount = 1))),
            schedule = ScheduleBundle(restart = spy),
            feasibleDescent = FeasibleDescent.RatchetAsConstraint,
        )
        LocalSearchSolver(problem.bake(), strategy = strategy, restartPolicy = FixedCadenceRestart(1_000_000))
            .solve(LocalSearchParams(maxFlips = 2_000L, randomSeed = 4L))
        assertTrue(spy.queried > 0, "the engine must use the restart policy from the strategy's schedule bundle")
    }

    @Test
    fun `engine drives per-round feedback to an adaptive restart policy`() {
        // A restart policy that is also an AdaptivePolicy must be fed RoundLogs by the engine,
        // exactly like an adaptive schedule — proven by a spy whose observe count is non-zero after a
        // run that spans several rounds on the UNSAT helper.
        val spy = object : RestartPolicy, AdaptivePolicy {
            var observed = 0
            override fun shouldRestart(stepsSinceLastRestart: Int) = stepsSinceLastRestart >= 1_000_000
            override fun restart(state: LocalSearchState, bestSoFar: Sample?) = state.restart()
            override fun observe(round: RoundLog) {
                observed++
            }
            override fun reset() = Unit
        }
        val problem = Problem(
            numBoolVars = 6,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Cardinality.exactlyOne(intArrayOf(Lit.make(1, true), Lit.make(2, true))),
                Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
            ),
        )
        LocalSearchSolver(problem.bake(), restartPolicy = spy)
            .solve(LocalSearchParams(maxFlips = 6_000L, randomSeed = 4L))
        assertTrue(spy.observed > 0, "the engine must feed RoundLogs to an adaptive restart policy")
    }
}
