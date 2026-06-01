package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import kotlin.math.abs

/**
 * `a * b = result`. Operates on signed integer domains (any min/max). The bit-blaster lowers
 * via an unsigned shift-and-add multiplier on absolute values, then conditionally negates the
 * product based on the operand sign bits.
 *
 * No payload: the product is recomputed in O(1) from the current assignment on each query.
 */
class Product(
    /** First factor variable id. */
    val a: Int,
    /** Second factor variable id. */
    val b: Int,
    /** Result variable id (`result = a * b`). */
    val result: Int,
) : LocalSearchFactor {

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = intArrayOf(a, b, result)

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

    /** Bound-only conflict reason. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectLinearTightenAntecedents(state, intVars, excludeIdx = -1, extraLit = 0)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // Forward: result ⊆ corner product range of (a, b).
        val da = state.intDomains[a]
        val db = state.intDomains[b]
        val (pLo, pHi) = cornerProductRange(da, db)
        if (!tightenLong(state, result, pLo, pHi, state.composeIntVarAtomAntecedents(intArrayOf(a, b)))) return false

        // Reverse — narrow `a` from result/b, then narrow `b` from result/a.
        //
        // The divisor must not contain zero (a/0 is undefined); the propagator skips that
        // case and lets the forward direction + future singleton refinements handle it
        // once one side is pinned. Within that constraint we handle two regimes:
        //   - singleton divisor (the original safe path): exact integer division.
        //   - non-singleton divisor: corner-division interval bounds over the four
        //     `(r-endpoint, d-endpoint)` pairs, ceiling for the lower bound and flooring
        //     for the upper. Both are monotonic in their inputs — tightening either
        //     operand can only shrink the computed target range — so worklist iteration
        //     reaches fixpoint cleanly without the bit-blast feedback that destabilised
        //     the earlier general-interval attempt (which used a different signed-division
        //     formulation susceptible to non-monotone updates on zero-crossing inputs).
        val dr = state.intDomains[result]
        val dbAfter = state.intDomains[b]
        if (!reverseNarrow(state, target = a, divisorDomain = dbAfter, r = dr)) return false
        val daAfter = state.intDomains[a]
        if (!reverseNarrow(state, target = b, divisorDomain = daAfter, r = state.intDomains[result])) return false

        // Zero-exclusion: if result's domain is strictly non-zero (0 ∉ result), then
        // neither a nor b can be zero (since 0·x = 0 ∉ result). With contiguous-interval
        // domains we can only push 0 out when it sits on an endpoint, but that's the
        // common case after upstream propagation has shaved most of the domain.
        val drFinal = state.intDomains[result]
        if (0 !in drFinal.min..drFinal.max) {
            val antR = state.composeIntVarAtomAntecedents(intArrayOf(result))
            val daFinal = state.intDomains[a]
            if (daFinal.min == 0 && !state.tightenIntMin(a, 1, antR)) return false
            if (daFinal.max == 0 && !state.tightenIntMax(a, -1, antR)) return false
            val dbFinal = state.intDomains[b]
            if (dbFinal.min == 0 && !state.tightenIntMin(b, 1, antR)) return false
            if (dbFinal.max == 0 && !state.tightenIntMax(b, -1, antR)) return false
        }
        return true
    }

    /**
     * Narrow [target]'s domain so that `target * d ∈ r` for some `d ∈ divisorDomain`.
     * Requires `0 ∉ divisorDomain` — caller's responsibility (we exit cleanly when it
     * isn't). Dispatches to the existing singleton-only fast path or to corner-division
     * for the general case.
     */
    private fun reverseNarrow(state: PropagationState, target: Int, divisorDomain: IntDomain, r: IntDomain): Boolean {
        if (0 in divisorDomain.min..divisorDomain.max) return true // skip: zero-crossing
        if (divisorDomain.min == divisorDomain.max) {
            return narrowByDivisor(state, target, divisorDomain.min.toLong(), r)
        }
        // Corner-division interval bounds. Four (r-endpoint, d-endpoint) pairs span the
        // continuous-division range; ceiling each gives a lower-bound candidate, flooring
        // each gives an upper-bound candidate. The min of ceilings and max of floors are
        // the tightest integer bounds the divisor-and-numerator endpoints induce.
        val rLo = r.min.toLong()
        val rHi = r.max.toLong()
        val dLo = divisorDomain.min.toLong()
        val dHi = divisorDomain.max.toLong()
        var tLo = Long.MAX_VALUE
        var tHi = Long.MIN_VALUE
        // Avoid `listOf` allocation in the hot path — explicit corner pairs.
        for (rn in longArrayOf(rLo, rHi)) {
            for (dn in longArrayOf(dLo, dHi)) {
                val c = ceilDivLong(rn, dn)
                val f = floorDivLong(rn, dn)
                if (c < tLo) tLo = c
                if (f > tHi) tHi = f
            }
        }
        if (tLo > tHi) return false
        // Antecedents: union of result and the divisor var. The factor's a/b/result form
        // a triangle; the third var is the target being tightened, the other two drove it.
        val others = if (target == a) {
            intArrayOf(b, result)
        } else if (target == b) {
            intArrayOf(a, result)
        } else {
            intArrayOf(a, b)
        }
        return tightenLong(state, target, tLo, tHi, state.composeIntVarAtomAntecedents(others))
    }

    /**
     * Narrow [target]'s domain so that `target * divisor ∈ [r.min, r.max]`. [divisor] must
     * be non-zero. Handles signed division by flipping the bound order when [divisor] < 0.
     */
    private fun narrowByDivisor(state: PropagationState, target: Int, divisor: Long, r: IntDomain): Boolean {
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
        val others = if (target == a) {
            intArrayOf(b, result)
        } else if (target == b) {
            intArrayOf(a, result)
        } else {
            intArrayOf(a, b)
        }
        return tightenLong(state, target, lo, hi, state.composeIntVarAtomAntecedents(others))
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
        if (rClamped == rTarget && rClamped != rv) sink.addChannelingIntSet(state, result, rClamped)
        // Candidate 2: if b ≠ 0 and result divisible by b, snap a = result/b.
        if (bv != 0 && rv % bv == 0) {
            val aTarget = rv / bv
            val aClamped = state.problem.intDomains[a].clamp(aTarget)
            if (aClamped == aTarget && aClamped != av) sink.addChannelingIntSet(state, a, aClamped)
        } else if (bv != 0) {
            // Secondary candidate: closest a in domain whose product with b approaches result.
            proposeClosestOperand(state, operandVar = a, otherValue = bv, currentValue = av, sink)
        }
        // Candidate 3: if a ≠ 0 and result divisible by a, snap b = result/a.
        if (av != 0 && rv % av == 0) {
            val bTarget = rv / av
            val bClamped = state.problem.intDomains[b].clamp(bTarget)
            if (bClamped == bTarget && bClamped != bv) sink.addChannelingIntSet(state, b, bClamped)
        } else if (av != 0) {
            proposeClosestOperand(state, operandVar = b, otherValue = av, currentValue = bv, sink)
        }
        // Fall back to ±1 nudges if none of the snap candidates apply.
        for (v in intArrayOf(a, b, result)) {
            val cur = state.assignment.intValue(v)
            val d = state.problem.intDomains[v]
            if (cur < d.max) sink.addChannelingIntSet(state, v, cur + 1)
            if (cur > d.min) sink.addChannelingIntSet(state, v, cur - 1)
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
        val center = rv / otherValue // truncated
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

    private fun tightenLong(state: PropagationState, v: Int, lo: Long, hi: Long, ant: IntArray? = null): Boolean {
        if (lo > Int.MAX_VALUE || hi < Int.MIN_VALUE) return false
        val loI = if (lo < Int.MIN_VALUE) Int.MIN_VALUE else lo.toInt()
        val hiI = if (hi > Int.MAX_VALUE) Int.MAX_VALUE else hi.toInt()
        if (!state.tightenIntMin(v, loI, ant)) return false
        if (!state.tightenIntMax(v, hiI, ant)) return false
        return true
    }
}
