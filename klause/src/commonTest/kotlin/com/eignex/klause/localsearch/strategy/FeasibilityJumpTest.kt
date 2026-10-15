package com.eignex.klause.localsearch.strategy

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.BoolFoldDefinition
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FeasibilityJumpTest {

    @Test
    fun `stall kicks should preserve maintained Boolean outputs`() {
        for (defined in listOf(false, true)) {
            val problem = Problem(
                1,
                0,
                emptyArray(),
                arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, false)))),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            if (defined) {
                val folds = listOf(BoolFoldDefinition(0, intArrayOf(), true))
                state.invariants = assertNotNull(DefinitionalSweep.infer(problem, boolFolds = folds)).network(0, 1)
            }
            state.assignment.setBool(0, true)
            state.recompute()
            val perturbation = StallPerturbation(1)
            assertNull(perturbation(state))
            state.tabu.step++

            val move = perturbation(state)

            assertEquals(if (defined) null else Move.BoolFlip(0), move)
        }
    }
}
