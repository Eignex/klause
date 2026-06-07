package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.propagation.PropagationState

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
 * variable). Conflicts and forced pins are explained by the set of currently-assigned literals in
 * the system — sound (the substitution that produced the derivation), if not minimal.
 */
class GaussianXor(constraints: List<Xor>) : Factor {

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

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val n = rowMask.size
        // Residual system over the *unassigned* variables: substitute current assignments. For each
        // row we also track `reason` — the bitset of assigned variables whose values determine that
        // row's right-hand side — so forced pins and conflicts get a sharp (asserting) explanation.
        val mask = Array(n) { LongArray(words) }
        val reason = Array(n) { LongArray(words) }
        val rhs = rowRhs.copyOf()
        for (r in 0 until n) {
            for (i in boolVars.indices) {
                if (!getBit(rowMask[r], i)) continue
                val assigned = state.boolValues[boolVars[i]]
                if (assigned == null) {
                    setBit(mask[r], i)
                } else {
                    setBit(reason[r], i)
                    if (assigned) rhs[r] = rhs[r] xor 1
                }
            }
        }

        // Gauss-Jordan to reduced row-echelon form over GF(2); rhs and reason combine with each row.
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
            pivotRow++
        }

        // Inspect the reduced rows: empty row with rhs=1 is a conflict; single-variable rows force.
        for (r in 0 until n) {
            val pop = popcount(mask[r])
            if (pop == 0) {
                if (rhs[r] == 1) { // 0 = 1
                    state.refPayload[factorId] = reasonLiterals(state, reason[r], excludeCol = -1)
                    return false
                }
                continue
            }
            if (pop == 1) {
                val col = firstSetBit(mask[r])
                val v = boolVars[col]
                if (state.boolValues[v] == null) {
                    if (!state.pinBool(v, rhs[r] == 1, reasonLiterals(state, reason[r], excludeCol = col))) {
                        return false
                    }
                }
            }
        }
        return true
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        state.refPayload[factorId] as? IntArray

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
            val b = state.boolValues[v] ?: continue
            out[w++] = Lit.make(v, !b)
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
