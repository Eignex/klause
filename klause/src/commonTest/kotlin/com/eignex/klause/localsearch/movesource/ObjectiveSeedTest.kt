package com.eignex.klause.localsearch.movesource

import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.BoolFoldDefinition
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
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
    fun `functional Boolean objective leaves should generate source moves under pins`() {
        val problem = Problem(2, 0, emptyArray(), emptyArray())
        val sweep = assertNotNull(
            DefinitionalSweep.infer(
                problem,
                boolFolds = listOf(
                    BoolFoldDefinition(1, intArrayOf(Lit.make(0, false)), true),
                ),
            ),
        )
        val objective = assertNotNull(
            sweep.functionalObjective(
                intArrayOf(),
                longArrayOf(),
                0,
                true,
                intArrayOf(1),
                longArrayOf(3),
            ),
        )
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

    @Test
    fun `functional integer objective leaves another factor owns should generate no moves`() {
        val problem = Problem(
            1,
            3,
            arrayOf(IntDomain(0, 2), IntDomain(3, 3), IntDomain(0, 6)),
            arrayOf<Factor>(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1), Product(0, 1, 2)),
        )
        val sweep = assertNotNull(DefinitionalSweep.infer(problem))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.invariants = sweep.network(3, 1)
        state.assignment.setInt(1, 3)
        sweep.sweep(state.assignment, state.rootDomains)
        state.recompute()
        state.shaping.objective = assertNotNull(sweep.functionalObjective(intArrayOf(2), longArrayOf(1), 0, true))
        val proposals = MoveSink().also {
            it.setInvariants(state.invariants)
            it.setOwners(intArrayOf(1, -1, -1))
        }

        ObjectiveSeed().generate(state, proposals)

        assertEquals(emptyList<Move>(), proposals.list)
    }
}
