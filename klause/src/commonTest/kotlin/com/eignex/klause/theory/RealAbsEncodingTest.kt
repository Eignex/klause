package com.eignex.klause.theory

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.GridAbs
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.ExactNeighborhoodCondition
import com.eignex.klause.solver.pipeline.ExactNeighborhoodResult
import com.eignex.klause.solver.pipeline.IntVariableOwner
import com.eignex.klause.solver.pipeline.OpenExactNeighborhood
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.pipeline.algebraicRealValue
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.theory.qfnra.PolynomialFormula
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RealAbsEncodingTest {
    @Test
    fun `polynomial grid participation preserves the complete finite image`() {
        val relation = GridAbs(0, 1, -1.0, 1.0, 5, 0.0, 0.8, 5)
        val source = Problem(0, 2, Array(2) { IntDomain(0, 4) }, arrayOf<Factor>(
            relation, RealProduct(0, 0, 1, 0.0, 0.0),
        ), numRealVars = 2, realLower = doubleArrayOf(0.0, 0.0), realUpper = doubleArrayOf(0.0, 0.0))
        val model = assertNotNull(source.prepareExecutionPlan().polynomialModel)
        assertEquals(0, model.encodingAttributions.first().sourceFactorIndex)
        for (input in 0L..4L) for (output in 0L..4L) {
            val arithmetic = PolynomialArithmetic()
            val values = longArrayOf(input, output)
            val fixed = mutableMapOf(0 to BigFraction.ZERO, 1 to BigFraction.ZERO)
            for ((id, column) in model.integerColumns) fixed[column] = BigFraction.ofLong(values[id])
            val normalized = PolynomialFormula.normalize(model.service.formula, arithmetic, fixed)
            val signs = normalized.atoms.map { atom ->
                assertTrue(atom.polynomial.variables.isEmpty())
                val sign = (atom.polynomial.terms.singleOrNull()?.coefficient ?: BigFraction.ZERO)
                    .compareTo(BigFraction.ZERO)
                if (atom.holds(sign)) PolynomialFormula.TRUE else PolynomialFormula.FALSE
            }.toIntArray()

            val truth = normalized.truth(intArrayOf(), signs, arithmetic)

            assertEquals(if (output == relation.image[input.toInt()].toLong()) PolynomialFormula.TRUE
                else PolynomialFormula.FALSE, truth)
        }
    }

    @Test
    fun `polynomial completion preserves exact algebraic absolute values`() {
        val source = Problem(0, 0, emptyArray(), arrayOf<Factor>(
            ContinuousProduct(0, 0, 2), RealAbs(0, 1),
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, -1.0),
        ), numRealVars = 3, realLower = doubleArrayOf(-2.0, 0.0, 2.0),
            realUpper = doubleArrayOf(0.0, 2.0, 2.0))

        val result = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(source).solve())

        val arithmetic = AlgebraicArithmetic()
        val operand = assertNotNull(result.assignment.algebraicRealValue(0))
        val magnitude = assertNotNull(result.assignment.algebraicRealValue(1))
        assertEquals(0, arithmetic.compare(arithmetic.negate(operand), magnitude))
        assertTrue(arithmetic.compare(magnitude, arithmetic.rational(BigFraction.ONE)) > 0)
    }

    @Test
    fun `real absolute value preserves finite integer image ownership`() {
        val parsed = parseFlatZinc(
            """var -2..2: n; var float: x; var float: y;
                constraint int2float(n,x); constraint float_abs(x,y);
                constraint float_eq(y,1.0); solve satisfy;""",
            exactFloats = true,
        )
        val source = parsed.problem
        val plan = source.prepareExecutionPlan()
        val integer = parsed.intVarsByName.getValue("n")
        val relation = source.factors.filterIsInstance<RealAbs>().single()

        val result = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(source).solve())

        assertEquals(IntVariableOwner.CP, assertNotNull(plan.componentPlan).intOwner(integer))
        assertTrue(result.assignment.intValue(integer) in setOf("-1", "1"))
        assertEquals("1", result.assignment.realValue(relation.result))
    }

    @Test
    fun `exact FlatZinc absolute value retains the native relation at a rational point`() {
        val parsed = parseFlatZinc(
            """var -2.0..2.0: x; var 0.0..2.0: y;
                constraint float_abs(x,y); constraint float_lin_eq([3.0],[x],-1.0); solve satisfy;""",
            exactFloats = true,
        )
        val relation = parsed.problem.factors.filterIsInstance<RealAbs>().single()

        val result = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(parsed.problem).solve())

        assertEquals("-1/3", result.assignment.realValue(relation.operand))
        assertEquals("1/3", result.assignment.realValue(relation.result))
    }

    @Test
    fun `exact real absolute value rejects a point permitted by its relaxation`() {
        val parsed = parseFlatZinc(
            """var -2.0..2.0: x; var 0.0..2.0: y; constraint float_abs(x,y);
                constraint float_eq(x,0.0); constraint float_eq(y,1.0); solve satisfy;""",
            exactFloats = true,
        )
        assertIs<LinearForm.Relaxation>(parsed.problem.factors.filterIsInstance<RealAbs>().single().linearForm)

        val result = BacktrackSolver.source(parsed.problem).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `real absolute value aliases exclude negative source coordinates`() {
        val parsed = parseFlatZinc(
            """var -2.0..2.0: x; constraint float_abs(x,x);
                constraint float_lt(x,0.0); solve satisfy;""",
            exactFloats = true,
        )

        val result = BacktrackSolver.source(parsed.problem).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `real absolute value retracts a contradictory reified source condition`() {
        val parsed = parseFlatZinc(
            """var bool: zero; var -2.0..2.0: x; var 0.0..2.0: y;
                constraint float_abs(x,y); constraint float_eq(y,1.0);
                constraint float_eq_reif(x,0.0,zero); solve satisfy;""",
            exactFloats = true,
        )
        val source = parsed.problem
        val relation = source.factors.filterIsInstance<RealAbs>().single()
        val boolean = parsed.boolVarsByName.getValue("zero")
        val queries = OpenExactNeighborhood(source, TheoryParams(), preparedSource = source.prepareExecutionPlan())
        queries.use {
            val refuted = ExactNeighborhoodCondition(mapOf(boolean to true), emptyMap(), emptyMap())
            val feasible = ExactNeighborhoodCondition(mapOf(boolean to false), emptyMap(), emptyMap())
            assertIs<ExactNeighborhoodResult.Refuted>(it.check(refuted, Cancellation.Never))
            assertIs<ExactNeighborhoodResult.Undecided>(it.check(feasible, Cancellation { true }))

            val sample = assertIs<ExactNeighborhoodResult.Witness>(it.check(feasible, Cancellation.Never)).sample

            val values = assertNotNull(sample.exactReals)
            assertEquals(BigFraction.ONE, values[relation.result])
            assertTrue(values[relation.operand] in listOf(BigFraction.ONE, BigFraction.MINUS_ONE))
            assertEquals(source.numRealVars, values.size)
        }
    }
}
