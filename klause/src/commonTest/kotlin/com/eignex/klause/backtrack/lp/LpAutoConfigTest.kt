package com.eignex.klause.backtrack.lp

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.NValue
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpAutoConfig
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.lp.bounding.LpPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        var config = KlauseConfig.DEFAULT.copy(lpCeilingTableauCells = Long.MAX_VALUE)
        assertTrue(LpAutoConfig.recommend(p.withSettings(config.problemSettings())).bounding)
        // Over the base cap but within the ceiling: LP still on.
        config = KlauseConfig.DEFAULT.copy(lpMaxTableauCells = 1L, lpCeilingTableauCells = Long.MAX_VALUE)
        assertTrue(
            LpAutoConfig.recommend(p.withSettings(config.problemSettings())).bounding,
            "over the base cap but within the ceiling, LP stays on",
        )
        config = KlauseConfig.DEFAULT.copy(lpCeilingTableauCells = 1L)
        assertFalse(LpAutoConfig.recommend(p.withSettings(config.problemSettings())).bounding)

    }

    @Test
    fun `continuous columns count toward the relaxation-size ceiling`() {
        val row = Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2)
        val discrete = problem(row)
        val mixed = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 5) },
            arrayOf<Factor>(row),
            numRealVars = 100,
            realLower = DoubleArray(100),
            realUpper = DoubleArray(100) { 1.0 },
        )
        val config = KlauseConfig.DEFAULT.copy(lpCeilingTableauCells = 50L)

        assertTrue(LpAutoConfig.recommend(discrete.withSettings(config.problemSettings())).bounding)
        assertFalse(LpAutoConfig.recommend(mixed.withSettings(config.problemSettings())).bounding)

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
    fun `size guard sheds an over-budget hull but keeps the base LP`() {
        // NValue over 32 vars × domain 32 = 1024 cells: under its own MAX_NVALUE_CELLS cap (so the
        // builder would build it), but its ~2048 columns + ~1089 rows blow a 2^20 relaxation budget.
        // The size guard (#484) must shed the hull (lpNValue off) while the base LP still runs.
        val config = KlauseConfig.DEFAULT.copy(lpMaxTableauCells = 1L shl 20)
        val n = 32
        val domains = Array(n + 1) { if (it < n) IntDomain(0, 31) else IntDomain(0, n.toLong()) }
        val big = Problem(0, n + 1, domains, arrayOf<Factor>(NValue(n, IntArray(n) { it })))
        val rBig = LpAutoConfig.recommend(big.withSettings(config.problemSettings()))
        assertFalse(rBig.nValue, "the over-budget NValue hull must be shed")
        assertTrue(rBig.bounding, "the base LP still runs; only the hull is shed")

        // A small NValue (3×3 = 9 cells) fits comfortably and is enabled.
        val small = Problem(
            0,
            4,
            Array(4) { if (it < 3) IntDomain(0, 2) else IntDomain(0, 3) },
            arrayOf<Factor>(NValue(3, intArrayOf(0, 1, 2))),
        )
        assertTrue(LpAutoConfig.recommend(small.withSettings(config.problemSettings())).nValue)

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
        // AGGRESSIVE matches the ungated structural recommend (every applicable technique on).
        val rec = LpAutoConfig.recommend(p)
        assertEquals(rec.bounding, aggressive.bounding)
        assertEquals(rec.cuts, aggressive.cuts)
        assertEquals(rec.lagrangian, aggressive.lagrangian)
    }

    @Test
    fun `a hull the build declines over an open side is not enabled`() {
        // The estimate has to decline exactly where the build declines, or the plan turns on a family
        // whose rows never arrive and the size budget is spent on nothing.
        val tuples = longArrayOf(0, 0, 1, 1)
        val closed = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 5) },
            arrayOf<Factor>(Table(xs = intArrayOf(0, 1), tuples = tuples)),
        )
        val open = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 5) },
            arrayOf<Factor>(Table(xs = intArrayOf(0, 1), tuples = tuples)),
            openIntHi = booleanArrayOf(false, true),
        )
        assertTrue(LpAutoConfig.recommend(closed).table, "a closed table gets its hull")
        assertFalse(LpAutoConfig.recommend(open).table, "an open-sided column gets none")
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
}
