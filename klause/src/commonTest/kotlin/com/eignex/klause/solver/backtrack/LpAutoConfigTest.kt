package com.eignex.klause.solver.backtrack

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Circuit
import com.eignex.klause.solver.factor.Cumulative
import com.eignex.klause.solver.factor.Element
import com.eignex.klause.solver.factor.GlobalCardinality
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.Table
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #245: structural auto-configuration of the LP-relaxation family. */
class LpAutoConfigTest {

    private fun problem(vararg factors: Factor, intVars: Int = 3): Problem =
        Problem(0, intVars, Array(intVars) { IntDomain(0, 5) }, arrayOf(*factors))

    @Test
    fun `linear structure enables the bounding stack but no cuts`() {
        val p = problem(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2))
        val r = LpAutoConfig.recommend(p)
        assertTrue(r.lpBounding)
        // The bounding-stack techniques that need no extra structure ride along.
        assertTrue(r.lpLearn)
        assertTrue(r.lpObjectiveBound)
        assertTrue(r.lpFixpoint)
        assertTrue(r.lpProbe)
        assertFalse(r.lpCuts)
        assertFalse(r.lpCutPool)
        assertFalse(r.lagrangian)
        assertFalse(r.energeticReasoning)
    }

    @Test
    fun `all-different enables bounding and cuts and pool and lagrangian`() {
        val p = problem(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 6))
        val r = LpAutoConfig.recommend(p)
        assertTrue(r.lpBounding)
        assertTrue(r.lpCuts)
        assertTrue(r.lpCutPool)
        assertTrue(r.lagrangian)
        assertFalse(r.energeticReasoning)
    }

    @Test
    fun `global cardinality enables cuts but not lagrangian`() {
        val gcc = GlobalCardinality(
            xs = intArrayOf(0, 1, 2),
            cover = intArrayOf(0, 1, 2),
            countLow = intArrayOf(0, 0, 0),
            countHigh = intArrayOf(1, 1, 1),
            closed = true,
        )
        val r = LpAutoConfig.recommend(problem(gcc))
        assertTrue(r.lpBounding)
        assertTrue(r.lpCuts)
        assertFalse(r.lagrangian)
    }

    @Test
    fun `cumulative enables energetic reasoning only`() {
        val p = problem(Cumulative(intArrayOf(0, 1, 2), intArrayOf(3, 3, 3), intArrayOf(1, 1, 1), capacity = 1))
        val r = LpAutoConfig.recommend(p)
        assertTrue(r.energeticReasoning)
        assertFalse(r.lpBounding)
        assertFalse(r.lpCuts)
        assertFalse(r.lpLearn)
        assertFalse(r.lpProbe)
    }

    @Test
    fun `cumulative with a verifiable makespan enables the makespan row and bounding`() {
        // ints 0,1,2 starts; 3 makespan, with M >= startᵢ + durᵢ — a verifiable scheduling makespan.
        val factors = arrayOf<Factor>(
            Linear(intArrayOf(1, -1), intArrayOf(3, 0), LinearOp.GE, 3),
            Linear(intArrayOf(1, -1), intArrayOf(3, 1), LinearOp.GE, 3),
            Linear(intArrayOf(1, -1), intArrayOf(3, 2), LinearOp.GE, 3),
            Cumulative(intArrayOf(0, 1, 2), intArrayOf(3, 3, 3), intArrayOf(1, 1, 1), capacity = 1),
        )
        val p = Problem(0, 4, Array(4) { IntDomain(0, 20) }, factors)
        val r = LpAutoConfig.recommend(p)
        assertTrue(r.lpCumulative)
        assertTrue(r.lpBounding) // the scheduling makespan turns the bounding stack on
        assertTrue(r.lpLearn)
        assertTrue(r.energeticReasoning) // the feasibility check rides along on the Cumulative
    }

    /** Makespan COP: ints 0..n-1 starts, n is the makespan with `M ≥ startᵢ + durᵢ`, plus a cumulative. */
    private fun makespanScheduling(startHi: Int): Problem {
        val n = 3
        val factors = ArrayList<Factor>()
        for (i in 0 until n) factors.add(Linear(intArrayOf(1, -1), intArrayOf(n, i), LinearOp.GE, 3))
        factors.add(Cumulative(intArrayOf(0, 1, 2), intArrayOf(3, 3, 3), intArrayOf(1, 1, 1), capacity = 1))
        val domains = Array(n + 1) { if (it < n) IntDomain(0, startHi) else IntDomain(0, startHi + 3) }
        return Problem(0, n + 1, domains, factors.toTypedArray())
    }

    @Test
    fun `bounded-horizon scheduling enables the time-indexed and flow relaxations`() {
        // Small declared start horizon + a verified makespan: both the time-indexed LP (#453) and the
        // preemptive flow prune (#454) auto-enable.
        val r = LpAutoConfig.recommend(makespanScheduling(startHi = 10))
        assertTrue(r.lpCumulativeFlow, "the flow prune rides along on any scheduling global")
        assertTrue(r.lpCumulativeTimeIndexed, "a small bounded horizon fits the time-indexed tableau")
        assertTrue(r.lpCumulative, "the energetic makespan row is on too")
    }

    @Test
    fun `huge-horizon scheduling keeps the time-indexed model off but the flow prune on`() {
        // A 200000-wide start horizon blows the O(n·H) time-indexed column budget, so it stays off;
        // the horizon-independent flow prune still rides along.
        val r = LpAutoConfig.recommend(makespanScheduling(startHi = 200_000))
        assertTrue(r.lpCumulativeFlow)
        assertFalse(r.lpCumulativeTimeIndexed, "the huge horizon must keep the time-indexed model off")
    }

    @Test
    fun `auto-enabled energetic reasoning derives a size-aware cadence`() {
        fun cumulative(tasks: Int) = Problem(
            0,
            tasks,
            Array(tasks) { IntDomain(0, 5) },
            arrayOf<Factor>(
                Cumulative(IntArray(tasks) { it }, IntArray(tasks) { 3 }, IntArray(tasks) { 1 }, capacity = 1),
            ),
        )
        // 27 tasks: ~20k scan ops per check, under the per-check budget — full cadence.
        assertEquals(1, LpAutoConfig.recommend(cumulative(27)).energeticEvery)
        // 256 tasks: ~16.7M ops — the cadence normalises it back to the budget.
        val big = LpAutoConfig.recommend(cumulative(256))
        assertTrue(big.energeticReasoning)
        assertEquals(128, big.energeticEvery)
        // A caller who enabled the check explicitly keeps their cadence untouched.
        val explicit = LpAutoConfig.recommend(cumulative(256), BacktrackParams(energeticReasoning = true))
        assertEquals(1, explicit.energeticEvery)
    }

    @Test
    fun `oversized model declines the lp family but keeps the structure-capped bounds`() {
        // 2000 unit rows over 2000 vars estimate a dense tableau of ~8M cells — past the auto
        // guard — so the LP-relaxation flags stay off; the Lagrangian/energetic bounds (own
        // internal caps) are not size-gated.
        val n = 2000
        val factors = ArrayList<Factor>(n + 1)
        repeat(n) { i -> factors.add(Linear(intArrayOf(1), intArrayOf(i), LinearOp.GE, 0)) }
        factors.add(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 6))
        val p = Problem(0, n, Array(n) { IntDomain(0, 5) }, factors.toTypedArray())
        val r = LpAutoConfig.recommend(p)
        assertFalse(r.lpBounding)
        assertFalse(r.lpCuts)
        assertFalse(r.lpProbe)
        assertTrue(r.lagrangian)
    }

    @Test
    fun `lpAuto resolves at minimize and engages the lp machinery`() {
        // Triangle covering: optimum 3. With lpAuto the node LPs must actually run (pivots
        // observed); without it the default params leave the LP family off.
        val p = problem(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2),
            Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 2),
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 2),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val auto = BacktrackSolver(p).minimize(obj, BacktrackParams(randomSeed = 1L, lpAuto = true))
        assertTrue(auto is MinimizeResult.Optimal)
        assertEquals(3.0, auto.objectiveValue)
        assertTrue(auto.stats.lpPivots.sum > 0.0, "lpAuto must engage the node LP")
        assertTrue(auto.stats.lpSeeded.sum > 0.0, "descendant node LPs must reuse the hot tableau")

        val plain = BacktrackSolver(p).minimize(obj, BacktrackParams(randomSeed = 1L))
        assertTrue(plain is MinimizeResult.Optimal)
        assertEquals(3.0, plain.objectiveValue)
        assertEquals(0.0, plain.stats.lpPivots.sum, "without lpAuto the LP family stays off")
    }

    @Test
    fun `circuit enables circuit cuts and bounding`() {
        val r = LpAutoConfig.recommend(problem(Circuit(intArrayOf(0, 1, 2))))
        assertTrue(r.lpBounding)
        assertTrue(r.lpCircuit)
    }

    @Test
    fun `constant-array element enables element hull but a variable array does not`() {
        val constArr = LpAutoConfig.recommend(
            problem(Element(idx = 0, result = 1, arr = intArrayOf(5, 7, 9), arrIsVars = false, indexOffset = 0)),
        )
        assertTrue(constArr.lpBounding)
        assertTrue(constArr.lpElement)

        val varArr = LpAutoConfig.recommend(
            problem(Element(idx = 0, result = 1, arr = intArrayOf(2), arrIsVars = true, indexOffset = 0)),
        )
        assertFalse(varArr.lpElement)
    }

    @Test
    fun `table enables table hull and bounding`() {
        val r = LpAutoConfig.recommend(problem(Table(xs = intArrayOf(0, 1), tuples = intArrayOf(0, 0, 1, 1))))
        assertTrue(r.lpBounding)
        assertTrue(r.lpTable)
    }

    @Test
    fun `pseudo-boolean enables cover cuts`() {
        val pb = PseudoBoolean(intArrayOf(2, 3), intArrayOf(Lit.make(0, true), Lit.make(1, true)), PbOp.LE, 4)
        val r = LpAutoConfig.recommend(Problem(2, 0, emptyArray(), arrayOf<Factor>(pb)))
        assertTrue(r.lpCuts)
        assertTrue(r.lpBounding)
    }

    @Test
    fun `pure boolean problem enables nothing`() {
        val r = LpAutoConfig.recommend(Problem(2, 0, emptyArray(), arrayOf<Factor>()))
        assertFalse(r.lpBounding)
        assertFalse(r.lpCuts)
        assertFalse(r.lagrangian)
        assertFalse(r.energeticReasoning)
    }

    @Test
    fun `caller-set flags are never turned off`() {
        // A Cumulative-only problem would not enable lpBounding, but an explicit base setting stays.
        val p = problem(Cumulative(intArrayOf(0, 1, 2), intArrayOf(3, 3, 3), intArrayOf(1, 1, 1), capacity = 1))
        val r = LpAutoConfig.recommend(p, BacktrackParams(lpBounding = true, lpGomory = false))
        assertTrue(r.lpBounding)
        assertFalse(r.lpGomory)
        assertTrue(r.energeticReasoning)
    }

    @Test
    fun `recommended config preserves the optimum`() {
        // Triangle covering (see LpBoundingTest): optimum 3; auto-config must not change it.
        val p = problem(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2),
            Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 2),
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 2),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val auto = LpAutoConfig.recommend(p, BacktrackParams(randomSeed = 1L))
        assertTrue(auto.lpBounding)
        val result = BacktrackSolver(p).minimize(obj, auto)
        assertTrue(result is MinimizeResult.Optimal)
        assertEquals(3.0, result.objectiveValue)
    }
}
