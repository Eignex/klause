package com.eignex.klause.lp.bounding

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
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpAutoConfigTest {
    private fun problem(vararg factors: Factor, intVars: Int = 3, realVars: Int = 0): Problem = Problem(
        0,
        intVars,
        Array(intVars) { IntDomain(0, 5) },
        arrayOf(*factors),
        numRealVars = realVars,
        realLower = DoubleArray(realVars),
        realUpper = DoubleArray(realVars) { 1.0 },
    )

    @Test
    fun `linear structure should enable the bounding stack and permitted cuts`() {
        val p = problem(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2))
        val r = LpAutoConfig.recommend(p)
        assertTrue(r.bounding)
        assertTrue(r.learn)
        assertTrue(r.probe)
        assertTrue(r.cuts)
        assertFalse(r.booleanRlt)
        assertFalse(r.flowCoverCuts)
        assertFalse(r.lagrangian)
        assertFalse(r.energeticReasoning)
    }

    @Test
    fun `ordinary linear cuts should respect emphasis and technique overrides`() {
        val p = problem(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2))
        for ((spec, expected) in listOf(
            "default" to false,
            "default,+cuts" to true,
            "aggressive" to true,
            "aggressive,-cuts" to false,
            "off,+cuts" to false,
        )) {
            assertEquals(expected, LpAutoConfig.resolve(p, LpConfig.parse(spec)).cuts, spec)
        }
    }

    @Test
    fun `ordinary integral root cuts should retain source-valid rounding proofs`() {
        val p = problem(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
            realVars = 1,
        )
        val config = LpConfig.parse("default,+cuts")
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val relaxer = CpToLpRelaxation(p, objective)
        val session = PropagationSession(p)
        val cuts = LpEngine(p, objective, LpParams(lpConfig = config), SolveStatsSink("cuts")).use {
            val plan = it.params.lpPlan
            it.harvestRootCuts(relaxer, session, emptyList(), plan.gomoryEnabled, plan.mirEnabled)
        }

        assertTrue(cuts.isNotEmpty())
        for (cut in cuts) {
            assertTrue(cut.global)
            assertTrue(assertNotNull(cut.provenance).rules.isNotEmpty())
            for (x in 0L..5L) {
                for (y in 0L..5L) {
                    for (z in 0L..5L) {
                        if (x + y < 1 || y + z < 1 || x + z < 1) continue
                        val point = longArrayOf(x, y, z)
                        val lhs = cut.cols.indices.sumOf { cut.coeffs[it] * point[cut.cols[it]] }
                        assertTrue(lhs >= cut.rhs)
                    }
                }
            }
        }
        assertTrue(cuts.any { cut -> cut.coeffs.sum() * 0.5 < cut.rhs })
    }

    @Test
    fun `ordinary mixed root separation should spend no simplex work across objective changes`() {
        val p = problem(
            Linear(longArrayOf(2), intArrayOf(0), doubleArrayOf(-1.0), intArrayOf(0), LinearOp.LE, 3),
            intVars = 1,
            realVars = 1,
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(-1))
        val relaxer = CpToLpRelaxation(p, objective)
        val session = PropagationSession(p)
        val sink = SolveStatsSink("cuts")
        LpEngine(p, objective, LpParams(lpConfig = LpConfig.parse("default,+cuts")), sink).use { engine ->
            assertTrue(engine.harvestRootCuts(relaxer, session, emptyList(), true, true).isEmpty())
            engine.forObjective(objective, Cancellation.Never).use {
                assertTrue(it.harvestRootCuts(relaxer, session, emptyList(), true, true).isEmpty())
            }
        }

        assertEquals(0.0, sink.snapshot().lp.rootPasses.sum)
    }

    @Test
    fun `ordinary root cut rows should stay within the original relaxation row count`() {
        val p = problem(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val relaxer = CpToLpRelaxation(p, objective)
        val session = PropagationSession(p)
        val originalRows = relaxer.build(session).model.m

        val cuts = LpEngine(
            p,
            objective,
            LpParams(lpConfig = LpConfig.parse("default,+cuts")),
            SolveStatsSink("cuts"),
        ).use {
            it.harvestRootCuts(relaxer, session, emptyList(), true, true)
        }

        assertTrue(cuts.size <= originalRows)
    }

    @Test
    fun `explicit cut plans should preserve mixed root separation`() {
        val p = problem(
            Linear(longArrayOf(2), intArrayOf(0), doubleArrayOf(-1.0), intArrayOf(0), LinearOp.LE, 3),
            intVars = 1,
            realVars = 1,
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(-1))
        val relaxer = CpToLpRelaxation(p, objective)
        val session = PropagationSession(p)
        val sink = SolveStatsSink("cuts")
        LpEngine(p, objective, LpParams(lpPlan = LpPlan(bounding = true, cuts = true)), sink).use {
            it.harvestRootCuts(relaxer, session, emptyList(), true, true)
        }

        assertTrue(sink.snapshot().lp.rootPasses.sum > 0.0)
    }

    @Test
    fun `the relaxation-size ceiling should gate auto bounding`() {
        val p = problem(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2))
        var config = KlauseConfig.DEFAULT.copy(lpCeilingTableauCells = Long.MAX_VALUE)
        assertTrue(LpAutoConfig.recommend(p.withSettings(config.problemSettings())).bounding)
        config = KlauseConfig.DEFAULT.copy(lpMaxTableauCells = 1L, lpCeilingTableauCells = Long.MAX_VALUE)
        assertTrue(
            LpAutoConfig.recommend(p.withSettings(config.problemSettings())).bounding,
            "over the base cap but within the ceiling, LP stays on",
        )
        config = KlauseConfig.DEFAULT.copy(lpCeilingTableauCells = 1L)
        assertFalse(LpAutoConfig.recommend(p.withSettings(config.problemSettings())).bounding)
        assertFalse(LpAutoConfig.recommend(p.withSettings(config.problemSettings())).cuts)
    }

    @Test
    fun `continuous columns should count toward the relaxation-size ceiling`() {
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
    fun `cumulative should enable energetic reasoning only`() {
        val p = problem(Cumulative(intArrayOf(0, 1, 2), longArrayOf(3, 3, 3), longArrayOf(1, 1, 1), capacity = 1L))
        val r = LpAutoConfig.recommend(p)
        assertTrue(r.energeticReasoning)
        assertFalse(r.bounding)
        assertFalse(r.cuts)
        assertFalse(r.learn)
        assertFalse(r.probe)
    }

    @Test
    fun `the size guard should shed an over-budget hull but keep the base LP`() {
        // 32 x 32 NValue cells fit its own cap, but ~2048 columns + ~1089 rows blow a 2^20 relaxation budget.
        val config = KlauseConfig.DEFAULT.copy(lpMaxTableauCells = 1L shl 20)
        val n = 32
        val domains = Array(n + 1) { if (it < n) IntDomain(0, 31) else IntDomain(0, n.toLong()) }
        val big = Problem(0, n + 1, domains, arrayOf<Factor>(NValue(n, IntArray(n) { it })))
        val rBig = LpAutoConfig.recommend(big.withSettings(config.problemSettings()))
        assertFalse(rBig.nValue)
        assertTrue(rBig.bounding)

        val small = Problem(
            0,
            4,
            Array(4) { if (it < 3) IntDomain(0, 2) else IntDomain(0, 3) },
            arrayOf<Factor>(NValue(3, intArrayOf(0, 1, 2))),
        )
        assertTrue(LpAutoConfig.recommend(small.withSettings(config.problemSettings())).nValue)
    }

    @Test
    fun `resolve should gate techniques by the emphasis cost tier`() {
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
        assertFalse(conservative.bounding)
        assertFalse(conservative.cuts)

        val default = LpAutoConfig.resolve(p, LpConfig(LpEmphasis.DEFAULT))
        assertTrue(default.bounding && default.lagrangian)
        assertFalse(default.cuts)

        val aggressive = LpAutoConfig.resolve(p, LpConfig(LpEmphasis.AGGRESSIVE))
        assertTrue(aggressive.bounding && aggressive.cuts)
        val rec = LpAutoConfig.recommend(p)
        assertEquals(rec.bounding, aggressive.bounding)
        assertEquals(rec.cuts, aggressive.cuts)
        assertEquals(rec.lagrangian, aggressive.lagrangian)
    }

    @Test
    fun `a hull the build declines over an open side should not be enabled`() {
        // The estimate must decline exactly where the build declines, or the size budget is spent on nothing.
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
        assertTrue(LpAutoConfig.recommend(closed).table)
        assertFalse(LpAutoConfig.recommend(open).table)
    }

    @Test
    fun `caller-set flags should never be turned off`() {
        val p = problem(Cumulative(intArrayOf(0, 1, 2), longArrayOf(3, 3, 3), longArrayOf(1, 1, 1), capacity = 1L))
        val r = LpAutoConfig.recommend(p, LpPlan(bounding = true, gomory = false))
        assertTrue(r.bounding)
        assertFalse(r.gomory)
        assertTrue(r.energeticReasoning)
    }

    @Test
    fun `retained hull planning should preserve the eager plan across checkpoints`() {
        val settings = KlauseConfig.DEFAULT.copy(
            lpCeilingTableauCells = Long.MAX_VALUE,
            lpMaxTableauCells = Long.MAX_VALUE,
        ).problemSettings()
        val p = Problem(
            0,
            514,
            Array(514) { if (it == 513) IntDomain(0, 513) else IntDomain(0, 0) },
            arrayOf(NValue(513, IntArray(513) { it })),
            settings = settings,
        )
        val expected = LpAutoConfig.resolve(p, LpConfig.AGGRESSIVE)
        var actual: LpPlan? = null
        val planning = sequence<Unit> {
            actual = with(LpAutoConfig) { prepare(p, LpConfig.AGGRESSIVE, LpPlan()) }
        }.iterator()

        planning.next()
        assertEquals(null, actual)
        while (planning.hasNext()) planning.next()

        assertEquals(expected, actual)
        assertTrue(expected.nValue)
    }

    private fun linearProblem(n: Int): Problem {
        val domains = Array(n) { IntDomain(0, 4) }
        val factors = arrayOf<Factor>(Linear(IntArray(n) { 1 }, IntArray(n) { it }, LinearOp.GE, n))
        return Problem(0, n, domains, factors)
    }

    @Test
    fun `relaxation size ceiling should gate LP bounding`() {
        val p = linearProblem(4)
        val overCapWithinCeiling =
            KlauseConfig.DEFAULT.copy(lpMaxTableauCells = 1L, lpCeilingTableauCells = Long.MAX_VALUE)
        val overCeiling = KlauseConfig.DEFAULT.copy(lpCeilingTableauCells = 1L)

        val on = LpAutoConfig.resolve(p.withSettings(overCapWithinCeiling.problemSettings()), LpConfig.AGGRESSIVE)
        val off = LpAutoConfig.resolve(p.withSettings(overCeiling.problemSettings()), LpConfig.AGGRESSIVE)

        assertTrue(on.bounding)
        assertFalse(off.bounding)
    }
}
