package com.eignex.klause.solver.lp

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow

/**
 * Sparse LU factorization `P·B·Q = L·U` of an `m × m` basis, with **Markowitz threshold pivoting**:
 * at each step the pivot is chosen to minimise fill (the Markowitz count `(rowNnz−1)·(colNnz−1)` over
 * the active submatrix) among entries that are numerically acceptable (`|a| ≥ τ·max|column|`), so the
 * factors stay sparse instead of filling toward `O(m²)`. Both a row permutation `P` and a column
 * permutation `Q` are produced; only the nonzeros of `L`/`U` are stored, so memory is `O(nnz)`.
 *
 * Right-looking Gaussian elimination over per-row hash maps; the factors are frozen into sparse arrays
 * in both orientations (indexed by **pivot position**) so [ftran] (`B x = b`) and [btran] (`Bᵀ x = b`)
 * are `O(nnz)` triangular solves. [ftran]'s result is scattered back to original-column order by `Q`;
 * [btran]'s right-hand side is gathered by `Q`. Returns null from [factorize] on a (near-)singular basis.
 */
internal class SparseLu private constructor(
    private val m: Int,
    private val perm: IntArray, // perm[k] = original row index now at pivot position k
    private val colPerm: IntArray, // colPerm[k] = original column index now at pivot position k
    private val lRowIdx: Array<IntArray>, // L by row (pivot positions < k; unit diagonal implicit)
    private val lRowVal: Array<DoubleArray>,
    private val uRowIdx: Array<IntArray>, // U by row (pivot positions ≥ k); first entry of row k is the diagonal
    private val uRowVal: Array<DoubleArray>,
    private val lColIdx: Array<IntArray>, // L by column (pivot positions > k)
    private val lColVal: Array<DoubleArray>,
    private val uColIdx: Array<IntArray>, // U by column (pivot positions < k, strictly upper)
    private val uColVal: Array<DoubleArray>,
    private val uDiag: DoubleArray,
    /** Per-original-row equilibration factor `eᵢ` applied before factorization (`L·U = E·B`); all 1.0
     *  when equilibration is off. [ftran]/[btran]/[determinant] correct for it transparently, so a solve
     *  returns the same mathematical result as factorizing the unscaled `B` — only more stably. */
    private val rowScale: DoubleArray,
    /** Total nonzeros in `L` + `U` (incl. diagonal) — the factorization's fill (#27 sparsity audit). */
    val nnz: Int,
) {

    /** Solve `B x = b` (FTRAN). `b` is indexed by original row; the result by original column. */
    fun ftran(b: DoubleArray): DoubleArray {
        // B x = b ⟺ (E B) x = E b, so feeding the scaled rhs into the L·U = E·B factors yields the same x.
        // L y = P (E b) (forward); rows/cols are in pivot-position space.
        val y = DoubleArray(m)
        for (k in 0 until m) {
            var s = b[perm[k]] * rowScale[perm[k]]
            val idx = lRowIdx[k]
            val v = lRowVal[k]
            for (t in idx.indices) s -= v[t] * y[idx[t]]
            y[k] = s
        }
        // U x' = y (back); x' is in pivot-column space.
        val xp = DoubleArray(m)
        for (k in m - 1 downTo 0) {
            var s = y[k]
            val idx = uRowIdx[k]
            val v = uRowVal[k]
            for (t in 1 until idx.size) s -= v[t] * xp[idx[t]] // skip [0] = diagonal
            xp[k] = s / uDiag[k]
        }
        // x = Q x'  ⇒  x[colPerm[k]] = x'[k].
        val x = DoubleArray(m)
        for (k in 0 until m) x[colPerm[k]] = xp[k]
        return x
    }

    /** Solve `Bᵀ x = b` (BTRAN). `b` is indexed by original column; the result by original row. */
    fun btran(b: DoubleArray): DoubleArray {
        // Uᵀ z = Qᵀ b (forward, lower): z[k] = (b[colPerm[k]] − Σ_{j<k} U[j][k] z[j]) / U[k][k].
        val z = DoubleArray(m)
        for (k in 0 until m) {
            var s = b[colPerm[k]]
            val idx = uColIdx[k]
            val v = uColVal[k]
            for (t in idx.indices) s -= v[t] * z[idx[t]]
            z[k] = s / uDiag[k]
        }
        // Lᵀ w = z (back, upper, unit diagonal): w[k] = z[k] − Σ_{j>k} L[j][k] w[j].
        val w = DoubleArray(m)
        for (k in m - 1 downTo 0) {
            var s = z[k]
            val idx = lColIdx[k]
            val v = lColVal[k]
            for (t in idx.indices) s -= v[t] * w[idx[t]]
            w[k] = s
        }
        // P x' = w gives x' = innerBtran(b); the true solution of Bᵀx = b is x = E·x' (since
        // Bᵀ = B_sᵀ E⁻¹), so scale the result by the row factors.  x[perm[k]] = w[k]·e_{perm[k]}.
        val x = DoubleArray(m)
        for (k in 0 until m) x[perm[k]] = w[k] * rowScale[perm[k]]
        return x
    }

    /**
     * `det(B)` in floating point: `sign(P)·sign(Q)·∏ uDiag / ∏ eᵢ` — the factors are of `E·B`, so the
     * row-equilibration product is divided back out (a no-op when equilibration is off). For an
     * integer basis the true determinant is an integer; this float value is only a *guess* of it — the
     * exact dual solve (#34) scales by `round(det)` and verifies the result exactly, so any float error
     * here merely costs a sound failed verification, never a wrong answer.
     */
    fun determinant(): Double {
        var d = permutationSign(perm) * permutationSign(colPerm)
        for (k in 0 until m) d *= uDiag[k]
        for (i in 0 until m) d /= rowScale[i] // undo the row equilibration (rowScale all 1.0 when off)
        return d
    }

    /** Sign of the permutation [p] (`p[k]` = original index at position `k`): `(-1)^(m − cycles)`. */
    private fun permutationSign(p: IntArray): Double {
        val seen = BooleanArray(p.size)
        var cycles = 0
        for (s in p.indices) {
            if (seen[s]) continue
            cycles++
            var i = s
            while (!seen[i]) {
                seen[i] = true
                i = p[i]
            }
        }
        return if ((p.size - cycles) % 2 == 0) 1.0 else -1.0
    }

    companion object {
        private const val TOL = 1e-9

        /** Markowitz stability threshold: a pivot must be at least this fraction of its column's
         *  largest magnitude, so fill-reducing choices never sacrifice numerical stability. */
        private const val PIVOT_THRESHOLD = 0.1

        /**
         * Factorize the matrix whose rows are [rows] (`rows[i][col] = B[i][col]`, dense per-row maps),
         * size [m]. Returns null if no numerically acceptable pivot remains (singular basis).
         *
         * With [equilibrate], each row is first scaled by a power of two `eᵢ ≈ 1/max|rowᵢ|` so the
         * largest magnitude in every row lands in `[1, 2)` — better-conditioned pivoting, and exact in
         * floating point (a power-of-two scale only shifts the exponent, introducing no rounding). The
         * scale is recorded and undone transparently in [ftran]/[btran]/[determinant], so the solve
         * returns the same result as factorizing the unscaled matrix.
         */
        @Suppress("NestedBlockDepth", "CyclomaticComplexMethod")
        fun factorize(rows: Array<HashMap<Int, Double>>, m: Int, equilibrate: Boolean = false): SparseLu? {
            val rowScale = DoubleArray(m) { 1.0 }
            if (equilibrate) {
                for (i in 0 until m) {
                    var maxAbs = 0.0
                    for ((_, value) in rows[i]) {
                        val a = abs(value)
                        if (a > maxAbs) maxAbs = a
                    }
                    if (maxAbs <= 0.0) continue
                    val e = 2.0.pow(-floor(log2(maxAbs)).toInt())
                    if (e == 1.0) continue
                    rowScale[i] = e
                    for ((c, value) in HashMap(rows[i])) rows[i][c] = value * e
                }
            }
            val u = rows // eliminated in place; u[perm[k]] ends as U's pivot row k (pivot cols ≥ k)
            // L multipliers recorded per elimination step, keyed by the eliminated original row.
            val lAtStep = Array(m) { HashMap<Int, Double>() }
            val perm = IntArray(m) { -1 }
            val colPerm = IntArray(m) { -1 }
            val rowActive = BooleanArray(m) { true }
            val colActive = BooleanArray(m) { true }
            val colCount = IntArray(m)
            val rowCount = IntArray(m)
            val colMax = DoubleArray(m)
            for (k in 0 until m) {
                // Active-submatrix degree counts + per-column max magnitude (for the pivot threshold).
                for (c in 0 until m) {
                    colCount[c] = 0
                    colMax[c] = 0.0
                }
                for (i in 0 until m) {
                    if (!rowActive[i]) continue
                    var rc = 0
                    for ((c, value) in u[i]) {
                        if (!colActive[c]) continue
                        rc++
                        colCount[c]++
                        val a = abs(value)
                        if (a > colMax[c]) colMax[c] = a
                    }
                    rowCount[i] = rc
                }
                // Pivot: minimum Markowitz count among entries passing the stability threshold.
                var pRow = -1
                var pCol = -1
                var bestMark = Long.MAX_VALUE
                var bestAbs = 0.0
                for (i in 0 until m) {
                    if (!rowActive[i]) continue
                    for ((c, value) in u[i]) {
                        if (!colActive[c]) continue
                        val a = abs(value)
                        if (a < TOL || a < PIVOT_THRESHOLD * colMax[c]) continue
                        val mark = (rowCount[i] - 1).toLong() * (colCount[c] - 1).toLong()
                        if (mark < bestMark || (mark == bestMark && a > bestAbs)) {
                            bestMark = mark
                            bestAbs = a
                            pRow = i
                            pCol = c
                        }
                    }
                }
                if (pRow == -1) return null // singular
                perm[k] = pRow
                colPerm[k] = pCol
                rowActive[pRow] = false
                colActive[pCol] = false
                val pivot = u[pRow].getValue(pCol)
                // Eliminate the pivot column from the remaining active rows.
                for (i in 0 until m) {
                    if (!rowActive[i]) continue
                    val aic = u[i][pCol] ?: continue
                    val f = aic / pivot
                    lAtStep[k][i] = f
                    u[i].remove(pCol)
                    for ((col, value) in u[pRow]) {
                        if (!colActive[col]) continue // skips the pivot column and already-pivoted columns
                        val nv = (u[i][col] ?: 0.0) - f * value
                        if (abs(nv) < TOL) u[i].remove(col) else u[i][col] = nv
                    }
                }
            }
            return freeze(u, lAtStep, perm, colPerm, m, rowScale)
        }

        @Suppress("LongMethod", "LongParameterList")
        private fun freeze(
            u: Array<HashMap<Int, Double>>,
            lAtStep: Array<HashMap<Int, Double>>,
            perm: IntArray,
            colPerm: IntArray,
            m: Int,
            rowScale: DoubleArray,
        ): SparseLu {
            val invPerm = IntArray(m).also { for (k in 0 until m) it[perm[k]] = k }
            val invColPerm = IntArray(m).also { for (k in 0 until m) it[colPerm[k]] = k }
            val uDiag = DoubleArray(m) { k -> u[perm[k]].getValue(colPerm[k]) }
            // U row k (pivot space): the pivot row's entries, original col → pivot position (all ≥ k),
            // diagonal (k) first then ascending.
            val uRowIdx = Array(m) { k ->
                val keys = u[perm[k]].keys.map { invColPerm[it] }.sorted()
                keys.toIntArray()
            }
            val uRowVal = Array(m) { k ->
                val row = u[perm[k]]
                DoubleArray(uRowIdx[k].size) { t -> row.getValue(colPerm[uRowIdx[k][t]]) }
            }
            // L row k' (pivot space): multipliers from each step j < k' that eliminated row perm[k'].
            val lRowMap = Array(m) { HashMap<Int, Double>() }
            for (j in 0 until m) {
                for ((origRow, f) in lAtStep[j]) lRowMap[invPerm[origRow]][j] = f
            }
            val lRowIdx = Array(m) { k -> lRowMap[k].keys.sorted().toIntArray() }
            val lRowVal = Array(m) { k -> DoubleArray(lRowIdx[k].size) { t -> lRowMap[k].getValue(lRowIdx[k][t]) } }
            // Column orientations (pivot space): U strictly-upper by column, L by column.
            val uColB = Array(m) { ArrayList<Int>() }
            val uColBv = Array(m) { ArrayList<Double>() }
            for (k in 0 until m) {
                val idx = uRowIdx[k]
                val v = uRowVal[k]
                for (t in idx.indices) {
                    val col = idx[t]
                    if (col > k) {
                        uColB[col].add(k)
                        uColBv[col].add(v[t])
                    }
                }
            }
            val lColB = Array(m) { ArrayList<Int>() }
            val lColBv = Array(m) { ArrayList<Double>() }
            for (k in 0 until m) {
                val idx = lRowIdx[k]
                val v = lRowVal[k]
                for (t in idx.indices) {
                    lColB[idx[t]].add(k)
                    lColBv[idx[t]].add(v[t])
                }
            }
            var nnz = 0
            for (k in 0 until m) nnz += uRowIdx[k].size + lRowIdx[k].size
            return SparseLu(
                m, perm, colPerm, lRowIdx, lRowVal, uRowIdx, uRowVal,
                Array(m) { lColB[it].toIntArray() }, Array(m) { lColBv[it].toDoubleArray() },
                Array(m) { uColB[it].toIntArray() }, Array(m) { uColBv[it].toDoubleArray() },
                uDiag, rowScale, nnz,
            )
        }
    }
}
