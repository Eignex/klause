package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.factor.compressViolation
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import kotlin.math.abs

/** LS invariant for [Product]: violation tracking and repair for `a * b = result`. */
internal class ProductInvariant(
    private val a: Int,
    private val b: Int,
    private val result: Int,
    override val boolVars: IntArray,
    override val intVars: IntArray,
) : Invariant {

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val av = state.assignment.intValue(a)
        val bv = state.assignment.intValue(b)
        val rv = state.assignment.intValue(result)
        return av.toLong() * bv != rv.toLong()
    }

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int {
        val av = state.assignment.intValue(a).toLong()
        val bv = state.assignment.intValue(b).toLong()
        val rv = state.assignment.intValue(result).toLong()
        return compressViolation(abs(av * bv - rv), state.violationSoftCap)
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val av = state.assignment.intValue(a).toLong()
        val bv = state.assignment.intValue(b).toLong()
        val rv = state.assignment.intValue(result).toLong()
        val nv = newValue.toLong()
        val after = when (intVar) {
            a -> abs(nv * bv - rv)
            b -> abs(av * nv - rv)
            result -> abs(av * bv - nv)
            else -> return 0
        }
        return compressViolation(after, state.violationSoftCap) -
            compressViolation(abs(av * bv - rv), state.violationSoftCap)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val av = state.assignment.intValue(a).toLong()
        val bv = state.assignment.intValue(b).toLong()
        val rv = state.assignment.intValue(result).toLong()
        val ov = oldValue.toLong()
        val before = when (intVar) {
            a -> abs(ov * bv - rv)
            b -> abs(av * ov - rv)
            result -> abs(av * bv - ov)
            else -> return 0
        }
        return compressViolation(abs(av * bv - rv), state.violationSoftCap) -
            compressViolation(before, state.violationSoftCap)
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val av = state.assignment.intValue(a)
        val bv = state.assignment.intValue(b)
        val rv = state.assignment.intValue(result)
        if (av.toLong() * bv == rv.toLong()) return
        val rTarget = av.toLong() * bv
        val rDomain = state.problem.intDomains[result]
        val rClamped = rDomain.clampLong(rTarget)
        if (rClamped.toLong() == rTarget && rClamped != rv) sink.addChannelingIntSet(state, result, rClamped)
        if (bv != 0 && rv % bv == 0) {
            val aTarget = rv / bv
            val aClamped = state.problem.intDomains[a].clamp(aTarget)
            if (aClamped == aTarget && aClamped != av) sink.addChannelingIntSet(state, a, aClamped)
        } else if (bv != 0) {
            proposeClosestOperand(state, operandVar = a, otherValue = bv, currentValue = av, sink)
        }
        if (av != 0 && rv % av == 0) {
            val bTarget = rv / av
            val bClamped = state.problem.intDomains[b].clamp(bTarget)
            if (bClamped == bTarget && bClamped != bv) sink.addChannelingIntSet(state, b, bClamped)
        } else if (av != 0) {
            proposeClosestOperand(state, operandVar = b, otherValue = av, currentValue = bv, sink)
        }
        val rvL = rv.toLong()
        val curResidual = abs(av.toLong() * bv - rvL)
        for (v in intArrayOf(a, b, result)) {
            val cur = state.assignment.intValue(v)
            val d = state.problem.intDomains[v]
            for (cand in intArrayOf(cur + 1, cur - 1)) {
                if (cand !in d) continue
                val res = when (v) {
                    a -> abs(cand.toLong() * bv - rvL)
                    b -> abs(av.toLong() * cand - rvL)
                    else -> abs(av.toLong() * bv - cand.toLong())
                }
                if (res <= curResidual) sink.addChannelingIntSet(state, v, cand)
            }
        }
    }

    private fun proposeClosestOperand(
        state: LocalSearchState,
        operandVar: Int,
        otherValue: Int,
        currentValue: Int,
        sink: MoveSink,
    ) {
        if (otherValue == 0) return
        val rv = state.assignment.intValue(result)
        val center = rv / otherValue
        val domain = state.problem.intDomains[operandVar]
        var bestCandidate = currentValue
        var bestError = abs(currentValue.toLong() * otherValue - rv)
        for (delta in -2..2) {
            val cand = center + delta
            if (cand !in domain) continue
            val error = abs(cand.toLong() * otherValue - rv)
            if (error < bestError) {
                bestError = error
                bestCandidate = cand
            }
        }
        if (bestCandidate != currentValue) sink.addChannelingIntSet(state, operandVar, bestCandidate)
    }
}
