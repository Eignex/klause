package com.eignex.klause.solver.lp

import kotlin.math.abs

/**
 * Sparse LU factorization `P·B = L·U` of an `m × m` basis, with partial (threshold) pivoting for
 * float stability. Replaces the explicit dense `B⁻¹` in [RevisedSimplex]: only the nonzeros of `L`
 * and `U` are stored, so memory is `O(nnz)` rather than `O(m²)` — the win on large sparse models.
 *
 * Right-looking Gaussian elimination over per-row hash maps (row swaps are O(1) pointer swaps); the
 * factors are then frozen into sparse arrays in both orientations so [ftran] (`B x = b`) and [btran]
 * (`Bᵀ x = b`) are `O(nnz)` triangular solves. Columns are not permuted, so the solution of [ftran]
 * is already in the original column order. Returns null from [factorize] on a (near-)singular basis.
 */
internal class SparseLu private constructor(
    private val m: Int,
    private val perm: IntArray, // perm[k] = original row index now at pivot position k
    private val lRowIdx: Array<IntArray>, // L by row (strictly lower, unit diagonal implicit)
    private val lRowVal: Array<DoubleArray>,
    private val uRowIdx: Array<IntArray>, // U by row (col ≥ k); first entry of row k is the diagonal
    private val uRowVal: Array<DoubleArray>,
    private val lColIdx: Array<IntArray>, // L by column (rows > k)
    private val lColVal: Array<DoubleArray>,
    private val uColIdx: Array<IntArray>, // U by column (rows < k, strictly upper)
    private val uColVal: Array<DoubleArray>,
    private val uDiag: DoubleArray,
) {

    /** Solve `B x = b` (FTRAN). `b` is indexed by original row; the result by original column. */
    fun ftran(b: DoubleArray): DoubleArray {
        val y = DoubleArray(m)
        for (k in 0 until m) {
            var s = b[perm[k]]
            val idx = lRowIdx[k]
            val v = lRowVal[k]
            for (t in idx.indices) s -= v[t] * y[idx[t]]
            y[k] = s
        }
        val x = DoubleArray(m)
        for (k in m - 1 downTo 0) {
            var s = y[k]
            val idx = uRowIdx[k]
            val v = uRowVal[k]
            for (t in 1 until idx.size) s -= v[t] * x[idx[t]] // skip [0] = diagonal
            x[k] = s / uDiag[k]
        }
        return x
    }

    /** Solve `Bᵀ x = b` (BTRAN). `b` is indexed by original column; the result by original row. */
    fun btran(b: DoubleArray): DoubleArray {
        // Uᵀ z = b (forward, lower): z[k] = (b[k] − Σ_{j<k} U[j][k] z[j]) / U[k][k].
        val z = DoubleArray(m)
        for (k in 0 until m) {
            var s = b[k]
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
        // P x = w  ⇒  x[perm[k]] = w[k].
        val x = DoubleArray(m)
        for (k in 0 until m) x[perm[k]] = w[k]
        return x
    }

    companion object {
        private const val TOL = 1e-9

        /**
         * Factorize the matrix whose rows are [rows] (`rows[i][col] = B[i][col]`, dense per-row maps),
         * size [m]. Returns null if a pivot column is numerically empty (singular basis).
         */
        fun factorize(rows: Array<HashMap<Int, Double>>, m: Int): SparseLu? {
            val u = rows // eliminated in place; row k ends as U's row k (cols ≥ k)
            val lRow = Array(m) { HashMap<Int, Double>() }
            val perm = IntArray(m) { it }
            for (k in 0 until m) {
                // Partial pivot: among positions ≥ k pick the largest |entry in column k|.
                var pPos = -1
                var best = TOL
                for (i in k until m) {
                    val a = u[i][k]
                    if (a != null && abs(a) > best) {
                        best = abs(a)
                        pPos = i
                    }
                }
                if (pPos == -1) return null // singular
                if (pPos != k) {
                    val tr = u[k]
                    u[k] = u[pPos]
                    u[pPos] = tr
                    val tl = lRow[k]
                    lRow[k] = lRow[pPos]
                    lRow[pPos] = tl
                    val tp = perm[k]
                    perm[k] = perm[pPos]
                    perm[pPos] = tp
                }
                val pivot = u[k].getValue(k)
                for (i in k + 1 until m) {
                    val aik = u[i][k] ?: continue
                    val f = aik / pivot
                    lRow[i][k] = f
                    u[i].remove(k)
                    for ((col, value) in u[k]) {
                        if (col == k) continue
                        val nv = (u[i][col] ?: 0.0) - f * value
                        if (abs(nv) < TOL) u[i].remove(col) else u[i][col] = nv
                    }
                }
            }
            return freeze(u, lRow, perm, m)
        }

        private fun freeze(
            u: Array<HashMap<Int, Double>>,
            lRow: Array<HashMap<Int, Double>>,
            perm: IntArray,
            m: Int,
        ): SparseLu {
            val uDiag = DoubleArray(m) { u[it].getValue(it) }
            // U rows: diagonal first, then cols > k (ascending). L rows: cols < k (ascending).
            val uRowIdx = Array(m) { k -> (u[k].keys.filter { it > k }.sorted()).toIntArray() }
            val uRowIdxD = Array(
                m,
            ) { k ->
                IntArray(uRowIdx[k].size + 1).also {
                    it[0] = k
                    uRowIdx[k].copyInto(it, 1)
                }
            }
            val uRowVal = Array(m) { k -> DoubleArray(uRowIdxD[k].size) { t -> u[k].getValue(uRowIdxD[k][t]) } }
            val lRowIdx = Array(m) { k -> lRow[k].keys.sorted().toIntArray() }
            val lRowVal = Array(m) { k -> DoubleArray(lRowIdx[k].size) { t -> lRow[k].getValue(lRowIdx[k][t]) } }
            // Column orientations: U strictly-upper by column, L by column.
            val uColB = Array(m) { ArrayList<Int>() }
            val uColBv = Array(m) { ArrayList<Double>() }
            for (k in 0 until m) {
                for ((col, value) in u[k]) {
                    if (col > k) {
                        uColB[col].add(k)
                        uColBv[col].add(value)
                    }
                }
            }
            val lColB = Array(m) { ArrayList<Int>() }
            val lColBv = Array(m) { ArrayList<Double>() }
            for (i in 0 until m) {
                for ((col, value) in lRow[i]) {
                    lColB[col].add(i)
                    lColBv[col].add(value)
                }
            }
            return SparseLu(
                m, perm, lRowIdx, lRowVal, uRowIdxD, uRowVal,
                Array(m) { lColB[it].toIntArray() }, Array(m) { lColBv[it].toDoubleArray() },
                Array(m) { uColB[it].toIntArray() }, Array(m) { uColBv[it].toDoubleArray() },
                uDiag,
            )
        }
    }
}
