package com.eignex.klause.presolve.linear

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PassDelta
import com.eignex.klause.presolve.RebuildStep
import com.eignex.klause.presolve.SourceDelta
import com.eignex.klause.presolve.SourceRebuilds
import com.eignex.klause.presolve.equivalentLinear
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList

/**
 * Project out a variable that occurs in exactly one linear **inequality** and nowhere else (nor in the
 * objective) — the singleton-column case [AffineSingletons] does not reach (it substitutes only
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

    // Work charged per factor scanned, scaled by its arity: the scan reads each row once.
    private const val PROJECTION_WORK_WEIGHT = 10L

    private fun fitsHalfLong(v: Long): Boolean = v > -(1L shl 31) && v < (1L shl 31)

    fun project(problem: BakedProblem, objectiveIntVars: Set<Int>): PassDelta {
        // Occurrence count per integer variable across every factor: a projectable variable is in one.
        val occ = IntArray(problem.numIntVars)
        for (f in problem.factors) for (v in f.intVars) if (v in occ.indices) occ[v]++

        val dropped = IntArrayList()
        val added = ArrayList<Factor>()
        val pinned = HashMap<Int, Long>()
        problem.factors.forEachIndexed { i, factor ->
            val f = factor.equivalentLinear() ?: return@forEachIndexed
            if ((f.op != LinearOp.LE && f.op != LinearOp.GE) || f.vars.size < 2) {
                return@forEachIndexed
            }
            val row = f.integerConstants ?: return@forEachIndexed
            if (!fitsHalfLong(row.bound)) return@forEachIndexed
            val j = f.vars.indices.firstOrNull { k ->
                val x = f.vars[k]
                occ[x] == 1 && x !in objectiveIntVars && x !in pinned &&
                    row.coeff(k) != 0L && fitsHalfLong(row.coeff(k))
            } ?: return@forEachIndexed
            val x = f.vars[j]
            val a = row.coeff(j)
            val dom = problem.rootIntDomain(x)
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
                    restCoeffs[w] = row.coeff(k)
                    w++
                }
            }
            dropped.add(i)
            added.add(Linear(restCoeffs, restVars, f.op, row.bound - a * xBest))
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

    /**
     * The source form, over declared ranges, where the side of `x` that relaxes the row can be open.
     *
     * A closed best side projects exactly as the finite form does, `x` pinned there. An open one means
     * `x` can always be chosen to satisfy the row whatever `rest` is, so the row drops outright and `x` is
     * rebuilt as the extreme integer the row admits — held inside its other side when that one is closed.
     * A column with an open side declares no value set, so that rebuilt value cannot land in a hole.
     */
    fun projectSource(
        problem: Problem,
        objectiveIntVars: Set<Int>,
        cancellation: Cancellation = Cancellation.Never,
    ): SourceDelta {
        val occ = IntArray(problem.numIntVars)
        for (f in problem.factors) for (v in f.intVars) if (v in occ.indices) occ[v]++
        val bounds = problem.intBounds
        val dropped = IntArrayList()
        val added = ArrayList<Factor>()
        val steps = ArrayList<RebuildStep>()
        val eliminated = HashSet<Int>()
        problem.factors.forEachIndexed { i, factor ->
            cancellation.charge(PROJECTION_WORK_WEIGHT * (1L + factor.intVars.size))
            val f = factor.equivalentLinear() ?: return@forEachIndexed
            if ((f.op != LinearOp.LE && f.op != LinearOp.GE) || f.vars.size < 2) return@forEachIndexed
            val row = f.integerConstants ?: return@forEachIndexed
            if (!fitsHalfLong(row.bound) || f.vars.indices.any { !fitsHalfLong(row.coeff(it)) }) {
                return@forEachIndexed
            }
            val j = f.vars.indices.firstOrNull { k ->
                val x = f.vars[k]
                occ[x] == 1 && x !in objectiveIntVars && x !in eliminated && row.coeff(k) != 0L
            } ?: return@forEachIndexed
            val x = f.vars[j]
            val a = row.coeff(j)
            // Which side of x gives `rest` the most room: its lower side when shrinking `a·x` relaxes the row.
            val relaxesDown = (f.op == LinearOp.LE) == (a > 0L)
            val restVars = IntArray(f.vars.size - 1)
            val restCoeffs = LongArray(f.vars.size - 1)
            var w = 0
            for (k in f.vars.indices) {
                if (k != j) {
                    restVars[w] = f.vars[k]
                    restCoeffs[w] = row.coeff(k)
                    w++
                }
            }
            val bestOpen = if (relaxesDown) bounds.isOpenLower(x) else bounds.isOpenUpper(x)
            if (bestOpen) {
                val clamp = when {
                    relaxesDown -> if (bounds.hasUpper(x)) bounds.upper(x) else null
                    else -> if (bounds.hasLower(x)) bounds.lower(x) else null
                }
                val negated = LongArray(restCoeffs.size) { -restCoeffs[it] }
                steps.add(RebuildStep.QuotientValue(x, row.bound, restVars, negated, a, relaxesDown, clamp))
            } else {
                val declared = problem.declaredIntDomains.declaredOrNull(x)
                val xBest = when {
                    relaxesDown -> declared?.min ?: bounds.lower(x)
                    else -> declared?.max ?: bounds.upper(x)
                }
                if (!fitsHalfLong(xBest)) return@forEachIndexed
                added.add(Linear(restCoeffs, restVars, f.op, row.bound - a * xBest))
                steps.add(RebuildStep.AffineValue(x, xBest, IntArray(0), LongArray(0), 1L))
            }
            dropped.add(i)
            eliminated.add(x)
        }
        if (dropped.isEmpty()) return SourceDelta()
        return SourceDelta(dropped.toIntArray(), added, rebuild = SourceRebuilds(steps))
    }
}
