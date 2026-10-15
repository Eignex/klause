package com.eignex.klause.localsearch.movesource

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SatisfiedStructuredTest {

    private fun electedProblem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 3,
        intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
        factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)),
    )

    private fun seedPermutation(state: LocalSearchState) {
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 2)
        state.recompute()
    }

    @Test
    fun `structural scope should reach an owned global among satisfied arithmetic rows`() {
        val global = electedProblem()
        val problem = Problem(
            0,
            3,
            global.finiteIntDomains(),
            global.factors + Array<Factor>(256) {
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2)
            },
        )
        val state = freshState(problem, 7L)
        seedPermutation(state)
        val sink = MoveSink()
        sink.setOwners(intArrayOf(0, 0, 0))

        SatisfiedStructured.structural(1).generate(state, sink)

        assertTrue(sink.list.isNotEmpty())
        for (move in sink.list) assertEquals(0L, state.netDelta(move))
        assertEquals(MoveSink.NO_PROPOSER, sink.proposer)
    }

    @Test
    fun `structural scope should skip a violated elected global`() {
        val state = freshState(electedProblem(), 7L)
        val sink = MoveSink()

        SatisfiedStructured.structural(1).generate(state, sink)

        assertTrue(sink.list.isEmpty())
    }
}
