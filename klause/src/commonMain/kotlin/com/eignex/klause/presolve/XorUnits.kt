package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongHashSet
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.isZero
import com.eignex.klause.util.rem

internal object XorUnits {

    /** Work budget for the one-shot GF(2) elimination, as `rows × pivots × words` (its asymptotic cost).
     *  Past it the elimination is skipped — on a system this large it would dominate presolve and rarely
     *  pays off, while small parity systems (where xor-derived units matter) stay orders of magnitude
     *  below it. */
    private const val XOR_ELIMINATION_WORK_CAP = 100_000_000L

    /**
     * One-shot GF(2) elimination over the root parity system, emitting only its global consequences: a
     * forced literal as a unit [Clause], a forced 0/1 integer column as a proved range, and a contradiction
     * (`0 = 1`) as a refutation. The parity rows stay in place for normal propagation.
     *
     * The system holds every [Xor] factor and the parity every integer equality implies. In `Σ cᵢxᵢ = b`
     * an even `cᵢ` contributes an even term whatever integer `xᵢ` takes, and an odd one contributes `xᵢ`'s
     * own parity, so the row says `Σ_{cᵢ odd} xᵢ ≡ b (mod 2)`. That parity names a value only for a column
     * confined to 0/1, so a row is read only when each of its odd-coefficient columns is a 0/1 or fixed
     * column; the even-coefficient columns need no range at all, which keeps the derivation sound before
     * any finite projection exists.
     *
     * Idempotent: a literal already forced and a column already fixed are not stated again, so the round
     * engine reaches a fixpoint.
     */
    fun deriveXorUnits(problem: Problem, cancellation: Cancellation = Cancellation.Never): SourceDelta {
        val system = ParitySystem(problem)
        for (x in problem.factors.filterIsInstance<Xor>()) system.addXor(x)
        for (f in problem.factors) if (f is Linear && f.op == LinearOp.EQ) system.addEquality(f)
        if (system.rows.isEmpty() || system.columns.isEmpty()) return SourceDelta()
        val vars = system.columns.toLongArray()
        val indexOf = HashMap<Long, Int>(vars.size * 2)
        for (i in vars.indices) indexOf[vars[i]] = i
        val words = (vars.size + 63) ushr 6
        // The dense GF(2) elimination below is O(rows × pivots × words). On a large parity system that work
        // dominates presolve, so skip it past a budget (sound — the parity rows still propagate normally,
        // only their globally-implied root units go underived), mirroring the size guards on the other
        // presolve searches (clique merge, SAC probing). The reduction matters on small parity systems,
        // which stay far under the cap.
        val rowCount = system.rows.size
        if (rowCount.toLong() * minOf(rowCount, vars.size) * words > XOR_ELIMINATION_WORK_CAP) return SourceDelta()
        val rows = Array(rowCount) { LongArray(words) }
        val rhs = IntArray(rowCount)
        for (r in 0 until rowCount) {
            system.rows[r].forEach { column -> Bits.set(rows[r], indexOf.getValue(column)) }
            rhs[r] = system.rhs[r]
        }

        var pivotRow = 0
        for (col in vars.indices) {
            // A partial elimination leaves no row it can trust as fully reduced, so a stop derives nothing.
            if (cancellation()) return SourceDelta()
            cancellation.charge(rowCount.toLong() * words)
            var sel = -1
            for (r in pivotRow until rows.size) {
                if (Bits.has(rows[r], col)) {
                    sel = r
                    break
                }
            }
            if (sel < 0) continue
            if (sel != pivotRow) {
                val tmpMask = rows[pivotRow]
                rows[pivotRow] = rows[sel]
                rows[sel] = tmpMask
                val tmpRhs = rhs[pivotRow]
                rhs[pivotRow] = rhs[sel]
                rhs[sel] = tmpRhs
            }
            for (r in rows.indices) {
                if (r != pivotRow && Bits.has(rows[r], col)) {
                    Bits.xorInto(rows[r], rows[pivotRow])
                    rhs[r] = rhs[r] xor rhs[pivotRow]
                }
            }
            pivotRow++
            if (pivotRow == rows.size) break
        }

        val forced = HashMap<Long, Boolean>()
        for (r in rows.indices) {
            when (Bits.popcount(rows[r])) {
                0 -> if (rhs[r] == 1) return SourceDelta(infeasible = true)

                1 -> {
                    val column = vars[Bits.firstSet(rows[r])]
                    val value = rhs[r] == 1
                    if ((forced.put(column, value) ?: value) != value) return SourceDelta(infeasible = true)
                }

                else -> {}
            }
        }
        return system.consequences(forced)
    }

