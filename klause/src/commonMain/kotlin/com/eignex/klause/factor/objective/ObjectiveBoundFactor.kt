package com.eignex.klause.factor.objective

import com.eignex.klause.factor.compressViolation
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.MixedVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.hashRemappedKey
import com.eignex.klause.ir.materializeKey
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.NoPropagator
import com.eignex.klause.propagation.withAppendedFactor
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.EmptyDoubleArray
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntIntMap
import kotlin.math.abs
import kotlin.math.ceil

/**
 * The [problem] with an objective-bound factor for [objective] appended (the ratchet arm's overlay),
 * paired with the shared [MutableObjectiveBound] the minimize engine tightens at each incumbent; `null`
 * when the objective has no variable terms. The appended factor is LS-only ([NoPropagator]), so the
 * overlay's bake is identical to the base — the extra factor adds one LS invariant and its occurrence
 * entries, nothing more.
 */
internal fun objectiveBoundOverlay(
    problem: BakedProblem,
    objective: LinearObjective,
): Pair<BakedProblem, MutableObjectiveBound>? {
    val bound = MutableObjectiveBound(objective.constant)
    val factor = ObjectiveBoundFactor.of(objective, bound) ?: return null
    return problem.withAppendedFactor(factor) to bound
}

/**
 * Shared, mutable upper bound on an objective's weighted sum `Σ w·b + Σ c·i` — the ratchet knob for
 * the objective-as-constraint local-search arm. The [ObjectiveBoundFactor] reads
 * [value]; the minimize engine calls [tightenBelow] each time it reaches a feasible incumbent, so the
 * factor goes violated again and the feasibility fight repairs "beat the incumbent" like any other
 * constraint. [Long.MAX_VALUE] (the initial value) is inactive: the raw sum never exceeds it, so the
 * factor is satisfied until the first incumbent tightens it.
 */
internal class MutableObjectiveBound(private val objectiveConstant: Long) {
    /** Upper bound on the raw weighted sum. Inactive at [Long.MAX_VALUE]. */
    var value: Long = Long.MAX_VALUE
        private set

    /** The same bound for a sum kept in floating point — an objective with continuous terms, or one whose
     *  sum can pass the 64-bit range. Inactive at [Double.POSITIVE_INFINITY]. */
    var realLimit: Double = Double.POSITIVE_INFINITY
        private set

    /** Tighten so the next feasible sum must strictly beat [objectiveValue] (`objective = sum +
     *  constant`), i.e. `sum ≤ objectiveValue − 1 − constant`, or by a relative margin for a sum kept in
     *  floating point. */
    fun tightenBelow(objectiveValue: Double) {
        value = objectiveValue.toLong() - 1 - objectiveConstant
        realLimit = objectiveValue - objectiveConstant - REAL_IMPROVEMENT * maxOf(1.0, abs(objectiveValue))
    }

    private companion object {
        // Relative improvement a floating-point sum must make over the incumbent.
        const val REAL_IMPROVEMENT: Double = 1e-9
    }
}

/**
 * A local-search-only soft constraint `Σ boolWeights·b + Σ intCoeffs·i ≤ bound` over the objective's
 * decision variables, sharing a [MutableObjectiveBound] that the minimize engine ratchets down at each
 * incumbent. Injected as an extra factor into one arm's `Problem` overlay so
 * `cost == 0` means "hard constraints satisfied AND objective beats the incumbent" — turning objective
 * optimization back into violation repair for the SAT-style feasibility arms (probSAT / WalkSAT).
 *
 * Its propagation projection is [NoPropagator]: the bound is an LS artifact and must never enter CP (the objective
 * is already enforced there through branch-and-bound), so this factor belongs only in an LS overlay.
 */
