package com.eignex.klause.solver.factor.bool

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.solver.propagation.RevInt
import com.eignex.klause.solver.propagation.RevIntArray
import com.eignex.klause.solver.propagation.RevLongArray

/**
 * A *system* of parity (XOR) constraints propagated jointly by Gauss-Jordan elimination over
 * GF(2). Each constraint is `XOR(vars) == rhs`; the factor owns all of them as one matrix.
 *
 * Unlike a single [Xor] factor — which can only force a variable once a constraint has exactly
 * one unassigned variable left — Gaussian elimination *combines* equations, so it detects an
 * inconsistency (`0 = 1`) or forces a variable as soon as the linear system implies it. That is
 * what makes XOR-hash model counting / sampling tractable: without it, the parity subspace has
 * no short clausal refutations and the search thrashes on every infeasible branch (klause has no
 * clausal Gaussian reasoning otherwise). With it, enumerating a hashed cell visits essentially
 * only its real solutions.
 *
 * Each [propagate] substitutes the current partial assignment, reduces the residual system to
 * row-echelon form, and pins every variable the system forces (rows that collapse to a single
 * variable). Conflicts and forced pins are explained sharply: every row carries a reason
 * bitset of the assigned variables feeding it, xor-combined through each elimination step,
 * so even-occurrence variables cancel and a derived row's reason is exactly its
 * odd-occurrence assigned support — the minimal sufficient set (#174).
 *
 * This factor is **propagation-only**: it inherits the [Factor] local-search defaults
 * (always-satisfied, zero deltas). The Gaussian system is redundant with the per-row [Xor]
 * factors posted alongside it, which carry the same parity semantics *with* real LS support,
 * so LS enforces each parity row via those siblings.
 */
class GaussianXor(private val constraints: List<Xor>) : Factor {

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        GaussianXor(constraints.map { it.remap(boolMap, intMap) as Xor })

    /** Union of all variables across the constraints, in stable order; column index = position. */
    override val boolVars: IntArray
    override val intVars: IntArray = EmptyIntArray

    private val colOfVar: HashMap<Int, Int>
    private val words: Int
    private val rowMask: Array<LongArray>
    private val rowRhs: IntArray

    init {
        require(constraints.isNotEmpty()) { "GaussianXor needs at least one constraint" }
        val order = LinkedHashSet<Int>()
        for (c in constraints) for (lit in c.literals) order.add(Lit.variable(lit))
        boolVars = order.toIntArray()
        colOfVar = HashMap(boolVars.size * 2)
        for (i in boolVars.indices) colOfVar[boolVars[i]] = i
        words = (boolVars.size + 63) ushr 6

        rowMask = Array(constraints.size) { LongArray(words) }
        rowRhs = IntArray(constraints.size)
        for (r in constraints.indices) {
            val c = constraints[r]
            // Normalize literals to a GF(2) row: a variable is present iff it occurs an odd
            // number of times; each negative literal flips the right-hand side.
            val occ = HashMap<Int, Int>()
            var negParity = 0
            for (lit in c.literals) {
                val v = Lit.variable(lit)
                occ[v] = (occ[v] ?: 0) + 1
                if (!Lit.isPositive(lit)) negParity = negParity xor 1
            }
            for ((v, count) in occ) {
                if (count and 1 == 1) setBit(rowMask[r], colOfVar.getValue(v))
            }
            rowRhs[r] = c.targetParity xor negParity
        }
    }

    /**
     * Per-[PropagationState] reversible incremental Gauss-Jordan state (never shared across worker
     * threads). The reduced matrix is maintained *across* fires on the engine undo trail instead of
     * being rebuilt every fire: [mask] (free-variable matrix, `rows × words` flattened), [reason]
     * (per-row assigned-support bitsets), [rhs], and the basis maps [basicCol] (pivot column of each
     * row, -1 if none) / [pivotRow] (row a column is the basis of, -1 if non-basic). [seenVal] is
     * the value each variable was substituted into the matrix with (-1 = still free in the matrix);
     * a backtrack rolls it back in lockstep with the matrix. [valid] is 0 until [rebuildReduce]
     * seeds the state at the current level; a backtrack above that level resets it to
     * 0, forcing a fresh rebuild (the reversible cells cannot be trusted below their seed level).
     */
    private class IncrState(state: PropagationState, rows: Int, cols: Int, words: Int) {
        val mask = RevLongArray(state, rows * words)
        val reason = RevLongArray(state, rows * words)
        val rhs = RevIntArray(state, rows)
        val basicCol = RevIntArray(state, rows, -1)
        val pivotRow = RevIntArray(state, cols, -1)
        val seenVal = RevIntArray(state, cols, -1)
        val valid = RevInt(state, 0)
        var conflictVars: IntArray? = null
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val cache = (state.refPayload[factorId] as? IncrState)
            ?: IncrState(state, rowMask.size, boolVars.size, words).also { state.refPayload[factorId] = it }
        cache.conflictVars = null
        return if (cache.valid.value == 0) rebuildReduce(state, cache) else incrementalStep(state, cache)
    }

