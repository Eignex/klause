package com.eignex.klause.solver.localsearch.movesource

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Behaviour tests for the Feasibility-Jump candidate source [ArgminJump]: a jump must move a
 * hot-spot variable to the value that minimizes its weighted violation, and so must never increase
 * weighted violation.
 */
class ArgminJumpTest {

    /** A single `x0 = 3` constraint over 0..5: from the all-zero start the unique argmin jump is
     *  `x0 ← 3` (which zeroes the constraint). */
    private fun eqProblem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 1,
        intDomains = arrayOf(IntDomain(0, 5)),
        factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 3)),
    )

    /** Two coupled sum constraints so a hot-spot jump has a non-trivial weighted argmin. */
    private fun coupledProblem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
        factors = arrayOf<Factor>(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 6),
            Linear(intArrayOf(2, 1), intArrayOf(0, 1), LinearOp.EQ, 8),
        ),
    )

    private fun jumps(state: LocalSearchState, candidateVars: Int): List<Move> {
        val sink = MoveSink(state.assumptions)
        sink.setInvariants(state.invariants)
        sink.clear()
        ArgminJump(candidateVars).generate(state, sink)
        return sink.list
    }

    @Test
    fun `jumps to the violation-minimizing value`() {
        val state = freshState(eqProblem(), 7L)
        assertTrue(state.cost > 0L, "all-zero start violates x0 = 3")
        val moves = jumps(state, candidateVars = 1)
        assertTrue(
            moves.contains(Move.IntSet(0, 3)),
            "the argmin jump must set x0 to 3 (got $moves)",
        )
    }

    @Test
    fun `emitted jumps never increase weighted violation`() {
        for (seed in longArrayOf(1L, 7L, 42L, 1234L)) {
            val state = freshState(coupledProblem(), seed)
            for (m in jumps(state, candidateVars = 4)) {
                assertTrue(
                    state.weightedNetDelta(m) <= 0.0,
                    "ArgminJump emitted a violation-increasing move $m (Δ=${state.weightedNetDelta(m)})",
                )
            }
        }
    }
}
