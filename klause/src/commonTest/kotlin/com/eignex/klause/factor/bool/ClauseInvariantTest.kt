package com.eignex.klause.factor.bool

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClauseInvariantTest {

    private fun stateFor(numBoolVars: Int, factor: Factor): LocalSearchState {
        val problem = Problem(numBoolVars, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem.bake(), Random(0))
        state.recompute()
        return state
    }

    @Test
    fun `flip deltas match clause truth for dense and sparse variable ids`() {
        for (arity in listOf(4, 5)) {
            for (spacing in listOf(1, 17)) {
                val literals = IntArray(arity) { Lit.make(it * spacing, it % 2 == 0) }
                val numVars = arity * spacing + 1
                val state = stateFor(numVars, Clause(literals))
                for (v in 0 until numVars) state.assignment.setBool(v, false)
                for (literal in literals) state.assignment.setBool(Lit.variable(literal), !Lit.isPositive(literal))
                state.recompute()

                for (i in 0..arity) {
                    val variable = i * spacing
                    repeat(2) {
                        val before = if (naiveIsViolated(literals, state)) 1 else 0
                        val predicted = state.factors[0].deltaIfBoolFlipped(state, 0, variable)

                        state.apply(Move.BoolFlip(variable))

                        val after = if (naiveIsViolated(literals, state)) 1 else 0
                        assertEquals(after - before, predicted, "arity=$arity spacing=$spacing variable=$variable")
                    }
                }
            }
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
