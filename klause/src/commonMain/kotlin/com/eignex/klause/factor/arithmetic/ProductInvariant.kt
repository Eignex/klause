package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.compressViolation
import com.eignex.klause.factor.distance
import com.eignex.klause.factor.saturatedAdd
import com.eignex.klause.factor.saturatedMul
import com.eignex.klause.factor.saturatedSub
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink

/** LS invariant for [Product]: violation tracking and repair for `a * b = result`. */
internal class ProductInvariant(private val a: Int, private val b: Int, private val result: Int) : Invariant {

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val av = state.assignment.intValue(a)
        val bv = state.assignment.intValue(b)
        val rv = state.assignment.intValue(result)
        return saturatedMul(av, bv) != rv
    }

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int {
        val av = state.assignment.intValue(a)
        val bv = state.assignment.intValue(b)
        val rv = state.assignment.intValue(result)
        return compressViolation(residual(av, bv, rv), state.violationSoftCap)
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int {
        val av = state.assignment.intValue(a)
        val bv = state.assignment.intValue(b)
        val rv = state.assignment.intValue(result)
        val nv = newValue
        val after = when (intVar) {
            a -> residual(nv, bv, rv)
            b -> residual(av, nv, rv)
            result -> residual(av, bv, nv)
            else -> return 0
        }
        return compressViolation(after, state.violationSoftCap) -
            compressViolation(residual(av, bv, rv), state.violationSoftCap)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int {
        val av = state.assignment.intValue(a)
        val bv = state.assignment.intValue(b)
        val rv = state.assignment.intValue(result)
        val ov = oldValue
        val before = when (intVar) {
            a -> residual(ov, bv, rv)
            b -> residual(av, ov, rv)
            result -> residual(av, bv, ov)
            else -> return 0
        }
        return compressViolation(residual(av, bv, rv), state.violationSoftCap) -
            compressViolation(before, state.violationSoftCap)
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val av = state.assignment.intValue(a)
        val bv = state.assignment.intValue(b)
        val rv = state.assignment.intValue(result)
        if (saturatedMul(av, bv) == rv) return
        val rTarget = saturatedMul(av, bv)
        val rDomain = state.rootDomains[result]
        val rClamped = rDomain.clamp(rTarget)
        if (rClamped == rTarget && rClamped != rv) sink.addChannelingIntSet(state, result, rClamped)
        if (bv != 0L && rv % bv == 0L) {
            val aTarget = rv / bv
            val aClamped = state.rootDomains[a].clamp(aTarget)
            if (aClamped == aTarget && aClamped != av) sink.addChannelingIntSet(state, a, aClamped)
        } else if (bv != 0L) {
            proposeClosestOperand(state, operandVar = a, otherValue = bv, currentValue = av, sink)
        }
        if (av != 0L && rv % av == 0L) {
            val bTarget = rv / av
            val bClamped = state.rootDomains[b].clamp(bTarget)
            if (bClamped == bTarget && bClamped != bv) sink.addChannelingIntSet(state, b, bClamped)
        } else if (av != 0L) {
            proposeClosestOperand(state, operandVar = b, otherValue = av, currentValue = bv, sink)
        }
        val rvL = rv
        val curResidual = residual(av, bv, rvL)
        for (v in intArrayOf(a, b, result)) {
            val cur = state.assignment.intValue(v)
            val d = state.rootDomains[v]
            for (cand in longArrayOf(saturatedAdd(cur, 1L), saturatedSub(cur, 1L))) {
                if (cand !in d) continue
                val res = when (v) {
                    a -> residual(cand, bv, rvL)
                    b -> residual(av, cand, rvL)
                    else -> residual(av, bv, cand)
                }
                if (res <= curResidual) sink.addChannelingIntSet(state, v, cand)
            }
        }
    }

    // |x·y − r| without wrapping, so a product past the Long range still reads as violated.
    private fun residual(x: Long, y: Long, r: Long): Long = distance(saturatedMul(x, y), r)

    private fun proposeClosestOperand(
        state: LocalSearchState,
        operandVar: Int,
        otherValue: Long,
        currentValue: Long,
        sink: MoveSink,
    ) {
        if (otherValue == 0L) return
        val rv = state.assignment.intValue(result)
        val center = rv / otherValue
        val domain = state.rootDomains[operandVar]
        var bestCandidate = currentValue
        var bestError = residual(currentValue, otherValue, rv)
        for (delta in -2..2) {
            val cand = saturatedAdd(center, delta.toLong())
            if (cand !in domain) continue
            val error = residual(cand, otherValue, rv)
            if (error < bestError) {
                bestError = error
                bestCandidate = cand
            }
        }
        if (bestCandidate != currentValue) sink.addChannelingIntSet(state, operandVar, bestCandidate)
    }
}
