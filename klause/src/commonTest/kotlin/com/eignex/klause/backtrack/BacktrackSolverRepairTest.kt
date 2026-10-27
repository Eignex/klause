package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toExactSourceProblem
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BacktrackSolverRepairTest {
    @Test
    fun `exact repair should distinguish both sides of a rational cutoff from equality`() {
        val problem = Problem(0, 0, emptyArray(), arrayOf(
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(3.0), LinearOp.EQ, 1.0),
        ), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0)).bake()
        val third = BigFraction.ofLong(3L).reciprocal()
        val epsilon = BigFraction.ofLong(1L shl 60).reciprocal()
        val params = BacktrackParams(toleranceCheck = { true })

        for (sign in listOf(-1L, 1L)) {
            val objective = LinearObjective(realCoefficients = doubleArrayOf(sign.toDouble()))
            val value = third * BigFraction.ofLong(sign)
            for (offset in listOf(-1L, 0L, 1L)) {
                val cutoff = value + epsilon * BigFraction.ofLong(offset)
                BacktrackSolver(problem).openRepair(objective, params).use { repair ->
                    repair.beginExact(Assumptions.None, 100L, cutoff, Cancellation.Never)
                    val result = repair.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L)

                    assertEquals(TerminationReason.SearchExhausted, result.reason)
                    if (offset > 0L) {
                        assertEquals(third, assertNotNull(result.sample).exactReals?.single())
                    } else assertNull(result.sample)
                }
            }
        }
    }

    @Test
    fun `exact repair should admit a wide integer improvement tied in floating point`() {
        val value = 9_007_199_254_740_992L
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val problem = Problem(0, 1, arrayOf(IntDomain(value, value + 2L)), emptyArray()).bake()
        val cutoff = BigFraction.ofLong(value + 1L)

        BacktrackSolver(problem).openRepair(objective, BacktrackParams()).use { repair ->
            repair.beginExact(Assumptions.None, 100L, cutoff, Cancellation.Never)
            val result = repair.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L)

            assertEquals(value, assertNotNull(result.sample).ints.single())
            assertEquals(TerminationReason.SearchExhausted, result.reason)
        }
    }

    @Test
    fun `exact repair should reject a rational loosening within the same floating value`() {
        val third = BigFraction.ofLong(3L).reciprocal()
        val epsilon = BigFraction.ofLong(1L shl 60).reciprocal()
        val solver = BacktrackSolver(Problem(0, 0, emptyArray(), emptyArray()).bake())
        solver.openRepair(LinearObjective(), BacktrackParams()).use { repair ->
            repair.beginExact(Assumptions.None, 100L, third, Cancellation.Never)

            assertFailsWith<IllegalArgumentException> {
                repair.beginExact(Assumptions.None, 100L, third + epsilon, Cancellation.Never)
            }
        }
    }

    @Test
    fun `exact repair should preserve scaled source direction across sibling reseeding`() {
        for (sense in listOf("MIN", "MAX")) {
            val compiled = Mps.parse(
                "NAME SCALED\nOBJSENSE\n $sense\nROWS\n N COST\n E R\nCOLUMNS\n" +
                    " M0 'MARKER' 'INTORG'\n I COST 0.1 R 1\n M1 'MARKER' 'INTEND'\n" +
                    " X R 3\nRHS\n RHS R 1 COST -0.2\nBOUNDS\n" +
                    " BV B I\n LO B X 0\n UP B X 1\nENDATA",
            ).toExactSourceProblem()
            val sourceObjective = assertNotNull(compiled.objective).toLinearObjective()
            val objective = if (compiled.maximize) sourceObjective.negated() else sourceObjective
            val epsilon = BigFraction.ofLong(1L shl 60).reciprocal()
            val first = if (compiled.maximize) 1L else 0L
            val second = 1L - first
            BacktrackSolver(compiled.model.bake()).openRepair(objective, BacktrackParams()).use { repair ->
                val cutoff = if (compiled.maximize) BigFraction.ofLong(-2L) + epsilon
                    else BigFraction.ofLong(3L) + epsilon
                repair.beginExact(Assumptions(ints = mapOf(0 to first)), 100L, cutoff, Cancellation.Never)
                val a = assertNotNull(repair.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L).sample)
                repair.beginExact(Assumptions(ints = mapOf(0 to second)), 100L, cutoff, Cancellation.Never)
                val b = assertNotNull(repair.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L).sample)

                assertEquals(10L, compiled.objectiveScale)
                assertEquals(if (compiled.maximize) "3/10" else "1/5",
                    compiled.sourceWitness(a.ints, assertNotNull(a.exactReals)).objective.toString())
                assertEquals(if (compiled.maximize) "1/5" else "3/10",
                    compiled.sourceWitness(b.ints, assertNotNull(b.exactReals)).objective.toString())
            }
        }
    }

    @Test
    fun `repair should leave an unconsumed improving ray unavailable`() {
        val model = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY)).bake()
        val objective = LinearObjective(realCoefficients = doubleArrayOf(-1.0))
        BacktrackSolver(model).openRepair(objective, BacktrackParams()).use { repair ->
            val step = repair.repairStep(Assumptions.None, 100L, -1.0, Cancellation.Never)

            assertEquals(TerminationReason.Unsupported, step.reason)
        }
    }

    @Test
    fun `repair should resume the same fragment after a cancelled slice`() {
        val objective = LinearObjective(boolWeights = longArrayOf(-1L, -2L))
        val solver = BacktrackSolver(Problem(2, 0, emptyArray(), emptyArray()).bake())
        solver.openRepair(objective, BacktrackParams()).use { repair ->
            repair.begin(Assumptions.None, 100L, 0.0, Cancellation.Never)

            val paused = repair.runSlice(Cancellation { true }, Long.MAX_VALUE, 1L)
            var completed = paused
            repeat(32) {
                if (completed.reason == null) completed = repair.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L)
            }

            assertNull(paused.reason)
            assertEquals(TerminationReason.SearchExhausted, completed.reason)
            assertEquals(-3.0, objective.evaluate(assertNotNull(completed.sample)))
        }
    }

    @Test
    fun `repair should keep lifetime cancellation terminal until another fragment begins`() {
        val objective = LinearObjective(boolWeights = longArrayOf(-1L))
        val solver = BacktrackSolver(Problem(1, 0, emptyArray(), emptyArray()).bake())
        var cancelled = false
        solver.openRepair(objective, BacktrackParams()).use { repair ->
            repair.begin(Assumptions.None, 100L, 0.0, Cancellation { cancelled })
            cancelled = true

            val stopped = repair.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L)
            cancelled = false
            val retained = repair.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L)
            val next = repair.repair(Assumptions.None, 100L, 0.0, Cancellation.Never)

            assertEquals(TerminationReason.Cancelled, stopped.reason)
            assertEquals(stopped, retained)
            assertEquals(-1.0, objective.evaluate(assertNotNull(next)))
        }
    }

    @Test
    fun `repair should reset an expired slice before reseeding a sibling scope`() {
        val objective = LinearObjective(boolWeights = longArrayOf(-1L, -2L))
        val solver = BacktrackSolver(Problem(2, 0, emptyArray(), emptyArray()).bake())
        solver.openRepair(objective, BacktrackParams()).use { repair ->
            repair.begin(Assumptions(mapOf(0 to false), emptyMap()), 100L, 0.0, Cancellation.Never)
            repair.runSlice(Cancellation.Never, Long.MAX_VALUE, 0L)

            val next = repair.repair(Assumptions(mapOf(1 to false), emptyMap()), 100L, 0.0, Cancellation.Never)

            assertEquals(true, assertNotNull(next).boolValue(0))
            assertEquals(false, next.boolValue(1))
        }
    }

    @Test
    fun `repair should reject an increasing or NaN cutoff`() {
        val objective = LinearObjective(boolWeights = longArrayOf(-1L))
        val solver = BacktrackSolver(Problem(1, 0, emptyArray(), emptyArray()).bake())

        for (invalid in listOf(1.0, Double.NaN)) {
            solver.openRepair(objective, BacktrackParams()).use { repair ->
                assertNotNull(repair.repair(Assumptions.None, 100L, 0.0, Cancellation.Never))
                assertFailsWith<IllegalArgumentException> {
                    repair.repair(Assumptions.None, 100L, invalid, Cancellation.Never)
                }
            }
        }
    }

    @Test
    fun `repair should compare wide discrete objectives to the cutoff exactly`() {
        val cutoff = -9_007_199_254_740_992.0
        val cutoffAsLong = -9_007_199_254_740_992L
        val solver = BacktrackSolver(Problem(1, 0, emptyArray(), emptyArray()).bake())

        for (weight in listOf(-9_007_199_254_740_993L, cutoffAsLong)) {
            val objective = LinearObjective(boolWeights = longArrayOf(weight))
            val improvingSourceValues = listOf(0L, weight).filter { it < cutoffAsLong }
            solver.openRepair(objective, BacktrackParams()).use { repair ->
                val sample = repair.repair(Assumptions.None, 100L, cutoff, Cancellation.Never)
                if (improvingSourceValues.isEmpty()) {
                    assertNull(sample)
                } else {
                    assertEquals(true, assertNotNull(sample).bools.single())
                    assertEquals(improvingSourceValues.single(), objective.evaluateLong(sample))
                }
            }
        }
    }

    @Test
    fun `repair should publish a second exact improvement with the same displayed score`() {
        val values = listOf(-9_007_199_254_740_992L, -9_007_199_254_740_993L)
        val problem = Problem(0, 1, arrayOf(IntDomain(values.min(), values.max())), emptyArray()).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val offers = ArrayList<Long>()
        val params = BacktrackParams(
            valueSelector = IndomainMax,
            improvedSolutionSink = { sample, _ -> offers += sample.ints.single() },
        )

        BacktrackSolver(problem).openRepair(objective, params).use { repair ->
            val best = assertNotNull(
                repair.repair(Assumptions.None, 100L, Double.POSITIVE_INFINITY, Cancellation.Never),
            )
            assertEquals(values.min(), best.ints.single())
            assertEquals(values, offers)
        }
    }

    @Test
    fun `repair should reuse one session across pin sets and return each optimum`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L, 3L))
        val handle = BacktrackSolver(problem.bake()).openRepair(objective, BacktrackParams())

        val a = handle.repair(
            Assumptions(mapOf(2 to false, 3 to false), emptyMap()),
            2_000L,
            Double.POSITIVE_INFINITY,
            Cancellation.Never,
        )
        assertNotNull(a)
        assertEquals(5.0, objective.evaluate(a))

        val b = handle.repair(
            Assumptions(mapOf(0 to false, 1 to false), emptyMap()),
            2_000L,
            Double.POSITIVE_INFINITY,
            Cancellation.Never,
        )
        assertNotNull(b)
        assertEquals(3.0, objective.evaluate(b))

        handle.close()
    }
}
