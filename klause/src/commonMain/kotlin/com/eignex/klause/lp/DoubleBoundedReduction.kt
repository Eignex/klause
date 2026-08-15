package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.solver.Cancellation

/**
 * Double-bounded reduction: the inequalities of a constraint system whose *direction* is unbounded can
 * be dropped without changing whether the system has a mixed solution.
 *
 * Split a system into an unbounded part `Ax ≤ b` — every direction in it unbounded — and a bounded part
 * `l ≤ Dx ≤ u`. The unbounded part can restrict which solutions remain but never removes them all: it is
 * absolutely unbounded, so it keeps infinitely many integer points, and the bounded part's own unbounded
 * directions are orthogonal ones it cannot cut away. The system is therefore equisatisfiable to its
 * bounded part alone, and a row left out is one fewer row for every later stage to carry.
 *
 * This is the first half of the reduction of unbounded linear mixed arithmetic to bounded arithmetic
 * (Bromberger, IJCAR 2018). It leaves the *inequalities* bounded but not necessarily the *variables* —
 * `1 ≤ 3x₁ − 3x₂ ≤ 2` has two bounded rows and two unbounded variables — so it does not on its own make
 * a search terminate. Closing that gap is the Mixed-Echelon-Hermite transformation's job.
 *
 * The reduction preserves *satisfiability*, not the solution set, and that is what keeps it out of the
 * two uses it otherwise looks made for. It cannot close a domain for the search: the guarantee is that
 * *some* solution survives inside the derived box, not that every solution lies there, so a bound taken
 * from it could cut away the optimum or the solutions an enumeration owes its caller — unlike the
 * containment bounds [tightenOpenIntBounds] and the echelon block produce. And it cannot refute; see
 * [unboundedlyInfeasible] for why. What is left is searching the reduced system itself and rebuilding an
 * original solution by travelling the discarded directions, and that rebuilding is the missing piece.
 *
 * A direction is bounded exactly when the auxiliary variable `z = aᵢᵀx` is, so each candidate row gets
 * one auxiliary column and the ordinary per-variable bound extraction decides it — including its
 * probe-frontier rule, which is what distinguishes "optimum at a real bound" from "rode to the stand-in
 * for infinity".
 */
internal fun boundedRowMask(
    bounds: Array<OpenIntBounds>,
    constraints: List<Linear>,
    cancellation: Cancellation = Cancellation.Never,
): BooleanArray {
    val out = BooleanArray(constraints.size)
    if (constraints.isEmpty()) return out

    val cb = openColumns(builderOf(), bounds)
    val builder = cb.builder
    val posCol = cb.posCol
    val negCol = cb.negCol

    // One auxiliary column per row, tied by `aᵢᵀx − zᵢ = 0`, so the row's direction is bounded exactly
    // when zᵢ is. Rows are added for every constraint, the auxiliary equalities on top.
    val auxCol = IntArray(constraints.size) { -1 }
    for (f in constraints) {
        val rel = when (f.op) {
            LinearOp.LE -> Relation.LE
            LinearOp.GE -> Relation.GE
            LinearOp.EQ -> Relation.EQ
            else -> continue
        }
        val (cols, vals) = splitTerms(f, posCol, negCol)
        builder.addRow(cols, vals, rel, f.bound)
    }
    for (i in constraints.indices) {
        val f = constraints[i]
        if (f.op != LinearOp.LE && f.op != LinearOp.GE && f.op != LinearOp.EQ) continue
        val z = builder.addFreeVar(null, null)
        auxCol[i] = z
        val (cols, vals) = splitTerms(f, posCol, negCol)
        val eqCols = cols.copyOf(cols.size + 1)
        val eqVals = vals.copyOf(vals.size + 1)
        eqCols[cols.size] = z
        eqVals[vals.size] = -1L
        builder.addRow(eqCols, eqVals, Relation.EQ, 0L)
    }

    val base = try {
        builder.build(Sense.MINIMIZE)
    } catch (_: LpOverflowException) {
        return out // no verdict on a relaxation the row arithmetic cannot express; keep every row
    }

    var warm: Basis? = null
    var prev = EmptyIntArrayLocal
    for (i in constraints.indices) {
        if (cancellation()) break
        val z = auxCol[i]
        if (z < 0) continue
        var boundedAbove = false
        var boundedBelow = false
        for (maximize in booleanArrayOf(true, false)) {
            val cols = intArrayOf(z)
            val model = base.withRowObjective(cols, longArrayOf(if (maximize) -1L else 1L), prev)
            prev = cols
            val result = try {
                newLpSolver(model, cancellation).solvePrimal(warm)
            } catch (_: LpOverflowException) {
                null
            } ?: continue
            warm = result.basis
            // `null` here is the frontier verdict: the optimum only reached the stand-in for infinity,
            // so this side of the direction is genuinely unbounded.
            val closed = model.safeVariableBound(result, z, maximize, model.probeClampedHi[z]) != null
            if (maximize) boundedAbove = closed else boundedBelow = closed
        }
        out[i] = boundedAbove && boundedBelow
    }
    return out
}

