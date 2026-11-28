package com.eignex.klause.lowering

import com.eignex.util.numbers.addExact
import com.eignex.util.numbers.mulExact
import com.eignex.util.numbers.subExact

// The term the SMT-LIB and XCSP3 parsers fold expressions into before lowering a relation.
internal data class LinComb(val coeffs: Map<Int, Long>, val constant: Long) {
    fun plus(other: LinComb): LinComb {
        val m = HashMap(coeffs)
        for ((v, c) in other.coeffs) m[v] = addExact(m[v] ?: 0L, c)
        return LinComb(m, addExact(constant, other.constant))
    }

    fun scaled(k: Long): LinComb = LinComb(coeffs.mapValues { mulExact(it.value, k) }, mulExact(constant, k))

    fun asSimpleVar(): Int? =
        if (constant == 0L && coeffs.size == 1 && coeffs.values.first() == 1L) coeffs.keys.first() else null
}

// `a op b` as `Σ (a − b)·vars op b.constant − a.constant + delta`; `delta` folds a strict inequality.
internal fun linCombDiff(a: LinComb, b: LinComb, delta: Long = 0L): Triple<IntArray, LongArray, Long> {
    val combined = HashMap(a.coeffs)
    for ((v, c) in b.coeffs) combined[v] = subExact(combined[v] ?: 0L, c)
    combined.entries.removeAll { it.value == 0L }
    val bound = addExact(subExact(b.constant, a.constant), delta)
    val vars = combined.keys.toIntArray()
    return Triple(vars, LongArray(vars.size) { combined.getValue(vars[it]) }, bound)
}
