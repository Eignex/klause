package com.eignex.klause.lp.engine

import com.eignex.klause.util.IntArrayList
import com.eignex.koblas.Workspace
import com.eignex.koblas.koblas
import com.eignex.koblas.sparse.SparseFactorization

/**
 * Maintains a basis factorization across rank-1 basis updates using the **product-form of the inverse**
 * (PFI): a base sparse LU factorization of the basis `B₀` at the last refactorization, plus a chain of
 * elementary "eta" transforms — one appended per update. [solve] applies the base LU solve and then walks
 * the eta chain in either direction, so an update costs one `O(m)` [update] instead of a full `O(nnz)`
 * refactorization.
 *
 * Each eta records the pivot position `p` (a basis slot) and the spike `η = B⁻¹ A_q` — the entering
 * column transformed by the factorization in effect *just before* the update, i.e. the FTRAN result the
 * caller already computed. After basis slot `p` is replaced by column `q`, the new basis is `B·E` where
 * `E` is the identity with column `p` set to `η`; its inverse differs from the identity only in column
 * `p`, so applying it (and its transpose) is `O(m)` per eta.
 *
 * The chain lengthens fill and accumulates rounding error, so the caller refactorizes once [etaCount]
 * reaches its limit (rebuilding `B₀` and dropping the chain). Spikes are stored densely (length `m`);
 * index spaces match the base factorization's (basis-slot in, original-row out).
 */
internal class EtaBasis private constructor(
    private val base: SparseFactorization,
    private val baseOps: Int,
    private val work: LpWork,
) {
    private val m: Int = base.n

    private val etaRow = IntArrayList()
    private val etaSpike = ArrayList<DoubleArray>()

    // This object is already mutable — update() appends to the chain — so it owns its solve scratch
    // rather than asking callers for a workspace. One basis is therefore driven by one thread, which is
    // how a simplex uses it; the underlying factorization stays free of mutable state and shareable.
    private val scratch = Workspace().apply { reserve(m, count = 3) }

    /** Number of updates folded into the chain since the base factorization. */
    val etaCount: Int get() = etaRow.size

    /**
     * Solve `B x = b`, or `Bᵀ x = b` when [transpose], into a fresh result.
     *
     * Spelled `solve` rather than `ftran`/`btran` so the sparse and dense halves share one vocabulary:
     * this is what `Lapack.solve` and `SparseFactorization.solve` do, with the same transpose flag. The
     * FTRAN and BTRAN names survive on the private halves below, where they name which sweep runs rather
     * than standing in for "solve".
     */
    fun solve(b: DoubleArray, transpose: Boolean = false): DoubleArray = solveInto(b, DoubleArray(m), transpose)

    /**
     * Solve into [out], which is returned, reusing this basis's own scratch: a simplex iteration allocates
     * nothing and needs no workspace of its own. [out] may be [b].
     */
    fun solveInto(b: DoubleArray, out: DoubleArray, transpose: Boolean = false): DoubleArray =
        if (transpose) btranInto(b, out) else ftranInto(b, out)

    /** The forward sweep: base LU solve, then forward through the eta chain in update order. Each eta
     *  applies over the two contiguous runs around the pivot via `VectorKernels.axpy`. */
    private fun ftranInto(b: DoubleArray, out: DoubleArray): DoubleArray {
        chargeSolve()
        val x = base.solveInto(b, out, transpose = false, workspace = scratch)
        for (j in etaSpike.indices) {
            val p = etaRow[j]
            val eta = etaSpike[j]
            val xp = x[p] / eta[p]
            if (xp != 0.0) {
                koblas.kernels.axpy(x, 0, -xp, eta, 0, p)
                koblas.kernels.axpy(x, p + 1, -xp, eta, p + 1, m - p - 1)
            }
            x[p] = xp
        }
        return x
    }

    /** The transposed sweep: the eta chain transposed in reverse update order, then the base LU. Each eta
     *  gathers over the two contiguous runs around the pivot via `VectorKernels.dot`. */
    private fun btranInto(b: DoubleArray, out: DoubleArray): DoubleArray {
        require(b.size == m) { "solve: b size ${b.size} != $m" }
        chargeSolve()
        // The eta chain transposed, applied to a working copy, then the base solve into out.
        val z = scratch.take(m)
        b.copyInto(z)
        for (j in etaSpike.indices.reversed()) {
            val p = etaRow[j]
            val eta = etaSpike[j]
            val s = z[p] - koblas.kernels.dot(
                eta,
                0,
                z,
                0,
                p,
            ) - koblas.kernels.dot(eta, p + 1, z, p + 1, m - p - 1)
            z[p] = s / eta[p]
        }
        val result = base.solveInto(z, out, transpose = true, workspace = scratch)
        scratch.release(z)
        return result
    }

    /** Append the eta for an update replacing basis slot [pivotRow]; [spike] must be this object's
     *  forward [solve] of the entering column, computed *before* this call (pivot magnitude already
     *  checked). */
    fun update(pivotRow: Int, spike: DoubleArray) {
        work.add(m)
        etaRow.add(pivotRow)
        etaSpike.add(spike.copyOf())
    }

    /** Charge one sweep: the base factorization's entries, then `m` per eta in the chain. */
    private fun chargeSolve() {
        work.add(baseOps.toLong() + etaCount.toLong() * m)
    }

    /** Factory for an [EtaBasis]. */
    companion object {
        /**
         * Wrap an already-factorized basis as a fresh, empty eta chain.
         *
         * Takes any [SparseFactorization], not just a sparse LU: the chain only ever asks its base to
         * solve, so a host solver's factors work here too. The dimension comes from the factorization
         * rather than being passed alongside it, which removes a way for the two to disagree.
         */
        fun of(base: SparseFactorization, baseOps: Int, work: LpWork): EtaBasis = EtaBasis(base, baseOps, work)
    }
}
