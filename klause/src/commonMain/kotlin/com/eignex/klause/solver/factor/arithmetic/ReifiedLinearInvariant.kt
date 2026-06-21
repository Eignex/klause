package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Move.BoolFlip
import com.eignex.klause.solver.Move.IntSet
import com.eignex.klause.solver.factor.arithmetic.internals.findCoeff
import com.eignex.klause.solver.factor.bool.linearDegree
import com.eignex.klause.solver.factor.bool.linearHolds
import com.eignex.klause.solver.factor.bool.reifiedDegree
import com.eignex.klause.solver.factor.bool.snapLinearTarget
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink

/** LS invariant for [ReifiedLinear]: reified violation tracking and repair. */
internal class ReifiedLinearInvariant(
    private val auxBoolVar: Int,
    override val boolVars: IntArray,
    override val intVars: IntArray,
    private val coeffs: IntArray,
    private val vars: IntArray,
    private val op: LinearOp,
    private val bound: Int,
) : Invariant {

    override fun initialize(state: LocalSearchState, factorId: Int) {
        var sum = 0L
        for (i in vars.indices) sum += coeffs[i].toLong() * state.assignment.intValue(vars[i])
        state.longPayload[factorId] = sum
    }

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

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val sum = state.longPayload[factorId]
        val coeff = findCoeff(coeffs, vars, intVar)
        val newSum = sum + coeff.toLong() * (newValue - state.assignment.intValue(intVar))
        return reifiedDegreeFor(newSum, aux, state.violationSoftCap) - state.factorDegree[factorId]
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        // aux already flipped in the assignment; report Δdegree (cost is reconciled by the engine).
        val sum = state.longPayload[factorId]
        val aux = state.assignment.boolValue(auxBoolVar)
        return reifiedDegreeFor(sum, aux, state.violationSoftCap) -
            reifiedDegreeFor(sum, !aux, state.violationSoftCap)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val coeff = findCoeff(coeffs, vars, intVar)
        val oldSum = state.longPayload[factorId]
        val newSum = oldSum + coeff.toLong() * (state.assignment.intValue(intVar) - oldValue)
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
            if (c == 0) continue
            val cur = state.assignment.intValue(v)
            val sumWithout = sum - c.toLong() * cur
            // Same-aux snap: shift body so the predicate matches the current aux.
            val targetSame = snapLinearTarget(op, bound, c, sumWithout, aux)
            if (targetSame != null) {
                val clamped = state.problem.intDomains[v].clampLong(targetSame)
                if (clamped != cur && aux == linearHolds(sumWithout + c.toLong() * clamped, op, bound)) {
                    sink.addChannelingIntSet(state, v, clamped)
                }
            }
            // Toggle-driven sub-region exploration: flip aux *and* shift body so the
            // predicate matches the flipped aux.
            val targetOpp = snapLinearTarget(op, bound, c, sumWithout, !aux)
            if (targetOpp != null) {
                val clamped = state.problem.intDomains[v].clampLong(targetOpp)
                if (clamped != cur && !aux == linearHolds(sumWithout + c.toLong() * clamped, op, bound)) {
                    sink.addCompound(listOf(auxFlipMove, IntSet(v, clamped)))
                }
            }
        }
    }

    override val maintainsBreakMakeIncrementally: Boolean get() = true

    /** [boolVars] contains only [auxBoolVar], so a bool flip is always an aux flip. Flipping
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
    override fun updateIntBreakMakeForIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int) {
        val newSum = state.longPayload[factorId]
        val coeff = findCoeff(coeffs, vars, intVar)
        val newValue = state.assignment.intValue(intVar)
        val oldSum = newSum - coeff.toLong() * (newValue - oldValue)
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
