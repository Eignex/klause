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
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealDifferenceSystemTest {
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

        val point = assertIs<RealDifferenceSystem.Result.Feasible>(system.check(intArrayOf(), Cancellation.Never)).point

        assertTrue(point.single() > BigFraction.ZERO)
        assertTrue(point.single() < BigFraction.ONE)
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

            assertIs<RealDifferenceSystem.Result.Infeasible>(result)
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
        assertIs<RealDifferenceSystem.Result.Infeasible>(system.check(intArrayOf(1), Cancellation.Never))

        val released = system.check(intArrayOf(-1), Cancellation.Never)
        val sibling = system.check(intArrayOf(0), Cancellation.Never)

        assertIs<RealDifferenceSystem.Result.Feasible>(released)
        assertIs<RealDifferenceSystem.Result.Feasible>(sibling)
    }

    @Test
    fun `unsupported bounds decline without rounding`() {
        for (bound in listOf(0.5, Double.MAX_VALUE)) {
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