internal class ObjectiveBoundFactor(
    private val objectiveBoolVars: IntArray,
    internal val boolWeights: LongArray,
    private val objectiveIntVars: IntArray,
    internal val intCoeffs: LongArray,
    internal val bound: MutableObjectiveBound,
    internal val realVars: IntArray = EmptyIntArray,
    internal val realCoeffs: DoubleArray = EmptyDoubleArray,
) : Factor {

    override val variables: VarList =
        MixedVars(spanInts = objectiveIntVars, boolVars = objectiveBoolVars, reals = realVars)

    override fun remap(mapping: VarRemap): Factor = ObjectiveBoundFactor(
        mapping.bools(boolVars),
        boolWeights,
        mapping.ints(intVars),
        intCoeffs,
        bound,
        mapping.reals(realVars),
        realCoeffs,
    )

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.OBJECTIVE_BOUND, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int =
        hashRemappedKey(FactorKind.OBJECTIVE_BOUND, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.sortedBoolVars(boolVars)
        sink.sortedIntVars(intVars)
    }

    companion object {
        /** The objective-bound factor for [objective] sharing [bound], or `null` when the objective has
         *  no variable terms (nothing to bound). Keeps only the nonzero-weight/coefficient variables. */
        fun of(objective: LinearObjective, bound: MutableObjectiveBound): ObjectiveBoundFactor? {
            val boolVars = objective.boolWeights.indices.filter { objective.boolWeights[it] != 0L }
            val intVars = objective.intCoefficients.indices.filter { objective.intCoefficients[it] != 0L }
            val realVars = objective.realCoefficients.indices.filter { objective.realCoefficients[it] != 0.0 }
            if (boolVars.isEmpty() && intVars.isEmpty() && realVars.isEmpty()) return null
            return ObjectiveBoundFactor(
                boolVars.toIntArray(),
                LongArray(boolVars.size) { objective.boolWeights[boolVars[it]] },
                intVars.toIntArray(),
                LongArray(intVars.size) { objective.intCoefficients[intVars[it]] },
                bound,
                realVars.toIntArray(),
                DoubleArray(realVars.size) { objective.realCoefficients[realVars[it]] },
            )
        }
    }
}

/**
 * LS invariant for [ObjectiveBoundFactor]: keeps the running weighted sum in
 * [LocalSearchState.longPayload] and grades violation by how far it exceeds the shared bound. Repair
 * moves push the sum down (toward the bound), so a SAT-style arm repairs the objective slack exactly
 * as it repairs any violated constraint.
 */
