package com.eignex.klause.solver.objective
import com.eignex.klause.localsearch.Move
import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.Sample

/**
 * Anything the local-search internals can score an assignment by; "lower is better".
 *
 * This is **not** the optimizer API type: [com.eignex.klause.solver.Optimizer.minimize] is statically typed
 * [LinearObjective] — the one canonical objective every front-end produces — so backends enable
 * their objective machinery (LP bounding, branch-and-bound bounds, native translations) from
 * params alone, with no objective-shape dispatch. This interface remains as the local-search
 * engine's *internal* abstraction over the two scoring views it can descend: the linear
 * objective itself and an optional [IncrementalObjective] gradient view of it
 * (`LocalSearchParams.lsObjective`).
 */
interface Objective {
    /** Objective value of [sample]; lower is better. */
    fun evaluate(sample: Sample): Double

    /** Objective value of the live [assignment]; lower is better. Equivalent to
     *  `evaluate(assignment.snapshot())` but without materialising a [Sample] — the descent loop calls
     *  this every iteration, so the default's snapshot copy dominates allocation. Hot implementations
     *  read the assignment's variables directly; the default is the safe fallback. */
    fun evaluate(assignment: Assignment): Double = evaluate(assignment.snapshot())
}

/**
 * A per-move *gradient view* of the (linear) objective for the local-search engine, supplied via
 * `LocalSearchParams.lsObjective`. The engine's cost-shaping path (see
 * `LocalSearchState.shapedObjectiveDelta`) calls [deltaIfApplied] to fold the objective into
 * per-move scoring without materialising a [Sample] for every candidate move.
 *
 * The canonical implementation is [FunctionalObjective]: a `minimizeInt(V)` objective on a
 * *derived* variable has zero linear gradient on decision moves (moving a decision variable
 * merely violates `V`'s defining constraint), so the view recomputes `V` from the decision
 * leaves. Implementations must agree with the linear objective at every **feasible** assignment
 * — incumbent objectives must stay comparable across engines — and must return the exact value
 * `evaluate(applyMove(current)) − evaluate(current)` for the current [Assignment] and the
 * proposed [Move] without applying it.
 */
interface IncrementalObjective : Objective {
    /** Change in objective if [move] were applied to [assignment]. */
    fun deltaIfApplied(assignment: Assignment, move: Move): Double
}

/**
 * `Σ boolWeights[b] · 1[bool[b]] + Σ intCoefficients[i] · int[i] + constant`, with **integer
 * coefficients** — the native objective. Every FlatZinc integer `solve minimize`, the
 * SAT/pseudo-Boolean/XCSP/LIA front-ends, and the `minimizeInt` family build this. Integer
 * coefficients let branch-and-bound compute the lower bound and apply the optimality cutoff
 * in exact [Long] arithmetic ([evaluateLong], `LpEngine.linearLowerBound`), so no
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

    /** Exact integer objective value of the live [assignment]; lower is better. Reads variables in
     *  place — no [Sample] copy — so the descent loop can score every iteration allocation-free. */
    fun evaluateLong(assignment: Assignment): Long {
        var total = constant
        for (b in 0 until minOf(assignment.numBoolVars, boolWeights.size)) {
            if (assignment.boolValue(b)) total += boolWeights[b]
        }
        for (i in 0 until minOf(assignment.numIntVars, intCoefficients.size)) {
            total += intCoefficients[i] * assignment.intValue(i)
        }
        return total
    }

    override fun evaluate(assignment: Assignment): Double = evaluateLong(assignment).toDouble()

    /**
     * If this objective is a single integer variable — exactly one nonzero coefficient, on
     * an int var, no bool weights — return that variable and whether the search minimises it
     * (coefficient positive) or maximises it (negative). FlatZinc objectives are always this
     * shape: minizinc reifies any objective expression, however non-linear, into one variable
     * defined by a constraint, and `solve minimize`/`maximize` points at it. Branch-and-bound
     * uses this to push each incumbent's bound onto the objective variable so the defining
     * constraint propagates it (see `PropagationSession.assertObjectiveBound`). Returns null
     * for weighted-sum objectives, which have no single variable to bound. The coefficient
     * magnitude is irrelevant (a monotone scaling), so only its sign is reported.
     */
    fun singleIntObjective(): SingleIntObjective? {
        if (boolWeights.any { it != 0L }) return null
        var found = -1
        for (i in intCoefficients.indices) {
            if (intCoefficients[i] == 0L) continue
            if (found >= 0) return null
            found = i
        }
        if (found < 0) return null
        return SingleIntObjective(found, ascending = intCoefficients[found] > 0L)
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

/**
 * A [LinearObjective] recognised as optimising a single integer variable: minimise
 * [varId] when [ascending], else maximise it. Returned by [LinearObjective.singleIntObjective];
 * consumed by branch-and-bound to bound the objective variable on each incumbent.
 */
data class SingleIntObjective(val varId: Int, val ascending: Boolean)
