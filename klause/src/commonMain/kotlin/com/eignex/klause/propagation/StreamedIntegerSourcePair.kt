package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.addExact
import com.eignex.klause.util.mulExact
import com.eignex.klause.util.subExact

internal class StreamedIntegerSourcePair(
    private val state: PropagationState,
    private val work: IntegerSourceWork,
    private val reserveCombination: () -> Boolean,
) {
    class Source(
        val row: IntegerConflictRow,
        val variable: Int,
        val trail: Int,
        val lower: Boolean,
        val nonOwner: Boolean = false,
    )

    private data class Key(
        val first: Int,
        val firstUseLo: Boolean,
        val second: Int,
        val secondUseLo: Boolean,
        val left: Long,
        val right: Long,
    )

    private class Original(val linear: LinearPropagator, val nonzero: Int) {
        val vars: IntArray get() = linear.vars
        val coeffs: LongArray get() = linear.coeffs
        val op: LinearOp get() = linear.op
        val bound: Long get() = linear.bound
    }

    private val originals = OriginalIntegerLinearSource(state, work)
    private val sources = mutableMapOf<Int, Original?>()
    private val attempted = mutableSetOf<Key>()
    private var exhausted = false

    private fun decline(cause: String): Nothing? {
        state.arithmeticLearningTrace?.record("integer.retained.original-pair.reject.$cause")
        return null
    }

    private fun original(factorId: Int): Original? {
        work.visit()
        if (factorId in sources) return sources[factorId]
        if (sources.size == INTEGER_LEARNING_STEPS) return decline("capacity")
        val source = originals.source(factorId)
        if (source == null) {
            sources[factorId] = null
            return null
        }
        var previous = -1
        var nonzero = 0
        for (index in source.vars.indices) {
            repeat(2) { work.visit() }
            val variable = source.vars[index]
            if (variable !in 0 until state.problem.numIntVars || variable <= previous) {
                sources[factorId] = null
                return decline("order-or-coordinate")
            }
            previous = variable
            if (source.coeffs[index] != 0L) nonzero++
        }
        return Original(source, nonzero).also { sources[factorId] = it }
    }

    private fun supports(source: Original, useLo: Boolean): Boolean = when (source.op) {
        LinearOp.LE -> useLo
        LinearOp.GE -> !useLo
        LinearOp.EQ -> true
        LinearOp.NE -> false
    }

    private fun signed(value: Long, useLo: Boolean): Long = if (useLo) value else subExact(0L, value)

    private fun position(source: Original, variable: Int): Int {
        var lo = 0
        var hi = source.vars.lastIndex
        while (lo <= hi) {
            work.visit()
            val middle = lo + (hi - lo) / 2
            val found = source.vars[middle]
            if (found == variable) return middle
            if (found < variable) lo = middle + 1 else hi = middle - 1
        }
        return -1
    }

    fun combine(
        firstId: Int,
        firstUseLo: Boolean,
        secondId: Int,
        secondUseLo: Boolean,
        variable: Int,
        nonOwner: Boolean = false,
    ): IntegerConflictRow? = try {
        combineChecked(firstId, firstUseLo, secondId, secondUseLo, variable, nonOwner)
    } catch (_: CheckedLongOverflowException) {
        decline("overflow")
    }

    private fun combineChecked(
        firstId: Int,
        firstUseLo: Boolean,
        secondId: Int,
        secondUseLo: Boolean,
        variable: Int,
        nonOwner: Boolean,
    ): IntegerConflictRow? {
        if (firstId == secondId || exhausted) return null
        val first = original(firstId) ?: return null
        val second = original(secondId) ?: return null
        if (!supports(first, firstUseLo) || !supports(second, secondUseLo)) return decline("side-or-pivot")
        if (first.vars.size <= INTEGER_SOURCE_TERMS && second.vars.size <= INTEGER_SOURCE_TERMS) return null
        if (kotlin.math.abs(first.nonzero - second.nonzero) > INTEGER_LEARNING_TERMS) {
            sources.remove(secondId)
            return decline("support-width")
        }
        val firstPosition = position(first, variable)
        val secondPosition = position(second, variable)
        if (firstPosition < 0 || secondPosition < 0) return decline("side-or-pivot")
        val a = signed(first.coeffs[firstPosition], firstUseLo)
        val b = signed(second.coeffs[secondPosition], secondUseLo)
        if (a == 0L || b == 0L || a == Long.MIN_VALUE || b == Long.MIN_VALUE || (a > 0L) == (b > 0L)) {
            return decline("side-or-pivot")
        }
        work.visit()
        val divisor = IntegerConflictRow.gcd(IntegerConflictRow.magnitude(a), IntegerConflictRow.magnitude(b))
        val left = IntegerConflictRow.magnitude(b) / divisor
        val right = IntegerConflictRow.magnitude(a) / divisor
        val key = Key(firstId, firstUseLo, secondId, secondUseLo, left, right)
        if (key in attempted) return null
        if (attempted.size == INTEGER_LEARNING_STEPS || !reserveCombination()) {
            exhausted = true
            return decline("attempt-limit")
        }
        attempted.add(key)
        state.arithmeticLearningTrace?.record("integer.retained.original-pair.trial")
        if (nonOwner) state.arithmeticLearningTrace?.record("integer.retained.non-owner-pair.trial")
        val terms = linkedMapOf<Int, Long>()
        var i = 0
        var j = 0
        while (i < first.vars.size || j < second.vars.size) {
            work.visit()
            val firstVariable = if (i < first.vars.size) first.vars[i] else Int.MAX_VALUE
            val secondVariable = if (j < second.vars.size) second.vars[j] else Int.MAX_VALUE
            val v = minOf(firstVariable, secondVariable)
            val firstCoefficient = if (firstVariable == v) signed(first.coeffs[i++], firstUseLo) else 0L
            val secondCoefficient = if (secondVariable == v) signed(second.coeffs[j++], secondUseLo) else 0L
            val coefficient = addExact(mulExact(firstCoefficient, left), mulExact(secondCoefficient, right))
            if (coefficient != 0L) {
                if (terms.size == INTEGER_LEARNING_TERMS) return decline("output-width")
                terms[v] = coefficient
            }
        }
        val bound = addExact(mulExact(signed(first.bound, firstUseLo), left),
            mulExact(signed(second.bound, secondUseLo), right))
        repeat(terms.size * 4 + 8) { work.visit() }
        return IntegerConflictRow.normalized(terms, bound, Lit.NONE)
    }

    fun source(factorId: Int, target: Int): Source? {
        if (state.incremental || !state.undoLogging) return decline("scope")
        val seed = original(factorId) ?: return null
        for (useLo in listOf(true, false)) {
            if (!supports(seed, useLo)) continue
            for (index in seed.vars.indices) {
                work.visit()
                if (exhausted) return null
                val variable = seed.vars[index]
                val coefficient = signed(seed.coeffs[index], useLo)
                if (coefficient == 0L) continue
                val lower = coefficient > 0L
                val level = if (lower) state.intMinLevel[variable] else state.intMaxLevel[variable]
                if (level <= target) continue
                val marker = if (lower) state.intMinAntecedents[variable] else state.intMaxAntecedents[variable]
                val owner = if (lower) state.intMinReason[variable] else state.intMaxReason[variable]
                if (!isLazyReason(marker) || marker == null || marker.size != LAZY_PAYLOAD + 6 ||
                    marker[1] != owner || marker[2] !in 0..state.undo.size || marker[LAZY_PAYLOAD + 5] != 0 ||
                    owner == factorId || owner !in 0 until state.baseFactorCount) continue
                if (seed.vars.size <= INTEGER_SOURCE_TERMS &&
                    ((state.factorAt(owner) as? LinearPropagator)?.vars?.size ?: 0) <= INTEGER_SOURCE_TERMS) continue
                val parent = original(owner) ?: continue
                if (parent.vars.getOrNull(marker[LAZY_PAYLOAD]) != variable) continue
                val side = marker[LAZY_PAYLOAD + 1]
                if (side !in 0..1) continue
                val row = combine(factorId, useLo, owner, side == 1, variable) ?: continue
                if (!conflicting(row)) {
                    decline("not-conflicting")
                    continue
                }
                state.arithmeticLearningTrace?.record("integer.retained.original-pair.selected")
                return Source(row, variable, marker[2], lower)
            }
        }
        return nonOwnerSource(factorId, seed, target)
    }

    private fun conflicting(row: IntegerConflictRow): Boolean {
        var minimum = 0L
        for (term in row.variables.indices) {
            work.visit()
            val c = row.coefficients[term]
            val domain = state.intDomains[row.variables[term]]
            minimum = addExact(minimum, mulExact(c, if (c > 0L) domain.min else domain.max))
        }
        return minimum > row.bound
    }

    private fun nonOwnerSource(factorId: Int, seed: Original, target: Int): Source? {
        state.arithmeticLearningTrace?.record("integer.retained.non-owner-pair.scan")
        for (useLo in listOf(true, false)) {
            if (!supports(seed, useLo)) continue
            for (index in seed.vars.indices) {
                work.visit()
                if (exhausted) return null
                val variable = seed.vars[index]
                val coefficient = signed(seed.coeffs[index], useLo)
                if (coefficient == 0L) continue
                val lower = coefficient > 0L
                val level = if (lower) state.intMinLevel[variable] else state.intMaxLevel[variable]
                if (level <= target) continue
                val owner = if (lower) state.intMinReason[variable] else state.intMaxReason[variable]
                val occurrences = state.projection.readyIntOccurrences(variable)
                state.arithmeticLearningTrace?.record(if (occurrences == null)
                    "integer.retained.non-owner-pair.unindexed" else "integer.retained.non-owner-pair.indexed")
                val size = occurrences?.size ?: state.baseFactorCount
                for (offset in 0 until size) {
                    work.visit()
                    if (exhausted) return null
                    val secondId = occurrences?.get(offset) ?: offset
                    if (secondId !in 0 until state.baseFactorCount ||
                        secondId == factorId || secondId == owner) continue
                    val factor = state.problem.factors[secondId]
                    if (factor !is Linear && factor !is ExactLinear) continue
                    val parent = original(secondId) ?: continue
                    for (parentUseLo in listOf(true, false)) {
                        if (!supports(parent, parentUseLo)) continue
                        val row = combine(factorId, useLo, secondId, parentUseLo, variable, nonOwner = true) ?: continue
                        if (!conflicting(row)) {
                            decline("not-conflicting")
                            continue
                        }
                        state.arithmeticLearningTrace?.record("integer.retained.original-pair.selected")
                        state.arithmeticLearningTrace?.record("integer.retained.non-owner-pair.selected")
                        return Source(row, variable, state.undo.size, lower, nonOwner = true)
                    }
                }
            }
        }
        return null
    }
}
