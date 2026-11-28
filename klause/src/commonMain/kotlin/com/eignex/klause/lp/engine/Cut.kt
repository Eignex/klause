package com.eignex.klause.lp.engine

// A valid inequality over structural LP columns: it never removes an integer-feasible point. [global] cuts hold for
// every solution of the problem, not only inside the separating node's box; cuts derived from tightened domains or
// the tableau are local. The flag feeds [LpModel.rowGlobal], which gates learning certificates over the cut model,
// so `false` is the sound default.
internal class Cut(
    cols: IntArray,
    coeffs: LongArray,
    val rel: Relation,
    val rhs: Long,
    global: Boolean = false,
    val provenance: CutProvenance? = null,
    val tableau: TableauCutProvenance? = null,
    val family: CutFamily = provenance?.family ?: CutFamily.UNSPECIFIED,
    val slack: LpRowSlack? = null,
) {
    val cols: IntArray = cols.copyOf()
    val coeffs: LongArray = coeffs.copyOf()
    val global: Boolean = global && tableau == null && (provenance?.global != false)

    init {
        require(cols.size == coeffs.size)
        require(provenance == null || tableau == null)
    }

    // Ignores column order.
    fun key(): String {
        val terms = cols.indices.sortedBy { cols[it] }.joinToString(",") { "${cols[it]}:${coeffs[it]}" }
        return "$terms|$rel|$rhs"
    }

    fun withFamily(value: CutFamily): Cut =
        Cut(cols, coeffs, rel, rhs, global, provenance?.withFamily(value), tableau, value, slack)
}
