package com.eignex.klause.solver.factor.bool

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.solver.propagation.RevInt
import com.eignex.klause.solver.propagation.RevIntArray
import com.eignex.klause.solver.propagation.RevLongArray

/**
 * CP contract for [GaussianXor]: incremental Gauss-Jordan elimination over GF(2) parity
 * constraints.
 *
 * A *system* of parity (XOR) constraints propagated jointly by Gauss-Jordan elimination over
 * GF(2). Each constraint is `XOR(vars) == rhs`; the factor owns all of them as one matrix.
 *
 * Unlike a single [Xor] factor — which can only force a variable once a constraint has exactly
 * one unassigned variable left — Gaussian elimination *combines* equations, so it detects an
 * inconsistency (`0 = 1`) or forces a variable as soon as the linear system implies it.
 *
 * Each [propagate] substitutes the current partial assignment, reduces the residual system to
 * row-echelon form, and pins every variable the system forces. Conflicts and forced pins are
 * explained sharply: every row carries a reason bitset of the assigned variables feeding it,
 * xor-combined through each elimination step, so even-occurrence variables cancel and a derived
 * row's reason is exactly its odd-occurrence assigned support.
 *
 * This factor is **propagation-only**: it inherits the [com.eignex.klause.solver.Factor]
 * local-search defaults (always-satisfied, zero deltas). The Gaussian system is redundant with
 * the per-row [Xor] factors posted alongside it, which carry the same parity semantics *with*
 * real LS support.
 */
interface GaussianXorPropagator : Propagator {

    /** The individual parity constraints forming this Gaussian system. */
    val constraints: List<Xor>

    /** Number of 64-bit words per row: `(boolVars.size + 63) ushr 6`. */
    val words: Int

    /** Bit-matrix of the initial (un-assigned) system rows. `rowMask[r][w]` is word `w` of row `r`. */
    val rowMask: Array<LongArray>

    /** Right-hand-side parity per row (0 or 1). */
    val rowRhs: IntArray

    /** Column index of each variable in the bit-matrix. */
    val colOfVar: HashMap<Int, Int>

    /**
     * Per-[PropagationState] reversible incremental Gauss-Jordan state. The reduced matrix is
     * maintained *across* fires on the engine undo trail instead of being rebuilt every fire.
     */
    class IncrState(state: PropagationState, rows: Int, cols: Int, words: Int) {
        internal val mask = RevLongArray(state, rows * words)
        internal val reason = RevLongArray(state, rows * words)
        internal val rhs = RevIntArray(state, rows)
        internal val basicCol = RevIntArray(state, rows, -1)
        internal val pivotRow = RevIntArray(state, cols, -1)
        internal val seenVal = RevIntArray(state, cols, -1)
        internal val valid = RevInt(state, 0)

        /** Variables involved in the latest conflict row, or null if no conflict. */
        var conflictVars: IntArray? = null
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        (state.refPayload[factorId] as? IncrState)?.conflictVars

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val cache = (state.refPayload[factorId] as? IncrState)
            ?: IncrState(
                state,
                rowMask.size,
                boolVars.size,
                words,
            ).also { state.refPayload[factorId] = it }
        cache.conflictVars = null
        return if (cache.valid.value == 0) rebuildReduce(state, cache) else incrementalStep(state, cache)
    }

