package com.eignex.klause.solver.objective
import com.eignex.klause.ir.LinearObjectiveSpec
import com.eignex.klause.localsearch.Move
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.EmptyDoubleArray
import com.eignex.klause.util.EmptyLongArray
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import com.eignex.klause.util.toDouble

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
 * Bucketed float objectives use the integer bucket's coefficient. LP-only continuous objectives
 * use [realCoefficients]; [evaluateExact] retains certified rational coordinates.
 *
 * Coefficient buffers are captured at construction. Exported arrays are independent copies;
 * evaluation reads the owned coefficients directly.
 */
class LinearObjective(
    boolWeights: LongArray = EmptyLongArray,
    intCoefficients: LongArray = EmptyLongArray,
    val constant: Long = 0L,
    /**
     * Objective coefficient of each LP-only continuous (real) variable, indexed by real var id (size
     * `problem.numRealVars`; empty for the integer/Boolean core). The real part of the objective is
     * resolved by the LP at nodes and leaves, not by [evaluateLong] — the integer/Boolean methods here
     * cover only the discrete part. See the LP-only-columns hybrid engine.
     */
    realCoefficients: DoubleArray = EmptyDoubleArray,
) : Objective {
    internal val boolWeightValues: LongArray = boolWeights.copyOf()

    /** Independent copy of the boolWeights coordinates. */
    val boolWeights: LongArray get() = boolWeightValues.copyOf()

    internal val intCoefficientValues: LongArray = intCoefficients.copyOf()

    /** Independent copy of the intCoefficients coordinates. */
    val intCoefficients: LongArray get() = intCoefficientValues.copyOf()

    internal val realCoefficientValues: DoubleArray = realCoefficients.copyOf()

    /** Independent copy of the realCoefficients coordinates. */
    val realCoefficients: DoubleArray get() = realCoefficientValues.copyOf()

    /** Copy this objective, capturing every replacement coefficient buffer. */
    fun copy(
        boolWeights: LongArray = boolWeightValues,
        intCoefficients: LongArray = intCoefficientValues,
        constant: Long = this.constant,
        realCoefficients: DoubleArray = realCoefficientValues,
    ): LinearObjective = LinearObjective(boolWeights, intCoefficients, constant, realCoefficients)

    /** Independent Boolean coefficients for destructuring. */
    operator fun component1(): LongArray = boolWeights

    /** Independent integer coefficients for destructuring. */
    operator fun component2(): LongArray = intCoefficients

    /** Constant term for destructuring. */
    operator fun component3(): Long = constant

    /** Independent real coefficients for destructuring. */
    operator fun component4(): DoubleArray = realCoefficients

    override fun toString(): String = "LinearObjective(boolWeights=${boolWeightValues.contentToString()}, " +
        "intCoefficients=${intCoefficientValues.contentToString()}, constant=$constant, " +
        "realCoefficients=${realCoefficientValues.contentToString()})"

    /** This objective with every coefficient and the constant negated: minimizing it maximizes this one. */
    fun negated(): LinearObjective = LinearObjective(
        boolWeights = LongArray(boolWeightValues.size) { -boolWeightValues[it] },
        intCoefficients = LongArray(intCoefficientValues.size) { -intCoefficientValues[it] },
        constant = -constant,
        realCoefficients = DoubleArray(realCoefficientValues.size) { -realCoefficientValues[it] },
    )

    /** Exact integer objective value of [sample]; lower is better. */
    fun evaluateLong(sample: Sample): Long {
        var total = constant
        for (b in 0 until minOf(sample.numBoolVars, boolWeightValues.size)) {
            if (sample.boolValue(b)) total += boolWeightValues[b]
        }
        for (i in 0 until minOf(sample.numIntVars, intCoefficientValues.size)) {
            total += intCoefficientValues[i] * sample.intValue(i)
        }
        return total
    }

    /** Exact value of [sample], using certified real values when present and binary64 coefficients. */
    fun evaluateExact(sample: Sample): BigFraction {
        var total = bigIntOf(constant)
        for (v in intCoefficientValues.indices) {
            if (intCoefficientValues[v] == 0L) continue
            total += bigIntOf(intCoefficientValues[v]) * sample.exactIntValue(v)
        }
        for (b in boolWeightValues.indices) {
            if (boolWeightValues[b] != 0L && sample.boolValue(b)) total += bigIntOf(boolWeightValues[b])
        }
        var value = BigFraction.of(total, BIG_ONE)
        for (r in realCoefficientValues.indices) {
            val c = realCoefficientValues[r]
            if (c == 0.0) continue
            val symbolic = sample.symbolicReals?.get(r)
            val x = sample.exactReals?.get(r) ?: if (symbolic != null) {
                requireNotNull(symbolic.rational) { "a symbolic objective coordinate requires exact real arithmetic" }
            } else checkNotNull(BigFraction.ofDouble(sample.approximateRealValue(r)))
            value += checkNotNull(BigFraction.ofDouble(c)) * x
        }
        return value
    }

    override fun evaluate(sample: Sample): Double {
        if (sample.exactInts != null) return evaluateExact(sample).toDouble()
        // The discrete part is exact; the continuous part is the LP-only real terms, present in [sample]
        // only at a leaf where the residual LP resolved them. A discrete sum past the 64-bit range is summed
        // exactly and rounded once, rather than wrapping.
        var total = (discreteSumOrNull(sample) ?: wideDiscreteSum(sample)).toDouble()
        for (r in 0 until minOf(sample.numRealVars, realCoefficientValues.size)) {
            total += realCoefficientValues[r] * sample.approximateRealValue(r)
        }
        return total
    }

    // The discrete part of [sample]'s value, or null when a product or partial sum leaves the 64-bit range.
    private fun discreteSumOrNull(sample: Sample): Long? {
        var total = constant
        for (b in 0 until minOf(sample.numBoolVars, boolWeightValues.size)) {
            if (sample.boolValue(b)) total = addOrNull(total, boolWeightValues[b]) ?: return null
        }
        for (i in 0 until minOf(sample.numIntVars, intCoefficientValues.size)) {
            val c = intCoefficientValues[i]
            val x = sample.intValue(i)
            if (c == 0L || x == 0L) continue
            val term = if (fitsHalf(c) && fitsHalf(x)) c * x else multiplyOrNull(c, x) ?: return null
            total = addOrNull(total, term) ?: return null
        }
        return total
    }

    private fun wideDiscreteSum(sample: Sample): Double {
        var total = bigIntOf(constant)
        for (b in 0 until minOf(sample.numBoolVars, boolWeightValues.size)) {
            if (sample.boolValue(b)) total += bigIntOf(boolWeightValues[b])
        }
        for (i in 0 until minOf(sample.numIntVars, intCoefficientValues.size)) {
            total += bigIntOf(intCoefficientValues[i]) * bigIntOf(sample.intValue(i))
        }
        return total.toDouble()
    }

    /** Exact integer objective value of the live [assignment]; lower is better. Reads variables in
     *  place — no [Sample] copy — so the descent loop can score every iteration allocation-free. */
    fun evaluateLong(assignment: Assignment): Long {
        var total = constant
        for (b in 0 until minOf(assignment.numBoolVars, boolWeightValues.size)) {
            if (assignment.boolValue(b)) total += boolWeightValues[b]
        }
        for (i in 0 until minOf(assignment.numIntVars, intCoefficientValues.size)) {
            total += intCoefficientValues[i] * assignment.intValue(i)
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
        if (boolWeightValues.any { it != 0L }) return null
        // A continuous term makes the objective more than the one variable, and callers treat the
        // variable as standing for the whole of it: LP bounding posts its relaxation bound — taken over
        // every column, real ones included — straight onto this variable, which refutes a feasible model
        // as soon as the reals carry any of the cost.
        if (realCoefficientValues.any { it != 0.0 }) return null
        var found = -1
        for (i in intCoefficientValues.indices) {
            if (intCoefficientValues[i] == 0L) continue
            if (found >= 0) return null
            found = i
        }
        if (found < 0) return null
        return SingleIntObjective(found, ascending = intCoefficientValues[found] > 0L)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is LinearObjective) return false
        return constant == other.constant &&
            boolWeightValues.contentEquals(other.boolWeightValues) &&
            intCoefficientValues.contentEquals(other.intCoefficientValues) &&
            realCoefficientValues.contentEquals(other.realCoefficientValues)
    }

    override fun hashCode(): Int {
        var h = constant.hashCode()
        h = 31 * h + boolWeightValues.contentHashCode()
        h = 31 * h + intCoefficientValues.contentHashCode()
        h = 31 * h + realCoefficientValues.contentHashCode()
        return h
    }
}

