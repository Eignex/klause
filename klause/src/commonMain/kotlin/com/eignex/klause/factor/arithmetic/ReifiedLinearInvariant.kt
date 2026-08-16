package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.arithmetic.internals.LinearCoeffIndex
import com.eignex.klause.factor.arithmetic.internals.initLinearSum
import com.eignex.klause.factor.bool.internals.linearDegree
import com.eignex.klause.factor.bool.internals.linearHolds
import com.eignex.klause.factor.bool.internals.reifiedDegree
import com.eignex.klause.factor.bool.internals.snapLinearTarget
import com.eignex.klause.localsearch.ChannelingSink
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move.BoolFlip
import com.eignex.klause.localsearch.Move.IntSet
import com.eignex.klause.localsearch.MoveSink

/** LS invariant for [ReifiedLinear]: reified violation tracking and repair. */
internal class ReifiedLinearInvariant(
    private val auxBoolVar: Int,
    private val coeffs: LongArray,
    private val vars: IntArray,
    private val op: LinearOp,
    private val bound: Long,
) : Invariant {

    // O(1) coefficient queries keep wide-row move scoring linear; see [LinearCoeffIndex].
    private val coeffIndex = LinearCoeffIndex(coeffs, vars)

    override fun initialize(state: LocalSearchState, factorId: Int) = initLinearSum(state, factorId, coeffs, vars)

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        state.assignment.boolValue(auxBoolVar) != linearHolds(state.longPayload[factorId], op, bound)

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val sum = state.longPayload[factorId]
        return reifiedDegreeFor(sum, aux, state.violationSoftCap)
    }

    /** Violation degree for the reified linear given [sum], reification value [aux], and [softCap]. */
    private fun reifiedDegreeFor(sum: Long, aux: Boolean, softCap: Int): Int =
        reifiedDegree(aux, linearHolds(sum, op, bound)) { linearDegree(sum, op, bound, softCap) }

    // The pre-move degree `degreeFor(sum, aux)` is the factor's current violation degree, already
    // maintained in factorDegree — reuse it instead of re-running the residual/compression.
    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val sum = state.longPayload[factorId]
        val aux = state.assignment.boolValue(auxBoolVar)
        return reifiedDegreeFor(sum, !aux, state.violationSoftCap) - state.factorDegree[factorId]
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val sum = state.longPayload[factorId]
        val coeff = coeffIndex.coeffOf(intVar)
        val newSum = sum + coeff * (newValue - state.assignment.intValue(intVar))
        return reifiedDegreeFor(newSum, aux, state.violationSoftCap) - state.factorDegree[factorId]
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        // aux already flipped in the assignment; report Δdegree (cost is reconciled by the engine).
        val sum = state.longPayload[factorId]
        val aux = state.assignment.boolValue(auxBoolVar)
        return reifiedDegreeFor(sum, aux, state.violationSoftCap) -
            reifiedDegreeFor(sum, !aux, state.violationSoftCap)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val coeff = coeffIndex.coeffOf(intVar)
        val oldSum = state.longPayload[factorId]
        val newSum = oldSum + coeff * (state.assignment.intValue(intVar) - oldValue)
        state.longPayload[factorId] = newSum
        return reifiedDegreeFor(newSum, aux, state.violationSoftCap) -
            reifiedDegreeFor(oldSum, aux, state.violationSoftCap)
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val aux = state.assignment.boolValue(auxBoolVar)
        val sum = state.longPayload[factorId]
        if (aux == linearHolds(sum, op, bound)) return
        sink.addBoolFlip(auxBoolVar)
        val auxFlipMove = BoolFlip(auxBoolVar)
        for (i in vars.indices) {
            val v = vars[i]
            val c = coeffs[i]
            if (c == 0L) continue
            val cur = state.assignment.intValue(v)
            val sumWithout = sum - c * cur
            // Same-aux snap: shift body so the predicate matches the current aux.
            val targetSame = snapLinearTarget(op, bound, c, sumWithout, aux)
            if (targetSame != null) {
                val clamped = state.rootDomains[v].clamp(targetSame)
                if (clamped != cur && aux == linearHolds(sumWithout + c * clamped, op, bound)) {
                    sink.addChannelingIntSet(state, v, clamped)
                }
            }
            // Toggle-driven sub-region exploration: flip aux *and* shift body so the
            // predicate matches the flipped aux.
            val targetOpp = snapLinearTarget(op, bound, c, sumWithout, !aux)
            if (targetOpp != null) {
                val clamped = state.rootDomains[v].clamp(targetOpp)
                if (clamped != cur && !aux == linearHolds(sumWithout + c * clamped, op, bound)) {
                    sink.addCompound(listOf(auxFlipMove, IntSet(v, clamped)))
                }
            }
        }
    }

    /** Single-var EQ indicator channeling: when the body var is set to a new value, flip the aux iff
     *  that changes the truth of `coeff·v == bound`, so the indicator tracks the value in one move. */
    override fun contributeChanneling(
        state: LocalSearchState,
        factorId: Int,
        intVar: Int,
        oldValue: Long,
        newValue: Long,
        sink: ChannelingSink,
    ) {
        if (vars.size != 1 || op != LinearOp.EQ) return
        if (state.assumptions.isFrozenBool(auxBoolVar)) return
        val shouldHold = coeffs[0] * newValue == bound
        if (state.assignment.boolValue(auxBoolVar) != shouldHold) sink.add(BoolFlip(auxBoolVar))
    }

    override val maintainsBreakMakeIncrementally: Boolean get() = true

    /** The bool scope contains only [auxBoolVar], so a bool flip is always an aux flip. Flipping
     *  aux always toggles violation (sum unchanged), so the aux's own contribution simply
     *  swaps between break and make. */
    override fun updateBoolBreakMakeForFlip(state: LocalSearchState, factorId: Int, flippedVar: Int) {
        val nowViolated = state.assignment.boolValue(auxBoolVar) !=
            linearHolds(state.longPayload[factorId], op, bound)
        if (nowViolated) {
            state.boolBreakCount[auxBoolVar]--
            state.boolMakeCount[auxBoolVar]++
        } else {
            state.boolMakeCount[auxBoolVar]--
            state.boolBreakCount[auxBoolVar]++
        }
    }

    override val maintainsIntBreakMakeIncrementallyForIntSet: Boolean get() = true

    /** Aux's break/make contribution depends only on `holds(sum)` and `aux`. An int set
     *  may flip `holds`, in which case the aux's contribution swaps; otherwise no change. */
    override fun updateIntBreakMakeForIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long) {
        val newSum = state.longPayload[factorId]
        val coeff = coeffIndex.coeffOf(intVar)
        val newValue = state.assignment.intValue(intVar)
        val oldSum = newSum - coeff * (newValue - oldValue)
        val oldHolds = linearHolds(oldSum, op, bound)
        val newHolds = linearHolds(newSum, op, bound)
        if (oldHolds == newHolds) return
        val aux = state.assignment.boolValue(auxBoolVar)
        val newViolated = aux != newHolds
        // oldViolated != newViolated, so the aux's contribution swaps break↔make.
        if (newViolated) {
            state.boolBreakCount[auxBoolVar]--
            state.boolMakeCount[auxBoolVar]++
        } else {
            state.boolMakeCount[auxBoolVar]--
            state.boolBreakCount[auxBoolVar]++
        }
    }
}
