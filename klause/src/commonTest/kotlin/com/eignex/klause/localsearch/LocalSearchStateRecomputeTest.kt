package com.eignex.klause.localsearch

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalSearchStateRecomputeTest {

    private val cases = localSearchStateCases()

    @Test
    fun `state should match a fresh recompute after random moves`() {
        for (case in cases) {
            val baked = case.problem.bake()
            for (seed in 0 until 3) {
                val state = LocalSearchState(baked, Random(seed.toLong()))
                state.restart()

                val rng = Random(seed.toLong() xor 0xBEEFL)
                repeat(50) {
                    val move = randomPrimitiveLocalSearchMove(case.problem, state, rng) ?: return@repeat
                    state.apply(move)
                }

                val sibling = LocalSearchState(baked, Random(seed.toLong()))
                copyLocalSearchAssignment(state, sibling)
                sibling.recompute()

                val label = "${case.name} seed=$seed"
                assertEquals(sibling.cost, state.cost, label)
                assertEquals(sibling.violated.toIntArray().sorted(), state.violated.toIntArray().sorted(), label)
                for (fid in 0 until case.problem.numFactors) {
                    assertEquals(sibling.intPayload[fid], state.intPayload[fid], "$label factor $fid")
                    // Clause watch positions may differ while tracking the same truth.
                    if (case.problem.factors[fid] !is Clause) {
                        assertEquals(sibling.longPayload[fid], state.longPayload[fid], "$label factor $fid")
                    }
                }
            }
        }
    }
}