/**
 * A [LinearObjective] recognised as optimising a single integer variable: minimise
 * [varId] when [ascending], else maximise it. Returned by [LinearObjective.singleIntObjective];
 * consumed by branch-and-bound to bound the objective variable on each incumbent.
 */
data class SingleIntObjective(val varId: Int, val ascending: Boolean)

/** Materialize a lowered objective for engine evaluation and bounding. */
fun LinearObjectiveSpec.toLinearObjective(): LinearObjective = LinearObjective(
    boolWeights = boolWeightValues,
    intCoefficients = intCoefficientValues,
    constant = constant,
    realCoefficients = realCoefficientValues,
)

// Whether [v] fits 31 bits, so a product of two such values cannot leave the 64-bit range.
private fun fitsHalf(v: Long): Boolean = v in -HALF_RANGE..HALF_RANGE

private fun multiplyOrNull(a: Long, b: Long): Long? {
    val product = a * b
    return if (product / b == a && !(a == -1L && b == Long.MIN_VALUE) && !(b == -1L && a == Long.MIN_VALUE)) {
        product
    } else {
        null
    }
}

private fun addOrNull(a: Long, b: Long): Long? {
    val sum = a + b
    return if (((a xor sum) and (b xor sum)) < 0L) null else sum
}

private const val HALF_RANGE: Long = (1L shl 31) - 1
