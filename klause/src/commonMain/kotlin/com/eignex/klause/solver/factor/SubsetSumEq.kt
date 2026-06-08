package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.propagation.PropagationState

/**
 * Exact reachability filtering for `Σ coeffs[i]·xs[i] == target` over 0/1 integer vars with
 * positive coefficients — the subset-sum / knapsack-profit shape. Bounds propagation on the
 * plain [Linear] sees almost nothing here (every partial sum admits wide completions until
 * nearly all vars are pinned); the classic dynamic-programming filter sees everything:
 *
 *  - forward pass: `f[i]` = the set of sums the prefix `xs[0..i)` can reach under current
 *    pins, as a bitset over `0..target`;
 *  - backward pass: `g[i]` = the set of sums `s` from which the remaining suffix can still
 *    complete to `target`;
 *  - the constraint is satisfiable iff the full forward set contains `target`, and var `i`
 *    is supported at value
 *    `v ∈ {0, 1}` iff some `s ∈ f[i]` has `s + v·coeffs[i] ∈ g[i+1]`. Unsupported values
 *    pin the var; an unsupported var is a conflict.
 *
 * Complexity per propagate: vars times target over 64 word operations — a few thousand word ops on
 * competition-size instances. Reasons are coarse (every currently-pinned var in scope),
 * which is sound; the Linear factor posted alongside carries the sharp bound reasons.
 */
class SubsetSumEq(
    /** 0/1 integer variable ids. */
    val xs: IntArray,
    /** Positive coefficient per variable. */
    val coeffs: IntArray,
    /** Required exact sum; positive. */
    val target: Int,
) : Factor {

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs

    init {
        require(xs.size == coeffs.size) { "SubsetSumEq: parallel arrays of unequal length" }
        require(coeffs.all { it > 0 }) { "SubsetSumEq needs positive coefficients" }
        require(target > 0) { "SubsetSumEq needs a positive target" }
    }

    private val words = (target + 64) ushr 6

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val n = xs.size
        // forward[i] = sums reachable by the prefix, given current pins.
        val forward = Array(n + 1) { LongArray(words) }
        setBit(forward[0], 0)
        for (i in 0 until n) {
            val d = state.intDomains[xs[i]]
            val canZero = d.min == 0
            val canOne = d.max == 1
            val cur = forward[i]
            val next = forward[i + 1]
            if (canZero) cur.copyInto(next)
            if (canOne) orShifted(next, cur, coeffs[i])
        }
        if (!getBit(forward[n], target)) {
            state.refPayload[factorId] = pinnedReason(state)
            return false
        }
        // backward[i] = sums s with the suffix able to take the total from s to target.
        var after = LongArray(words)
        setBit(after, target)
        // Walk suffixes from the end; filter each var against forward[i] × backward[i+1].
        for (i in n - 1 downTo 0) {
            val d = state.intDomains[xs[i]]
            val canZero = d.min == 0
            val canOne = d.max == 1
            val zeroOk = canZero && intersectsDirect(forward[i], after)
            val oneOk = canOne && intersectsShifted(forward[i], after, coeffs[i])
            if (!zeroOk && !oneOk) {
                state.refPayload[factorId] = pinnedReason(state)
                return false
            }
            if (!zeroOk && d.min == 0) {
                if (!state.tightenIntMin(xs[i], 1, pinnedReason(state))) return false
            }
            if (!oneOk && d.max == 1) {
                if (!state.tightenIntMax(xs[i], 0, pinnedReason(state))) return false
            }
            // backward[i]: s is good if (var can be 0 and s ∈ after) or (var can be 1 and
            // s + c ∈ after). Recompute from the possibly-just-tightened domain.
            val d2 = state.intDomains[xs[i]]
            val prev = LongArray(words)
            if (d2.min == 0) after.copyInto(prev)
            if (d2.max == 1) orShiftedDown(prev, after, coeffs[i])
            after = prev
        }
        return true
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        state.refPayload[factorId] as? IntArray

    /** Coarse, sound reason: the equality atoms of every currently-pinned var in scope. */
    private fun pinnedReason(state: PropagationState): IntArray? {
        val pinned = ArrayList<Int>(xs.size)
        for (v in xs) {
            val d = state.intDomains[v]
            if (d.min == d.max) pinned.add(v)
        }
        if (pinned.isEmpty()) return null
        return state.composeIntVarAtomAntecedents(pinned.toIntArray())
    }

    private fun setBit(row: LongArray, bit: Int) {
        row[bit ushr 6] = row[bit ushr 6] or (1L shl (bit and 63))
    }

    private fun getBit(row: LongArray, bit: Int): Boolean =
        bit in 0..target && (row[bit ushr 6] ushr (bit and 63)) and 1L == 1L

    /** dst |= src << shift, truncated past [target]. */
    private fun orShifted(dst: LongArray, src: LongArray, shift: Int) {
        val wordShift = shift ushr 6
        val bitShift = shift and 63
        for (w in words - 1 downTo wordShift) {
            var v = src[w - wordShift] shl bitShift
            if (bitShift != 0 && w - wordShift - 1 >= 0) {
                v = v or (src[w - wordShift - 1] ushr (64 - bitShift))
            }
            dst[w] = dst[w] or v
        }
    }

    /** dst |= src >> shift (sums s with s+shift ∈ src). */
    private fun orShiftedDown(dst: LongArray, src: LongArray, shift: Int) {
        val wordShift = shift ushr 6
        val bitShift = shift and 63
        for (w in 0 until words - wordShift) {
            var v = src[w + wordShift] ushr bitShift
            if (bitShift != 0 && w + wordShift + 1 < words) {
                v = v or (src[w + wordShift + 1] shl (64 - bitShift))
            }
            dst[w] = dst[w] or v
        }
    }

    /** Any common bit between `a` and `b`? */
    private fun intersectsDirect(a: LongArray, b: LongArray): Boolean {
        for (w in 0 until words) if (a[w] and b[w] != 0L) return true
        return false
    }

    /** Any s with s ∈ a and s+shift ∈ b? Equivalent to a ∩ (b >> shift). */
    private fun intersectsShifted(a: LongArray, b: LongArray, shift: Int): Boolean {
        val wordShift = shift ushr 6
        val bitShift = shift and 63
        for (w in 0 until words - wordShift) {
            var v = b[w + wordShift] ushr bitShift
            if (bitShift != 0 && w + wordShift + 1 < words) {
                v = v or (b[w + wordShift + 1] shl (64 - bitShift))
            }
            if (a[w] and v != 0L) return true
        }
        return false
    }
}
