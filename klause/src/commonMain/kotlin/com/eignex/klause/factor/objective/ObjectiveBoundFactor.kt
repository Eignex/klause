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
import com.eignex.util.arrays.emptyDoubleArray
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.collections.IntIntMap
import kotlin.math.abs
import kotlin.math.ceil

// Null when the objective has no variable terms. The appended factor has no propagator, so the overlay bakes
// exactly like the base.
internal fun objectiveBoundOverlay(
    problem: BakedProblem,
    objective: LinearObjective,
): Pair<BakedProblem, MutableObjectiveBound>? {
    val bound = MutableObjectiveBound(objective.constant)
    val factor = ObjectiveBoundFactor.of(objective, bound) ?: return null
    return problem.withAppendedFactor(factor) to bound
}

// The minimize engine tightens this at each incumbent, so the bound factor turns "beat the incumbent" into
// ordinary repair. Inactive until the first incumbent.
internal class MutableObjectiveBound(private val objectiveConstant: Long) {
    var value: Long = Long.MAX_VALUE
        private set

    // For a sum kept in floating point.
    var realLimit: Double = Double.POSITIVE_INFINITY
        private set

    // The next feasible sum must strictly beat [objectiveValue]; a floating-point sum by a relative margin.
    fun tightenBelow(objectiveValue: Double) {
        value = objectiveValue.toLong() - 1 - objectiveConstant
        realLimit = objectiveValue - objectiveConstant - REAL_IMPROVEMENT * maxOf(1.0, abs(objectiveValue))
    }

    private companion object {
        const val REAL_IMPROVEMENT: Double = 1e-9
    }
}

/**
 * LS-only soft constraint `Σ boolWeights·b + Σ intCoeffs·i ≤ bound`, so `cost == 0` means the hard constraints
 * hold and the objective beats the incumbent. Its propagator is [NoPropagator]: branch-and-bound already enforces
 * the objective in CP.
 */
internal class ObjectiveBoundFactor(
    private val objectiveBoolVars: IntArray,
    internal val boolWeights: LongArray,
    private val objectiveIntVars: IntArray,
    internal val intCoeffs: LongArray,
    internal val bound: MutableObjectiveBound,
    internal val realVars: IntArray = emptyIntArray(),
    internal val realCoeffs: DoubleArray = emptyDoubleArray(),
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
        fun of(objective: LinearObjective, bound: MutableObjectiveBound): ObjectiveBoundFactor? {
            val boolVars = objective.boolWeightValues.indices.filter { objective.boolWeightValues[it] != 0L }
            val intVars = objective.intCoefficientValues.indices.filter { objective.intCoefficientValues[it] != 0L }
            val realVars = objective.realCoefficientValues.indices.filter { objective.realCoefficientValues[it] != 0.0 }
            if (boolVars.isEmpty() && intVars.isEmpty() && realVars.isEmpty()) return null
            return ObjectiveBoundFactor(
                boolVars.toIntArray(),
                LongArray(boolVars.size) { objective.boolWeightValues[boolVars[it]] },
                intVars.toIntArray(),
                LongArray(intVars.size) { objective.intCoefficientValues[intVars[it]] },
                bound,
                realVars.toIntArray(),
                DoubleArray(realVars.size) { objective.realCoefficientValues[realVars[it]] },
            )
        }
    }
}

internal class ObjectiveBoundInvariant(
    private val boolVars: IntArray,
    private val boolWeights: LongArray,
    private val intVars: IntArray,
    private val intCoeffs: LongArray,
    private val bound: MutableObjectiveBound,
) : Invariant {

    // Indexed so a move reads one weight instead of scanning an objective that may have thousands of terms.
    private val boolIndex = IntIntMap.build(boolVars, IntArray(boolVars.size) { it }, absent = -1)
    private val intIndex = IntIntMap.build(intVars, IntArray(intVars.size) { it }, absent = -1)

    private fun boolWeightOf(boolVar: Int): Long = boolIndex[boolVar].let { if (it < 0) 0L else boolWeights[it] }

    private fun intCoeffOf(intVar: Int): Long = intIndex[intVar].let { if (it < 0) 0L else intCoeffs[it] }

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

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (state.longPayload[factorId] <= bound.value) return
        for (i in boolVars.indices) {
            val cur = state.assignment.boolValue(boolVars[i])
            if (boolWeights[i] > 0L && cur) sink.addChannelingBoolFlip(state, boolVars[i])
            if (boolWeights[i] < 0L && !cur) sink.addChannelingBoolFlip(state, boolVars[i])
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

// For sums a `Long` cannot carry exactly. The ratchet only steers search and every incumbent is evaluated from its
// assignment, so a rounded sum costs steering accuracy, never a wrong objective.
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

    // Each continuous term moves by the whole overshoot.
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val overshoot = cell(state, factorId)[0] - bound.realLimit
        if (overshoot <= 0.0) return
        for (i in boolVars.indices) {
            val cur = state.assignment.boolValue(boolVars[i])
            if (boolWeights[i] > 0L && cur) sink.addChannelingBoolFlip(state, boolVars[i])
            if (boolWeights[i] < 0L && !cur) sink.addChannelingBoolFlip(state, boolVars[i])
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

// The objective counterpart of needsExactSum.
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