    /** Full Gauss-Jordan reduction over the current partial assignment, written into the reversible
     *  matrix and basis maps. Used on the first fire and after a backtrack invalidated the state.
     *  Pins forced variables / reports conflicts identically to the pre-incremental version. */
    private fun rebuildReduce(state: PropagationState, cache: IncrState): Boolean {
        val n = rowMask.size
        val mask = Array(n) { LongArray(words) }
        val reason = Array(n) { LongArray(words) }
        val rhs = IntArray(n)
        rowRhs.copyInto(rhs)
        for (r in 0 until n) {
            // Substitute current assignments (sparse over each row's set bits); track the assigned
            // support per row in `reason` for sharp explanations.
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

        // Gauss-Jordan to reduced row-echelon form over GF(2); record each row's pivot column.
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

        // Seed the reversible state: matrix + reason + rhs + basis maps + substituted-value record.
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

        // Inspect reduced rows: empty row with rhs=1 is a conflict; single-variable rows force.
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

    /** Incremental fire: substitute the variables assigned since the last fire into the reduced
     *  matrix (a basis re-pivot + one elimination pass per assigned *basic* variable; a cheap
     *  column drop per assigned non-basic variable), then pin newly-forced variables / report
     *  conflicts. The reduced-row-echelon-over-free-variables invariant is preserved throughout, so
     *  this detects exactly the same forced pins and conflicts as a full re-reduction. */
    private fun incrementalStep(state: PropagationState, cache: IncrState): Boolean {
        val n = rowMask.size
        val cols = boolVars.size
        var progress = true
        while (progress) {
            progress = false
            // 1. Substitute every variable assigned (or pinned) since it was last in the matrix.
            for (col in 0 until cols) {
                if (cache.seenVal[col] != -1) continue
                val v = boolVars[col]
                if (!state.boolAssignedAt(v)) continue
                val bit = if (state.boolValueAt(v)) 1 else 0
                cache.seenVal[col] = bit
                progress = true
                applyAssignment(cache, col, bit, n)
            }
            // 2. Inspect rows for conflicts / forced units.
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
                        progress = true // a pin is a new assignment to substitute next round
                    }
                }
            }
        }
        return true
    }

    /** Fold the assignment `boolVars[col] = bit` into the reduced matrix, preserving RREF over the
     *  free variables: a non-basic column is dropped from every row it occurs in; a basic column is
     *  dropped from its own row, which then re-pivots on another free variable (eliminated from the
     *  other rows) — or, if none remains, becomes an assigned-only row caught by the unit/conflict
     *  scan. */
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

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        (state.refPayload[factorId] as? IncrState)?.conflictVars

    /**
     * Clause-form reason for a forced pin or conflict: one currently-false literal per assigned
     * variable flagged in [reasonBits] (the variables whose values determine the reduced row),
     * skipping [excludeCol] (the variable being pinned). All flagged variables are assigned.
     */
    private fun reasonLiterals(state: PropagationState, reasonBits: LongArray, excludeCol: Int): IntArray? {
        var count = 0
        for (i in boolVars.indices) if (i != excludeCol && getBit(reasonBits, i)) count++
        if (count == 0) return null
        val out = IntArray(count)
        var w = 0
        for (i in boolVars.indices) {
            if (i == excludeCol || !getBit(reasonBits, i)) continue
            val v = boolVars[i]
            // reasonBits only flags assigned variables, so the value bit is always meaningful.
            out[w++] = Lit.make(v, !state.boolValueAt(v))
        }
        return out
    }

    // ---- Reversible flattened-row helpers (row r occupies `mask`/`reason` words [r·words, +words)). ----

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

    /** Substitute the assigned variable [col]=[bit] out of row [r]: drop its mask bit, flip the rhs
     *  if it was 1, and record it in the row's reason support. */
    private fun substituteOut(cache: IncrState, r: Int, col: Int, bit: Int) {
        val mIdx = r * words + (col ushr 6)
        cache.mask[mIdx] = cache.mask[mIdx] and (1L shl (col and 63)).inv()
        if (bit == 1) cache.rhs[r] = cache.rhs[r] xor 1
        val rIdx = r * words + (col ushr 6)
        cache.reason[rIdx] = cache.reason[rIdx] or (1L shl (col and 63))
    }

    /** XOR row [src] into row [dst] (mask, reason and rhs) — one GF(2) elimination step. */
    private fun xorRowInto(cache: IncrState, dst: Int, src: Int) {
        val db = dst * words
        val sb = src * words
        for (w in 0 until words) {
            cache.mask[db + w] = cache.mask[db + w] xor cache.mask[sb + w]
            cache.reason[db + w] = cache.reason[db + w] xor cache.reason[sb + w]
        }
        cache.rhs[dst] = cache.rhs[dst] xor cache.rhs[src]
    }

    /** Clause-form reason for a pin/conflict read from the reversible reason row [r] (see
     *  [reasonLiterals]); [excludeCol] is the variable being pinned. */
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
