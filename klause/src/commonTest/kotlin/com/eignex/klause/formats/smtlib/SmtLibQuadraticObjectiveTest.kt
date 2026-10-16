package com.eignex.klause.formats.smtlib

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.evaluateExact
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.NonlinearIntegerTheoryParams
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.PolynomialTheoryParams
import com.eignex.klause.solver.pipeline.minimize
import com.eignex.klause.solver.pipeline.pipelineRequest
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.times
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SmtLibQuadraticObjectiveTest {
    @Test
    fun `a quadratic macro objective should retain definition scope under caller shadowing`() {
        val input = (
            SmtLib.parse(
                "(declare-const x Real) (define-fun f ((y Real)) Real (* x y)) " +
                    "(minimize (let ((x 99)) (f 3)))",
            ))
        val sample = Sample(
            booleanArrayOf(),
            longArrayOf(),
            doubleArrayOf(2.0),
            exactReals = listOf(BigFraction.ofLong(2)),
        )

        val value = assertNotNull(input.quadraticObjective).evaluateExact(sample, AlgebraicArithmetic())

        assertEquals(BigFraction.ofLong(6), value.rational)
    }

    @Test
    fun `quadratic rational coefficients should retain integers beyond binary64 precision`() {
        val input = (
            SmtLib.parse(
                "(declare-const x Real) (minimize (* (/ 9007199254740993 7) x x))",
            ))
        val sample = Sample(
            booleanArrayOf(),
            longArrayOf(),
            doubleArrayOf(7.0),
            exactReals = listOf(BigFraction.ofLong(7)),
        )

        val value = assertNotNull(input.quadraticObjective).evaluateExact(sample, AlgebraicArithmetic())

        assertEquals(BigFraction.of(parseBigInt("9007199254740993") * bigIntOf(7), BIG_ONE), value.rational)
    }

    @Test
    fun `a quadratic objective should separate interleaved integer real and Boolean source identities`() {
        val input = (
            SmtLib.parse(
                "(declare-const x Real) (declare-const i Int) (declare-const b Bool) " +
                    "(minimize (+ (* (to_real i) x) (* (ite b 1 0) i)))",
            ))
        val sample = Sample(
            booleanArrayOf(true),
            longArrayOf(3),
            doubleArrayOf(2.0),
            exactReals = listOf(BigFraction.ofLong(2)),
        )

        val value = assertNotNull(input.quadraticObjective).evaluateExact(sample, AlgebraicArithmetic())

        assertIs<NonlinearIntegerProblem>((input.source).originalSource)
        assertEquals(BigFraction.ofLong(9), value.rational)
    }

    @Test
    fun `numeric Boolean selection should preserve a quadratic real objective`() {
        val input = (
            SmtLib.parse(
                "(declare-const x Real) (declare-const b Bool) " +
                    "(minimize (+ (* (ite b x 0) 2) (* x x)))",
            ))
        val sample = Sample(
            booleanArrayOf(true),
            longArrayOf(),
            doubleArrayOf(-1.0),
            exactReals = listOf(BigFraction.MINUS_ONE),
        )

        val value = assertNotNull(input.quadraticObjective).evaluateExact(sample, AlgebraicArithmetic())

        assertEquals(BigFraction.MINUS_ONE, value.rational)
    }

    @Test
    fun `nested numeric Boolean selectors should expand into original source products`() {
        val input = (
            SmtLib.parse(
                "(declare-const b Bool) (declare-const c Bool) (minimize (^ (ite b (ite c 2 0) 0) 1))",
            ))

        val value = assertNotNull(input.quadraticObjective).evaluateExact(
            Sample(booleanArrayOf(true, true), longArrayOf()),
            AlgebraicArithmetic(),
        )

        assertEquals(BigFraction.ofLong(2), value.rational)
    }

    @Test
    fun `a captured Boolean mixed quadratic objective should reach a checked global source optimum`() {
        val input = (
            SmtLib.parse(
                "(declare-const x Real) (declare-const i Int) (declare-const b Bool) " +
                    "(minimize (+ (* i i) (^ (- x (ite b 1 0)) 2)))",
            ))
        val source = assertIs<NonlinearIntegerProblem>((input.source).originalSource)

        val execution = OpenTheoryPipeline.minimize(
            source.pipelineRequest(),
            assertNotNull(input.quadraticObjective),
            NonlinearIntegerTheoryParams(polynomial = PolynomialTheoryParams(maximumWork = 100000)),
            FiniteEngine.BACKTRACK,
            input.sense,
        )

        val result = assertIs<QuadraticOptimizationResult.Optimal>(execution.result)
        assertEquals(BigFraction.ZERO, result.incumbent.value.rational)
        assertEquals(BigFraction.ZERO, result.bound.value.rational)
    }

    @Test
    fun `quadratic maximization should retain its original source direction and constant`() {
        val input = (
            SmtLib.parse(
                "(declare-const x Real) (maximize (- 5 (* x x)))",
            ))

        val value = assertNotNull(input.quadraticObjective).evaluateExact(
            Sample(
                booleanArrayOf(),
                longArrayOf(),
                doubleArrayOf(0.0),
                exactReals = listOf(BigFraction.ZERO),
            ),
            AlgebraicArithmetic(),
        )

        assertEquals(ObjectiveSense.MAXIMIZE, input.sense)
        assertEquals(BigFraction.ofLong(5), value.rational)
    }

    @Test
    fun `a cubic source objective should be refused rather than truncated`() {
        assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(declare-const x Real) (minimize (* x x x))")
        }
    }

    @Test
    fun `multiple quadratic objectives should be refused rather than selecting one`() {
        assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(declare-const x Real) (minimize (* x x)) (maximize (* x x))")
        }
    }

    @Test
    fun `a quadratic objective should not capture a later declaration`() {
        assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(minimize (* x x)) (declare-const x Real)")
        }
    }

    @Test
    fun `an ordinary bounded integer linear objective should retain linear admission`() {
        val input = SmtLib.parse(
            "(declare-const i Int) (assert (and (>= i 0) (<= i 2))) (minimize i)",
        )

        assertIs<Problem>((input).source.originalSource)
    }
}
