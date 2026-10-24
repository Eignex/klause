package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.LongHashSet
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.toLongExact

internal class DivModSupports(relation: DivMod, state: PropagationState) {
    private val dividend = relation.dividend
    private val divisor = relation.divisor
    private val sourceDividend = state.intDomains[dividend]
    private val sourceDivisor = divisor?.let { state.intDomains[it] }
    private val roles = relation.intVars
    private val variables = roles.distinct().toIntArray()
    private val columns = IntArray(roles.size) { variables.indexOf(roles[it]) }
    private val supports = Array(variables.size) { LongHashSet() }
    private val minima = LongArray(variables.size)
    private val maxima = LongArray(variables.size)
    private val tuples: LongArray
    val storageCells: Int = 64 + sourceDividend.span().size * (sourceDivisor?.span()?.size ?: 1) * roles.size

    init {
        val dividends = sourceDividend.span()
        val divisors = sourceDivisor?.span()
        val constant = relation.constantDivisor
        val constantWord = constant?.takeIf { it.fitsLong() }?.toLongExact()
        val rows = LongArrayList()
        for (i in 0 until dividends.size) {
            val av = dividends.valueAt(i)
            for (j in 0 until (divisors?.size ?: 1)) {
                val bv = divisors?.valueAt(j)
                val wordDivisor = bv ?: constantWord
                val outputs = if (wordDivisor != null) relation.wordOutputs(av, wordDivisor) else {
                    val exact = relation.outputs(bigIntOf(av), checkNotNull(constant)) ?: continue
                    if (!exact.first.fitsLong() || !exact.second.fitsLong()) continue
                    exact.first.toLongExact() to exact.second.toLongExact()
                } ?: continue
                val values = if (divisor == null) longArrayOf(av, outputs.first, outputs.second)
                    else longArrayOf(av, checkNotNull(bv), outputs.first, outputs.second)
                if (roles.indices.any { role ->
                        (0 until role).any { previous ->
                            roles[previous] == roles[role] && values[previous] != values[role]
                        }
                    }) continue
                for (value in values) rows.add(value)
            }
        }
        tuples = rows.toLongArray()
    }

    // Output domains never constrain construction: their restored values must recover all supports.
    // Operand coverage is rechecked because this advisory payload survives undo without a snapshot.
    fun covers(state: PropagationState): Boolean =
        covers(sourceDividend, state.intDomains[dividend]) &&
            (divisor == null || covers(checkNotNull(sourceDivisor), state.intDomains[divisor]))

    private fun covers(source: IntDomain, current: IntDomain): Boolean {
        if (source === current) return true
        if (current.min < source.min || current.max > source.max) return false
        if (source.holeCount == 0L) return true
        val span = current.span()
        for (i in 0 until span.size) if (span.valueAt(i) !in source) return false
        return true
    }

    fun filter(state: PropagationState, reason: IntArray?, limit: Int): Boolean {
        for (values in supports) values.clear()
        minima.fill(Long.MAX_VALUE)
        maxima.fill(Long.MIN_VALUE)
        var row = 0
        while (row < tuples.size) {
            var live = true
            for (role in roles.indices) {
                if (tuples[row + role] !in state.intDomains[roles[role]]) {
                    live = false
                    break
                }
            }
            if (live) {
                for (role in roles.indices) {
                    val value = tuples[row + role]
                    val column = columns[role]
                    supports[column].add(value)
                    if (value < minima[column]) minima[column] = value
                    if (value > maxima[column]) maxima[column] = value
                }
            }
            row += roles.size
        }
        for (column in variables.indices) {
            val values = supports[column]
            if (values.isEmpty()) return false
            val variable = variables[column]
            if (!state.tightenIntMin(variable, minima[column], reason) ||
                !state.tightenIntMax(variable, maxima[column], reason)) return false
            val domain = state.intDomains[variable].spanOrNull(limit.toLong()) ?: continue
            for (i in 0 until domain.size) {
                val value = domain.valueAt(i)
                if (value !in values && !state.excludeIntValue(variable, value, reason)) return false
            }
        }
        return true
    }
}
