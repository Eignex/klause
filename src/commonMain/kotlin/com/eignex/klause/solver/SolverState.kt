package com.eignex.klause.solver

import com.eignex.klause.util.IntSwapSet
import kotlin.random.Random

/**
 * Mutable state of an ongoing solve. Owns the [Assignment], the violated-factor set, the
 * per-factor scratch arrays ([intPayload], [refPayload]), and the aggregated hard/soft cost.
 */
class SolverState(
    val problem: Problem,
    val rng: Random,
) {
    val assignment: Assignment = Assignment(problem.numBoolVars, problem.numIntVars)
    val violated: IntSwapSet = IntSwapSet(problem.numFactors)
    val intPayload: IntArray = IntArray(problem.numFactors)
    val refPayload: Array<Any?> = arrayOfNulls(problem.numFactors)

    var hardCost: Int = 0
        internal set

    var softCost: Double = 0.0
        internal set

    fun restart() {
        assignment.randomize(rng, problem.intDomains)
        recompute()
    }

    fun recompute() {
        for (i in 0 until problem.numFactors) violated.remove(i)
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

    fun apply(move: Move) = when (move) {
        is Move.BoolFlip -> applyBoolFlip(move.varId)
        is Move.IntSet -> applyIntSet(move.varId, move.newValue)
    }

    private fun applyBoolFlip(boolVar: Int) {
        assignment.flipBool(boolVar)
        for (factorId in problem.boolOccurrences[boolVar]) {
            val factor = problem.factors[factorId]
            updateViolation(factor, factorId, factor.applyBoolFlip(this, factorId, boolVar))
        }
    }

    private fun applyIntSet(intVar: Int, newValue: Int) {
        val old = assignment.intValue(intVar)
        if (old == newValue) return
        assignment.setInt(intVar, newValue)
        for (factorId in problem.intOccurrences[intVar]) {
            val factor = problem.factors[factorId]
            updateViolation(factor, factorId, factor.applyIntSet(this, factorId, intVar, old))
        }
    }

    private fun updateViolation(factor: Factor, factorId: Int, deltaViolated: Int) {
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
