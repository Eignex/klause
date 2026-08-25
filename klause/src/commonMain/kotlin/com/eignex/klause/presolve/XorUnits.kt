package com.eignex.klause.presolve

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Problem
import com.eignex.klause.util.Bits
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.MutableIntIntMap

internal object XorUnits {

    /** Work budget for the one-shot GF(2) elimination, as `rows × pivots × words` (its asymptotic cost).
     *  Past it the elimination is skipped — on a system this large it would dominate presolve and rarely
     *  pays off, while small parity systems (where xor-derived units matter) stay orders of magnitude
     *  below it. */
    private const val XOR_ELIMINATION_WORK_CAP = 100_000_000L

    /**
     * One-shot GF(2) elimination over all [Xor] factors. Reduces the root xor system once and emits
     * only its global consequences: forced literals as unit [Clause]s and, on contradiction (`0 = 1`),
     * a contradictory unit pair. The original xor factors stay in place for normal propagation.
     *
     * This is idempotent: units already present are not re-added, so the presolve round engine reaches
     * a fixpoint instead of appending duplicates on each round.
     */
    fun deriveXorUnits(problem: Problem): PassDelta {
        val xors = problem.factors.filterIsInstance<Xor>()
        if (xors.isEmpty()) return PassDelta()

        val varOrder = LinkedHashSet<Int>()
        for (x in xors) for (lit in x.literals) varOrder.add(Lit.variable(lit))
        if (varOrder.isEmpty()) return PassDelta()
        val vars = varOrder.toIntArray()
        val colOfVar = MutableIntIntMap(vars.size * 2)
        for (i in vars.indices) colOfVar.put(vars[i], i)
        val words = (vars.size + 63) ushr 6
        // The dense GF(2) elimination below is O(rows × pivots × words). On a large xor system that work
        // dominates presolve, so skip it past a budget (sound — the xor factors still propagate normally,
        // only their globally-implied root units go underived), mirroring the size guards on the other
        // presolve searches (clique merge, SAC probing). The reduction matters on small parity systems,
        // which stay far under the cap.
        if (xors.size.toLong() * minOf(xors.size, vars.size) * words > XOR_ELIMINATION_WORK_CAP) return PassDelta()
        val rows = Array(xors.size) { LongArray(words) }
        val rhs = IntArray(xors.size)

        for (r in xors.indices) {
            val x = xors[r]
            val row = rows[r]
            val occParity = MutableIntIntMap()
            var negParity = 0
            for (lit in x.literals) {
                val v = Lit.variable(lit)
                occParity.put(v, occParity.getOrDefault(v, 0) xor 1)
                if (!Lit.isPositive(lit)) negParity = negParity xor 1
            }
            occParity.forEach { v, parity -> if (parity == 1) Bits.set(row, colOfVar.getOrDefault(v, 0)) }
            rhs[r] = x.targetParity xor negParity
        }

        var pivotRow = 0
        for (col in vars.indices) {
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

        var contradiction = false
        val forced = HashMap<Int, Boolean>()
        for (r in rows.indices) {
            when (Bits.popcount(rows[r])) {
                0 -> if (rhs[r] == 1) {
                    contradiction = true
                    break
                }

                1 -> {
                    val v = vars[Bits.firstSet(rows[r])]
                    val value = rhs[r] == 1
                    val previous = forced[v]
                    if (previous == null) {
                        forced[v] = value
                    } else if (previous != value) {
                        contradiction = true
                        break
                    }
                }

                else -> {}
            }
        }

        val existingUnits = IntHashSet()
        for (f in problem.factors) {
            if (f is Clause && f.literals.size == 1) existingUnits.add(f.literals[0])
        }
        val extra = ArrayList<Factor>()

        if (contradiction) {
            val witness = forced.keys.firstOrNull() ?: vars[0]
            val pos = Lit.make(witness, true)
            val neg = Lit.make(witness, false)
            if (pos !in existingUnits) extra.add(Clause(intArrayOf(pos)))
            if (neg !in existingUnits) extra.add(Clause(intArrayOf(neg)))
        } else {
            for ((v, value) in forced.entries.sortedBy { it.key }) {
                val lit = Lit.make(v, value)
                if (lit !in existingUnits) extra.add(Clause(intArrayOf(lit)))
            }
        }

        if (extra.isEmpty()) return PassDelta()
        return PassDelta(addedFactors = extra)
    }
}
