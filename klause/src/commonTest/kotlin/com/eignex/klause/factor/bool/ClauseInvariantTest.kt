package com.eignex.klause.factor.bool

import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.VarRemap
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class ClauseInvariantTest {

    private fun stateFor(numBoolVars: Int, factor: Factor): LocalSearchState {
        val problem = Problem(numBoolVars, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        state.recompute()
        return state
    }

    @Test
    fun `violated when all literals false`() {
        val clause = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false)))
        val state = stateFor(2, clause)
        state.assignment.setBool(0, false)
        state.assignment.setBool(1, true)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `delta if flipped matches apply flip`() {
        val clause = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false), Lit.make(2, true)))
        val state = stateFor(3, clause)
        state.assignment.setBool(0, false)
        state.assignment.setBool(1, true)
        state.assignment.setBool(2, false)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))

        val predictedDelta = state.factors[0].deltaIfBoolFlipped(state, 0, 0)
        state.apply(Move.BoolFlip(0))
        assertEquals(-1, predictedDelta)
        assertFalse(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `flipping maintains violation status`() {
        val clause = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)))
        val state = stateFor(3, clause)
        state.assignment.setBool(0, true)
        state.assignment.setBool(1, true)
        state.assignment.setBool(2, false)
        state.recompute()
        assertFalse(state.factors[0].isViolated(state, 0))
        state.apply(Move.BoolFlip(0))
        assertFalse(state.factors[0].isViolated(state, 0))
        state.apply(Move.BoolFlip(1))
        assertTrue(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `violated clause proposes every var once`() {
        val a = 0
        val b = 1
        val c = 2
        val factor = Clause(intArrayOf(Lit.make(a, true), Lit.make(b, false), Lit.make(c, true)))
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setBool(a, false)
        state.assignment.setBool(b, true)
        state.assignment.setBool(c, false)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))

        val sink = MoveSink()
        state.factors[0].proposeRepairMoves(state, 0, sink)
        val proposed = sink.list.filterIsInstance<Move.BoolFlip>().map { it.varId }.toSet()
        assertEquals(setOf(a, b, c), proposed)
    }

    @Test
    fun `satisfied clause proposes nothing`() {
        val a = 0
        val b = 1
        val factor = Clause(intArrayOf(Lit.make(a, true), Lit.make(b, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setBool(a, true)
        state.assignment.setBool(b, false)
        state.recompute()
        assertTrue(!state.factors[0].isViolated(state, 0))
        val sink = MoveSink()
        state.factors[0].proposeRepairMoves(state, 0, sink)
        assertTrue(sink.list.isEmpty())
    }

    @Test
    fun `is violated agrees with brute force over every assignment`() {
        val literals = intArrayOf(
            Lit.make(0, true),
            Lit.make(1, false),
            Lit.make(2, true),
            Lit.make(3, false),
            Lit.make(4, true),
        )
        val clause = Clause(literals)
        val problem = Problem(5, 0, emptyArray(), listOf(clause))
        val state = LocalSearchState(problem, Random(0))
        for (mask in 0..31) {
            for (i in 0..4) state.assignment.setBool(i, (mask shr i) and 1 == 1)
            state.recompute()
            val expected = naiveIsViolated(literals, state)
            assertEquals(expected, state.factors[0].isViolated(state, 0), "mask=$mask")
        }
    }

    @Test
    fun `watched literals survive long flip sequence`() {
        val literals = intArrayOf(
            Lit.make(0, true),
            Lit.make(1, true),
            Lit.make(2, false),
            Lit.make(3, true),
            Lit.make(4, false),
            Lit.make(5, true),
        )
        val clause = Clause(literals)
        val problem = Problem(6, 0, emptyArray(), listOf(clause))
        val state = LocalSearchState(problem, Random(0))

        for (i in 0..5) state.assignment.setBool(i, false)
        state.recompute()

        val seq = intArrayOf(0, 0, 1, 2, 3, 0, 4, 5, 1, 2, 3, 4, 5, 0)
        for (v in seq) {
            state.apply(Move.BoolFlip(v))
            val expected = naiveIsViolated(literals, state)
            assertEquals(expected, state.factors[0].isViolated(state, 0), "after flip of $v")
        }
    }

    @Test
    fun `duplicate literals are folded before local search`() {
        val input = intArrayOf(Lit.make(0, true), Lit.make(0, true), Lit.make(1, false))
        val clause = Clause(input)
        input[0] = Lit.make(2, true)

        assertContentEquals(intArrayOf(Lit.make(0, true), Lit.make(1, false)), clause.literals)
        val state = stateFor(2, clause)
        state.assignment.setBool(0, false)
        state.assignment.setBool(1, true)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))

        state.apply(Move.BoolFlip(0))

        assertFalse(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `tautological clause is never violated after flips`() {
        val clause = Clause(intArrayOf(Lit.make(0, true), Lit.make(0, false), Lit.make(1, true)))
        val state = stateFor(2, clause)
        state.assignment.setBool(0, false)
        state.assignment.setBool(1, false)
        state.recompute()

        state.apply(Move.BoolFlip(0))
        state.apply(Move.BoolFlip(1))

        assertFalse(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `remapping folds duplicate clause literals`() {
        val clause = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))

        val remapped = clause.remap(VarRemap(intArrayOf(0, 0), intArrayOf())) as Clause

        assertContentEquals(intArrayOf(Lit.make(0, true)), remapped.literals)
    }

    private fun naiveIsViolated(literals: IntArray, state: LocalSearchState): Boolean {
        for (lit in literals) {
            if (Lit.evaluate(lit, state.assignment.boolValue(Lit.variable(lit)))) return false
        }
        return true
    }
}
