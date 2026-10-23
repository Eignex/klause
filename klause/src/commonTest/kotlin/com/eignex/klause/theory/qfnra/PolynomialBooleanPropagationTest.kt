package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.FALSE
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.TRUE
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.UNASSIGNED
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolynomialBooleanPropagationTest {
    @Test
    fun `required junction identities force every child`() {
        for (conjunction in listOf(true, false)) {
            val nodes = listOf(
                FormulaNode.BooleanVariable(0),
                FormulaNode.BooleanVariable(1),
                FormulaNode.Junction(intArrayOf(0, 1), conjunction),
                FormulaNode.Not(2),
            )
            val state = PolynomialBooleanPropagation(nodes, if (conjunction) 2 else 3, PolynomialArithmetic())
            assertTrue(state.initialize())

            val consistent = state.propagate()

            assertTrue(consistent)
            val identity = if (conjunction) TRUE else FALSE
            assertEquals(identity, state.value(0))
            assertEquals(identity, state.value(1))
        }
    }

    @Test
    fun `duplicate and shared children retain required identity truth`() {
        for (conjunction in listOf(true, false)) {
            val nodes = listOf(
                FormulaNode.BooleanVariable(0),
                FormulaNode.BooleanVariable(1),
                FormulaNode.Junction(intArrayOf(0, 0, 1), conjunction),
                FormulaNode.Junction(intArrayOf(0, 2), conjunction),
                FormulaNode.Not(3),
            )
            val state = PolynomialBooleanPropagation(nodes, if (conjunction) 3 else 4, PolynomialArithmetic())
            assertTrue(state.initialize())

            val consistent = state.propagate()

            assertTrue(consistent)
            val identity = if (conjunction) TRUE else FALSE
            for (index in 0..3) assertEquals(identity, state.value(index))
        }
    }

    @Test
    fun `opposite child truth conflicts with a required junction identity`() {
        for (conjunction in listOf(true, false)) {
            val nodes = listOf(
                FormulaNode.BooleanVariable(0),
                FormulaNode.BooleanVariable(1),
                FormulaNode.Junction(intArrayOf(0, 1), conjunction),
                FormulaNode.Not(2),
            )
            val state = PolynomialBooleanPropagation(nodes, if (conjunction) 2 else 3, PolynomialArithmetic())
            assertTrue(state.initialize())
            assertTrue(state.assign(0, if (conjunction) FALSE else TRUE))

            val consistent = state.propagate()

            assertFalse(consistent)
        }
    }

    @Test
    fun `backtracking retracts a shared child's identity consequences`() {
        val nodes = listOf(
            FormulaNode.BooleanVariable(0),
            FormulaNode.BooleanVariable(1),
            FormulaNode.BooleanVariable(2),
            FormulaNode.Junction(intArrayOf(0, 1), true),
            FormulaNode.Junction(intArrayOf(0, 2), true),
            FormulaNode.Junction(intArrayOf(3, 4), false),
        )
        val state = PolynomialBooleanPropagation(nodes, 5, PolynomialArithmetic())
        assertTrue(state.initialize())
        assertTrue(state.propagate())
        assertTrue(state.decide())
        assertFalse(state.propagate())
        assertTrue(state.backtrack())
        assertTrue(state.propagate())
        assertTrue(state.decide())
        assertTrue(state.propagate())
        assertEquals(FALSE, state.value(1))
        assertEquals(TRUE, state.value(2))

        assertTrue(state.backtrack())
        val consistent = state.propagate()

        assertTrue(consistent)
        assertEquals(TRUE, state.value(0))
        assertEquals(TRUE, state.value(1))
        assertEquals(UNASSIGNED, state.value(2))
    }

    @Test
    fun `required child propagation observes cancellation after assigning a child`() {
        val nodes = listOf(
            FormulaNode.BooleanVariable(0),
            FormulaNode.BooleanVariable(1),
            FormulaNode.Junction(intArrayOf(0, 1), true),
        )
        var state: PolynomialBooleanPropagation? = null
        val arithmetic = PolynomialArithmetic(Cancellation { state?.value(0) == TRUE })
        val propagating = PolynomialBooleanPropagation(nodes, 2, arithmetic)
        state = propagating
        assertTrue(propagating.initialize())

        assertFailsWith<PolynomialInterrupted> { propagating.propagate() }
    }

    @Test
    fun `required child propagation stops at the containing work limit`() {
        val nodes = List(64) { FormulaNode.BooleanVariable(it) } +
            FormulaNode.Junction(IntArray(64) { it }, true)
        val work = QfNraWork(QfNraParams(maximumWork = 512))
        val state = PolynomialBooleanPropagation(nodes, 64, PolynomialArithmetic(work))
        assertTrue(state.initialize())

        assertFailsWith<PolynomialInterrupted> { state.propagate() }

        assertTrue(work.limitReached)
    }
}
