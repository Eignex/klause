package com.eignex.klause.solver

import com.eignex.klause.util.IntSwapSet
import kotlin.random.Random

/**
 * Mutable state of an ongoing solve. Owns the [Assignment], the violated-factor set, the
 * per-factor scratch arrays ([intPayload], [refPayload]), and the aggregated hard cost.
 */
class SolverState(
    val problem: Problem,
    val rng: Random,
) {
    val assignment: Assignment = Assignment(problem.numBoolVars, problem.numIntVars)
    val violated: IntSwapSet = IntSwapSet(problem.numFactors)
    val intPayload: IntArray = IntArray(problem.numFactors)
    val refPayload: Array<Any?> = arrayOfNulls(problem.numFactors)
    val moveSink: MoveSink = MoveSink()

    /** Step counter incremented on every accepted move. Strategies use this together with
     *  [lastTouched] to enforce a tabu list. */
    var step: Long = 0L
        private set

    /** Step at which each variable was last flipped or set. Index is the bool var id for
     *  Boolean vars (`[0, numBoolVars)`); int var ids are offset by `numBoolVars`. */
    val lastTouched: LongArray = LongArray(problem.numBoolVars + problem.numIntVars)

    /** Lazy cache for [breakScore] of `Move.BoolFlip`. Entry `v` is fresh iff
     *  `boolBreakValid[v]`; otherwise the cached value is stale and must be recomputed. The
     *  cache is invalidated for every variable in the factor-neighbourhood of an applied
     *  move (so a flip of `u` invalidates `u` itself plus every other var sharing a factor
     *  with `u`). `IntSet` break scores are not cached — the target value widens the key. */
    private val boolBreakCache: IntArray = IntArray(problem.numBoolVars)
    private val boolBreakValid: BooleanArray = BooleanArray(problem.numBoolVars)

    var cost: Int = 0
        internal set

    /** Per-factor weight, default 1.0. Not read by the engine itself — every violation
     *  contributes +1/-1 to [cost] regardless. Strategies that want to bias the search
     *  toward repairing persistently-violated factors (e.g. SAPS) read and mutate this
     *  array between picks. */
    val factorWeights: DoubleArray = DoubleArray(problem.numFactors) { 1.0 }

    fun restart() {
        assignment.randomize(rng, problem.intDomains)
        for (i in lastTouched.indices) lastTouched[i] = 0L
        step = 0L
        recompute()
    }

    fun recompute() {
        for (i in 0 until problem.numFactors) violated.remove(i)
        cost = 0
        for (v in boolBreakValid.indices) boolBreakValid[v] = false
        problem.factors.forEachIndexed { id, factor ->
            factor.initialize(this, id)
            if (factor.isViolated(this, id)) {
                violated.add(id)
                cost++
            }
        }
    }

    fun apply(move: Move) = when (move) {
        is Move.BoolFlip -> applyBoolFlip(move.varId)
        is Move.IntSet -> applyIntSet(move.varId, move.newValue)
    }

    /**
     * Number of currently-satisfied factors that would become violated if [move] were
     * applied. Used by strategies (WalkSAT-style noise/greedy, probSAT-style weighting) to
     * score repair candidates. Computed on demand by walking the var's occurrence list and
     * asking each factor for its `deltaIf*`.
     */
    fun breakScore(move: Move): Int = when (move) {
        is Move.BoolFlip -> {
            val v = move.varId
            if (boolBreakValid[v]) {
                boolBreakCache[v]
            } else {
                var count = 0
                for (factorId in problem.boolOccurrences[v]) {
                    val f = problem.factors[factorId]
                    if (f.deltaIfBoolFlipped(this, factorId, v) > 0) count++
                }
                boolBreakCache[v] = count
                boolBreakValid[v] = true
                count
            }
        }
        is Move.IntSet -> {
            var count = 0
            for (factorId in problem.intOccurrences[move.varId]) {
                val f = problem.factors[factorId]
                if (f.deltaIfIntSet(this, factorId, move.varId, move.newValue) > 0) count++
            }
            count
        }
    }

    private fun applyBoolFlip(boolVar: Int) {
        assignment.flipBool(boolVar)
        val touchedFactors = problem.boolOccurrences[boolVar]
        for (factorId in touchedFactors) {
            val factor = problem.factors[factorId]
            updateViolation(factor, factorId, factor.applyBoolFlip(this, factorId, boolVar))
        }
        invalidateBoolBreakNeighbourhood(touchedFactors)
        step++
        lastTouched[boolVar] = step
    }

    private fun applyIntSet(intVar: Int, newValue: Int) {
        val old = assignment.intValue(intVar)
        if (old == newValue) return
        assignment.setInt(intVar, newValue)
        val touchedFactors = problem.intOccurrences[intVar]
        for (factorId in touchedFactors) {
            val factor = problem.factors[factorId]
            updateViolation(factor, factorId, factor.applyIntSet(this, factorId, intVar, old))
        }
        invalidateBoolBreakNeighbourhood(touchedFactors)
        step++
        lastTouched[problem.numBoolVars + intVar] = step
    }

    private fun invalidateBoolBreakNeighbourhood(factorIds: IntArray) {
        for (factorId in factorIds) {
            val f = problem.factors[factorId]
            for (v in f.boolVars) boolBreakValid[v] = false
        }
    }

    /** True iff [move]'s var was touched within the last [tenure] accepted moves. */
    fun isTaboo(move: Move, tenure: Int): Boolean {
        if (tenure <= 0) return false
        val slot = when (move) {
            is Move.BoolFlip -> move.varId
            is Move.IntSet -> problem.numBoolVars + move.varId
        }
        val touched = lastTouched[slot]
        if (touched == 0L) return false   // never touched (lastTouched is reset on restart)
        return step - touched < tenure
    }

    private fun updateViolation(@Suppress("UNUSED_PARAMETER") factor: Factor, factorId: Int, deltaViolated: Int) {
        when (deltaViolated) {
            +1 -> {
                violated.add(factorId)
                cost++
            }
            -1 -> {
                violated.remove(factorId)
                cost--
            }
        }
    }
}