    private fun rebuildReduce(state: PropagationState, cache: IncrState): Boolean {
        val n = rowMask.size
        val mask = Array(n) { LongArray(words) }
        val reason = Array(n) { LongArray(words) }
        val rhs = IntArray(n)
        rowRhs.copyInto(rhs)
        for (r in 0 until n) {
            val rm = rowMask[r]
            for (wi in rm.indices) {
                var w = rm[wi]
                while (w != 0L) {
                    val i = (wi shl 6) + w.countTrailingZeroBits()
                    w = w and (w - 1L)
                    val v = boolVars[i]
                    if (!state.boolAssignedAt(v)) {
                        setBit(mask[r], i)
                    } else {
                        setBit(reason[r], i)
                        if (state.boolValueAt(v)) rhs[r] = rhs[r] xor 1
                    }
                }
            }
        }

        val pivotColOfRow = IntArray(n) { -1 }
        var pivotRow = 0
        for (col in boolVars.indices) {
            var sel = -1
            for (r in pivotRow until n) {
                if (getBit(mask[r], col)) {
                    sel = r
                    break
                }
            }
            if (sel < 0) continue
            swap(mask, reason, rhs, pivotRow, sel)
            for (r in 0 until n) {
                if (r != pivotRow && getBit(mask[r], col)) {
                    xorInto(mask[r], mask[pivotRow])
                    xorInto(reason[r], reason[pivotRow])
                    rhs[r] = rhs[r] xor rhs[pivotRow]
                }
            }
            pivotColOfRow[pivotRow] = col
            pivotRow++
        }

        for (r in 0 until n) {
            for (w in 0 until words) {
                cache.mask[r * words + w] = mask[r][w]
                cache.reason[r * words + w] = reason[r][w]
            }
            cache.rhs[r] = rhs[r]
            cache.basicCol[r] = pivotColOfRow[r]
        }
        for (col in boolVars.indices) cache.pivotRow[col] = -1
        for (r in 0 until n) if (pivotColOfRow[r] >= 0) cache.pivotRow[pivotColOfRow[r]] = r
        for (col in boolVars.indices) {
            val v = boolVars[col]
            cache.seenVal[col] = if (state.boolAssignedAt(v)) (if (state.boolValueAt(v)) 1 else 0) else -1
        }
        cache.valid.set(1)

        for (r in 0 until n) {
            val pop = popcount(mask[r])
            if (pop == 0) {
                if (rhs[r] == 1) {
                    cache.conflictVars = reasonLiterals(state, reason[r], excludeCol = -1)
                    return false
                }
                continue
            }
            if (pop == 1) {
                val col = firstSetBit(mask[r])
                val v = boolVars[col]
                if (!state.boolAssignedAt(v)) {
                    if (!state.pinBool(v, rhs[r] == 1, reasonLiterals(state, reason[r], excludeCol = col))) {
                        return false
                    }
                }
            }
        }
        return true
    }

    private fun incrementalStep(state: PropagationState, cache: IncrState): Boolean {
        val n = rowMask.size
        val cols = boolVars.size
        var progress = true
        while (progress) {
            progress = false
            for (col in 0 until cols) {
                if (cache.seenVal[col] != -1) continue
                val v = boolVars[col]
                if (!state.boolAssignedAt(v)) continue
                val bit = if (state.boolValueAt(v)) 1 else 0
                cache.seenVal[col] = bit
                progress = true
                applyAssignment(cache, col, bit, n)
            }
            for (r in 0 until n) {
                val pop = rowPopCount(cache, r)
                if (pop == 0) {
                    if (cache.rhs[r] == 1) {
                        cache.conflictVars = rowReasonLiterals(state, cache, r, excludeCol = -1)
                        return false
                    }
                    continue
                }
                if (pop == 1) {
                    val col = rowFirstCol(cache, r)
                    val v = boolVars[col]
                    if (!state.boolAssignedAt(v)) {
                        if (!state.pinBool(v, cache.rhs[r] == 1, rowReasonLiterals(state, cache, r, col))) {
                            return false
                        }
                        progress = true
                    }
                }
            }
        }
        return true
    }

    private fun applyAssignment(cache: IncrState, col: Int, bit: Int, n: Int) {
        val owner = cache.pivotRow[col]
        if (owner >= 0) {
            cache.pivotRow[col] = -1
            cache.basicCol[owner] = -1
            substituteOut(cache, owner, col, bit)
            val newBasic = rowFirstCol(cache, owner)
            if (newBasic >= 0) {
                cache.basicCol[owner] = newBasic
                cache.pivotRow[newBasic] = owner
                for (r in 0 until n) {
                    if (r != owner && rowGetBit(cache, r, newBasic)) xorRowInto(cache, r, owner)
                }
            }
        } else {
            for (r in 0 until n) if (rowGetBit(cache, r, col)) substituteOut(cache, r, col, bit)
        }
    }