    /**
     * The parity rows of one model over a shared column space, where Boolean `v` is column `2v` and integer
     * `v` column `2v + 1`.
     */
    private class ParitySystem(private val problem: Problem) {
        val columns = LinkedHashSet<Long>()
        val rows = ArrayList<LongHashSet>()
        val rhs = IntArrayList()
        private val bounds = problem.intBounds

        fun addXor(x: Xor) {
            val row = LongHashSet()
            var parity = x.targetParity
            for (lit in x.literals) {
                toggle(row, boolColumn(Lit.variable(lit)))
                if (!Lit.isPositive(lit)) parity = parity xor 1
            }
            add(row, parity)
        }

        fun addEquality(row: Linear) {
            if (row.realVars.isNotEmpty()) return
            // A 64-bit row reads its parities straight from the longs; only a wide one needs exact integers.
            val narrow = row.integerConstants
            val wide = row.integralConstants ?: return
            val support = LongHashSet()
            var parity = if (narrow?.bound?.isOdd() ?: wide.exactBound.isOdd()) 1 else 0
            for (k in row.vars.indices) {
                if (!(narrow?.coeff(k)?.isOdd() ?: wide.exactCoeff(k).isOdd())) continue
                val v = row.vars[k]
                val (lo, hi) = range(v) ?: return
                when {
                    lo == hi -> parity = parity xor (lo and 1L).toInt()
                    lo == 0L && hi == 1L -> toggle(support, intColumn(v))
                    else -> return
                }
            }
            add(support, parity)
        }

        /** The forced values as the source lane's change: unit clauses and fixed integer ranges. */
        fun consequences(forced: Map<Long, Boolean>): SourceDelta {
            val units = HashSet<Int>()
            for (f in problem.factors) if (f is Clause && f.literals.size == 1) units.add(f.literals[0])
            val added = ArrayList<Factor>()
            val tightening = bounds.tightening()
            for ((column, value) in forced.entries.sortedBy { it.key }) {
                val v = (column ushr 1).toInt()
                if (column and 1L == 0L) {
                    val lit = Lit.make(v, value)
                    if (lit !in units) added.add(Clause(intArrayOf(lit)))
                } else {
                    val fixed = if (value) 1L else 0L
                    tightening.atLeast(v, fixed)
                    tightening.atMost(v, fixed)
                }
            }
            val proved = tightening.build()
            if (added.isEmpty() && proved == null) return SourceDelta()
            return SourceDelta(addedFactors = added, bounds = proved)
        }

        // The narrowest range the model states: a baked model's root domain, else the declared value set, which
        // can sit inside wider model bounds, else those bounds.
        private fun range(v: Int): Pair<Long, Long>? {
            val domain = (problem as? BakedProblem)?.rootIntDomain(v) ?: problem.intDomainOrNull(v)
            if (domain != null) return domain.min to domain.max
            if (!bounds.hasLower(v) || !bounds.hasUpper(v)) return null
            return bounds.lower(v) to bounds.upper(v)
        }

        private fun add(row: LongHashSet, parity: Int) {
            if (row.isEmpty() && parity == 0) return
            row.forEach { columns.add(it) }
            rows.add(row)
            rhs.add(parity)
        }

        private fun toggle(row: LongHashSet, column: Long) {
            if (!row.remove(column)) row.add(column)
        }

        private fun boolColumn(v: Int): Long = v.toLong() shl 1

        private fun intColumn(v: Int): Long = (v.toLong() shl 1) or 1L
    }
}

private fun BigInt.isOdd(): Boolean = !rem(TWO).isZero()

private fun Long.isOdd(): Boolean = this and 1L != 0L

private val TWO = bigIntOf(2)
