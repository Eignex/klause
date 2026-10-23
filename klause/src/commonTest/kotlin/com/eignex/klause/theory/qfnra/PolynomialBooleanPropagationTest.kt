package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolynomialBooleanPropagationTest {
    @Test
    fun `empty junction identities propagate inside an optional branch`() {
        for (skipConfirmedParents in listOf(false, true)) {
            for (conjunction in listOf(false, true)) {
                val nodes = listOf(
                    FormulaNode.Junction(intArrayOf(), conjunction),
                    FormulaNode.Not(0),
                    FormulaNode.BooleanVariable(0),
                    FormulaNode.Junction(intArrayOf(if (conjunction) 0 else 1, 2), false),
                )
                val state = PolynomialBooleanPropagation(nodes, 3, PolynomialArithmetic(), skipConfirmedParents)

                assertTrue(state.initialize())
                assertTrue(state.propagate())

                assertEquals(if (conjunction) PolynomialFormula.TRUE else PolynomialFormula.FALSE, state.value(0))
                assertEquals(PolynomialFormula.UNASSIGNED, state.value(2))
            }
        }
    }

    @Test
    fun `an empty disjunction cannot be required true`() {
        val nodes = listOf(FormulaNode.Junction(intArrayOf(), false))
        val state = PolynomialBooleanPropagation(nodes, 0, PolynomialArithmetic())

        assertFalse(state.initialize())
    }

    @Test
    fun `constant truth contradicts its required negation`() {
        for (skipConfirmedParents in listOf(false, true)) {
            val nodes = listOf(FormulaNode.Constant(true), FormulaNode.Not(0))
            val state = PolynomialBooleanPropagation(nodes, 1, PolynomialArithmetic(), skipConfirmedParents)
            assertTrue(state.initialize())

            val consistent = state.propagate()

            assertFalse(consistent)
        }
    }

    @Test
    fun `shared leaves carry implications through multiple parents`() {
        for (skipConfirmedParents in listOf(false, true)) {
            val nodes = listOf(
                FormulaNode.BooleanVariable(0),
                FormulaNode.Not(0),
                FormulaNode.BooleanVariable(1),
                FormulaNode.Junction(intArrayOf(0, 2), false),
                FormulaNode.Junction(intArrayOf(1, 3), true),
            )
            val state = PolynomialBooleanPropagation(nodes, 4, PolynomialArithmetic(), skipConfirmedParents)

            assertTrue(state.initialize())
            assertTrue(state.propagate())

            assertEquals(PolynomialFormula.FALSE, state.value(0))
            assertEquals(PolynomialFormula.TRUE, state.value(2))
        }
    }

    @Test
    fun `backtracking retracts consequences before the next branch`() {
        for (skipConfirmedParents in listOf(false, true)) {
            val nodes = listOf(
                FormulaNode.BooleanVariable(0),
                FormulaNode.BooleanVariable(1),
                FormulaNode.Junction(intArrayOf(0, 1), false),
            )
            val state = PolynomialBooleanPropagation(nodes, 2, PolynomialArithmetic(), skipConfirmedParents)
            assertTrue(state.initialize())
            assertTrue(state.propagate())
            assertTrue(state.decide())
            assertTrue(state.propagate())
            assertEquals(PolynomialFormula.TRUE, state.value(1))

            assertTrue(state.backtrack())
            assertTrue(state.propagate())

            assertEquals(PolynomialFormula.TRUE, state.value(0))
            assertEquals(PolynomialFormula.UNASSIGNED, state.value(1))
        }
    }

    @Test
    fun `initialization observes a newly cancelled arithmetic context`() {
        var stopped = false
        val state = PolynomialBooleanPropagation(
            listOf(FormulaNode.BooleanVariable(0)), 0, PolynomialArithmetic(Cancellation { stopped }),
        )
        stopped = true

        assertFailsWith<PolynomialInterrupted> { state.initialize() }
    }

    @Test
    fun `initialization stops at its remaining work allowance`() {
        val work = QfNraWork(QfNraParams(maximumWork = 3))
        val nodes = listOf(FormulaNode.BooleanVariable(0), FormulaNode.Not(0))
        val state = PolynomialBooleanPropagation(nodes, 1, PolynomialArithmetic(work))

        assertFailsWith<PolynomialInterrupted> { state.initialize() }
    }
}
