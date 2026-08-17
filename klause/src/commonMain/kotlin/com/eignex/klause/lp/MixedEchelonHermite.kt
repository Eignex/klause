package com.eignex.klause.lp

import com.eignex.klause.solver.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * Bromberger, *A Reduction from Unbounded Linear Mixed Arithmetic Problems into Bounded Problems*
 * (IJCAR 2018): the Mixed-Echelon-Hermite half of the pair of transformations that turn an unbounded
 * system into an equisatisfiable bounded one, in polynomial time and without a priori bounds.
 *
 * A system rewritten in the variables `y`, together with the change of variables that produced it.
 *
 * [transform] is unimodular, so `x = V·y` is a bijection of the integer lattice: `y` ranges over exactly
 * the integer points `x` does. A solution of the rewritten system therefore maps back to a solution of
 * the original by [recover], and an unsatisfiability proof needs no conversion at all.
 */
internal class MixedEchelonHermite(
    /** The equality rows in the new variables, in echelon form. */
    val equalities: List<SparseIntRow>,
    /** The inequality rows in the new variables, in the same column order. */
    val inequalities: List<SparseIntRow>,
    /** The unimodular `V` of `x = V·y`, column-major over `cols` columns. */
    val transform: UnimodularTransform,
    /** The equality right-hand sides after the same elimination, index-aligned with [equalities]; empty
     *  when the caller did not supply them. The Hermite step is a *column* operation, so it leaves these
     *  untouched — only the echelon step recombines them. */
    val equalityRhs: Array<BigInteger> = emptyArray(),
    /** True when the equalities reduced to `0 = c` for a non-zero `c`, refuting them on their own. */
    val inconsistent: Boolean = false,
) {
    /** The original point `x = V·y` for a solution [y] of the rewritten system. */
    fun recover(y: Array<BigInteger>): Array<BigInteger> {
        val x = Array(transform.size) { BigInteger.ZERO }
        transform.forEachEntry { row, col, value -> x[row] += value * y[col] }
        return x
    }
}

/**
 * Rewrite a system into the mixed echelon-Hermite form: the equality block drives a unimodular change of
 * variables, and every row is expressed in the new variables.
 *
 * The equalities are the part that pins variables down exactly, so they choose the basis. Putting them
 * in column Hermite form makes them lower triangular, and a triangular block bounds its pivot variables
 * by forward substitution ([triangularBounds]) — which is the whole point: after the rewrite a variable
 * is bounded by the model's own structure rather than by an invented search box.
 *
 * The same `V` is applied to the inequalities, since a change of variables has to be applied to the whole
 * system to preserve its solutions. `V` is unimodular, so the correspondence is exact over the integers,
 * not merely over the rationals — an integer solution of the rewritten system maps to an integer solution
 * of the original and back.
 *
 * The equality block is first reduced to echelon form ([bareissEchelon]) so that dependent equalities are
 * dropped: they constrain nothing, and carrying them would enlarge the Hermite step, which is the
 * expensive half. A reduction cut short by [cancellation] comes back with no equalities and the identity
 * transform, which is the honest "the structure implied nothing here".
 */
internal fun mixedEchelonHermite(
    equalities: List<SparseIntRow>,
    inequalities: List<SparseIntRow>,
    cols: Int,
    equalityRhs: Array<BigInteger>? = null,
    cancellation: Cancellation = Cancellation.Never,
): MixedEchelonHermite {
    if (cols == 0) {
        return MixedEchelonHermite(emptyList(), inequalities, UnimodularTransform(0))
    }
    // Independent equalities only; a dependent one adds nothing and would widen the Hermite step. The
    // right-hand sides ride along through the elimination, since row swaps and combinations reorder and
    // recombine them exactly as they do the coefficients.
    val echelon = if (equalities.isEmpty()) null else bareissEchelon(equalities, cols, equalityRhs, cancellation)
    val independent = echelon?.rows ?: emptyList()
    // No equalities to choose a basis, or none survived the budget: the identity leaves the system as it
    // stands, which costs nothing to carry because an untouched transform column is never materialised.
    val hnf = if (independent.isEmpty()) null else hermiteNormalForm(independent, cols, cancellation)
    val v = hnf?.v ?: UnimodularTransform(cols)
    val eq = hnf?.h ?: emptyList()
    val ineq = inequalities.map { applyTransform(it, v) }
    val rhs = if (hnf == null) emptyArray() else echelon?.rhs ?: emptyArray()
    return MixedEchelonHermite(eq, ineq, v, rhs, echelon?.inconsistent == true)
}

/**
 * One row in the new variables: `(a·V)ⱼ = Σₖ aₖ·V(k, j)`.
 *
 * Column-major `V` makes this a scatter over the transform's non-zeros rather than a lookup per column,
 * which matters because the row is sparse and `V` is mostly the identity. Only inequalities go through
 * here; the equalities come out of the Hermite step already transformed.
 */
private fun applyTransform(row: SparseIntRow, v: UnimodularTransform): SparseIntRow {
    val acc = HashMap<Int, BigInteger>()
    v.forEachEntry { k, j, value ->
        val a = row[k]
        if (!a.isZero()) acc[j] = (acc[j] ?: BigInteger.ZERO) + a * value
    }
    return sparseIntRow(acc)
}

/**
 * Ranges for the ORIGINAL variables implied by ranges for the rewritten ones, through `x = V·y`.
 *
 * This is what makes the transformation usable without rewriting the problem: the rewritten variables
 * are bounded by the model's own structure ([triangularBounds] over the triangular equality block), and
 * pushing those ranges back through `V` bounds the original variables too. A bound obtained this way is
 * the model's, not an invented search box, so a refutation inside it is a real refutation.
 *
 * A side stays `null` when any term feeding it is open — an unbounded `y` makes `x` unbounded in the
 * direction its coefficient points, and claiming otherwise would invent exactly the box this avoids.
 */
internal fun MixedEchelonHermite.originalBounds(yLo: Array<BigInteger?>, yHi: Array<BigInteger?>): TriangularBounds {
    val n = transform.size
    val lo = arrayOfNulls<BigInteger>(n)
    val hi = arrayOfNulls<BigInteger>(n)
    // A row with no term at all reads as pinned to zero, which is what an all-zero row of V would mean;
    // V is unimodular so no such row exists, and every row starts the sweep at the empty sum.
    val open = BooleanArray(n)
    val openHigh = BooleanArray(n)
    for (i in 0 until n) {
        lo[i] = BigInteger.ZERO
        hi[i] = BigInteger.ZERO
    }
    transform.forEachEntry { row, col, c ->
        val positive = c > BigInteger.ZERO
        val termLo = if (positive) yLo.getOrNull(col)?.times(c) else yHi.getOrNull(col)?.times(c)
        val termHi = if (positive) yHi.getOrNull(col)?.times(c) else yLo.getOrNull(col)?.times(c)
        if (termLo == null) open[row] = true else lo[row] = lo[row]?.plus(termLo)
        if (termHi == null) openHigh[row] = true else hi[row] = hi[row]?.plus(termHi)
    }
    for (i in 0 until n) {
        if (open[i]) lo[i] = null
        if (openHigh[i]) hi[i] = null
    }
    return TriangularBounds(lo, hi)
}
