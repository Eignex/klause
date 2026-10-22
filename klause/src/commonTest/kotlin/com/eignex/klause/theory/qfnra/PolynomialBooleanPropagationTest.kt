package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.FALSE
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.TRUE
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.UNASSIGNED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolynomialBooleanPropagationTest {
    @Test
    fun `the last unassigned child is forced by either junction polarity`() {
        for ((conjunction, skipConfirmedParents) in listOf(
            true to false, true to true, false to false, false to true,
        )) {
            val nodes = listOf(
                FormulaNode.BooleanVariable(0), FormulaNode.BooleanVariable(1), FormulaNode.BooleanVariable(2),
                FormulaNode.Junction(intArrayOf(0, 1, 2), conjunction), FormulaNode.Not(3),
            )
            val state = PolynomialBooleanPropagation(nodes, if (conjunction) 4 else 3,
                PolynomialArithmetic(), skipConfirmedParents)
            assertTrue(state.initialize())
            assertTrue(state.propagate())
            val identity = if (conjunction) TRUE else FALSE
            assertTrue(state.assign(0, identity))
            assertTrue(state.propagate())
            assertTrue(state.assign(1, identity))

            assertTrue(state.propagate())

            assertEquals(TRUE - identity, state.value(2))
        }
    }

    @Test
    fun `backtracking restores shared junction children before the opposite branch`() {
        for (skipConfirmedParents in listOf(false, true)) {
            val nodes = listOf(
                FormulaNode.BooleanVariable(0), FormulaNode.BooleanVariable(1), FormulaNode.BooleanVariable(2),
                FormulaNode.Not(0), FormulaNode.Junction(intArrayOf(0, 1), false),
                FormulaNode.Junction(intArrayOf(3, 2), false), FormulaNode.Junction(intArrayOf(4, 5), true),
            )
            val state = PolynomialBooleanPropagation(nodes, 6, PolynomialArithmetic(), skipConfirmedParents)
            assertTrue(state.initialize())
            assertTrue(state.propagate())
            assertTrue(state.decide())
            assertTrue(state.propagate())
            assertEquals(TRUE, state.value(1))
            assertEquals(UNASSIGNED, state.value(2))

            assertTrue(state.backtrack())
            assertTrue(state.propagate())

            assertEquals(listOf(TRUE, UNASSIGNED, TRUE), (0..2).map(state::value))
        }
    }

    @Test
    fun `duplicate child edges retain the satisfiable opposite branch`() {
        for (children in listOf(intArrayOf(0, 0, 1), intArrayOf(1, 0, 0))) {
            val nodes = listOf(
                FormulaNode.BooleanVariable(0), FormulaNode.BooleanVariable(1),
                FormulaNode.Junction(children, false),
            )
            val state = PolynomialBooleanPropagation(nodes, 2, PolynomialArithmetic(), skipConfirmedParents = true)
            assertTrue(state.initialize())
            assertTrue(state.propagate())
            assertTrue(state.assign(1, FALSE))
            assertTrue(state.propagate())
            assertTrue(state.decide())
            assertFalse(state.propagate())

            assertTrue(state.backtrack())
            assertTrue(state.propagate())

            assertEquals(listOf(TRUE, FALSE), (0..1).map(state::value))
        }
    }

    @Test
    fun `wide disjunction propagation retains the last literal within caller work`() {
        val children = IntArray(512) { it }
        val nodes: List<FormulaNode> = children.map { FormulaNode.BooleanVariable(it) } +
            FormulaNode.Junction(children, false)
        val work = QfNraWork(QfNraParams(maximumWork = 20_000))
        val state = PolynomialBooleanPropagation(nodes, 512, PolynomialArithmetic(work), skipConfirmedParents = true)
        assertTrue(state.initialize())
        assertTrue(state.propagate())

        for (child in 0 until 511) {
            assertTrue(state.assign(child, FALSE))
            assertTrue(state.propagate())
        }

        assertEquals(TRUE, state.value(511))
    }

    @Test
    fun `empty junctions retain their identities`() {
        for (conjunction in listOf(false, true)) {
            val state = PolynomialBooleanPropagation(listOf(FormulaNode.Junction(intArrayOf(), conjunction)), 0,
                PolynomialArithmetic())
            assertTrue(state.initialize())

            val consistent = state.propagate()

            assertEquals(conjunction, consistent)
        }
    }
}
