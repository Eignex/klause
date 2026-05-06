package com.eignex.klause.solver.factor

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolverState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class OccurrenceDedupTest {

    @Test
    fun cardinalityWithSameVarTwiceDedupsOccurrenceList() {
        // Cardinality literals = [+a, -a, +b]; var `a` appears twice but should be registered
        // once in the occurrence list, so applyBoolFlip is invoked once per real flip.
        val a = 0; val b = 1
        val factor = Cardinality(
            literals = intArrayOf(Lit.make(a, true), Lit.make(a, false), Lit.make(b, true)),
            min = 1, max = 2,
        )
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val occA = problem.boolOccurrences[a]
        assertEquals(1, occA.size, "var a registered ${occA.size} times in occurrence list (expected 1)")
        // Sanity: hardCost stays consistent with the brute-force violation check.
        val state = SolverState(problem, Random(7))
        state.recompute()
        val brute = if (factor.isViolated(state, 0)) 1 else 0
        assertEquals(brute, state.hardCost)
        state.apply(com.eignex.klause.solver.Move.BoolFlip(a))
        val brute2 = if (factor.isViolated(state, 0)) 1 else 0
        assertEquals(brute2, state.hardCost, "hardCost drifted from brute-force after flipping a")
    }

    @Test
    fun clauseWithSameVarTwiceDedupsOccurrenceList() {
        val a = 0
        val factor = Clause(literals = intArrayOf(Lit.make(a, true), Lit.make(a, false)))
        val problem = Problem(1, 0, emptyArray(), listOf(factor))
        assertEquals(1, problem.boolOccurrences[a].size)
    }

    @Test
    fun pseudoBooleanWithSameVarTwiceDedupsOccurrenceList() {
        val a = 0
        val factor = PseudoBoolean(
            weights = intArrayOf(2, 3),
            literals = intArrayOf(Lit.make(a, true), Lit.make(a, false)),
            op = com.eignex.klause.ast.PbOp.LE,
            bound = 4,
        )
        val problem = Problem(1, 0, emptyArray(), listOf(factor))
        assertEquals(1, problem.boolOccurrences[a].size)
    }
}
