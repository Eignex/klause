package com.eignex.klause.lp.lattice

import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * Bareiss, *Sylvester's Identity and Multistep Integer-Preserving Gaussian Elimination* (Mathematics of
 * Computation, 1968) for the fraction-free identity, with the row content stripped after each step as in
 * Nakos, Turner and Williams, *Fraction-free algorithms for linear and polynomial equations* (ACM SIGSAM
 * Bulletin 31(3), 1997): an integer matrix stays integer throughout and the entries stay small, without
 * the whole-matrix rescaling that makes the plain Bareiss sweep dense.
 *
 * A system reduced to row echelon form by fraction-free elimination, with the pivot column of each row.
 *
 * [rows] is the reduced matrix; [pivots] holds, per reduced row, the column its leading entry sits in,
 * ascending. A row that reduced to all zeros is dropped, so [rows] has exactly the system's rank.
 */
internal class BareissEchelon(
    val rows: List<SparseIntRow>,
    val pivots: IntArray,
    /** The right-hand sides carried through the same row operations, when the caller supplied them.
     *  Index-aligned with [rows]; empty when the elimination was run on coefficients alone. */
    val rhs: Array<BigInteger> = emptyArray(),
    /** True when a row reduced to `0 = c` with `c` non-zero: the equalities alone are unsatisfiable.
     *  Only ever set when right-hand sides were supplied, since without them it cannot be seen. */
    val inconsistent: Boolean = false,
)

/**
 * Row echelon form of [a] by fraction-free elimination over the sparse rows.
 *
 * Ordinary Gaussian elimination over the rationals would carry denominators; combining two rows by the
 * cross-multiplied `p·rᵢ − f·rₚ` keeps every entry integral, and dividing the result through by the gcd
 * of its own entries — the right-hand side included, so the equation is unchanged — keeps the entries
 * from compounding the way a naive fraction-free scheme's do.
 *
 * Dividing a row through by a constant leaves the equation it states untouched, so the reduced system is
 * still implied by the original one and a bound derived from it is still the model's. That is what the
 * whole chain needs; the plain Bareiss sweep additionally rescales the rows that do *not* meet the pivot,
 * which pins every entry to a minor of the input but touches every row at every step. On a matrix with
 * five non-zeros per row that rescaling, not the elimination, is what turns the sweep dense.
 *
 * The pivot row for a column is the sparsest of the rows leading there, which is the Markowitz rule
 * specialised to a single candidate column: fill-in is what the reduction is fighting, and the sparsest
 * candidate creates the least of it. Zero rows are dropped rather than kept, so [BareissEchelon.rows] is
 * exactly the rank — a system whose equalities are dependent contributes only its independent ones.
 */
