package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.ScanMeter
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.LongHashSet
import com.eignex.util.collections.MutableLongIntMap
import com.eignex.util.collections.storage.PackedBits
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.isZero
import com.eignex.util.numbers.rem

internal object XorUnits {

    // In `rows × pivots × words`. Past it only the globally implied root units go underived; the parity rows
    // still propagate normally.
    private const val XOR_ELIMINATION_WORK_CAP = 100_000_000L

    // One-shot GF(2) elimination over the root parity system (every [Xor] and the parity of every integer
    // equality), emitting forced literals as units, forced 0/1 columns as ranges, and `0 = 1` as a refutation.
    // An equality `Σ cᵢxᵢ = b` implies `Σ_{cᵢ odd} xᵢ ≡ b (mod 2)`, which names a value only for 0/1 columns,
    // so a row is read only when every odd-coefficient column is 0/1 or fixed; even-coefficient columns need
    // no range, which keeps this sound before any finite projection exists. Already-forced values are not
    // restated, so the round engine reaches a fixpoint.
    fun deriveXorUnits(problem: Problem, cancellation: ExecutionControl = ExecutionControl.Never): SourceDelta {
        val work = ScanMeter(cancellation)
        if (!work.check()) return SourceDelta()
        val system = ParitySystem(problem, work)
        if (!system.collect() || !work.check()) return SourceDelta()
        if (system.infeasible) return SourceDelta(infeasible = true)
        if (system.rows.isEmpty() || system.columns.isEmpty()) return SourceDelta()
        val vars = system.columns.toLongArray()
        val indexOf = MutableLongIntMap(vars.size)
        for (i in vars.indices) indexOf.put(vars[i], i)
        val words = (vars.size + 63) ushr 6
        val rowCount = system.rows.size
        if (rowCount.toLong() * minOf(rowCount, vars.size) > XOR_ELIMINATION_WORK_CAP / words) return SourceDelta()
        val rows = Array(rowCount) { LongArray(words) }
        val rhs = IntArray(rowCount)
        for (r in 0 until rowCount) {
            system.rows[r].forEach { column -> PackedBits.set(rows[r], indexOf.getOrDefault(column, -1)) }
            rhs[r] = system.rhs[r]
        }

        var pivotRow = 0
        for (col in vars.indices) {
            // A partial elimination leaves no row it can trust as fully reduced, so a stop derives nothing.
            if (cancellation()) return SourceDelta()
            cancellation.charge(rowCount.toLong() * words)
            var sel = -1
            for (r in pivotRow until rows.size) {
                if (PackedBits.has(rows[r], col)) {
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
                if (r != pivotRow && PackedBits.has(rows[r], col)) {
                    PackedBits.xorInto(rows[r], rows[pivotRow])
                    rhs[r] = rhs[r] xor rhs[pivotRow]
                }
            }
            pivotRow++
            if (pivotRow == rows.size) break
        }

        if (cancellation()) return SourceDelta()
        val forced = HashMap<Long, Boolean>()
        for (r in rows.indices) {
            when (PackedBits.popcount(rows[r])) {
                0 -> if (rhs[r] == 1) return SourceDelta(infeasible = true)

                1 -> {
                    val column = vars[PackedBits.firstSet(rows[r])]
                    val value = rhs[r] == 1
                    if ((forced.put(column, value) ?: value) != value) return SourceDelta(infeasible = true)
                }

                else -> {}
            }
        }
        return system.consequences(forced)
    }

    // Boolean `v` is column `2v` and integer `v` column `2v + 1`.
    private class ParitySystem(private val problem: Problem, private val work: ScanMeter) {
        val columns = LinkedHashSet<Long>()
        val rows = ArrayList<LongHashSet>()
        val rhs = IntArrayList()
        private val bounds = problem.intBounds
        private val root = (problem as? BakedProblem)?.rootDeductions as? PropagationResult.Implied
        private val units = HashMap<Int, Boolean>()
        var infeasible = false
            private set

        fun collect(): Boolean {
            for (factor in problem.factors) {
                if (!work.visit(1L)) return false
                if (factor !is Clause || factor.literals.size != 1) continue
                val lit = factor.literals[0]
                val v = Lit.variable(lit)
                val value = Lit.isPositive(lit)
                if ((boolValue(v) ?: value) != value) infeasible = true
                units[v] = value
            }
            for (factor in problem.factors) {
                if (!work.visit(1L)) return false
                when (factor) {
                    is Xor -> if (!addXor(factor)) return false
                    is Linear -> if (factor.op == LinearOp.EQ && !addEquality(factor)) return false
                }
            }
            return true
        }

        private fun boolValue(v: Int): Boolean? = units[v] ?: root?.boolValueOrNull(v)

        private fun addXor(x: Xor): Boolean {
            val row = LongHashSet()
            var parity = x.targetParity
            for (lit in x.literals) {
                if (!work.visit(1L)) return false
                val v = Lit.variable(lit)
                val fixed = boolValue(v)
                if (fixed == null) {
                    toggle(row, boolColumn(v))
                } else if (fixed) {
                    parity = parity xor 1
                }
                if (!Lit.isPositive(lit)) parity = parity xor 1
            }
            add(row, parity)
            return true
        }

        private fun addEquality(row: Linear): Boolean {
            if (row.realVars.isNotEmpty()) return true
            // A 64-bit row reads its parities straight from the longs; only a wide one needs exact integers.
            val narrow = row.integerConstants
            val wide = row.integralConstants ?: return true
            val support = LongHashSet()
            var parity = if (narrow?.bound?.isOdd() ?: wide.exactBound.isOdd()) 1 else 0
            for (k in row.vars.indices) {
                if (!work.visit(1L)) return false
                if (!(narrow?.coeff(k)?.isOdd() ?: wide.exactCoeff(k).isOdd())) continue
                val v = row.vars[k]
                val (lo, hi) = range(v) ?: return true
                when {
                    lo == hi -> parity = parity xor (lo and 1L).toInt()
                    lo == 0L && hi == 1L -> toggle(support, intColumn(v))
                    else -> return true
                }
            }
            add(support, parity)
            return true
        }

        fun consequences(forced: Map<Long, Boolean>): SourceDelta {
            val added = ArrayList<Factor>()
            val tightening = bounds.tightening()
            for ((column, value) in forced.entries.sortedBy { it.key }) {
                val v = (column ushr 1).toInt()
                if (column and 1L == 0L) {
                    val lit = Lit.make(v, value)
                    added.add(Clause(intArrayOf(lit)))
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
            if (row.isEmpty()) {
                if (parity == 1) infeasible = true
                return
            }
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
