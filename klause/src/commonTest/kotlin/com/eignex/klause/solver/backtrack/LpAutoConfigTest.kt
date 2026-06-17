package com.eignex.klause.solver.backtrack

import com.eignex.klause.config.KlauseConfig
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
import com.eignex.klause.solver.factor.NValue
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
        assertTrue(r.lpProbe)
        assertFalse(r.lpCuts)
        assertFalse(r.lpCutPool)
        assertFalse(r.lagrangian)
        assertFalse(r.energeticReasoning)
    }

    @Test
    fun `the configurable relaxation-size ceiling gates auto bounding`() {
        // LP activation gates on the ceiling cap (#705): within it LP is on (even over the base cap,
        // where only the hull budget shrinks); past it LP is declined. Pure cost guard — sound either way.
        val p = problem(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2))
        val saved = KlauseConfig.current
        try {
            KlauseConfig.current = saved.copy(lpSparseMaxTableauCells = Long.MAX_VALUE)
            assertTrue(LpAutoConfig.recommend(p).lpBounding, "a large ceiling must enable auto LP")
            // Over the base cap but within the ceiling: LP still on.
            KlauseConfig.current = saved.copy(lpMaxTableauCells = 1L, lpSparseMaxTableauCells = Long.MAX_VALUE)
            assertTrue(LpAutoConfig.recommend(p).lpBounding, "over the base cap but within the ceiling, LP stays on")
            KlauseConfig.current = saved.copy(lpSparseMaxTableauCells = 1L)
            assertFalse(LpAutoConfig.recommend(p).lpBounding, "a 1-cell ceiling must disable auto LP")
        } finally {
            KlauseConfig.current = saved
        }
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
    fun `size guard sheds an over-budget hull but keeps the base LP`() {
        // NValue over 32 vars × domain 32 = 1024 cells: under its own MAX_NVALUE_CELLS cap (so the
        // builder would build it), but its ~2048 columns + ~1089 rows blow a 2^20 relaxation budget.
        // The size guard (#484) must shed the hull (lpNValue off) while the base LP still runs.
        val saved = KlauseConfig.current
        try {
            KlauseConfig.current = saved.copy(lpMaxTableauCells = 1L shl 20)
            val n = 32
            val domains = Array(n + 1) { if (it < n) IntDomain(0, 31) else IntDomain(0, n) }
            val big = Problem(0, n + 1, domains, arrayOf<Factor>(NValue(n, IntArray(n) { it })))
            val rBig = LpAutoConfig.recommend(big)
            assertFalse(rBig.lpNValue, "the over-budget NValue hull must be shed")
            assertTrue(rBig.lpBounding, "the base LP still runs; only the hull is shed")

            // A small NValue (3×3 = 9 cells) fits comfortably and is enabled.
            val small = Problem(
                0,
                4,
                Array(4) { if (it < 3) IntDomain(0, 2) else IntDomain(0, 3) },
                arrayOf<Factor>(NValue(3, intArrayOf(0, 1, 2))),
            )
            assertTrue(LpAutoConfig.recommend(small).lpNValue, "a small NValue hull fits and is enabled")
        } finally {
            KlauseConfig.current = saved
        }
    }

    @Test
    fun `size guard sheds the larger of two stacked hulls`() {
        // A Table (1024 tuples) and an NValue (1024 cells) both fit on their own, but together they
        // exceed a 2^20 budget — the size guard keeps the cheaper one (smallest-first) and sheds the other.
        val saved = KlauseConfig.current
        try {
            KlauseConfig.current = saved.copy(lpMaxTableauCells = 1L shl 20)
            val nVars = 32
            val tableXs = intArrayOf(0, 1)
            val tuples = IntArray(1024 * 2) { it % 2 } // 1024 two-column tuples
            val xs = IntArray(nVars) { it + 2 } // NValue over fresh vars 2..33
            val total = 2 + nVars + 1 // table xs + nvalue xs + nvalue count var
            val domains = Array(total) { IntDomain(0, 31) }
            val p = Problem(
                0,
                total,
                domains,
                arrayOf<Factor>(Table(tableXs, tuples), NValue(total - 1, xs)),
            )
            val r = LpAutoConfig.recommend(p)
            assertFalse(r.lpTable && r.lpNValue, "two stacked hulls cannot both be enabled past the budget")
            assertTrue(r.lpTable || r.lpNValue, "the cheaper hull is still kept")
        } finally {
            KlauseConfig.current = saved
        }
    }

    @Test
    fun `resolve gates techniques by the emphasis cost tier`() {
        // Linear (bounding-MEDIUM), AllDifferent (lagrangian-FAST + cuts-EXHAUSTIVE), Cumulative
        // (energetic / flow-FAST). Each tier should switch on exactly its cost class and below.
        val p = problem(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2),
            AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 6),
            Cumulative(intArrayOf(0, 1, 2), intArrayOf(1, 1, 1), intArrayOf(1, 1, 1), capacity = 1),
        )
        val off = LpAutoConfig.resolve(p, LpConfig(LpEmphasis.OFF))
        assertFalse(off.lpBounding || off.lpCuts || off.lagrangian || off.energeticReasoning || off.lpCumulativeFlow)

        val conservative = LpAutoConfig.resolve(p, LpConfig(LpEmphasis.CONSERVATIVE))
        assertTrue(conservative.lagrangian && conservative.energeticReasoning && conservative.lpCumulativeFlow)
        assertFalse(conservative.lpBounding, "MEDIUM simplex stays off at CONSERVATIVE")
        assertFalse(conservative.lpCuts, "EXHAUSTIVE cuts stay off at CONSERVATIVE")

        val default = LpAutoConfig.resolve(p, LpConfig(LpEmphasis.DEFAULT))
        assertTrue(default.lpBounding && default.lagrangian)
        assertFalse(default.lpCuts, "EXHAUSTIVE cuts stay off at DEFAULT")

        val aggressive = LpAutoConfig.resolve(p, LpConfig(LpEmphasis.AGGRESSIVE))
        assertTrue(aggressive.lpBounding && aggressive.lpCuts)
        // AGGRESSIVE reproduces the historical structural recommend (every applicable technique on).
        val rec = LpAutoConfig.recommend(p)
        assertEquals(rec.lpBounding, aggressive.lpBounding)
        assertEquals(rec.lpCuts, aggressive.lpCuts)
        assertEquals(rec.lagrangian, aggressive.lagrangian)
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
    fun `large model keeps the full bounding stack within the ceiling`() {
        // 2000 unit rows over 2000 vars estimate ~8M cells — over the 2^20 base cap but under the 2^26
        // ceiling (#705) — so LP bounding is on (hulls budget against the ceiling). The whole bounding
        // stack rides along over the sparse revised simplex: the probe, LP learning, and the structural
        // cut separators all fire. The Lagrangian/energetic bounds (own internal caps) are not size-gated.
        val n = 2000
        val factors = ArrayList<Factor>(n + 1)
        repeat(n) { i -> factors.add(Linear(intArrayOf(1), intArrayOf(i), LinearOp.GE, 0)) }
        factors.add(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 6))
        val p = Problem(0, n, Array(n) { IntDomain(0, 5) }, factors.toTypedArray())
        val r = LpAutoConfig.recommend(p)
        assertTrue(r.lpBounding)
        // The AllDifferent makes the instance cut-eligible; cuts gate on lpActive (#705).
        assertTrue(r.lpCuts)
        assertTrue(r.lpProbe)
        assertTrue(r.lpLearn)
        assertTrue(r.lagrangian)

        // Past the ceiling ⇒ the LP family fully declines; the Lagrangian still runs.
        val saved = KlauseConfig.current
        try {
            KlauseConfig.current = saved.copy(lpSparseMaxTableauCells = 1L)
            val off = LpAutoConfig.recommend(p)
            assertFalse(off.lpBounding)
            assertTrue(off.lagrangian)
        } finally {
            KlauseConfig.current = saved
        }
    }

    @Test
    fun `lpConfig resolves at minimize and engages the lp machinery`() {
        // Triangle covering: optimum 3. With an LP emphasis the node LPs must actually run (sparse
        // solves observed); a null lpConfig leaves the LP family off.
        val p = problem(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2),
            Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 2),
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 2),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val auto = BacktrackSolver(p).minimize(obj, BacktrackParams(randomSeed = 1L, lpConfig = LpConfig.AGGRESSIVE))
        assertTrue(auto is MinimizeResult.Optimal)
        assertEquals(3.0, auto.objectiveValue)
        assertTrue(auto.stats.lpSolves.sum > 0.0, "an LP emphasis must engage the node LP")

        val plain = BacktrackSolver(p).minimize(obj, BacktrackParams(randomSeed = 1L))
        assertTrue(plain is MinimizeResult.Optimal)
        assertEquals(3.0, plain.objectiveValue)
        assertEquals(0.0, plain.stats.lpSolves.sum, "a null lpConfig leaves the LP family off")

        // The CONSERVATIVE emphasis (FAST tier only) keeps the per-node simplex off — no solves.
        val conservative = BacktrackSolver(p).minimize(
            obj,
            BacktrackParams(randomSeed = 1L, lpConfig = LpConfig(LpEmphasis.CONSERVATIVE)),
        )
        assertTrue(conservative is MinimizeResult.Optimal && conservative.objectiveValue == 3.0)
        assertEquals(0.0, conservative.stats.lpSolves.sum, "CONSERVATIVE runs no per-node simplex")
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
