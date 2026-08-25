package com.eignex.klause.factor.objective

import com.eignex.klause.factor.compressViolation
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.propagation.NoPropagator
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.BakedProblem
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.KeySink
import com.eignex.klause.solver.MixedVars
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.VarList
import com.eignex.klause.solver.hashRemappedKey
import com.eignex.klause.solver.materializeKey
import com.eignex.klause.solver.objective.LinearObjective

/**
 * The [problem] with an objective-bound factor for [objective] appended (the ratchet arm's overlay),
 * paired with the shared [MutableObjectiveBound] the minimize engine tightens at each incumbent; `null`
 * when the objective has no variable terms. The appended factor is LS-only ([NoPropagator]), so the
 * overlay's bake is identical to the base — the extra factor adds one LS invariant and its occurrence
 * entries, nothing more.
 */
internal fun objectiveBoundOverlay(
    problem: Problem,
    objective: LinearObjective,
): Pair<Problem, MutableObjectiveBound>? {
    val bound = MutableObjectiveBound(objective.constant)
    val factor = ObjectiveBoundFactor.of(objective, bound) ?: return null
    return problem.withAppendedFactor(factor) to bound
}

/** A copy of this problem with [extra] appended to its factors, reusing the base's bake rather than
 *  paying a fresh one. The appended factor is [NoPropagator], so it changes nothing the bake would
 *  derive; the base's domains are already folded, so `alreadyFolded` skips the redundant construction-time
 *  fold and `seedDeductions` carries the base's proven deductions (a no-op on already-tightened domains)
 *  so the deferred bake stays exact. An implied-factor mask grows by one non-implied slot. */
private fun Problem.withAppendedFactor(extra: Factor): BakedProblem = BakedProblem(
    numBoolVars = numBoolVars,
    numIntVars = numIntVars,
    intDomains = requireFiniteIntDomains(),
    factors = factors + extra,
    seedDeductions = baked,
    cancellation = cancellation,
    impliedFactorMask = impliedFactorMask?.let { it + false },
    hasSymmetryBreaking = hasSymmetryBreaking,
    alreadyFolded = true,
)

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

    /** Tighten so the next feasible sum must strictly beat [objectiveValue] (`objective = sum +
     *  constant`), i.e. `sum ≤ objectiveValue − 1 − constant`. */
    fun tightenBelow(objectiveValue: Double) {
        value = objectiveValue.toLong() - 1 - objectiveConstant
    }
}

/**
 * A local-search-only soft constraint `Σ boolWeights·b + Σ intCoeffs·i ≤ bound` over the objective's
 * decision variables, sharing a [MutableObjectiveBound] that the minimize engine ratchets down at each
 * incumbent. Injected as an extra factor into one arm's [com.eignex.klause.solver.Problem] overlay so
 * `cost == 0` means "hard constraints satisfied AND objective beats the incumbent" — turning objective
 * optimization back into violation repair for the SAT-style feasibility arms (probSAT / WalkSAT).
 *
 * [asPropagator] is [NoPropagator]: the bound is an LS artifact and must never enter CP (the objective
 * is already enforced there through branch-and-bound), so this factor belongs only in an LS overlay.
 */
internal class ObjectiveBoundFactor(
    private val objectiveBoolVars: IntArray,
    private val boolWeights: LongArray,
    private val objectiveIntVars: IntArray,
    private val intCoeffs: LongArray,
    private val bound: MutableObjectiveBound,
) : Factor {

    override val variables: VarList = MixedVars(spanInts = objectiveIntVars, boolVars = objectiveBoolVars)

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = ObjectiveBoundFactor(
        IntArray(boolVars.size) { boolMap[boolVars[it]] },
        boolWeights,
        IntArray(intVars.size) { intMap[intVars[it]] },
        intCoeffs,
        bound,
    )

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.OBJECTIVE_BOUND, ::buildKey)

    override fun remapStructuralHash(boolMap: IntArray, intMap: IntArray): Int =
        hashRemappedKey(FactorKind.OBJECTIVE_BOUND, boolMap, intMap, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.sortedBoolVars(boolVars)
        sink.sortedIntVars(intVars)
    }

    override fun asPropagator(): Propagator = NoPropagator

    override fun asInvariant(): Invariant = ObjectiveBoundInvariant(boolVars, boolWeights, intVars, intCoeffs, bound)

    companion object {
        /** The objective-bound factor for [objective] sharing [bound], or `null` when the objective has
         *  no variable terms (nothing to bound). Keeps only the nonzero-weight/coefficient variables. */
        fun of(objective: LinearObjective, bound: MutableObjectiveBound): ObjectiveBoundFactor? {
            val boolVars = objective.boolWeights.indices.filter { objective.boolWeights[it] != 0L }
            val intVars = objective.intCoefficients.indices.filter { objective.intCoefficients[it] != 0L }
            if (boolVars.isEmpty() && intVars.isEmpty()) return null
            return ObjectiveBoundFactor(
                boolVars.toIntArray(),
                LongArray(boolVars.size) { objective.boolWeights[boolVars[it]] },
                intVars.toIntArray(),
                LongArray(intVars.size) { objective.intCoefficients[intVars[it]] },
                bound,
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
private class ObjectiveBoundInvariant(
    private val boolVars: IntArray,
    private val boolWeights: LongArray,
    private val intVars: IntArray,
    private val intCoeffs: LongArray,
    private val bound: MutableObjectiveBound,
) : Invariant {

    private fun boolWeightOf(boolVar: Int): Long {
        for (i in boolVars.indices) if (boolVars[i] == boolVar) return boolWeights[i]
        return 0L
    }

    private fun intCoeffOf(intVar: Int): Long {
        for (i in intVars.indices) if (intVars[i] == intVar) return intCoeffs[i]
        return 0L
    }

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
            if (intCoeffs[i] > 0L && cur > d.min) sink.addChannelingIntSet(state, v, cur - 1)
            if (intCoeffs[i] < 0L && cur < d.max) sink.addChannelingIntSet(state, v, cur + 1)
        }
    }
}
