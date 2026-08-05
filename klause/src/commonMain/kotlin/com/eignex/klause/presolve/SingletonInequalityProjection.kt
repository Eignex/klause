package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.IntArrayList

/**
 * Project out a variable that occurs in exactly one linear **inequality** and nowhere else (nor in the
 * objective) — the singleton-column case [AffineElimination] does not reach (it substitutes only
 * *equality*-defined variables). In `a·x + rest ⟨≤/≥⟩ b`, giving `rest` the most room means driving `x`
 * to the bound that minimizes (for `≤`) or maximizes (for `≥`) `a·x`; the surviving constraint on `rest`
 * is then `rest ⟨≤/≥⟩ b − a·x_best` (exact Fourier-Motzkin elimination of `x` from a single inequality).
 * `x` is dropped and rebuilt at that bound on reconstruct.
 *
 * Solution-set altering (a complete enumerator would branch over `x`'s whole feasible range, which the
 * pinned reconstruct collapses), so it is gated off for solution-set-sensitive queries; gated to
 * `|value| < 2³¹` so `a·x_best` cannot overflow.
 */
internal object SingletonInequalityProjection {

    private fun fitsHalfLong(v: Long): Boolean = v > -(1L shl 31) && v < (1L shl 31)

    fun project(problem: Problem, objectiveIntVars: Set<Int>): PassDelta {
        // Occurrence count per integer variable across every factor: a projectable variable is in one.
        val occ = IntArray(problem.numIntVars)
        for (f in problem.factors) for (v in f.intVars) if (v in occ.indices) occ[v]++

        val dropped = IntArrayList()
        val added = ArrayList<Factor>()
        val pinned = HashMap<Int, Long>()
        problem.factors.forEachIndexed { i, f ->
            if (f !is Linear || !f.isIntegerCore || (f.op != LinearOp.LE && f.op != LinearOp.GE) || f.vars.size < 2) {
                return@forEachIndexed
            }
            if (!fitsHalfLong(f.bound)) return@forEachIndexed
            val j = f.vars.indices.firstOrNull { k ->
                val x = f.vars[k]
                occ[x] == 1 && x !in objectiveIntVars && x !in pinned && f.coeffs[k] != 0L && fitsHalfLong(f.coeffs[k])
            } ?: return@forEachIndexed
            val x = f.vars[j]
            val a = f.coeffs[j]
            val dom = problem.intDomains[x]
            if (!fitsHalfLong(dom.min) || !fitsHalfLong(dom.max)) return@forEachIndexed
            // The bound of x that leaves `rest` the widest feasible region.
            val xBest = when (f.op) {
                LinearOp.LE -> if (a > 0L) dom.min else dom.max
                else -> if (a > 0L) dom.max else dom.min
            }
            val restVars = IntArray(f.vars.size - 1)
            val restCoeffs = LongArray(f.vars.size - 1)
            var w = 0
            for (k in f.vars.indices) {
                if (k != j) {
                    restVars[w] = f.vars[k]
                    restCoeffs[w] = f.coeffs[k]
                    w++
                }
            }
            dropped.add(i)
            added.add(Linear(restCoeffs, restVars, f.op, f.bound - a * xBest))
            pinned[x] = xBest
        }
        if (dropped.isEmpty()) return PassDelta()
        val reconstruct: (Sample) -> Sample = { s ->
            val ints = s.ints.copyOf()
            for ((x, v) in pinned) if (x in ints.indices) ints[x] = v
            s.copy(ints = ints)
        }
        return PassDelta(dropped.toIntArray(), added, reconstruct = reconstruct)
    }
}
