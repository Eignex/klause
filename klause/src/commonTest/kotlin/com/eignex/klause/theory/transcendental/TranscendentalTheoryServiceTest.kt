package com.eignex.klause.theory.transcendental

import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.TranscendentalExpression
import com.eignex.klause.ir.TranscendentalFormula
import com.eignex.klause.ir.TranscendentalFunction
import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TranscendentalTheoryServiceTest {
    @Test
    fun `inverse exponential equation publishes an exact logarithmic coordinate`() {
        val x = TranscendentalExpression.Variable(SourceRealId(0))
        val exp = TranscendentalExpression.Function(TranscendentalFunction.EXP, x)
        val source = TranscendentalProblem(
            atom(exp, PolynomialRelation.EQ, constant(2)), emptyMap(), mapOf("x" to 0), emptyMap(),
        )

        val result = TranscendentalTheoryService(source).check()

        val witness = assertIs<TranscendentalCheckResult.Witness>(result).value
        assertNull(witness.reals.single().algebraic)
    }

    @Test
    fun `exponential positivity refutes an unrestricted source`() {
        val x = TranscendentalExpression.Variable(SourceRealId(0))
        val exp = TranscendentalExpression.Function(TranscendentalFunction.EXP, x)
        val source = TranscendentalProblem(
            atom(exp, PolynomialRelation.LE, constant(0)), emptyMap(), mapOf("x" to 0), emptyMap(),
        )

        val result = TranscendentalTheoryService(source).check()

        assertEquals(TranscendentalCheckResult.Refuted, result)
    }

    @Test
    fun `a relaxed near equality cannot publish a source witness`() {
        val x = TranscendentalExpression.Variable(SourceRealId(0))
        val exp = TranscendentalExpression.Function(TranscendentalFunction.EXP, x)
        val source = TranscendentalProblem(atom(exp, PolynomialRelation.EQ, x), emptyMap(), mapOf("x" to 0), emptyMap())

        val result = TranscendentalTheoryService(source).check()

        assertEquals(TranscendentalCheckResult.Unknown, result)
    }

    @Test
    fun `mixed integer source keeps symbolic real coordinate authority`() {
        val integer = TranscendentalExpression.Variable(SourceRealId(0))
        val real = TranscendentalExpression.Variable(SourceRealId(1))
        val exp = TranscendentalExpression.Function(TranscendentalFunction.EXP, integer)
        val formula = TranscendentalFormula.And(listOf(
            atom(integer, PolynomialRelation.EQ, constant(1)), atom(real, PolynomialRelation.EQ, exp),
        ))
        val source = TranscendentalProblem(formula, mapOf("i" to 0), mapOf("y" to 1), emptyMap())

        val result = TranscendentalTheoryService(source).check()

        val witness = assertIs<TranscendentalCheckResult.Witness>(result).value
        assertEquals("1", witness.integers.single().toString())
        assertNull(witness.reals.single().algebraic)
    }

    @Test
    fun `conditioned refutation is restricted to its pinned neighborhood`() {
        val x = TranscendentalExpression.Variable(SourceRealId(0))
        val sine = TranscendentalExpression.Function(TranscendentalFunction.SIN, x)
        val source = TranscendentalProblem(
            atom(sine, PolynomialRelation.GT, constant(0)), emptyMap(), mapOf("x" to 0), emptyMap(),
        )
        val service = TranscendentalTheoryService(source)
        val pins = mapOf(0 to BigFraction.ZERO)

        val restricted = service.check(TranscendentalCondition(PolynomialCondition(lower = pins, upper = pins)))
        val unrestricted = service.check()

        assertEquals(TranscendentalCheckResult.Refuted, restricted)
        assertIs<TranscendentalCheckResult.Witness>(unrestricted)
    }

    @Test
    fun `cancellation cannot publish a partial original source witness`() {
        val source = TranscendentalProblem(TranscendentalFormula.Constant(true), emptyMap(), emptyMap(), emptyMap())

        val result = TranscendentalTheoryService(source, cancellation = Cancellation { true }).check()

        assertEquals(TranscendentalCheckResult.Unknown, result)
    }

    @Test
    fun `exponential ordering retains a complete monotonic refutation`() {
        val x = TranscendentalExpression.Variable(SourceRealId(0))
        val y = TranscendentalExpression.Variable(SourceRealId(1))
        val expX = TranscendentalExpression.Function(TranscendentalFunction.EXP, x)
        val expY = TranscendentalExpression.Function(TranscendentalFunction.EXP, y)
        val source = TranscendentalProblem(TranscendentalFormula.And(listOf(
            atom(x, PolynomialRelation.LE, y), atom(expX, PolynomialRelation.GT, expY),
        )), emptyMap(), mapOf("x" to 0, "y" to 1), emptyMap())

        val result = TranscendentalTheoryService(source).check()

        assertEquals(TranscendentalCheckResult.Refuted, result)
    }

    private fun atom(
        left: TranscendentalExpression,
        relation: PolynomialRelation,
        right: TranscendentalExpression,
    ): TranscendentalFormula.Atom = TranscendentalFormula.Atom(left, relation, right)

    private fun constant(value: Long): TranscendentalExpression.Constant {
        val fraction = BigFraction.ofLong(value)
        return TranscendentalExpression.Constant(PolynomialExpression.Constant.of(fraction.num, fraction.den))
    }
}
