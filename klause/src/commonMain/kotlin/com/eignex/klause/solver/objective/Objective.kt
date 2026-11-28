package com.eignex.klause.solver.objective

import com.eignex.klause.ir.LinearObjectiveSpec
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.Move
import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.Sample
import com.eignex.util.arrays.emptyDoubleArray
import com.eignex.util.arrays.emptyLongArray
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.addExactOrNull
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.mulExactOrNull
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.times
import com.eignex.util.numbers.toDouble

/**
 * A local-search scoring view of an assignment; lower is better. The optimizer API itself takes
 * [LinearObjective]; this abstracts it and its optional [IncrementalObjective] view.
 */
interface Objective {
    /** Objective value of [sample]; lower is better. */
    fun evaluate(sample: Sample): Double

    /** Objective value of the live [assignment]; lower is better. Hot implementations override the
     *  default to avoid its per-call snapshot copy. */
    fun evaluate(assignment: Assignment): Double = evaluate(assignment.snapshot())
}

/**
 * A per-move view of the linear objective for local search, supplied via `LocalSearchParams.lsObjective`.
 * Implementations, such as [FunctionalObjective], must agree with the linear objective at every feasible
 * assignment so incumbents stay comparable across engines.
 */
interface IncrementalObjective : Objective {
    /** Exact change in objective if [move] were applied to [assignment], without applying it. */
    fun deltaIfApplied(assignment: Assignment, move: Move): Double
}

/**
 * `Σ boolWeights(b) · bool(b) + Σ intCoefficients(i) · int(i) + Σ realCoefficients(r) · real(r) + constant`,
 * minimized. Integer coefficients let branch-and-bound bound and cut off in exact [Long] arithmetic.
 * [boolWeights] and [intCoefficients] index by original-problem variable id. Coefficient buffers are
 * copied at construction and on export. [constant] is added to every value.
 */
class LinearObjective(
    boolWeights: LongArray = emptyLongArray(),
    intCoefficients: LongArray = emptyLongArray(),
    val constant: Long = 0L,
    /** Coefficient of each LP-only real variable by real var id; [evaluateLong] ignores them. */
    realCoefficients: DoubleArray = emptyDoubleArray(),
) : Objective {
    private var capturedSourcePairing: SourceObjectivePairing? = null
    private var capturedBinaryIntegerSource: BinaryIntegerObjectiveSource? = null

    internal val binaryIntegerSource: BinaryIntegerObjectiveSource? get() = capturedBinaryIntegerSource

    internal fun captureBinaryIntegerView(problem: Problem, objective: LinearObjective): LinearObjective = apply {
        capturedBinaryIntegerSource = BinaryIntegerObjectiveSource(problem, objective)
    }

    internal val sourcePairing: SourceObjectivePairing? get() = capturedSourcePairing

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
    ): LinearObjective = LinearObjective(boolWeights, intCoefficients, constant, realCoefficients).also {
        it.capturedSourcePairing = capturedSourcePairing?.copied(this, it)
        it.capturedBinaryIntegerSource = capturedBinaryIntegerSource?.takeIf {
            constant == this.constant && sameObjectiveTerms(boolWeights, boolWeightValues) &&
                sameObjectiveTerms(intCoefficients, intCoefficientValues) &&
                realCoefficients.contentEquals(realCoefficientValues)
        }
    }

    internal fun paired(pairing: SourceObjectivePairing): LinearObjective =
        LinearObjective(boolWeightValues, intCoefficientValues, constant, realCoefficientValues).also {
            it.capturedSourcePairing = pairing
            it.capturedBinaryIntegerSource = capturedBinaryIntegerSource
        }

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
    ).also {
        it.capturedSourcePairing = capturedSourcePairing?.negated()
        it.capturedBinaryIntegerSource = capturedBinaryIntegerSource?.let { source ->
            BinaryIntegerObjectiveSource(source.problem, source.objective.negated())
        }
    }

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
            val x = sample.exactReals?.get(r) ?: sample.algebraicReals?.get(r)?.rational ?: if (symbolic != null) {
                requireNotNull(symbolic.rational) { "a symbolic objective coordinate requires exact real arithmetic" }
            } else checkNotNull(BigFraction.ofDouble(sample.approximateRealValue(r)))
            value += checkNotNull(BigFraction.ofDouble(c)) * x
        }
        return value
    }

    override fun evaluate(sample: Sample): Double {
        if (sample.exactInts != null) return evaluateExact(sample).toDouble()
        // A discrete sum past the 64-bit range is summed exactly and rounded once, rather than wrapping.
        var total = (discreteSumOrNull(sample) ?: wideDiscreteSum(sample)).toDouble()
        for (r in 0 until minOf(sample.numRealVars, realCoefficientValues.size)) {
            total += realCoefficientValues[r] * sample.approximateRealValue(r)
        }
        return total
    }

    // Null when a product or partial sum leaves the 64-bit range.
    private fun discreteSumOrNull(sample: Sample): Long? {
        var total = constant
        for (b in 0 until minOf(sample.numBoolVars, boolWeightValues.size)) {
            if (sample.boolValue(b)) total = addExactOrNull(total, boolWeightValues[b]) ?: return null
        }
        for (i in 0 until minOf(sample.numIntVars, intCoefficientValues.size)) {
            val c = intCoefficientValues[i]
            val x = sample.intValue(i)
            if (c == 0L || x == 0L) continue
            val term = if (fitsHalf(c) && fitsHalf(x)) c * x else mulExactOrNull(c, x) ?: return null
            total = addExactOrNull(total, term) ?: return null
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

    /** Exact integer objective value of the live [assignment], read in place without a copy. */
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
     * The single integer variable this objective optimises, with the direction given by its coefficient's
     * sign, or null when the objective has any other term. Branch-and-bound posts each incumbent's bound
     * onto that variable.
     */
    fun singleIntObjective(): SingleIntObjective? {
        if (boolWeightValues.any { it != 0L }) return null
        // Callers treat the variable as the whole objective: LP bounding posts its relaxation bound, taken
        // over real columns too, onto it, which would refute a feasible model whose reals carry cost.
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

/** A [LinearObjective] that minimises [varId] when [ascending], else maximises it. */
data class SingleIntObjective(val varId: Int, val ascending: Boolean)

/** Materialize a lowered objective for engine evaluation and bounding. */
fun LinearObjectiveSpec.toLinearObjective(): LinearObjective = LinearObjective(
    boolWeights = boolWeightValues,
    intCoefficients = intCoefficientValues,
    constant = constant,
    realCoefficients = realCoefficientValues,
)

// A product of two 31-bit values cannot leave the 64-bit range.
private fun fitsHalf(v: Long): Boolean = v in -HALF_RANGE..HALF_RANGE

private const val HALF_RANGE: Long = (1L shl 31) - 1

private fun sameObjectiveTerms(left: LongArray, right: LongArray): Boolean {
    for (index in 0 until maxOf(left.size, right.size)) {
        if (left.getOrElse(index) { 0L } != right.getOrElse(index) { 0L }) return false
    }
    return true
}