internal class ObjectiveBoundInvariant(
    private val boolVars: IntArray,
    private val boolWeights: LongArray,
    private val intVars: IntArray,
    private val intCoeffs: LongArray,
    private val bound: MutableObjectiveBound,
) : Invariant {

    // Each objective variable's position: a move reads its one weight, not a scan of the objective, which on a
    // MaxSAT model with thousands of soft literals made every flip cost as much as the objective is long.
    private val boolIndex = IntIntMap.build(boolVars, IntArray(boolVars.size) { it }, absent = -1)
    private val intIndex = IntIntMap.build(intVars, IntArray(intVars.size) { it }, absent = -1)

    private fun boolWeightOf(boolVar: Int): Long = boolIndex[boolVar].let { if (it < 0) 0L else boolWeights[it] }

    private fun intCoeffOf(intVar: Int): Long = intIndex[intVar].let { if (it < 0) 0L else intCoeffs[it] }

    /** Graded degree of `sum ≤ bound`: `0` when satisfied, else the (soft-capped) overshoot. A
     *  [Long.MAX_VALUE] bound makes the overshoot non-positive, so the factor is inert. */
    private fun degree(state: LocalSearchState, sum: Long): Int {
        val overshoot = sum - bound.value
        return if (overshoot <= 0L) 0 else compressViolation(overshoot, state.violationSoftCap)
    }

    override fun initialize(state: LocalSearchState, factorId: Int) {
        var sum = 0L
        for (i in boolVars.indices) if (state.assignment.boolValue(boolVars[i])) sum += boolWeights[i]
        for (i in intVars.indices) sum += intCoeffs[i] * state.assignment.intValue(intVars[i])
        state.longPayload[factorId] = sum
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = state.longPayload[factorId] > bound.value

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        degree(state, state.longPayload[factorId])

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val w = boolWeightOf(boolVar)
        val cur = if (state.assignment.boolValue(boolVar)) 1 else 0
        val newSum = state.longPayload[factorId] + w * (1 - 2 * cur)
        return degree(state, newSum) - state.factorDegree[factorId]
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int {
        val c = intCoeffOf(intVar)
        val old = state.assignment.intValue(intVar)
        val newSum = state.longPayload[factorId] + c * (newValue - old)
        return degree(state, newSum) - state.factorDegree[factorId]
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val w = boolWeightOf(boolVar)
        // The assignment is already flipped, so the current value is the post-flip one.
        val newVal = if (state.assignment.boolValue(boolVar)) 1 else 0
        val oldSum = state.longPayload[factorId]
        val newSum = oldSum + w * (2 * newVal - 1)
        state.longPayload[factorId] = newSum
        return degree(state, newSum) - degree(state, oldSum)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int {
        val c = intCoeffOf(intVar)
        val cur = state.assignment.intValue(intVar)
        val oldSum = state.longPayload[factorId]
        val newSum = oldSum + c * (cur - oldValue)
        state.longPayload[factorId] = newSum
        return degree(state, newSum) - degree(state, oldSum)
    }

    /** Push the sum toward the bound: drop positive-weight trues (raise negative-weight ones), and
     *  step each int var in the direction its coefficient says lowers the sum. */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (state.longPayload[factorId] <= bound.value) return
        for (i in boolVars.indices) {
            val cur = state.assignment.boolValue(boolVars[i])
            if (boolWeights[i] > 0L && cur) sink.addBoolFlip(boolVars[i])
            if (boolWeights[i] < 0L && !cur) sink.addBoolFlip(boolVars[i])
        }
        for (i in intVars.indices) {
            val v = intVars[i]
            val cur = state.assignment.intValue(v)
            val d = state.rootDomains[v]
            if (intCoeffs[i] > 0L && cur > d.min) sink.addChannelingIntSet(state, v, d.lower(cur))
            if (intCoeffs[i] < 0L && cur < d.max) sink.addChannelingIntSet(state, v, d.higher(cur))
        }
    }
}

/**
 * LS invariant for an [ObjectiveBoundFactor] whose sum a `Long` cannot carry exactly: one with continuous terms, or
 * one whose integer terms can pass the 64-bit range over the search domains. The sum is kept in floating point and
 * compared with [MutableObjectiveBound.realLimit]. The ratchet only steers the search — every incumbent's objective
 * is evaluated from its assignment — so a rounded sum costs steering accuracy, never a wrong objective.
 */
internal class FloatObjectiveBoundInvariant(
    private val boolVars: IntArray,
    private val boolWeights: LongArray,
    private val intVars: IntArray,
    private val intCoeffs: LongArray,
    private val realVars: IntArray,
    private val realCoeffs: DoubleArray,
    private val bound: MutableObjectiveBound,
) : Invariant {
    private val boolIndex = IntIntMap.build(boolVars, IntArray(boolVars.size) { it }, absent = -1)
    private val intIndex = IntIntMap.build(intVars, IntArray(intVars.size) { it }, absent = -1)
    private val realIndex = IntIntMap.build(realVars, IntArray(realVars.size) { it }, absent = -1)

    // The running sum, boxed once per factor so the state needs no floating-point payload of its own.
    private fun cell(state: LocalSearchState, factorId: Int): DoubleArray = state.refPayload[factorId] as DoubleArray

    private fun degree(state: LocalSearchState, sum: Double): Int {
        val overshoot = sum - bound.realLimit
        if (overshoot <= 0.0) return 0
        val units = ceil(overshoot)
        val raw = if (units >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else units.toLong()
        return compressViolation(raw, state.violationSoftCap)
    }

    override fun initialize(state: LocalSearchState, factorId: Int) {
        var sum = 0.0
        for (i in boolVars.indices) if (state.assignment.boolValue(boolVars[i])) sum += boolWeights[i].toDouble()
        for (i in intVars.indices) sum += intCoeffs[i].toDouble() * state.assignment.intValue(intVars[i]).toDouble()
        for (i in realVars.indices) sum += realCoeffs[i] * state.assignment.realValue(realVars[i])
        state.refPayload[factorId] = doubleArrayOf(sum)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        cell(state, factorId)[0] > bound.realLimit

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int = degree(state, cell(state, factorId)[0])

    private fun boolStep(boolVar: Int, flippedFrom: Boolean): Double {
        val i = boolIndex[boolVar]
        if (i < 0) return 0.0
        return if (flippedFrom) -boolWeights[i].toDouble() else boolWeights[i].toDouble()
    }

    private fun intCoeff(intVar: Int): Double = intIndex[intVar].let { if (it < 0) 0.0 else intCoeffs[it].toDouble() }

    private fun realCoeff(realVar: Int): Double = realIndex[realVar].let { if (it < 0) 0.0 else realCoeffs[it] }

    private fun shift(state: LocalSearchState, factorId: Int, step: Double): Int {
        val holder = cell(state, factorId)
        val old = holder[0]
        holder[0] = old + step
        return degree(state, holder[0]) - degree(state, old)
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int =
        degree(state, cell(state, factorId)[0] + boolStep(boolVar, state.assignment.boolValue(boolVar))) -
            state.factorDegree[factorId]

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int {
        val step = intCoeff(intVar) * (newValue - state.assignment.intValue(intVar)).toDouble()
        return degree(state, cell(state, factorId)[0] + step) - state.factorDegree[factorId]
    }

    override fun deltaIfRealSet(state: LocalSearchState, factorId: Int, realVar: Int, newValue: Double): Int {
        val step = realCoeff(realVar) * (newValue - state.assignment.realValue(realVar))
        return degree(state, cell(state, factorId)[0] + step) - state.factorDegree[factorId]
    }

    // The assignment is already flipped, so the pre-flip value is the opposite of the current one.
    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int =
        shift(state, factorId, boolStep(boolVar, !state.assignment.boolValue(boolVar)))

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int =
        shift(state, factorId, intCoeff(intVar) * (state.assignment.intValue(intVar) - oldValue).toDouble())

    override fun applyRealSet(state: LocalSearchState, factorId: Int, realVar: Int, oldValue: Double): Int =
        shift(state, factorId, realCoeff(realVar) * (state.assignment.realValue(realVar) - oldValue))

    /** Push the sum toward the bound, as [ObjectiveBoundInvariant] does, with each continuous term moved by the
     *  whole overshoot. */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val overshoot = cell(state, factorId)[0] - bound.realLimit
        if (overshoot <= 0.0) return
        for (i in boolVars.indices) {
            val cur = state.assignment.boolValue(boolVars[i])
            if (boolWeights[i] > 0L && cur) sink.addBoolFlip(boolVars[i])
            if (boolWeights[i] < 0L && !cur) sink.addBoolFlip(boolVars[i])
        }
        for (i in intVars.indices) {
            val v = intVars[i]
            val cur = state.assignment.intValue(v)
            val d = state.rootDomains[v]
            if (intCoeffs[i] > 0L && cur > d.min) sink.addChannelingIntSet(state, v, d.lower(cur))
            if (intCoeffs[i] < 0L && cur < d.max) sink.addChannelingIntSet(state, v, d.higher(cur))
        }
        for (i in realVars.indices) {
            val r = realVars[i]
            val target = state.assignment.realValue(r) - overshoot / realCoeffs[i]
            val clamped = target.coerceIn(state.problem.realLower[r], state.problem.realUpper[r])
            if (clamped.isFinite() && clamped != state.assignment.realValue(r)) sink.addRealSet(r, clamped)
        }
    }
}

/**
 * Whether an objective sum over [intVars] can pass the 64-bit range over [domains]: `Σ |coeff|·max(|min|, |max|)`
 * plus the Boolean weights reaches 2^61, estimated in `Double` as [com.eignex.klause.factor.arithmetic.needsExactSum]
 * does for a row.
 */
internal fun objectiveSumIsWide(
    boolWeights: LongArray,
    intVars: IntArray,
    intCoeffs: LongArray,
    domains: Array<IntDomain>,
): Boolean {
    var total = 0.0
    for (w in boolWeights) total += abs(w.toDouble())
    for (i in intVars.indices) {
        val d = domains[intVars[i]]
        total += abs(intCoeffs[i].toDouble()) * maxOf(abs(d.min.toDouble()), abs(d.max.toDouble()))
    }
    return total >= WIDE_SUM_LIMIT
}

private const val WIDE_SUM_LIMIT: Double = (1L shl 61).toDouble()
