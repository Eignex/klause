package com.eignex.klause.presolve.linear

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PassDelta
import com.eignex.klause.presolve.RebuildStep
import com.eignex.klause.presolve.SourceDelta
import com.eignex.klause.presolve.SourceRebuilds
import com.eignex.klause.presolve.asSampleLift
import com.eignex.klause.presolve.equivalentLinear
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.ScanMeter

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

    private const val PROJECTION_WORK_WEIGHT = 10L

    private class Interrupted : RuntimeException()

    private class Projection {
        val dropped = IntArrayList()
        val added = ArrayList<Factor>()
        val steps = ArrayList<RebuildStep>()
        val eliminated = HashSet<Int>()
    }

    private fun visit(scan: ScanMeter) {
        if (!scan.visit(PROJECTION_WORK_WEIGHT)) throw Interrupted()
    }

    private fun occurrences(problem: Problem, scan: ScanMeter): IntArray {
        val counts = IntArray(problem.numIntVars)
        for (factor in problem.factors) {
            visit(scan)
            for (variable in factor.intVars) {
                visit(scan)
                if (variable in counts.indices) counts[variable]++
            }
        }
        if (!scan.check()) throw Interrupted()
        return counts
    }

    private fun fitsHalfLong(v: Long): Boolean = v > -(1L shl 31) && v < (1L shl 31)

    fun project(
        problem: BakedProblem,
        objectiveIntVars: Set<Int>,
        cancellation: Cancellation = Cancellation.Never,
    ): PassDelta {
        val scan = ScanMeter(cancellation)
        val result = Projection()
        try {
            if (!scan.check()) return PassDelta()
            val occ = occurrences(problem, scan)
            result.projectRows(problem, objectiveIntVars, occ, scan)
        } catch (_: Interrupted) {
            // Only rows published with their reconstruction survive an interrupted scan.
        } finally {
            scan.flush()
        }
        if (result.dropped.isEmpty()) return PassDelta()
        val rebuild = SourceRebuilds(result.steps)
        return PassDelta(result.dropped.toIntArray(), result.added, reconstruct = rebuild.asSampleLift())
            .also { it.rebuild = rebuild }
    }

    private fun Projection.projectRows(
        problem: BakedProblem,
        objectiveIntVars: Set<Int>,
        occ: IntArray,
        scan: ScanMeter,
    ) {
        problem.factors.forEachIndexed { i, factor ->
            visit(scan)
            val f = factor.equivalentLinear { visit(scan) } ?: return@forEachIndexed
            if ((f.op != LinearOp.LE && f.op != LinearOp.GE) || f.vars.size < 2) {
                return@forEachIndexed
            }
            val row = f.integerConstants ?: return@forEachIndexed
            if (!fitsHalfLong(row.bound)) return@forEachIndexed
            val j = f.vars.indices.firstOrNull { k ->
                visit(scan)
                val x = f.vars[k]
                occ[x] == 1 && x !in objectiveIntVars && x !in eliminated &&
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
                visit(scan)
                if (k != j) {
                    restVars[w] = f.vars[k]
                    restCoeffs[w] = row.coeff(k)
                    w++
                }
            }
            val replacement = Linear(restCoeffs, restVars, f.op, row.bound - a * xBest)
            val step = RebuildStep.AffineValue(x, xBest, IntArray(0), LongArray(0), 1L)
            if (!scan.check()) throw Interrupted()
            dropped.add(i)
            added.add(replacement)
            steps.add(step)
            eliminated.add(x)
        }
        scan.check()
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
        val scan = ScanMeter(cancellation)
        val result = Projection()
        try {
            if (!scan.check()) return SourceDelta()
            val occ = occurrences(problem, scan)
            result.projectSourceRows(problem, objectiveIntVars, occ, scan)
        } catch (_: Interrupted) {
            // Only rows published with their reconstruction survive an interrupted scan.
        } finally {
            scan.flush()
        }
        if (result.dropped.isEmpty()) return SourceDelta()
        return SourceDelta(result.dropped.toIntArray(), result.added, rebuild = SourceRebuilds(result.steps))
    }

    private fun Projection.projectSourceRows(
        problem: Problem,
        objectiveIntVars: Set<Int>,
        occ: IntArray,
        scan: ScanMeter,
    ) {
        val bounds = problem.intBounds
        problem.factors.forEachIndexed { i, factor ->
            visit(scan)
            val f = factor.equivalentLinear { visit(scan) } ?: return@forEachIndexed
            if ((f.op != LinearOp.LE && f.op != LinearOp.GE) || f.vars.size < 2) return@forEachIndexed
            val row = f.integerConstants ?: return@forEachIndexed
            if (!fitsHalfLong(row.bound) || f.vars.indices.any {
                    visit(scan)
                    !fitsHalfLong(row.coeff(it))
                }
            ) {
                return@forEachIndexed
            }
            val j = f.vars.indices.firstOrNull { k ->
                visit(scan)
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
                visit(scan)
                if (k != j) {
                    restVars[w] = f.vars[k]
                    restCoeffs[w] = row.coeff(k)
                    w++
                }
            }
            val bestOpen = if (relaxesDown) bounds.isOpenLower(x) else bounds.isOpenUpper(x)
            val replacement: Factor?
            val step: RebuildStep
            if (bestOpen) {
                val clamp = when {
                    relaxesDown -> if (bounds.hasUpper(x)) bounds.upper(x) else null
                    else -> if (bounds.hasLower(x)) bounds.lower(x) else null
                }
                val negated = LongArray(restCoeffs.size) {
                    visit(scan)
                    -restCoeffs[it]
                }
                replacement = null
                step = RebuildStep.QuotientValue(x, row.bound, restVars, negated, a, relaxesDown, clamp)
            } else {
                val declared = problem.declaredIntDomains.declaredOrNull(x)
                val xBest = when {
                    relaxesDown -> declared?.min ?: bounds.lower(x)
                    else -> declared?.max ?: bounds.upper(x)
                }
                if (!fitsHalfLong(xBest)) return@forEachIndexed
                replacement = Linear(restCoeffs, restVars, f.op, row.bound - a * xBest)
                step = RebuildStep.AffineValue(x, xBest, IntArray(0), LongArray(0), 1L)
            }
            if (!scan.check()) throw Interrupted()
            dropped.add(i)
            if (replacement != null) added.add(replacement)
            steps.add(step)
            eliminated.add(x)
        }
        scan.check()
    }
}
