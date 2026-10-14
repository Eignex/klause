package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.compressViolation
import com.eignex.klause.factor.distance
import com.eignex.klause.factor.saturatedAdd
import com.eignex.klause.factor.saturatedMul
import com.eignex.klause.factor.saturatedSub
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.util.Int128

internal class ProductInvariant(private val a: Int, private val b: Int, private val result: Int) : Invariant {

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val av = state.assignment.intValue(a)
        val bv = state.assignment.intValue(b)
        val rv = state.assignment.intValue(result)
        return residual(av, bv, rv) != 0L
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
        if (intVar != a && intVar != b && intVar != result) return 0
        val after = residual(
            if (intVar == a) newValue else av,
            if (intVar == b) newValue else bv,
            if (intVar == result) newValue else rv,
        )
        return compressViolation(after, state.violationSoftCap) -
            compressViolation(residual(av, bv, rv), state.violationSoftCap)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int {
        val av = state.assignment.intValue(a)
        val bv = state.assignment.intValue(b)
        val rv = state.assignment.intValue(result)
        if (intVar != a && intVar != b && intVar != result) return 0
        val before = residual(
            if (intVar == a) oldValue else av,
            if (intVar == b) oldValue else bv,
            if (intVar == result) oldValue else rv,
        )
        return compressViolation(residual(av, bv, rv), state.violationSoftCap) -
            compressViolation(before, state.violationSoftCap)
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val av = state.assignment.intValue(a)
        val bv = state.assignment.intValue(b)
        val rv = state.assignment.intValue(result)
        if (residual(av, bv, rv) == 0L) return
        val rTarget = saturatedMul(av, bv)
        val rDomain = state.rootDomains[result]
        val rClamped = rDomain.clamp(rTarget)
        if (rClamped == rTarget && rClamped != rv) proposeIfImproving(state, result, rClamped, sink)
        if (bv != 0L && rv % bv == 0L) {
            val aTarget = rv / bv
            val aClamped = state.rootDomains[a].clamp(aTarget)
            if (aClamped == aTarget && aClamped != av) proposeIfImproving(state, a, aClamped, sink)
        } else if (bv != 0L) {
            proposeClosestOperand(state, operandVar = a, otherValue = bv, currentValue = av, sink)
        }
        if (av != 0L && rv % av == 0L) {
            val bTarget = rv / av
            val bClamped = state.rootDomains[b].clamp(bTarget)
            if (bClamped == bTarget && bClamped != bv) proposeIfImproving(state, b, bClamped, sink)
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
                val res = residual(
                    if (v == a) cand else av,
                    if (v == b) cand else bv,
                    if (v == result) cand else rvL,
                )
                if (res <= curResidual) sink.addChannelingIntSet(state, v, cand)
            }
        }
    }

    // Capping after subtraction preserves a nonzero residual at signed-word endpoints.
    private fun residual(x: Long, y: Long, r: Long): Long {
        val product = x * y
        if (x == 0L || (product / x == y && !(x == -1L && y == Long.MIN_VALUE))) {
            return distance(product, r)
        }
        val exact = Int128()
        exact.addProduct(x, y)
        if (r == Long.MIN_VALUE) {
            exact.addLong(Long.MAX_VALUE)
            exact.addLong(1L)
        } else {
            exact.addLong(-r)
        }
        return if (exact.fitsLong()) distance(exact.toLong(), 0L) else Long.MAX_VALUE
    }

    private fun residualAfterSet(state: LocalSearchState, intVar: Int, value: Long): Long = residual(
        if (intVar == a) value else state.assignment.intValue(a),
        if (intVar == b) value else state.assignment.intValue(b),
        if (intVar == result) value else state.assignment.intValue(result),
    )

    private fun proposeIfImproving(state: LocalSearchState, intVar: Int, value: Long, sink: MoveSink) {
        if (residualAfterSet(state, intVar, value) <=
            residual(state.assignment.intValue(a), state.assignment.intValue(b), state.assignment.intValue(result))
        ) sink.addChannelingIntSet(state, intVar, value)
    }

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
        var bestError = residualAfterSet(state, operandVar, currentValue)
        for (delta in -2..2) {
            val cand = saturatedAdd(center, delta.toLong())
            if (cand !in domain) continue
            val error = residualAfterSet(state, operandVar, cand)
            if (error < bestError) {
                bestError = error
                bestCandidate = cand
            }
        }
        if (bestCandidate != currentValue) sink.addChannelingIntSet(state, operandVar, bestCandidate)
    }
}
