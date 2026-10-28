package com.eignex.klause.count

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BooleanCountComponentsTest {
    @Test
    fun `component products should match monolithic counts for every Boolean projection`() {
        val problem = Problem(4, 0, emptyArray(), listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
            Clause(intArrayOf(Lit.make(2, false), Lit.make(3, false))),
        )).bake()

        for (mask in 0 until 16) {
            val projection = (0 until 4).filter { mask and (1 shl it) != 0 }.toIntArray()
            val config = ExactCountConfig(samplingSet = projection)
            val ordinary = AnytimeCounter.monolithic(problem, config).last()
            val product = AnytimeCounter.run(problem, config).last()

            assertTrue(ordinary.exact)
            assertEquals(ordinary, product, "projection=${projection.toList()}")
        }
    }

    @Test
    fun `unprojected components should contribute feasibility rather than completion counts`() {
        val problem = Problem(4, 0, emptyArray(), listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
        )).bake()

        val result = AnytimeCounter.run(problem, ExactCountConfig(samplingSet = intArrayOf(2))).last()

        assertTrue(result.exact)
        assertEquals(2L, result.lower)
    }

    @Test
    fun `an infeasible unprojected component should annihilate the count`() {
        val problem = Problem(4, 0, emptyArray(), listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
            Clause(intArrayOf(Lit.make(2, true), Lit.make(3, false))),
            Clause(intArrayOf(Lit.make(2, false), Lit.make(3, true))),
            Clause(intArrayOf(Lit.make(2, false), Lit.make(3, false))),
        )).bake()
        val config = ExactCountConfig(samplingSet = intArrayOf(0))

        val result = AnytimeCounter.run(problem, config).last()

        assertEquals(AnytimeCounter.monolithic(problem, config).last(), result)
        assertTrue(result.exact)
        assertEquals(0L, result.lower)
    }

    @Test
    fun `free projected columns should multiply component counts`() {
        val problem = Problem(6, 0, emptyArray(), listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
        )).bake()

        val result = AnytimeCounter.run(problem, ExactCountConfig(samplingSet = intArrayOf(0, 2, 4))).last()

        assertTrue(result.exact)
        assertEquals(8L, result.lower)
    }

    @Test
    fun `conditioning should discharge a bridge regardless of literal order`() {
        for (position in 0..2) {
            val bridge = mutableListOf(Lit.make(0, true), Lit.make(2, true))
            bridge.add(position, Lit.make(4, true))
            val problem = Problem(5, 0, emptyArray(), listOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
                Clause(bridge.toIntArray()),
            )).bake().countingScope(Assumptions(bools = mapOf(4 to true)))
            val config = ExactCountConfig()

            val result = AnytimeCounter.run(problem, config).last()

            assertNotNull(BooleanCountComponents.partition(problem, config))
            assertEquals(AnytimeCounter.monolithic(problem, config).last(), result)
            assertTrue(result.exact)
            assertEquals(9L, result.lower)
        }
    }

    @Test
    fun `a live bridge should retain monolithic counting`() {
        val problem = Problem(4, 0, emptyArray(), listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
            Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
        )).bake()
        val config = ExactCountConfig(maxChecks = 3L)

        assertNull(BooleanCountComponents.partition(problem, config))
        assertEquals(AnytimeCounter.monolithic(problem, config).toList(), AnytimeCounter.run(problem, config).toList())
    }

    @Test
    fun `components should share one feasibility check allowance`() {
        val problem = Problem(4, 0, emptyArray(), listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
        )).bake()
        val config = ExactCountConfig(samplingSet = intArrayOf(0, 2), maxChecks = 5L)

        val partial = AnytimeCounter.run(problem, config).last()
        val complete = AnytimeCounter.run(problem, config.copy(maxChecks = 6L)).last()

        assertEquals(2L, partial.lower)
        assertEquals(4L, partial.upper)
        assertFalse(partial.exact)
        assertTrue(complete.exact)
        assertEquals(4L, complete.lower)
    }

    @Test
    fun `stopping before an existential component should retain uncertainty`() {
        val problem = Problem(4, 0, emptyArray(), listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
        )).bake()
        val config = ExactCountConfig(samplingSet = intArrayOf(0), maxChecks = 3L)

        val result = AnytimeCounter.run(problem, config).last()

        assertEquals(0L, result.lower)
        assertEquals(2L, result.upper)
        assertFalse(result.exact)
    }

    @Test
    fun `component intervals should tighten monotonically across budget boundaries`() {
        val problem = Problem(4, 0, emptyArray(), listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
        )).bake()
        var previous = Count(0L, 0L, 16L, exact = false, confidence = 1.0)

        for (budget in 1L..14L) {
            val reports = AnytimeCounter.run(problem, ExactCountConfig(maxChecks = budget, reportEvery = 1L)).toList()

            for (report in reports) {
                assertTrue(report.lower <= 9L && report.upper >= 9L, "budget=$budget: $report")
                assertTrue(report.estimate in report.lower..report.upper)
            }
            for ((before, after) in reports.zipWithNext()) {
                assertTrue(after.lower >= before.lower)
                assertTrue(after.upper <= before.upper)
            }
            assertTrue(reports.last().lower >= previous.lower)
            assertTrue(reports.last().upper <= previous.upper)
            previous = reports.last()
        }
        assertTrue(previous.exact)
    }

    @Test
    fun `disjoint pairs should complete with fewer checks than the monolithic traversal`() {
        val problem = Problem(6, 0, emptyArray(), List(6) { index ->
            val first = index / 2 * 2
            Clause(intArrayOf(Lit.make(first, index % 2 == 0), Lit.make(first + 1, index % 2 == 0)))
        }).bake()
        val config = ExactCountConfig(maxChecks = 21L)

        val product = BacktrackSolver(problem).exactCount(config).last()
        val ordinary = AnytimeCounter.monolithic(problem, config).last()

        assertTrue(product.exact)
        assertEquals(8L, product.lower)
        assertFalse(ordinary.exact)
        assertTrue(ordinary.lower < 8L)
        assertEquals(product, AnytimeCounter.monolithic(problem, config.copy(maxChecks = 100L)).last())
    }

    @Test
    fun `undecided component checks should preserve all unchecked mass`() {
        val problem = Problem(4, 0, emptyArray(), listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
        )).bake()

        val result = AnytimeCounter.run(problem, ExactCountConfig(maxDecisionsPerCheck = 0L)).last()

        assertEquals(0L, result.lower)
        assertEquals(16L, result.upper)
        assertFalse(result.exact)
    }

    @Test
    fun `zero times an unresolved interval should be exactly zero`() {
        val problem = Problem(0, 0, emptyArray(), emptyArray()).bake()
        val zero = ExactCountInterval(BIG_ZERO, BIG_ZERO)
        val unknown = ExactCountInterval(BIG_ZERO, BIG_ONE)

        val result = (unknown * zero).snapshot(problem)

        assertEquals(0L, result.upper)
        assertTrue(result.exact)
    }

    @Test
    fun `saturated equal product bounds should not claim exactness`() {
        val problem = Problem(0, 0, emptyArray(), emptyArray()).bake()
        val large = ExactCountInterval(bigIntOf(Long.MAX_VALUE), bigIntOf(Long.MAX_VALUE))
        val two = ExactCountInterval(bigIntOf(2L), bigIntOf(2L))

        val result = (large * two).snapshot(problem)

        assertEquals(Long.MAX_VALUE, result.lower)
        assertEquals(Long.MAX_VALUE, result.upper)
        assertFalse(result.exact)
    }

    @Test
    fun `free column mass beyond Long range should remain in the composed result`() {
        val problem = Problem(67, 0, emptyArray(), listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
        )).bake()

        val result = AnytimeCounter.run(problem, ExactCountConfig(maxChecks = 14L)).last()

        assertEquals(Long.MAX_VALUE, result.lower)
        assertEquals(Long.MAX_VALUE, result.upper)
        assertFalse(result.exact)
    }

    @Test
    fun `an explicit empty integer projection should leave all Boolean columns existential`() {
        val problem = Problem(4, 0, emptyArray(), listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
        )).bake()

        val result = AnytimeCounter.run(problem, ExactCountConfig(intSamplingSet = intArrayOf())).last()

        assertTrue(result.exact)
        assertEquals(1L, result.lower)
    }

    @Test
    fun `component products should preserve the original source interpretation`() {
        val semantics = SourceSemantics(SourceFormat.DIMACS).resolve()
        val problem = Problem(4, 0, emptyArray(), listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
        ), semantics = semantics).bake()

        val result = AnytimeCounter.run(problem, ExactCountConfig()).last()

        assertSame(semantics, result.semantics)
    }

    @Test
    fun `unsupported globals and custom projections should retain the monolithic fallback`() {
        val problem = Problem(4, 0, emptyArray(), listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
            Cardinality(IntArray(4) { Lit.make(it, true) }, min = 1, max = 2),
        ))
        val config = ExactCountConfig(maxChecks = 3L)

        val clausesOnly = problem.withFactors(problem.factors.copyOfRange(0, 2))
        for (baked in listOf(problem.bake(), clausesOnly.bake(FactorProjectionProviders.Default))) {
            assertNull(BooleanCountComponents.partition(baked, config))
            assertEquals(AnytimeCounter.monolithic(baked, config).toList(), AnytimeCounter.run(baked, config).toList())
        }
    }

    @Test
    fun `real ownership should decline Boolean component counting`() {
        val problem = Problem(4, 0, emptyArray(), listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
        ), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0)).bake()

        assertNull(BooleanCountComponents.partition(problem, ExactCountConfig()))
    }

    @Test
    fun `integer holes should retain monolithic projected counting`() {
        val domain = IntDomain(-2, 2).excludeValues(longArrayOf(-1, 1))!!
        val problem = Problem(4, 1, arrayOf(domain), listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
        )).bake()
        val config = ExactCountConfig(intSamplingSet = intArrayOf(0))

        val result = AnytimeCounter.run(problem, config).last()

        assertNull(BooleanCountComponents.partition(problem, config))
        assertEquals(AnytimeCounter.monolithic(problem, config).last(), result)
        assertTrue(result.exact)
        assertEquals(3L, result.lower)
    }
}
