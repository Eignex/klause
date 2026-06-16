package com.eignex.klause.solver.lp

/**
 * A linear inequality `Σ coeffs[k]·x_{cols[k]} rel rhs` over LP columns, appended to a relaxation as
 * an extra row; valid — satisfied by every integer-feasible point — so it never removes a real solution.
 * [global] flows into [LpModel.rowGlobal]; defaults to `false` (the sound direction).
 */
internal class Cut(
    val cols: IntArray,
    val coeffs: LongArray,
    val rel: Relation,
    val rhs: Long,
    val global: Boolean = false,
) {
    /** A stable key for deduplicating cuts (ignores column order). */
    fun key(): String {
        val terms = cols.indices.sortedBy { cols[it] }.joinToString(",") { "${cols[it]}:${coeffs[it]}" }
        return "$terms|$rel|$rhs"
    }
}