    private fun reasonLiterals(state: PropagationState, reasonBits: LongArray, excludeCol: Int): IntArray? {
        var count = 0
        for (i in boolVars.indices) if (i != excludeCol && getBit(reasonBits, i)) count++
        if (count == 0) return null
        val out = IntArray(count)
        var w = 0
        for (i in boolVars.indices) {
            if (i == excludeCol || !getBit(reasonBits, i)) continue
            val v = boolVars[i]
            out[w++] = Lit.make(v, !state.boolValueAt(v))
        }
        return out
    }

    private fun rowPopCount(cache: IncrState, r: Int): Int {
        var c = 0
        val base = r * words
        for (w in 0 until words) c += cache.mask[base + w].countOneBits()
        return c
    }

    private fun rowFirstCol(cache: IncrState, r: Int): Int {
        val base = r * words
        for (w in 0 until words) {
            val word = cache.mask[base + w]
            if (word != 0L) return (w shl 6) + word.countTrailingZeroBits()
        }
        return -1
    }

    private fun rowGetBit(cache: IncrState, r: Int, col: Int): Boolean =
        (cache.mask[r * words + (col ushr 6)] ushr (col and 63)) and 1L == 1L

    private fun substituteOut(cache: IncrState, r: Int, col: Int, bit: Int) {
        val mIdx = r * words + (col ushr 6)
        cache.mask[mIdx] = cache.mask[mIdx] and (1L shl (col and 63)).inv()
        if (bit == 1) cache.rhs[r] = cache.rhs[r] xor 1
        val rIdx = r * words + (col ushr 6)
        cache.reason[rIdx] = cache.reason[rIdx] or (1L shl (col and 63))
    }

    private fun xorRowInto(cache: IncrState, dst: Int, src: Int) {
        val db = dst * words
        val sb = src * words
        for (w in 0 until words) {
            cache.mask[db + w] = cache.mask[db + w] xor cache.mask[sb + w]
            cache.reason[db + w] = cache.reason[db + w] xor cache.reason[sb + w]
        }
        cache.rhs[dst] = cache.rhs[dst] xor cache.rhs[src]
    }

    private fun rowReasonLiterals(state: PropagationState, cache: IncrState, r: Int, excludeCol: Int): IntArray? {
        val base = r * words
        var count = 0
        for (i in boolVars.indices) {
            if (i != excludeCol && (cache.reason[base + (i ushr 6)] ushr (i and 63)) and 1L == 1L) count++
        }
        if (count == 0) return null
        val out = IntArray(count)
        var w = 0
        for (i in boolVars.indices) {
            if (i == excludeCol || (cache.reason[base + (i ushr 6)] ushr (i and 63)) and 1L != 1L) continue
            val v = boolVars[i]
            out[w++] = Lit.make(v, !state.boolValueAt(v))
        }
        return out
    }

    private fun setBit(row: LongArray, bit: Int) {
        row[bit ushr 6] = row[bit ushr 6] or (1L shl (bit and 63))
    }

    private fun getBit(row: LongArray, bit: Int): Boolean = (row[bit ushr 6] ushr (bit and 63)) and 1L == 1L

    private fun xorInto(dst: LongArray, src: LongArray) {
        for (w in dst.indices) dst[w] = dst[w] xor src[w]
    }

    private fun popcount(row: LongArray): Int {
        var c = 0
        for (w in row) c += w.countOneBits()
        return c
    }

    private fun firstSetBit(row: LongArray): Int {
        for (w in row.indices) {
            if (row[w] != 0L) return (w shl 6) + row[w].countTrailingZeroBits()
        }
        return -1
    }

    private fun swap(mask: Array<LongArray>, reason: Array<LongArray>, rhs: IntArray, a: Int, b: Int) {
        if (a == b) return
        val tmpMask = mask[a]
        mask[a] = mask[b]
        mask[b] = tmpMask
        val tmpReason = reason[a]
        reason[a] = reason[b]
        reason[b] = tmpReason
        val tmpRhs = rhs[a]
        rhs[a] = rhs[b]
        rhs[b] = tmpRhs
    }
}
