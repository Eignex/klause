package com.eignex.klause.lp

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
    val equalities: Array<Array<BigInteger>>,
    /** The inequality rows in the new variables, in the same column order. */
    val inequalities: Array<Array<BigInteger>>,
    /** The unimodular `V` of `x = V·y`, row-major and `cols × cols`. */
    val transform: Array<Array<BigInteger>>,
) {
    /** The original point `x = V·y` for a solution [y] of the rewritten system. */
    fun recover(y: Array<BigInteger>): Array<BigInteger> = Array(transform.size) { i ->
        var acc = BigInteger.ZERO
        for (j in transform[i].indices) acc += transform[i][j] * y[j]
        acc
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
 * expensive half.
 */
internal fun mixedEchelonHermite(
    equalities: Array<Array<BigInteger>>,
    inequalities: Array<Array<BigInteger>>,
    cols: Int,
): MixedEchelonHermite {
    if (cols == 0) {
        return MixedEchelonHermite(emptyArray(), inequalities, emptyArray())
    }
    // Independent equalities only; a dependent one adds nothing and would widen the Hermite step.
    val independent = if (equalities.isEmpty()) emptyArray() else bareissEchelon(equalities).rows
    val v: Array<Array<BigInteger>>
    val eq: Array<Array<BigInteger>>
    if (independent.isEmpty()) {
        // No equalities to choose a basis: the identity leaves the system as it stands.
        v = Array(cols) { i -> Array(cols) { j -> if (i == j) BigInteger.ONE else BigInteger.ZERO } }
        eq = emptyArray()
    } else {
        val hnf = hermiteNormalForm(independent)
        v = hnf.v
        eq = hnf.h
    }
    val ineq = Array(inequalities.size) { i -> applyTransform(inequalities[i], v, cols) }
    return MixedEchelonHermite(eq, ineq, v)
}

/** One row in the new variables: `(a·V)ⱼ = Σₖ aₖ·V[k][j]`. */
private fun applyTransform(row: Array<BigInteger>, v: Array<Array<BigInteger>>, cols: Int): Array<BigInteger> =
    Array(cols) { j ->
        var acc = BigInteger.ZERO
        for (k in row.indices) if (k < v.size) acc += row[k] * v[k][j]
        acc
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
    for (i in 0 until n) {
        var accLo: BigInteger? = BigInteger.ZERO
        var accHi: BigInteger? = BigInteger.ZERO
        for (j in transform[i].indices) {
            val c = transform[i][j]
            if (c.isZero()) continue
            val termLo = if (c > BigInteger.ZERO) yLo.getOrNull(j)?.times(c) else yHi.getOrNull(j)?.times(c)
            val termHi = if (c > BigInteger.ZERO) yHi.getOrNull(j)?.times(c) else yLo.getOrNull(j)?.times(c)
            accLo = if (termLo == null || accLo == null) null else accLo + termLo
            accHi = if (termHi == null || accHi == null) null else accHi + termHi
        }
        lo[i] = accLo
        hi[i] = accHi
    }
    return TriangularBounds(lo, hi)
}
