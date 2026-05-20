package com.eignex.klause.solver

/**
 * Anything an [Optimizer] can score an assignment by. The contract is "lower is better" —
 * optimisation backends minimise this. To maximise, negate the weights.
 *
 * The primary subtype is [LinearObjective]. Backends pattern-match on it for fast paths
 * (incremental delta in the local-search engine, native `mkAdd` translation in Z3) and may
 * either fall back to the generic [evaluate] or refuse to optimise other subtypes.
 */
interface Objective {
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
    fun deltaIfApplied(assignment: Assignment, move: Move): Double
}

/**
 * Σ boolWeights[b] · 1[bool[b]] + Σ intCoefficients[i] · int[i] + constant.
 *
 * - [boolWeights] indexes by the original-problem bool var id; size must equal
 *   `problem.numBoolVars`.
 * - [intCoefficients] indexes by the original-problem int var id (note: float vars after
 *   compilation live in the int-var namespace, so a coefficient on a `floatVar` is
 *   applied to its bucket index — multiply by `(max - min) / (buckets - 1)` and fold
 *   `min · coeff` into [constant] if you need real-valued semantics).
 * - [constant] is added unconditionally; useful for objectives whose "zero" assignment
 *   has nonzero cost.
 *
 * All arrays are kept by reference, not copied. Treat them as immutable after handing the
 * objective to an optimiser.
 */
data class LinearObjective(
    val boolWeights: DoubleArray = DoubleArray(0),
    val intCoefficients: DoubleArray = DoubleArray(0),
    val constant: Double = 0.0,
) : Objective {

    override fun evaluate(sample: Sample): Double {
        var total = constant
        for (b in 0 until minOf(sample.bools.size, boolWeights.size)) {
            if (sample.bools[b]) total += boolWeights[b]
        }
        for (i in 0 until minOf(sample.ints.size, intCoefficients.size)) {
            total += intCoefficients[i] * sample.ints[i]
        }
        return total
    }

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
