package com.eignex.klause.factor

import com.eignex.klause.brute.BruteForceParams
import com.eignex.klause.brute.BruteForceSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.invariantProjection
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.bake
import kotlin.test.Test
import kotlin.test.assertEquals

class FactorPropagationOracleTest {

    private val deductions: Map<String, (PropagationState) -> Boolean> = mapOf(
        "min" to { it.tightenIntMin(0, 1L) },
        "max" to { it.tightenIntMax(0, 1L) },
        "pin" to { it.tightenIntMin(0, 1L) && it.tightenIntMax(0, 1L) },
        "hole" to { it.excludeIntValue(0, 1L) },
    )

    private fun overPruned(prune: (PropagationState) -> Boolean): Problem {
        val relation = Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2)
        // Keep the relation's semantics and replace only its deductions with a faulty projection.
        val faulty = object : Factor by relation, Invariant by relation.invariantProjection(), Propagator {
            override fun propagate(state: PropagationState, factorId: Int): Boolean = prune(state)
        }
        return Problem(0, 1, arrayOf(IntDomain(0, 2)), arrayOf<Factor>(faulty))
    }

    @Test
    fun `baked reference hides unsound root pruning from the oracle`() {
        for ((label, prune) in deductions) {
            val problem = overPruned(prune)
            val bakedSolutions = BruteForceSolver(problem.bake()).enumerate(BruteForceParams(randomSeed = 0L)).toList()

            FactorPropagationOracle.assertSound(problem, label)
            FactorPropagationOracle.assertGac(problem, label)

            assertEquals(if (label == "pin") 1 else 2, bakedSolutions.size, label)
            assertEquals(3L, problem.finiteIntDomain(0).valueCount, label)
        }
    }
}
