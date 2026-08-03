package com.eignex.klause.backtrack.lp

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.factor.global.NValue
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.Table
import com.eignex.klause.lp.bounding.LpAutoConfig
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.bounding.LpTechnique
import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
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
        assertTrue(r.bounding)
        // The bounding-stack techniques that need no extra structure ride along.
        assertTrue(r.learn)
        assertTrue(r.probe)
        assertFalse(r.cuts)
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
            KlauseConfig.current = saved.copy(lpCeilingTableauCells = Long.MAX_VALUE)
            assertTrue(LpAutoConfig.recommend(p).bounding, "a large ceiling must enable auto LP")
            // Over the base cap but within the ceiling: LP still on.
            KlauseConfig.current = saved.copy(lpMaxTableauCells = 1L, lpCeilingTableauCells = Long.MAX_VALUE)
            assertTrue(
                LpAutoConfig.recommend(p).bounding,
                "over the base cap but within the ceiling, LP stays on",
            )
            KlauseConfig.current = saved.copy(lpCeilingTableauCells = 1L)
            assertFalse(LpAutoConfig.recommend(p).bounding, "a 1-cell ceiling must disable auto LP")
        } finally {
            KlauseConfig.current = saved
        }
    }

    @Test
    fun `all-different enables bounding and cuts and lagrangian`() {
        val p = problem(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 6))
        val r = LpAutoConfig.recommend(p)
        assertTrue(r.bounding)
        assertTrue(r.cuts)
        assertTrue(r.lagrangian)
        assertFalse(r.energeticReasoning)
    }

    @Test
    fun `global cardinality enables cuts but not lagrangian`() {
        val gcc = GlobalCardinality(
            xs = intArrayOf(0, 1, 2),
            cover = longArrayOf(0, 1, 2),
            countLow = intArrayOf(0, 0, 0),
            countHigh = intArrayOf(1, 1, 1),
            closed = true,
        )
        val r = LpAutoConfig.recommend(problem(gcc))
        assertTrue(r.bounding)
        assertTrue(r.cuts)
        assertFalse(r.lagrangian)
    }

    @Test
    fun `cumulative enables energetic reasoning only`() {
        val p = problem(Cumulative(intArrayOf(0, 1, 2), longArrayOf(3, 3, 3), longArrayOf(1, 1, 1), capacity = 1L))
        val r = LpAutoConfig.recommend(p)
        assertTrue(r.energeticReasoning)
        assertFalse(r.bounding)
        assertFalse(r.cuts)
        assertFalse(r.learn)
        assertFalse(r.probe)
    }

    @Test
    fun `cumulative with a verifiable makespan enables the makespan row and bounding`() {
        // ints 0,1,2 starts; 3 makespan, with M >= startᵢ + durᵢ — a verifiable scheduling makespan.
        val factors = arrayOf<Factor>(
            Linear(intArrayOf(1, -1), intArrayOf(3, 0), LinearOp.GE, 3),
            Linear(intArrayOf(1, -1), intArrayOf(3, 1), LinearOp.GE, 3),
            Linear(intArrayOf(1, -1), intArrayOf(3, 2), LinearOp.GE, 3),
            Cumulative(intArrayOf(0, 1, 2), longArrayOf(3, 3, 3), longArrayOf(1, 1, 1), capacity = 1L),
        )
        val p = Problem(0, 4, Array(4) { IntDomain(0, 20) }, factors)
        val r = LpAutoConfig.recommend(p)
        assertTrue(r.cumulative)
        assertTrue(r.bounding) // the scheduling makespan turns the bounding stack on
        assertTrue(r.learn)
        assertTrue(r.energeticReasoning) // the feasibility check rides along on the Cumulative
    }

    /** Makespan COP: ints 0..n-1 starts, n is the makespan with `M ≥ startᵢ + durᵢ`, plus a cumulative. */
    private fun makespanScheduling(startHi: Int): Problem {
        val n = 3
        val factors = ArrayList<Factor>()
        for (i in 0 until n) factors.add(Linear(intArrayOf(1, -1), intArrayOf(n, i), LinearOp.GE, 3))
        factors.add(Cumulative(intArrayOf(0, 1, 2), longArrayOf(3, 3, 3), longArrayOf(1, 1, 1), capacity = 1L))
        val domains = Array(
            n + 1,
        ) { if (it < n) IntDomain(0, startHi.toLong()) else IntDomain(0, (startHi + 3).toLong()) }
        return Problem(0, n + 1, domains, factors.toTypedArray())
    }

    @Test
    fun `bounded-horizon scheduling enables the time-indexed and flow relaxations`() {
        // Small declared start horizon + a verified makespan: both the time-indexed LP (#453) and the
        // preemptive flow prune (#454) auto-enable.
        val r = LpAutoConfig.recommend(makespanScheduling(startHi = 10))
        assertTrue(r.cumulativeFlow, "the flow prune rides along on any scheduling global")
        assertTrue(r.cumulativeTimeIndexed, "a small bounded horizon fits the time-indexed tableau")
        assertTrue(r.cumulative, "the energetic makespan row is on too")
    }

    @Test
    fun `huge-horizon scheduling keeps the time-indexed model off but the flow prune on`() {
        // A 200000-wide start horizon blows the O(n·H) time-indexed column budget, so it stays off;
        // the horizon-independent flow prune still rides along.
        val r = LpAutoConfig.recommend(makespanScheduling(startHi = 200_000))
        assertTrue(r.cumulativeFlow)
        assertFalse(r.cumulativeTimeIndexed, "the huge horizon must keep the time-indexed model off")
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
            val domains = Array(n + 1) { if (it < n) IntDomain(0, 31) else IntDomain(0, n.toLong()) }
            val big = Problem(0, n + 1, domains, arrayOf<Factor>(NValue(n, IntArray(n) { it })))
            val rBig = LpAutoConfig.recommend(big)
            assertFalse(rBig.nValue, "the over-budget NValue hull must be shed")
            assertTrue(rBig.bounding, "the base LP still runs; only the hull is shed")

            // A small NValue (3×3 = 9 cells) fits comfortably and is enabled.
            val small = Problem(
                0,
                4,
                Array(4) { if (it < 3) IntDomain(0, 2) else IntDomain(0, 3) },
                arrayOf<Factor>(NValue(3, intArrayOf(0, 1, 2))),
            )
            assertTrue(LpAutoConfig.recommend(small).nValue, "a small NValue hull fits and is enabled")
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
            val tuples = LongArray(1024 * 2) { (it % 2).toLong() } // 1024 two-column tuples
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
            assertFalse(r.table && r.nValue, "two stacked hulls cannot both be enabled past the budget")
            assertTrue(r.table || r.nValue, "the cheaper hull is still kept")
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
            Cumulative(intArrayOf(0, 1, 2), longArrayOf(1, 1, 1), longArrayOf(1, 1, 1), capacity = 1L),
        )
        val off = LpAutoConfig.resolve(p, LpConfig(LpEmphasis.OFF))
        assertFalse(
            off.bounding || off.cuts || off.lagrangian || off.energeticReasoning ||
                off.cumulativeFlow,
        )

        val conservative = LpAutoConfig.resolve(p, LpConfig(LpEmphasis.CONSERVATIVE))
        assertTrue(
            conservative.lagrangian && conservative.energeticReasoning &&
                conservative.cumulativeFlow,
        )
        assertFalse(conservative.bounding, "MEDIUM simplex stays off at CONSERVATIVE")
        assertFalse(conservative.cuts, "EXHAUSTIVE cuts stay off at CONSERVATIVE")

        val default = LpAutoConfig.resolve(p, LpConfig(LpEmphasis.DEFAULT))
        assertTrue(default.bounding && default.lagrangian)
        assertFalse(default.cuts, "EXHAUSTIVE cuts stay off at DEFAULT")

        val aggressive = LpAutoConfig.resolve(p, LpConfig(LpEmphasis.AGGRESSIVE))
        assertTrue(aggressive.bounding && aggressive.cuts)
        // AGGRESSIVE reproduces the historical structural recommend (every applicable technique on).
        val rec = LpAutoConfig.recommend(p)
        assertEquals(rec.bounding, aggressive.bounding)
        assertEquals(rec.cuts, aggressive.cuts)
        assertEquals(rec.lagrangian, aggressive.lagrangian)
    }

    @Test
    fun `default emphasis admits a small hull but not a large one`() {
        // NValue hull dimension is 3·(Σ domain sizes) + 2·|xs| + 1. Three vars over [0,5] ⇒ 61 ≤ 128,
        // so the middle tier admits it at DEFAULT; eight vars ⇒ 161 > 128, so it stays AGGRESSIVE-only.
        val small = Problem(0, 4, Array(4) { IntDomain(0, 5) }, arrayOf<Factor>(NValue(3, intArrayOf(0, 1, 2))))
        assertTrue(
            LpAutoConfig.resolve(small, LpConfig(LpEmphasis.DEFAULT)).nValue,
            "a small hull is admitted at DEFAULT (the middle tier)",
        )

        val large = Problem(0, 9, Array(9) { IntDomain(0, 5) }, arrayOf<Factor>(NValue(8, IntArray(8) { it })))
        assertFalse(
            LpAutoConfig.resolve(large, LpConfig(LpEmphasis.DEFAULT)).nValue,
            "a large hull stays gated to AGGRESSIVE at DEFAULT",
        )
        assertTrue(
            LpAutoConfig.resolve(large, LpConfig(LpEmphasis.AGGRESSIVE)).nValue,
            "AGGRESSIVE still enables the large hull",
        )
    }

    @Test
    fun `an override beats the middle-tier size gate`() {
        val small = Problem(0, 4, Array(4) { IntDomain(0, 5) }, arrayOf<Factor>(NValue(3, intArrayOf(0, 1, 2))))
        assertFalse(
            LpAutoConfig.resolve(small, LpConfig(LpEmphasis.DEFAULT, mapOf(LpTechnique.NVALUE to false))).nValue,
            "an explicit -nvalue override keeps the small hull off at DEFAULT",
        )
        val large = Problem(0, 9, Array(9) { IntDomain(0, 5) }, arrayOf<Factor>(NValue(8, IntArray(8) { it })))
        assertTrue(
            LpAutoConfig.resolve(large, LpConfig(LpEmphasis.DEFAULT, mapOf(LpTechnique.NVALUE to true))).nValue,
            "an explicit +nvalue override enables even the large hull at DEFAULT",
        )
    }

    @Test
    fun `auto-enabled energetic reasoning derives a size-aware cadence`() {
        fun cumulative(tasks: Int) = Problem(
            0,
            tasks,
            Array(tasks) { IntDomain(0, 5) },
            arrayOf<Factor>(
                Cumulative(IntArray(tasks) { it }, LongArray(tasks) { 3L }, LongArray(tasks) { 1L }, capacity = 1L),
            ),
        )
        // 27 tasks: ~20k scan ops per check, under the per-check budget — full cadence.
        assertEquals(1, LpAutoConfig.recommend(cumulative(27)).energeticEvery)
        // 256 tasks: ~16.7M ops — the cadence normalises it back to the budget.
        val big = LpAutoConfig.recommend(cumulative(256))
        assertTrue(big.energeticReasoning)
        assertEquals(128, big.energeticEvery)
        // A caller who enabled the check explicitly keeps their cadence untouched.
        val explicit = LpAutoConfig.recommend(
            cumulative(256),
            LpPlan(energeticReasoning = true),
        )
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
        assertTrue(r.bounding)
        // The AllDifferent makes the instance cut-eligible; cuts gate on lpActive (#705).
        assertTrue(r.cuts)
        assertTrue(r.probe)
        assertTrue(r.learn)
        assertTrue(r.lagrangian)

        // Past the ceiling ⇒ the LP family fully declines; the Lagrangian still runs.
        val saved = KlauseConfig.current
        try {
            KlauseConfig.current = saved.copy(lpCeilingTableauCells = 1L)
            val off = LpAutoConfig.recommend(p)
            assertFalse(off.bounding)
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
        val auto = BacktrackSolver(
            p.bake(),
        ).minimize(obj, BacktrackParams(randomSeed = 1L, lpConfig = LpConfig.AGGRESSIVE))
        assertTrue(auto is MinimizeResult.Optimal)
        assertEquals(3.0, auto.objectiveValue)
        assertTrue(auto.stats.lp.solves.sum > 0.0, "an LP emphasis must engage the node LP")

        val plain = BacktrackSolver(p.bake()).minimize(obj, BacktrackParams(randomSeed = 1L))
        assertTrue(plain is MinimizeResult.Optimal)
        assertEquals(3.0, plain.objectiveValue)
        assertEquals(0.0, plain.stats.lp.solves.sum, "a null lpConfig leaves the LP family off")

        // The CONSERVATIVE emphasis (FAST tier only) keeps the per-node simplex off — no solves.
        val conservative = BacktrackSolver(p.bake()).minimize(
            obj,
            BacktrackParams(randomSeed = 1L, lpConfig = LpConfig(LpEmphasis.CONSERVATIVE)),
        )
        assertTrue(conservative is MinimizeResult.Optimal && conservative.objectiveValue == 3.0)
        assertEquals(0.0, conservative.stats.lp.solves.sum, "CONSERVATIVE runs no per-node simplex")
    }

    @Test
    fun `circuit enables circuit cuts and bounding`() {
        val r = LpAutoConfig.recommend(problem(Circuit(intArrayOf(0, 1, 2))))
        assertTrue(r.bounding)
        assertTrue(r.circuit)
    }

    @Test
    fun `aggressive enables the new parity techniques`() {
        // ArrayMinMax ⇒ lin-max tight face. (The simplex always uses Devex / Harris / scaling /
        // bound-flip internally — they are no longer plan knobs.)
        val mm = LpAutoConfig.recommend(problem(ArrayMinMax(result = 0, xs = intArrayOf(1, 2), max = true)))
        assertTrue(mm.bounding)
        assertTrue(mm.linMaxTightFace)

        // Product ⇒ McCormick envelope.
        val prod = LpAutoConfig.recommend(problem(Product(a = 0, b = 1, result = 2)))
        assertTrue(prod.bounding)
        assertTrue(prod.productMcCormick)
    }

    @Test
    fun `element hull is enabled for both constant and variable arrays`() {
        val constArr = LpAutoConfig.recommend(
            problem(Element(idx = 0, result = 1, arr = longArrayOf(5, 7, 9), arrIsVars = false, indexOffset = 0)),
        )
        assertTrue(constArr.bounding)
        assertTrue(constArr.element)

        // Variable arrays now also enable the element hull — they route to the big-M form.
        val varArr = LpAutoConfig.recommend(
            problem(Element(idx = 0, result = 1, arr = longArrayOf(2), arrIsVars = true, indexOffset = 0)),
        )
        assertTrue(varArr.element)
    }

    @Test
    fun `table enables table hull and bounding`() {
        val r = LpAutoConfig.recommend(problem(Table(xs = intArrayOf(0, 1), tuples = longArrayOf(0, 0, 1, 1))))
        assertTrue(r.bounding)
        assertTrue(r.table)
    }

    @Test
    fun `pseudo-boolean enables cover cuts`() {
        val pb = PseudoBoolean(longArrayOf(2, 3), intArrayOf(Lit.make(0, true), Lit.make(1, true)), PbOp.LE, 4L)
        val r = LpAutoConfig.recommend(Problem(2, 0, emptyArray(), arrayOf<Factor>(pb)))
        assertTrue(r.cuts)
        assertTrue(r.bounding)
    }

    @Test
    fun `pure boolean problem enables nothing`() {
        val r = LpAutoConfig.recommend(Problem(2, 0, emptyArray(), arrayOf<Factor>()))
        assertFalse(r.bounding)
        assertFalse(r.cuts)
        assertFalse(r.lagrangian)
        assertFalse(r.energeticReasoning)
    }

    @Test
    fun `caller-set flags are never turned off`() {
        // A Cumulative-only problem would not enable lpBounding, but an explicit base setting stays.
        val p = problem(Cumulative(intArrayOf(0, 1, 2), longArrayOf(3, 3, 3), longArrayOf(1, 1, 1), capacity = 1L))
        val r = LpAutoConfig.recommend(p, LpPlan(bounding = true, gomory = false))
        assertTrue(r.bounding)
        assertFalse(r.gomory)
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
        val auto = BacktrackParams(lpPlan = LpAutoConfig.recommend(p), randomSeed = 1L)
        assertTrue(auto.lpPlan.bounding)
        val result = BacktrackSolver(p.bake()).minimize(obj, auto)
        assertTrue(result is MinimizeResult.Optimal)
        assertEquals(3.0, result.objectiveValue)
    }
}
