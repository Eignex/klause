package com.eignex.klause.solver.presolve

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.bool.Clause
import com.eignex.klause.solver.factor.bool.Xor
import com.eignex.klause.util.Bits

internal object XorUnits {

    /**
     * One-shot GF(2) elimination over all [Xor] factors. Reduces the root xor system once and emits
     * only its global consequences: forced literals as unit [Clause]s and, on contradiction (`0 = 1`),
     * a contradictory unit pair. The original xor factors stay in place for normal propagation.
     *
     * This is idempotent: units already present are not re-added, so the presolve round engine reaches
     * a fixpoint instead of appending duplicates on each round.
     */
    fun deriveXorUnits(problem: Problem): Problem {
        val xors = problem.factors.filterIsInstance<Xor>()
        if (xors.isEmpty()) return problem

        val varOrder = LinkedHashSet<Int>()
        for (x in xors) for (lit in x.literals) varOrder.add(Lit.variable(lit))
        if (varOrder.isEmpty()) return problem
        val vars = varOrder.toIntArray()
        val colOfVar = HashMap<Int, Int>(vars.size * 2)
        for (i in vars.indices) colOfVar[vars[i]] = i
        val words = (vars.size + 63) ushr 6
        val rows = Array(xors.size) { LongArray(words) }
        val rhs = IntArray(xors.size)

        for (r in xors.indices) {
            val x = xors[r]
            val row = rows[r]
            val occParity = HashMap<Int, Int>()
            var negParity = 0
            for (lit in x.literals) {
                val v = Lit.variable(lit)
                occParity[v] = (occParity[v] ?: 0) xor 1
                if (!Lit.isPositive(lit)) negParity = negParity xor 1
            }
            for ((v, parity) in occParity) if (parity == 1) Bits.set(row, colOfVar.getValue(v))
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

        val existingUnits = HashSet<Int>()
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

        if (extra.isEmpty()) return problem
        return PresolveShared.rebuildProblem(problem, problem.factors.toList() + extra)
    }
}
