package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.linearRows
import com.eignex.klause.lp.exactForm
import com.eignex.klause.model.PbOp
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealDifferenceSystemTest {
    @Test
    fun `fractional strict bounds retain an exact interior witness`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
            factors = arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.LE, 0.0, true),
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(2.0), LinearOp.LE, 1.0, true),
            ))
        val system = assertNotNull(RealDifferenceSystem.prepare(model,
            model.factors.map { factor -> factor.linearRows.map { it.exactForm(1) } }))

        val point = assertIs<RealDifferenceSystem.Result.Feasible>(
            system.check(intArrayOf(), Cancellation.Never)).point.single()

        assertTrue(point > BigFraction.ZERO)
        assertTrue(point < BigFraction.of(bigIntOf(1), bigIntOf(2)))
    }

    @Test
    fun `fractional equality retains its exact source coordinate`() {
        for (coefficient in listOf(3.0, -3.0)) {
            val model = Problem(0,
                intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
                numRealVars = 1,
                realLower = doubleArrayOf(-0.5),
                realUpper = doubleArrayOf(0.5),
                factors = arrayOf(Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0),
                    doubleArrayOf(coefficient), LinearOp.EQ, 1.0)))
            val system = assertNotNull(RealDifferenceSystem.prepare(model,
                model.factors.map { factor -> factor.linearRows.map { it.exactForm(1) } }))

            val point = assertIs<RealDifferenceSystem.Result.Feasible>(
                system.check(intArrayOf(), Cancellation.Never)).point.single()

            assertEquals(BigFraction.of(bigIntOf(if (coefficient > 0) 1 else -1), bigIntOf(3)), point)
        }
    }

    @Test
    fun `fractional strict cycles preserve positive slack and guarded conflicts`() {
        for (reverse in listOf(-0.5, -0.25)) {
            val model = Problem(2,
                intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
                numRealVars = 2,
                realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
                realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY },
                factors = arrayOf(
                    ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
                        doubleArrayOf(1.0, -1.0), LinearOp.LE, 0.5, true),
                    ReifiedRealLinear(1, intArrayOf(), doubleArrayOf(), intArrayOf(1, 0),
                        doubleArrayOf(1.0, -1.0), LinearOp.LE, reverse),
                ))
            val system = assertNotNull(RealDifferenceSystem.prepare(model,
                model.factors.map { factor -> factor.linearRows.map { it.exactForm(2) } }))

            val result = system.check(intArrayOf(1, 1), Cancellation.Never)

            if (reverse == -0.5) {
                val conflict = assertIs<RealDifferenceSystem.Result.Infeasible>(result)
                assertContentEquals(intArrayOf(Lit.make(0, false), Lit.make(1, false)), conflict.clause)
            } else {
                val point = assertIs<RealDifferenceSystem.Result.Feasible>(result).point
                assertTrue(point[0] - point[1] < BigFraction.of(bigIntOf(1), bigIntOf(2)))
                assertTrue(point[1] - point[0] <= BigFraction.of(bigIntOf(-1), bigIntOf(4)))
            }
            assertIs<RealDifferenceSystem.Result.Feasible>(system.check(intArrayOf(1, -1), Cancellation.Never))
            assertIs<RealDifferenceSystem.Result.Feasible>(system.check(intArrayOf(-1, 1), Cancellation.Never))
        }
    }

    @Test
    fun `fractional false guards retain their strict complement`() {
        val model = Problem(1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(0.5),
            factors = arrayOf(ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                doubleArrayOf(2.0), LinearOp.LE, 1.0)))
        val system = assertNotNull(RealDifferenceSystem.prepare(model,
            model.factors.map { factor -> factor.linearRows.map { it.exactForm(1) } }))

        val conflict = assertIs<RealDifferenceSystem.Result.Infeasible>(
            system.check(intArrayOf(0), Cancellation.Never))

        assertContentEquals(intArrayOf(Lit.make(0, true)), conflict.clause)
        assertIs<RealDifferenceSystem.Result.Feasible>(system.check(intArrayOf(1), Cancellation.Never))
        assertIs<RealDifferenceSystem.Result.Feasible>(system.check(intArrayOf(-1), Cancellation.Never))
    }

    @Test
    fun `unsafe common denominators decline optional graph preparation`() {
        for (coefficients in listOf(doubleArrayOf(3037000499.0, 3037000501.0), doubleArrayOf(4.0e18))) {
            val model = Problem(0,
                intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
                numRealVars = coefficients.size,
                realLower = DoubleArray(coefficients.size) { Double.NEGATIVE_INFINITY },
                realUpper = DoubleArray(coefficients.size) { Double.POSITIVE_INFINITY },
                factors = Array(coefficients.size) { column ->
                    Linear(intArrayOf(), doubleArrayOf(), intArrayOf(column),
                        doubleArrayOf(coefficients[column]), LinearOp.LE, 1.0)
                })

            val system = RealDifferenceSystem.prepare(model,
                model.factors.map { factor -> factor.linearRows.map { it.exactForm(model.numRealVars) } })

            assertNull(system)
        }
    }

    @Test
    fun `fractional preparation retains its containing exhausted work allowance`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.5),
            factors = emptyArray())
        var spent = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = spent > 0L
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }

        val system = RealDifferenceSystem.prepare(model, emptyList(), stop)

        assertNull(system)
        assertTrue(spent > 0L)
        assertNull(RealDifferenceSystem.prepare(model, emptyList(), stop))
        assertNotNull(RealDifferenceSystem.prepare(model, emptyList()))
    }

    @Test
    fun `strict bounds retain a rational interior witness`() {
        val model = Problem(
            0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
            factors = arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.LE, 0.0, true),
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 1.0, true),
            ),
        )
        val system = assertNotNull(RealDifferenceSystem.prepare(model,
            model.factors.map { factor -> factor.linearRows.map { it.exactForm(1) } }))

        val point = assertIs<RealDifferenceSystem.Result.Feasible>(
            system.check(intArrayOf(), Cancellation.Never)).point

        assertTrue(point.single() > BigFraction.ZERO)
        assertTrue(point.single() < BigFraction.ONE)
    }

    @Test
    fun `cached potentials follow changed guard polarity`() {
        val model = Problem(1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
            factors = arrayOf(ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                doubleArrayOf(1.0), LinearOp.LE, 0.0)))
        val system = assertNotNull(RealDifferenceSystem.prepare(model,
            model.factors.map { factor -> factor.linearRows.map { it.exactForm(1) } }))

        for (truth in listOf(1, 0, 1)) {
            val point = assertIs<RealDifferenceSystem.Result.Feasible>(
                system.check(intArrayOf(truth), Cancellation.Never)).point.single()

            assertTrue(if (truth == 1) point <= BigFraction.ZERO else point > BigFraction.ZERO)
        }
    }

    @Test
    fun `negative and strict cycles require active guards`() {
        for (strict in listOf(false, true)) {
            val model = Problem(
                3,
                intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
                numRealVars = 2,
                realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
                realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY },
                factors = arrayOf(
                    ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
                        doubleArrayOf(1.0, -1.0), LinearOp.LE, 0.0, strict),
                    ReifiedRealLinear(1, intArrayOf(), doubleArrayOf(), intArrayOf(1, 0),
                        doubleArrayOf(1.0, -1.0), LinearOp.LE, if (strict) 0.0 else -1.0),
                    ReifiedRealLinear(2, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                        doubleArrayOf(1.0), LinearOp.LE, 100.0),
                ),
            )
            val system = assertNotNull(RealDifferenceSystem.prepare(model,
                model.factors.map { factor -> factor.linearRows.map { it.exactForm(2) } }))

            val result = system.check(intArrayOf(1, 1, 1), Cancellation.Never)

            val conflict = assertIs<RealDifferenceSystem.Result.Infeasible>(result)
            assertContentEquals(intArrayOf(Lit.make(0, false), Lit.make(1, false)), conflict.clause)
            assertIs<RealDifferenceSystem.Result.Feasible>(system.check(intArrayOf(1, -1, 1), Cancellation.Never))
            assertIs<RealDifferenceSystem.Result.Feasible>(system.check(intArrayOf(-1, 1, 1), Cancellation.Never))
        }
    }

    @Test
    fun `released guards remove their cycle across sibling checks`() {
        val model = Problem(
            1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
            factors = arrayOf(ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                doubleArrayOf(1.0), LinearOp.LE, -1.0)),
        )
        val system = assertNotNull(RealDifferenceSystem.prepare(model,
            model.factors.map { factor -> factor.linearRows.map { it.exactForm(1) } }))
        val conflict = assertIs<RealDifferenceSystem.Result.Infeasible>(system.check(intArrayOf(1), Cancellation.Never))
        assertContentEquals(intArrayOf(Lit.make(0, false)), conflict.clause)

        val released = system.check(intArrayOf(-1), Cancellation.Never)
        val sibling = system.check(intArrayOf(0), Cancellation.Never)

        assertIs<RealDifferenceSystem.Result.Feasible>(released)
        assertIs<RealDifferenceSystem.Result.Feasible>(sibling)
    }

    @Test
    fun `a false guard contributes its positive conflict literal`() {
        val model = Problem(1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(0.0),
            factors = arrayOf(ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                doubleArrayOf(1.0), LinearOp.LE, 0.0)))
        val system = assertNotNull(RealDifferenceSystem.prepare(model,
            model.factors.map { factor -> factor.linearRows.map { it.exactForm(1) } }))

        val conflict = assertIs<RealDifferenceSystem.Result.Infeasible>(system.check(intArrayOf(0), Cancellation.Never))

        assertContentEquals(intArrayOf(Lit.make(0, true)), conflict.clause)
    }

    @Test
    fun `contradictory source bounds need no Boolean premise`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 1, realLower = doubleArrayOf(1.0), realUpper = doubleArrayOf(0.0), factors = emptyArray())
        val system = assertNotNull(RealDifferenceSystem.prepare(model, emptyList()))

        val conflict = assertIs<RealDifferenceSystem.Result.Infeasible>(system.check(intArrayOf(), Cancellation.Never))

        assertContentEquals(intArrayOf(), conflict.clause)
    }

    @Test
    fun `cancellation after a negative sweep withholds its conflict`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 1, realLower = doubleArrayOf(1.0), realUpper = doubleArrayOf(0.0), factors = emptyArray())
        val system = assertNotNull(RealDifferenceSystem.prepare(model, emptyList()))
        var polls = 0

        val result = system.check(intArrayOf(), Cancellation { ++polls > 1 })

        assertIs<RealDifferenceSystem.Result.Interrupted>(result)
        assertIs<RealDifferenceSystem.Result.Infeasible>(system.check(intArrayOf(), Cancellation.Never))
    }

    @Test
    fun `unsupported bounds decline without rounding`() {
        for (bound in listOf(Double.MIN_VALUE, 1.0e18, Double.MAX_VALUE)) {
            val model = Problem(
                0,
                intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
                numRealVars = 1,
                realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
                realUpper = doubleArrayOf(bound),
                factors = emptyArray(),
            )

            val system = RealDifferenceSystem.prepare(model, emptyList())

            assertNull(system)
        }
    }

    @Test
    fun `Boolean arithmetic retains the exact LP route`() {
        val model = Problem(1, intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
            factors = arrayOf(PseudoBoolean(longArrayOf(2), intArrayOf(Lit.make(0, true)), PbOp.GE, 1L)))

        val system = RealDifferenceSystem.prepare(model,
            model.factors.map { factor -> factor.linearRows.map { it.exactForm(1) } })

        assertNull(system)
    }

    @Test
    fun `shared Boolean factors leave difference arithmetic admissible`() {
        for (factor in listOf(Clause(intArrayOf(Lit.make(0, true))),
            Cardinality(intArrayOf(Lit.make(0, true)), 1, 1))) {
            val model = Problem(1, intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
                numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
                factors = arrayOf(factor))

            val system = RealDifferenceSystem.prepare(model,
                model.factors.map { source -> source.linearRows.map { it.exactForm(1) } })

            assertNotNull(system)
        }
    }

    @Test
    fun `cancelled graph checks publish no result`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 1, realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0), factors = emptyArray())
        val system = assertNotNull(RealDifferenceSystem.prepare(model, emptyList()))

        val result = system.check(intArrayOf(), Cancellation { true })

        assertIs<RealDifferenceSystem.Result.Interrupted>(result)
    }
}
