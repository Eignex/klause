package com.eignex.klause.lp.lattice

import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.isZero
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.times

// The Mixed-Echelon-Hermite transformation of M. Bromberger, *A Reduction from Unbounded Linear Mixed Arithmetic
// Problems into Bounded Problems*, IJCAR 2018, https://doi.org/10.1007/978-3-319-94205-6_22. [transform] is
// unimodular, so `x = V·y` is a bijection of the integer lattice and an unsatisfiability proof needs no conversion.
internal class MixedEchelonHermite(
    val equalities: List<SparseIntRow>,
    val inequalities: List<SparseIntRow>,
    val transform: UnimodularTransform,
    // Index-aligned with [equalities]. The Hermite step is a column operation, so only the echelon step
    // recombines these.
    val equalityRhs: Array<BigInt> = emptyArray(),
    val inconsistent: Boolean = false,
) {
    fun recover(y: Array<BigInt>): Array<BigInt> {
        val x = Array(transform.size) { BIG_ZERO }
        transform.forEachEntry { row, col, value -> x[row] += value * y[col] }
        return x
    }
}

// The equalities choose the basis: in column Hermite form they are lower triangular, so [triangularBounds] bounds
// their pivot variables by the model's own structure rather than an invented search box. Dependent equalities
// are dropped first since they would enlarge the expensive Hermite step. Cancellation yields no equalities and the
// identity transform.
internal fun mixedEchelonHermite(
    equalities: List<SparseIntRow>,
    inequalities: List<SparseIntRow>,
    cols: Int,
    equalityRhs: Array<BigInt>? = null,
    cancellation: ExecutionControl = ExecutionControl.Never,
): MixedEchelonHermite {
    if (cols == 0) {
        return MixedEchelonHermite(emptyList(), inequalities, UnimodularTransform(0))
    }
    val echelon = if (equalities.isEmpty()) null else bareissEchelon(equalities, cols, equalityRhs, cancellation)
    val independent = echelon?.rows ?: emptyList()
    // An untouched transform column is never materialised, so the identity costs nothing to carry.
    val hnf = if (independent.isEmpty()) null else hermiteNormalForm(independent, cols, cancellation)
    val v = hnf?.v ?: UnimodularTransform(cols)
    val eq = hnf?.h ?: emptyList()
    val ineq = inequalities.map { applyTransform(it, v) }
    val rhs = if (hnf == null) emptyArray() else echelon?.rhs ?: emptyArray()
    return MixedEchelonHermite(eq, ineq, v, rhs, echelon?.inconsistent == true)
}

// `(a·V)ⱼ = Σₖ aₖ·V(k, j)` as a scatter over V's non-zeros, since the row is sparse and V mostly the identity.
private fun applyTransform(row: SparseIntRow, v: UnimodularTransform): SparseIntRow {
    val acc = HashMap<Int, BigInt>()
    v.forEachEntry { k, j, value ->
        val a = row[k]
        if (!a.isZero()) acc[j] = (acc[j] ?: BIG_ZERO) + a * value
    }
    return sparseIntRow(acc)
}

// Pushes ranges of `y` back through `x = V·y`, so the original variables get bounds that are the model's own and a
// refutation inside them is real. A side stays null when any term feeding it is open.
internal fun MixedEchelonHermite.originalBounds(yLo: Array<BigInt?>, yHi: Array<BigInt?>): TriangularBounds {
    val n = transform.size
    val lo = arrayOfNulls<BigInt>(n)
    val hi = arrayOfNulls<BigInt>(n)
    val open = BooleanArray(n)
    val openHigh = BooleanArray(n)
    for (i in 0 until n) {
        lo[i] = BIG_ZERO
        hi[i] = BIG_ZERO
    }
    transform.forEachEntry { row, col, c ->
        val positive = c > BIG_ZERO
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
