package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lp.engine.LpOverflowException
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.solveAndCertify
import com.eignex.klause.util.Cancellation
import kotlin.math.round

/**
 * Find an integer solution by fitting a unit cube inside the constraint polyhedron.
 *
 * Bromberger and Weidenbach, *Fast Cube Tests for LIA Constraint Solving* (IJCAR 2016) and *New
 * Techniques for Linear Arithmetic: Cubes and Equalities* (Formal Methods in System Design, 2017);
 * the transformation below is their Proposition 3 and the infinite-lattice-width guarantee their Lemma 4.
 *
 * The linear cube transformation shifts each row's bound by half its 1-norm: a cube of edge length `e`
 * centred at `z` lies inside `Ax ≤ b` exactly when `Az ≤ b'` with `b'ᵢ = bᵢ − (e/2)·‖aᵢ‖₁`. At `e = 1`
 * the solutions of the shifted system are the centres of unit cubes that fit, and a unit cube always
 * contains an integer point — so rounding such a centre yields an integer solution of the original.
 *
 * The point of doing this here is that it needs **no bounds at all**. A model whose columns are genuinely
 * unbounded is the hardest case for a search that must pick a finite box, and it is the easiest case for
 * this test: a polyhedron of infinite lattice width contains cubes of every edge length, so the shifted
 * system stays feasible however far the bound moves. Where the search would need an invented box, the
 * cube finds the solution outright.
 *
 * Rows come in as `≤` or `≥`, the latter negated into the former. An equality is declined outright: it
 * pins its variables to a hyperplane, and a hyperplane has no interior for a cube to sit in.
 *
 * Incomplete by construction: a polyhedron can hold integer points without holding a unit cube — a thin
 * or degenerate one holds no cube at all — so a failure means "no conclusion". It is also *checked*: the
 * rounded centre is verified against the rows before it is returned, so a mistake anywhere above costs a
 * missed solution and never a wrong one.
 */
internal fun unitCubeSolution(
    openBounds: Array<OpenIntBounds>,
    constraints: List<Linear>,
    cancellation: Cancellation = Cancellation.Never,
): LongArray? {
    if (openBounds.isEmpty() || constraints.isEmpty()) return null
    // An equality pins its variables to a hyperplane, which has no interior for a cube to sit in.
    if (constraints.any { it.op != LinearOp.LE && it.op != LinearOp.GE }) return null
    val rows = constraints.map { asLe(it) ?: return null }
    val cb = openColumns(builderOf(), openBounds)
    for (f in rows) {
        val shifted = cubeShiftedBound(f) ?: return null
        val (cols, vals) = splitTerms(f, cb.posCol, cb.negCol) ?: return null
        cb.builder.addRow(cols, vals, Relation.LE, shifted)
    }
    val model = try {
        cb.builder.build(Sense.MINIMIZE)
    } catch (_: LpOverflowException) {
        return null
    }
    if (model.n == 0 || cancellation()) return null
    val outcome = solveAndCertify(model, cancellation = cancellation)
    if (outcome.verdict != LpVerdict.OPTIMAL) return null
    val centre = outcome.exactPrimal ?: outcome.float?.primal ?: return null
    val candidate = LongArray(openBounds.size) { v ->
        val pos = centre.getOrElse(cb.posCol[v]) { 0.0 }
        val neg = if (cb.negCol[v] >= 0) centre.getOrElse(cb.negCol[v]) { 0.0 } else 0.0
        val rounded = round(pos - neg)
        if (!rounded.isFinite() || rounded > Long.MAX_VALUE.toDouble() || rounded < Long.MIN_VALUE.toDouble()) {
            return null
        }
        rounded.toLong()
    }
    return if (satisfies(candidate, openBounds, rows)) candidate else null
}

/**
 * [f] as a `≤` row, negating a `≥` in place of it. Null when a coefficient or the bound cannot be negated
 * without wrapping, or when [f] carries coefficients too wide for the `Long` row this builds.
 */
private fun asLe(f: Linear): Linear? {
    val row = f.integerConstants ?: return null
    if (f.op == LinearOp.LE) return f
    if (f.op != LinearOp.GE) return null
    if (row.bound == Long.MIN_VALUE) return null
    val coeffs = LongArray(f.vars.size) {
        val c = row.coeff(it)
        if (c == Long.MIN_VALUE) return null
        -c
    }
    return Linear(coeffs, f.vars, LinearOp.LE, -row.bound)
}

/**
 * `bᵢ − ⌈‖aᵢ‖₁⌉ / 2` for a unit cube, rounded so the shifted bound stays integral and never claims more
 * room than the row has. Null when the norm leaves the `Long` range, where the shift cannot be taken.
 */
private fun cubeShiftedBound(f: Linear): Long? {
    val row = f.integerConstants ?: return null
    var norm = 0L
    for (k in f.vars.indices) {
        val c = row.coeff(k)
        val magnitude = if (c < 0L) {
            if (c == Long.MIN_VALUE) return null
            -c
        } else {
            c
        }
        if (norm > Long.MAX_VALUE - magnitude) return null
        norm += magnitude
    }
    // Half the norm rounded *up*, so the cube asked for is never larger than one the row admits.
    val half = norm / 2L + norm % 2L
    val shifted = row.bound - half
    return if (row.bound < 0L && half > 0L && shifted > row.bound) null else shifted
}

/** Whether [candidate] satisfies every row and declared bound — the check that makes the test safe. */
private fun satisfies(candidate: LongArray, openBounds: Array<OpenIntBounds>, constraints: List<Linear>): Boolean {
    for (v in candidate.indices) {
        val b = openBounds[v]
        if (b.lo != null && candidate[v] < b.lo) return false
        if (b.hi != null && candidate[v] > b.hi) return false
    }
    for (f in constraints) {
        val row = f.integerConstants ?: return false
        var acc = 0L
        for (k in f.vars.indices) {
            val v = f.vars[k]
            if (v >= candidate.size) return false
            val term = row.coeff(k) * candidate[v]
            // A row whose activity leaves `Long` cannot be checked, so the candidate is not accepted.
            if (row.coeff(k) != 0L && term / row.coeff(k) != candidate[v]) return false
            if ((acc > 0L && term > Long.MAX_VALUE - acc) || (acc < 0L && term < Long.MIN_VALUE - acc)) {
                return false
            }
            acc += term
        }
        if (acc > row.bound) return false
    }
    return true
}