internal fun bareissEchelon(
    a: List<SparseIntRow>,
    cols: Int,
    rhsIn: Array<BigInteger>? = null,
    cancellation: Cancellation = Cancellation.Never,
): BareissEchelon {
    val m = a.size
    if (m == 0 || cols == 0) return BareissEchelon(emptyList(), IntArray(0))
    val w = a.toMutableList()
    // The right-hand side is just another column: it must take every swap and every elimination the
    // coefficients take, or the reduced rows end up paired with bounds that belong to other rows.
    val rhs = rhsIn?.copyOf()

    // Rows bucketed by their leading column. A row's lead only ever moves right, so sweeping the columns
    // in order visits each bucket once and never has to scan the rows that do not reach the column.
    val byLead = HashMap<Int, MutableList<Int>>()
    var inconsistent = false
    for (i in 0 until m) {
        if (w[i].isZero) {
            if (rhs != null && !rhs[i].isZero()) inconsistent = true
        } else {
            byLead.getOrPut(w[i].lead) { ArrayList() }.add(i)
        }
    }

    val pivots = ArrayList<Int>()
    val kept = ArrayList<SparseIntRow>()
    val keptRhs = ArrayList<BigInteger>()
    for (c in 0 until cols) {
        val bucket = byLead.remove(c) ?: continue
        // Entries grow with the elimination and the row supports fill in, so a large equality block runs
        // far past any budget it was given. Polled per pivot: the caller treats a short result as "no
        // bound derived", which is the same answer it gets when the structure implies nothing.
        if (cancellation()) return BareissEchelon(emptyList(), IntArray(0))
        val p = sparsestRow(bucket, w)
        val pivotValue = w[p][c]
        for (q in bucket) {
            if (q == p) continue
            val factor = w[q][c]
            val g = gcdOf(pivotValue, factor)
            val scaleQ = pivotValue / g
            val scaleP = factor / g
            val combined = combine(w[q], scaleQ, w[p], scaleP)
            var combinedRhs = if (rhs == null) BigInteger.ZERO else scaleQ * rhs[q] - scaleP * rhs[p]
            val content = content(combined, if (rhs == null) null else combinedRhs)
            val reduced = if (content == BigInteger.ONE) combined else divide(combined, content)
            if (rhs != null) {
                if (content != BigInteger.ONE) combinedRhs /= content
                rhs[q] = combinedRhs
            }
            w[q] = reduced
            if (reduced.isZero) {
                if (rhs != null && !rhs[q].isZero()) inconsistent = true
            } else {
                byLead.getOrPut(reduced.lead) { ArrayList() }.add(q)
            }
        }
        pivots.add(c)
        kept.add(w[p])
        if (rhs != null) keptRhs.add(rhs[p])
    }

    return BareissEchelon(kept, pivots.toIntArray(), keptRhs.toTypedArray(), inconsistent)
}

/** The candidate with the fewest non-zeros, ties broken by row order so the reduction is reproducible. */
private fun sparsestRow(bucket: List<Int>, w: List<SparseIntRow>): Int {
    var best = bucket[0]
    for (i in bucket) if (w[i].index.size < w[best].index.size) best = i
    return best
}

/** `scaleTarget·target − scaleSource·source`, as a merge over the two supports. */
private fun combine(
    target: SparseIntRow,
    scaleTarget: BigInteger,
    source: SparseIntRow,
    scaleSource: BigInteger,
): SparseIntRow {
    val ti = target.index
    val si = source.index
    val index = IntArray(ti.size + si.size)
    val value = arrayOfNulls<BigInteger>(ti.size + si.size)
    var t = 0
    var s = 0
    var n = 0
    while (t < ti.size || s < si.size) {
        val tc = if (t < ti.size) ti[t] else Int.MAX_VALUE
        val sc = if (s < si.size) si[s] else Int.MAX_VALUE
        val col: Int
        val v: BigInteger
        when {
            tc < sc -> {
                col = tc
                v = scaleTarget * target.value[t]
                t++
            }

            sc < tc -> {
                col = sc
                v = -(scaleSource * source.value[s])
                s++
            }

            else -> {
                col = tc
                v = scaleTarget * target.value[t] - scaleSource * source.value[s]
                t++
                s++
            }
        }
        if (!v.isZero()) {
            index[n] = col
            value[n] = v
            n++
        }
    }
    if (n == 0) return SparseIntRow.Zero
    return SparseIntRow(index.copyOf(n), Array(n) { value[it]!! })
}

/** The gcd of the row's entries and [extra], or one as soon as nothing more can be divided out. */
private fun content(row: SparseIntRow, extra: BigInteger?): BigInteger {
    var g = extra?.abs() ?: BigInteger.ZERO
    for (v in row.value) {
        g = gcdOf(g, v)
        if (g == BigInteger.ONE) return BigInteger.ONE
    }
    return if (g.isZero()) BigInteger.ONE else g
}

private fun divide(row: SparseIntRow, by: BigInteger): SparseIntRow =
    SparseIntRow(row.index, Array(row.index.size) { row.value[it] / by })

private tailrec fun gcdOf(a: BigInteger, b: BigInteger): BigInteger = if (b.isZero()) a.abs() else gcdOf(b, a % b)
