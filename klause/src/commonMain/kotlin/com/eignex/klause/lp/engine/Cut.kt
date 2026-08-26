package com.eignex.klause.lp.engine

/**
 * A linear inequality `Σ coeffs[k]·x_{cols[k]} rel rhs` over LP columns, added to the relaxation to
 * cut off a fractional LP point. Columns index the relaxation's structural columns; the cut
 * must be valid — satisfied by every integer-feasible point — so it never removes a real solution.
 *
 * [global] says the cut is satisfied by every integer **solution of the problem**, not merely by
 * the points inside the separating node's box: a cut whose derivation read only factor structure
 * (knapsack cover, clique, circuit cutset) or unbranched root domains is global; one derived from
 * live tightened domains (Hall/GCC/assignment sums deeper in the tree, Gomory/MIR tableau cuts) is
 * not. The flag flows into [LpModel.rowGlobal], which gates whether LP certificates over the
 * cut-augmented model may be learned. Defaults to `false` — the sound direction.
 */
internal class Cut(
    val cols: IntArray,
    val coeffs: LongArray,
    val rel: Relation,
    val rhs: Long,
    val global: Boolean = false,
) {
    /** A stable key for deduplicating cuts across separation rounds (ignores column order). */
    fun key(): String {
        val terms = cols.indices.sortedBy { cols[it] }.joinToString(",") { "${cols[it]}:${coeffs[it]}" }
        return "$terms|$rel|$rhs"
    }
}
