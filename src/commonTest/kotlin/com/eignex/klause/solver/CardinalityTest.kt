package com.eignex.klause.solver

import com.eignex.klause.solver.factor.Cardinality
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardinalityTest {

    @Test
    fun atMostOneViolatedWithTwoTrue() {
        val amo = Cardinality.atMostOne(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)))
        val problem = Problem(3, 0, emptyArray(), listOf(amo))
        val state = SolverState(problem, Random(0))
        state.assignment.setBool(0, true); state.assignment.setBool(1, true)
        state.recompute()
        assertTrue(amo.isViolated(state, 0))
        assertEquals(2, state.intPayload[0])
    }

    @Test
    fun exactlyOneTransitions() {
        val one = Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)))
        val problem = Problem(3, 0, emptyArray(), listOf(one))
        val state = SolverState(problem, Random(0))
        state.assignment.setBool(0, true)
        state.recompute()
        assertFalse(one.isViolated(state, 0))
        val deltaPredicted = one.deltaIfBoolFlipped(state, 0, 1)
        assertEquals(1, deltaPredicted)
        state.apply(Move.BoolFlip(1))
        assertTrue(one.isViolated(state, 0))
        assertEquals(1, state.hardCost)
    }
}
