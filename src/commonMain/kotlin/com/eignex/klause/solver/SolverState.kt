package com.eignex.klause.solver

import com.eignex.klause.util.IntSwapSet
import kotlin.random.Random

/**
 * Mutable state of an ongoing solve. Holds the current assignment, per-factor scratch, the set
 * of currently violated factors, and aggregated hard/soft costs.
 *
 * Each factor type stashes its own scratch in [intPayload] (e.g. `numSat` for clauses, `trueCount`
 * for at-most-one). Factors that need richer scratch can use [refPayload]. Both are sized to
 * [Problem.numFactors].
 */
class SolverState(
    val problem: Problem,
    val rng: Random,
) {
    val assignment: Assignment = Assignment(problem.numVars)
    val violated: IntSwapSet = IntSwapSet(problem.numFactors)
    val intPayload: IntArray = IntArray(problem.numFactors)
    val refPayload: Array<Any?> = arrayOfNulls(problem.numFactors)

    var hardCost: Int = 0
        internal set

    var softCost: Double = 0.0
        internal set

    /**
     * Reinitialize: randomize the assignment, then ask every factor to rebuild its payload
     * and refresh hard/soft cost and the violated set from scratch.
     */
    fun restart() {
        assignment.randomize(rng)
        recompute()
    }

    fun recompute() {
        violated.let { v -> for (i in 0 until problem.numFactors) v.remove(i) }
        hardCost = 0
        softCost = 0.0
        problem.factors.forEachIndexed { id, factor ->
            factor.initialize(this, id)
            if (factor.isViolated(this, id)) {
                violated.add(id)
                if (factor.isHard) hardCost++ else softCost += factor.weight
            }
        }
    }

    /**
     * Flip [variable], propagate the change to every factor mentioning it, and update violated
     * set + costs incrementally.
     */
    fun flip(variable: Int) {
        assignment.flip(variable)
        val occurs = problem.occurrences[variable]
        for (factorId in occurs) {
            val factor = problem.factors[factorId]
            val deltaViolated = factor.applyFlip(this, factorId, variable)
            when (deltaViolated) {
                +1 -> {
                    violated.add(factorId)
                    if (factor.isHard) hardCost++ else softCost += factor.weight
                }
                -1 -> {
                    violated.remove(factorId)
                    if (factor.isHard) hardCost-- else softCost -= factor.weight
                }
            }
        }
    }
}
