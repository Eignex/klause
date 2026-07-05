package com.eignex.klause.lp

import com.eignex.klause.util.IntArrayList

/**
 * Maintains a basis factorization across dual-simplex pivots using the **product-form of the inverse**
 * (PFI): a base sparse LU [SparseLu] of the basis `B₀` at the last refactorization, plus a chain of
 * elementary "eta" transforms — one appended per pivot. [ftran] (`B x = b`) and [btran] (`Bᵀ x = b`)
 * apply the base LU solve and then walk the eta chain, so a pivot costs one `O(m)` [update] instead of
 * a full `O(nnz)` refactorization. This is the speed step the per-iteration refactorize left on the
 * table; correctness is unchanged because the float basis is only ever exactly certified downstream.
 *
 * Each eta records the pivot position `p` (a basis slot) and the spike `η = B⁻¹ A_q` — the entering
 * column transformed by the factorization in effect *just before* the pivot, i.e. the FTRAN result the
 * caller already computed for the ratio test. After basis slot `p` is replaced by column `q`, the new
 * basis is `B·E` where `E` is the identity with column `p` set to `η`; its inverse `E⁻¹` differs from
 * the identity only in column `p`, so applying it (and its transpose) is `O(m)` per eta.
 *
 * The chain lengthens fill and accumulates rounding error, so the caller refactorizes once [etaCount]
 * reaches its limit (rebuilding `B₀` from the current basis and dropping the chain). Spikes are stored
 * densely (length `m`); all index spaces match [SparseLu]'s (basis-slot in, original-row out).
 */
internal class EtaBasis private constructor(private val m: Int, private val base: SparseLu) {
    private val etaRow = IntArrayList()
    private val etaSpike = ArrayList<DoubleArray>()

    /** Number of pivots folded into the chain since the base factorization. */
    val etaCount: Int get() = etaRow.size

    /** Solve `B x = b` (FTRAN): base LU solve, then forward through the eta chain in pivot order. */
    fun ftran(b: DoubleArray): DoubleArray {
        val x = base.ftran(b)
        for (j in etaSpike.indices) {
            val p = etaRow[j]
            val eta = etaSpike[j]
            val xp = x[p] / eta[p]
            for (i in 0 until m) if (i != p) x[i] -= eta[i] * xp
            x[p] = xp
        }
        return x
    }

    /** Solve `Bᵀ x = b` (BTRAN): the eta chain transposed in reverse pivot order, then the base LU. */
    fun btran(b: DoubleArray): DoubleArray {
        val z = b.copyOf()
        for (j in etaSpike.indices.reversed()) {
            val p = etaRow[j]
            val eta = etaSpike[j]
            var s = z[p]
            for (i in 0 until m) if (i != p) s -= eta[i] * z[i]
            z[p] = s / eta[p]
        }
        return base.btran(z)
    }

    /** Append the eta for a pivot replacing basis slot [pivotRow]; [spike] must be this object's
     *  [ftran] of the entering column, computed *before* this call (pivot magnitude already checked). */
    fun update(pivotRow: Int, spike: DoubleArray) {
        etaRow.add(pivotRow)
        etaSpike.add(spike.copyOf())
    }

    companion object {
        /** Wrap an already-factorized basis [lu] (`m × m`) as a fresh, empty eta chain. */
        fun of(lu: SparseLu, m: Int): EtaBasis = EtaBasis(m, lu)
    }
}
