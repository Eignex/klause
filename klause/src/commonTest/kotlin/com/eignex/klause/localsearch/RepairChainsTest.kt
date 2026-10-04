package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.IntHashSet
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class RepairChainsTest {

    @Test
    fun `chain primitives step across a hole and keep the endpoints`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 6).excludeValue(3), IntDomain(0, 6)),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 20),
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 20),
            ),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 4)
        state.recompute()
        val sink = MoveSink()

        state.emitFactorPrimitives(seed = 0, nf = 1, seenFactors = IntHashSet(), sink = sink)

        val xTargets = sink.list.filterIsInstance<Move.IntSet>().filter { it.varId == 0 }.map { it.newValue }
        assertEquals(setOf(0L, 2L, 5L, 6L), xTargets.toSet(), "the step below 4 must skip the hole at 3")
    }
}
