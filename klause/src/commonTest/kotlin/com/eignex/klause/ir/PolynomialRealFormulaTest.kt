package com.eignex.klause.ir

import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PolynomialRealFormulaTest {
    @Test
    fun `separately built deep equal structures compare without call stack recursion`() {
        var first: PolynomialRealFormula = PolynomialRealFormula.BooleanVariable(SourceBoolId(0))
        var second: PolynomialRealFormula = PolynomialRealFormula.BooleanVariable(SourceBoolId(0))
        repeat(1000) {
            first = PolynomialRealFormula.And(listOf(first))
            second = PolynomialRealFormula.And(listOf(second))
        }

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `formula remaps preserve Boolean polarity and strict real relations`() {
        val input = PolynomialRealFormula.Or(
            listOf(
                PolynomialRealFormula.Not(PolynomialRealFormula.BooleanVariable(SourceBoolId(0))),
                PolynomialRealFormula.Atom(
                    PolynomialExpression.Variable(SourceRealId(0)), PolynomialRelation.LT,
                    PolynomialExpression.Constant.of(bigIntOf(-2)),
                ),
            ),
        )
        val mapping = VarRemap(intArrayOf(3), intArrayOf(), intArrayOf(5))

        val remapped = input.remap(mapping)

        assertEquals(
            PolynomialRealFormula.Or(
                listOf(
                    PolynomialRealFormula.Not(PolynomialRealFormula.BooleanVariable(SourceBoolId(3))),
                    PolynomialRealFormula.Atom(
                        PolynomialExpression.Variable(SourceRealId(5)), PolynomialRelation.LT,
                        PolynomialExpression.Constant.of(bigIntOf(-2)),
                    ),
                ),
            ),
            remapped,
        )
    }

    @Test
    fun `formula storage is independent of caller operand mutations`() {
        val variable = PolynomialRealFormula.BooleanVariable(SourceBoolId(0))
        val operands = mutableListOf<PolynomialRealFormula>(variable, variable)
        val conjunction = PolynomialRealFormula.And(operands)
        val disjunction = PolynomialRealFormula.Or(operands)

        operands[0] = PolynomialRealFormula.Constant(false)
        (conjunction.operands as? MutableList)?.set(0, operands[0])
        (disjunction.operands as? MutableList)?.set(0, operands[0])

        assertEquals(listOf(variable, variable), conjunction.operands)
        assertEquals(listOf(variable, variable), disjunction.operands)
    }

    @Test
    fun `removing a referenced polynomial Boolean is rejected`() {
        val input = PolynomialRealFormula.BooleanVariable(SourceBoolId(0))

        assertFailsWith<IllegalArgumentException> { input.remap(VarRemap(intArrayOf(-1), intArrayOf())) }
    }

    @Test
    fun `source variable count validation rejects out of range identities`() {
        val cases = listOf(
            PolynomialRealFormula.BooleanVariable(SourceBoolId(1)),
            PolynomialRealFormula.Atom(
                PolynomialExpression.Variable(SourceRealId(1)), PolynomialRelation.NE,
                PolynomialExpression.Constant.of(bigIntOf(0)),
            ),
        )
        for (formula in cases) {
            assertFailsWith<IllegalArgumentException> { formula.requireVariableCounts(1, 1) }
        }
    }
}
