package com.eignex.klause.solver

/**
 * Anything an [Optimizer] can score an assignment by. The contract is "lower is better" —
 * optimisation backends minimise this. To maximise, negate the weights.
 *
 * The primary subtype is [LinearObjective] — the native, integer-coefficient linear
 * objective every FlatZinc `solve minimize` and the SAT/PB/XCSP/LIA front-ends produce.
 * Backends pattern-match on it for exact integer bounding and fast incremental deltas.
 * Float-variable objectives are not a distinct type: klause optimises the integer bucket
 * variable and recovers the real value only at output.
 */
interface Objective {
    /** Objective value of [sample]; lower is better. */
    fun evaluate(sample: Sample): Double
}

/**
 * Opt-in extension for non-[LinearObjective] objectives that can compute their per-move
 * change incrementally. The local-search engine's cost-shaping path (see
 * `LocalSearchState.shapedObjectiveDelta`) calls [deltaIfApplied] to fold the objective into
 * per-move scoring without materialising a [Sample] for every candidate move. Without this
 * interface, non-Linear objectives are only considered at "best feasible" evaluation time —
 * the descent itself is objective-blind.
 *
 * Implementations must return the exact value `evaluate(applyMove(current)) − evaluate(current)`
 * for the current [Assignment] and the proposed [Move]. The move is *not* applied — the
 * engine asks about hypothetical deltas while picking the next step.
 *
 * For piecewise-linear or coordinate-separable objectives the body is typically O(1) per
 * Bool/IntSet move and O(parts) per Compound. If your objective can't compute an
 * incremental delta cheaper than full re-evaluation, prefer not to implement this — the
 * default unshaped path is faster than apply/evaluate/revert per scored move.
 */
interface IncrementalObjective : Objective {
    /** Change in objective if [move] were applied to [assignment]. */
    fun deltaIfApplied(assignment: Assignment, move: Move): Double
}

/**
 * Σ boolWeights[b] · 1[bool[b]] + Σ intCoefficients[i] · int[i] + constant, with **integer
 * coefficients** — the native objective. Every FlatZinc integer `solve minimize`, the
 * SAT/pseudo-Boolean/XCSP/LIA front-ends, and the `minimizeInt` family build this. Integer
 * coefficients let branch-and-bound compute the lower bound and apply the optimality cutoff
 * in exact [Long] arithmetic ([evaluateLong], `BacktrackSolver.linearLowerBound`), so no
 * floating point enters the pruning decision.
 *
 * - [boolWeights] indexes by the original-problem bool var id; size must equal
 *   `problem.numBoolVars`.
 * - [intCoefficients] indexes by the original-problem int var id.
 * - [constant] is added unconditionally; useful for objectives whose "zero" assignment
 *   has nonzero cost.
 *
 * Float-variable objectives are not represented here: klause optimises the integer bucket
 * variable directly (the real value is a monotonic affine map of the bucket index, so the
 * argmin is identical) and the real value is recovered only at solution output. Real-valued
 * objective handling, where wanted, is left to external reference solvers.
 *
 * All arrays are kept by reference, not copied. Treat them as immutable after handing the
 * objective to an optimiser.
 */
data class LinearObjective(
    val boolWeights: LongArray = LongArray(0),
    val intCoefficients: LongArray = LongArray(0),
    val constant: Long = 0L,
) : Objective {

    /** Exact integer objective value of [sample]; lower is better. */
    fun evaluateLong(sample: Sample): Long {
        var total = constant
        for (b in 0 until minOf(sample.bools.size, boolWeights.size)) {
            if (sample.bools[b]) total += boolWeights[b]
        }
        for (i in 0 until minOf(sample.ints.size, intCoefficients.size)) {
            total += intCoefficients[i] * sample.ints[i]
        }
        return total
    }

    override fun evaluate(sample: Sample): Double = evaluateLong(sample).toDouble()

    override fun equals(other: Any?): Boolean {
        if (other !is LinearObjective) return false
        return constant == other.constant &&
            boolWeights.contentEquals(other.boolWeights) &&
            intCoefficients.contentEquals(other.intCoefficients)
    }

    override fun hashCode(): Int {
        var h = constant.hashCode()
        h = 31 * h + boolWeights.contentHashCode()
        h = 31 * h + intCoefficients.contentHashCode()
        return h
    }
}