/** The split column pair per variable, plus the builder they were added to. */
internal class OpenColumns(val builder: LpBuilder, val posCol: IntArray, val negCol: IntArray)

internal fun builderOf(): LpBuilder = LpBuilder()

/**
 * Add one column per variable over its genuinely open range: a variable open below enters split as
 * `x = x⁺ − x⁻` with both parts non-negative, never as a probe-magnitude lower bound — the double view
 * folds a column's lower bound into the row rhs, and at that magnitude the fold absorbs the true rhs.
 */
internal fun openColumns(builder: LpBuilder, bounds: Array<OpenIntBounds>): OpenColumns {
    val n = bounds.size
    val posCol = IntArray(n)
    val negCol = IntArray(n) { -1 }
    for (v in 0 until n) {
        val b = bounds[v]
        if (b.lo != null) {
            // A hi-open column carries no upper at all rather than the probe stand-in: a Farkas
            // certificate that reads a probe bound proves nothing about the unbounded model.
            posCol[v] = if (b.hi != null) builder.addVar(b.lo, b.hi) else builder.addOpenAboveVar(b.lo)
        } else {
            posCol[v] = if (b.hi != null && b.hi >= 0L) builder.addVar(0L, b.hi) else builder.addFreeVar(0L, null)
            negCol[v] = builder.addFreeVar(0L, null)
            if (b.hi != null && b.hi < 0L) {
                builder.addRow(intArrayOf(posCol[v], negCol[v]), longArrayOf(1L, -1L), Relation.LE, b.hi)
            }
        }
    }
    return OpenColumns(builder, posCol, negCol)
}

/** The builder relation for a linear row, or null for an operator the relaxation cannot express. */
internal fun relationOfLinear(f: Linear): Relation? = when (f.op) {
    LinearOp.LE -> Relation.LE
    LinearOp.GE -> Relation.GE
    LinearOp.EQ -> Relation.EQ
    else -> null
}

/** Row terms over the split column pairs: a variable represented `x = x⁺ − x⁻` contributes both halves. */
internal fun splitTerms(f: Linear, posCol: IntArray, negCol: IntArray): Pair<IntArray, LongArray> {
    var extra = 0
    for (k in f.vars.indices) if (negCol[f.vars[k]] >= 0) extra++
    val cols = IntArray(f.vars.size + extra)
    val vals = LongArray(cols.size)
    var w = 0
    for (k in f.vars.indices) {
        val v = f.vars[k]
        cols[w] = posCol[v]
        vals[w] = f.coeff(k)
        w++
        if (negCol[v] >= 0) {
            cols[w] = negCol[v]
            vals[w] = -f.coeff(k)
            w++
        }
    }
    return cols to vals
}

private val EmptyIntArrayLocal = IntArray(0)
