package com.eignex.klause.localsearch.movesource

import com.eignex.klause.ir.BoolFoldDefinition
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.propagation.Assumptions
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ObjectiveSeedTest {
    @Test
    fun `functional Boolean objective leaves generate source moves under pins`() {
        val problem = Problem(2, 0, emptyArray(), emptyArray())
        val sweep = assertNotNull(DefinitionalSweep.infer(problem, boolFolds = listOf(
            BoolFoldDefinition(1, intArrayOf(Lit.make(0, false)), true),
        )))
        val objective = assertNotNull(sweep.functionalObjective(
            intArrayOf(), longArrayOf(), 0, true, intArrayOf(1), longArrayOf(3),
        ))
        for (pinned in listOf(false, true)) {
            val assumptions = if (pinned) Assumptions(bools = mapOf(0 to false)) else Assumptions.None
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3), assumptions)
            state.invariants = sweep.network(0, 2)
            sweep.sweep(state.assignment, state.rootDomains)
            state.recompute()
            state.shaping.objective = objective
            val proposals = MoveSink(assumptions).also { it.setInvariants(state.invariants) }

            ObjectiveSeed().generate(state, proposals)

            assertEquals(if (pinned) emptyList() else listOf(Move.BoolFlip(0)), proposals.list)
        }
    }
}
