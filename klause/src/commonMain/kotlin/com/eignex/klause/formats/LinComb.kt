package com.eignex.klause.formats

/**
 * An integer linear combination `Σ coeffs[v]·v + constant` over variable ids — the shared term the
 * SMT-LIB (QF_LIA) and XCSP3 parsers both fold expressions into before lowering a relation to a
 * [com.eignex.klause.solver.Factor]. Each parser keeps its own AST walk and its own operator/error
 * conventions; only this algebra ([plus], [scaled], [asSimpleVar], and [linCombDiff]) is shared.
 */
internal data class LinComb(val coeffs: Map<Int, Long>, val constant: Long) {
    /** Sum of two combinations (coefficients added per variable, constants added). */
    fun plus(other: LinComb): LinComb {
        val m = HashMap(coeffs)
        for ((v, c) in other.coeffs) m[v] = (m[v] ?: 0L) + c
        return LinComb(m, constant + other.constant)
    }

    /** This combination scaled by [k]. */
    fun scaled(k: Long): LinComb = LinComb(coeffs.mapValues { it.value * k }, constant * k)

    /** The single variable id when this is exactly `1·v` (no constant, one unit coefficient), else null. */
    fun asSimpleVar(): Int? =
        if (constant == 0L && coeffs.size == 1 && coeffs.values.first() == 1L) coeffs.keys.first() else null
}

/**
 * Combine `a op b` into `(a − b) op 0` as `(vars, coeffs, bound)` for a [com.eignex.klause.factor.arithmetic.Linear]
 * row: subtract `b`'s coefficients from `a`'s, drop the resulting zeros, and set `bound = b.constant −
 * a.constant + delta` where [delta] is the strict-inequality offset (`< / >` fold to `≤ / ≥` with ∓1).
 */
internal fun linCombDiff(a: LinComb, b: LinComb, delta: Long = 0L): Triple<IntArray, LongArray, Long> {
    val combined = HashMap(a.coeffs)
    for ((v, c) in b.coeffs) combined[v] = (combined[v] ?: 0L) - c
    combined.entries.removeAll { it.value == 0L }
    val bound = b.constant - a.constant + delta
    val vars = combined.keys.toIntArray()
    return Triple(vars, LongArray(vars.size) { combined.getValue(vars[it]) }, bound)
}
