package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.factor.ceilDivLong
import com.eignex.klause.solver.factor.floorDivLong

/**
 * `a * b = result`. Operates on signed integer domains (any min/max). The bit-blaster lowers
 * via an unsigned shift-and-add multiplier on absolute values, then conditionally negates the
 * product based on the operand sign bits.
 *
 * No payload: the product is recomputed in O(1) from the current assignment on each query.
 */
class Product(
    val a: Int,
    val b: Int,
    val result: Int,
) : LocalSearchFactor {

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = intArrayOf(a, b, result)

    override fun initialize(state: LocalSearchState, factorId: Int) {}

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val av = state.assignment.intValue(a)
        val bv = state.assignment.intValue(b)
        val rv = state.assignment.intValue(result)
        return av * bv != rv
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val av = state.assignment.intValue(a)
        val bv = state.assignment.intValue(b)
        val rv = state.assignment.intValue(result)
        val was = av * bv != rv
        val will = when (intVar) {
            a -> newValue * bv != rv
            b -> av * newValue != rv
            result -> av * bv != newValue
            else -> return 0
        }
        return (if (will) 1 else 0) - (if (was) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val av = state.assignment.intValue(a)
        val bv = state.assignment.intValue(b)
        val rv = state.assignment.intValue(result)
        val now = av * bv != rv
        val was = when (intVar) {
            a -> oldValue * bv != rv
            b -> av * oldValue != rv
            result -> av * bv != oldValue
            else -> return 0
        }
        return (if (now) 1 else 0) - (if (was) 1 else 0)
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // Forward: result ⊆ corner product range of (a, b).
        val da = state.intDomains[a]
        val db = state.intDomains[b]
        val (pLo, pHi) = cornerProductRange(da, db)
        if (!tightenLong(state, result, pLo, pHi)) return false
        // Reverse, singleton-operand only. Non-singleton interval division is sound but
        // produced unstable worklist interactions with bit-blasted Product chains in the
        // earlier attempt; the singleton case is the high-value sub-case (constant factor
        // arithmetic, channelled mod constraints) and is provably stable since `tighten*`
        // is monotonic.
        val dbAfter = state.intDomains[b]
        val daAfter = state.intDomains[a]
        val dr = state.intDomains[result]
        if (dbAfter.min == dbAfter.max && dbAfter.min != 0) {
            if (!narrowByDivisor(state, target = a, divisor = dbAfter.min.toLong(), r = dr)) return false
        }
        if (daAfter.min == daAfter.max && daAfter.min != 0) {
            if (!narrowByDivisor(state, target = b, divisor = daAfter.min.toLong(), r = state.intDomains[result])) return false
        }
        return true
    }

    /**
     * Narrow [target]'s domain so that `target * divisor ∈ [r.min, r.max]`. [divisor] must
     * be non-zero. Handles signed division by flipping the bound order when [divisor] < 0.
     */
    private fun narrowByDivisor(
        state: PropagationState,
        target: Int,
        divisor: Long,
        r: IntDomain,
    ): Boolean {
        val rLo = r.min.toLong()
        val rHi = r.max.toLong()
        val lo: Long
        val hi: Long
        if (divisor > 0) {
            lo = ceilDivLong(rLo, divisor)
            hi = floorDivLong(rHi, divisor)
        } else {
            lo = ceilDivLong(rHi, divisor)
            hi = floorDivLong(rLo, divisor)
        }
        if (lo > hi) return false
        return tightenLong(state, target, lo, hi)
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val av = state.assignment.intValue(a)
        val bv = state.assignment.intValue(b)
        val rv = state.assignment.intValue(result)
        if (av * bv == rv) return
        // Candidate 1: snap result = a*b.
        val rTarget = av * bv
        val rDomain = state.problem.intDomains[result]
        val rClamped = rDomain.clamp(rTarget)
        if (rClamped == rTarget && rClamped != rv) sink.addIntSet(result, rClamped)
        // Candidate 2: if b ≠ 0 and result divisible by b, snap a = result/b.
        if (bv != 0 && rv % bv == 0) {
            val aTarget = rv / bv
            val aClamped = state.problem.intDomains[a].clamp(aTarget)
            if (aClamped == aTarget && aClamped != av) sink.addIntSet(a, aClamped)
        } else if (bv != 0) {
            // Secondary candidate: closest a in domain whose product with b approaches result.
            proposeClosestOperand(state, operandVar = a, otherValue = bv, currentValue = av, sink)
        }
        // Candidate 3: if a ≠ 0 and result divisible by a, snap b = result/a.
        if (av != 0 && rv % av == 0) {
            val bTarget = rv / av
            val bClamped = state.problem.intDomains[b].clamp(bTarget)
            if (bClamped == bTarget && bClamped != bv) sink.addIntSet(b, bClamped)
        } else if (av != 0) {
            proposeClosestOperand(state, operandVar = b, otherValue = av, currentValue = bv, sink)
        }
        // Fall back to ±1 nudges if none of the snap candidates apply.
        for (v in intArrayOf(a, b, result)) {
            val cur = state.assignment.intValue(v)
            val d = state.problem.intDomains[v]
            if (cur < d.max) sink.addIntSet(v, cur + 1)
            if (cur > d.min) sink.addIntSet(v, cur - 1)
        }
    }

    /**
     * Propose snapping [operandVar] to the value in its domain whose product with [otherValue]
     * is closest to the current `result`. Walks a small window around `result / otherValue` so
     * the search stays O(1).
     */
    private fun proposeClosestOperand(
        state: LocalSearchState,
        operandVar: Int,
        otherValue: Int,
        currentValue: Int,
        sink: MoveSink,
    ) {
        if (otherValue == 0) return
        val rv = state.assignment.intValue(result)
        val center = rv / otherValue   // truncated
        val domain = state.problem.intDomains[operandVar]
        var bestCandidate = currentValue
        var bestError = kotlin.math.abs(currentValue.toLong() * otherValue - rv)
        for (delta in -2..2) {
            val cand = center + delta
            if (cand !in domain.min..domain.max) continue
            val error = kotlin.math.abs(cand.toLong() * otherValue - rv)
            if (error < bestError) {
                bestError = error
                bestCandidate = cand
            }
        }
        if (bestCandidate != currentValue) sink.addIntSet(operandVar, bestCandidate)
    }

    /**
     * Min/max of the four corner products of two integer intervals. Correct under signed
     * integer arithmetic (handles zero crossing) because all four products are evaluated;
     * uses `Long` to dodge `Int` overflow.
     */
    private fun cornerProductRange(da: IntDomain, db: IntDomain): Pair<Long, Long> {
        val p1 = da.min.toLong() * db.min
        val p2 = da.min.toLong() * db.max
        val p3 = da.max.toLong() * db.min
        val p4 = da.max.toLong() * db.max
        return minOf(p1, p2, p3, p4) to maxOf(p1, p2, p3, p4)
    }

private fun tightenLong(state: PropagationState, v: Int, lo: Long, hi: Long): Boolean {
        if (lo > Int.MAX_VALUE || hi < Int.MIN_VALUE) return false
        val loI = if (lo < Int.MIN_VALUE) Int.MIN_VALUE else lo.toInt()
        val hiI = if (hi > Int.MAX_VALUE) Int.MAX_VALUE else hi.toInt()
        if (!state.tightenIntMin(v, loI)) return false
        if (!state.tightenIntMax(v, hiI)) return false
        return true
    }}
