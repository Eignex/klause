package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.IntComb
import com.eignex.klause.lowering.LinComb
import com.eignex.klause.lowering.toWide
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.div
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.signum
import com.eignex.klause.util.toLongExact
import com.eignex.klause.util.rem as truncatedRemainder

internal fun Compiler.Builder.exclusiveIntegerSelection(
    result: Int,
    conditions: List<Int>,
    arms: List<LinComb>,
    default: LinComb,
): List<Factor> {
    require(conditions.size == arms.size)
    if (intDomains[result] is PresolveDomain.Open) {
        val start = factors.size
        val value = LinComb(mapOf(result to 1L), 0)
        for ((condition, arm) in conditions.zip(arms)) {
            factors.add(Clause(intArrayOf(Lit.negate(condition), reifyEq(value, arm))))
        }
        factors.add(Clause(conditions.toIntArray() + reifyEq(value, default)))
        return factors.subList(start, factors.size).toList()
    }
    if (conditions.size == 1) {
        booleanConstantSelection(result, conditions[0], arms[0], default)?.let { return it }
    }
    val entries = longArrayOf(selectionColumn(default).toLong()) +
        arms.map { selectionColumn(it).toLong() }.toLongArray()
    val index = selectionIndex(conditions)
    val relation = Element(index, result, entries, arrIsVars = true, indexOffset = 0)
    factors.add(relation)
    return listOf(relation)
}

private fun Compiler.Builder.booleanConstantSelection(
    result: Int,
    condition: Int,
    whenTrue: LinComb,
    whenFalse: LinComb,
): List<Factor>? {
    if (whenTrue.coeffs.isNotEmpty() || whenFalse.coeffs.isNotEmpty()) return null
    val value = whenTrue.constant
    val domain = (intDomains[result] as? PresolveDomain.Finite)?.domain ?: return null
    if (value !in 0L..1L || whenFalse.constant != 1L - value || domain.min < 0L || domain.max > 1L) return null
    val relation = if (Lit.isPositive(condition)) LinearOp.EQ else LinearOp.NE
    val channel = ReifiedLinear(Lit.variable(condition), longArrayOf(1), intArrayOf(result), relation, value)
    factors.add(channel)
    return listOf(channel)
}

internal fun Compiler.Builder.realSelection(condition: Int, whenTrue: RealComb, whenFalse: RealComb): RealComb {
    if (trueLitCache >= 0) {
        if (condition == trueLitCache) return whenTrue
        if (condition == Lit.negate(trueLitCache)) return whenFalse
    }
    val result = RealComb(emptyMap(), mapOf(nextReal++ to BigFraction.ONE), BigFraction.ZERO)
    factors.add(Clause(intArrayOf(Lit.negate(condition), reifyRealRel("=", result, whenTrue))))
    factors.add(Clause(intArrayOf(condition, reifyRealRel("=", result, whenFalse))))
    return result
}

internal fun Compiler.Builder.wideIntegerSelection(
    condition: Int,
    result: IntComb,
    whenTrue: IntComb,
    whenFalse: IntComb,
    magnitude: BigInt?,
) {
    if (magnitude == null) {
        factors.add(Clause(intArrayOf(Lit.negate(condition), reifyRelation("=", result, whenTrue))))
        factors.add(Clause(intArrayOf(condition, reifyRelation("=", result, whenFalse))))
        return
    }
    val trueTerm = whenTrue.toWide()
    val falseTerm = whenFalse.toWide()
    if (trueTerm.coeffs.isEmpty() && falseTerm.coeffs.isEmpty()) {
        val columns = selectionColumns(result)
        val trueDigits = selectionDigits(trueTerm.constant, columns)
        val falseDigits = selectionDigits(falseTerm.constant, columns)
        val index = selectionIndex(listOf(condition))
        for ((position, column) in columns.withIndex()) {
            factors.add(Element(index, column, longArrayOf(falseDigits[position], trueDigits[position]),
                arrIsVars = false, indexOffset = 0))
        }
        return
    }
    val trueValue = selectionWideValue(whenTrue, magnitude)
    val falseValue = selectionWideValue(whenFalse, magnitude)
    val index = selectionIndex(listOf(condition))
    when (result) {
        is IntComb.Narrow -> {
            val column = checkNotNull(result.lin.asSimpleVar())
            val entries = longArrayOf(checkNotNull((falseValue as IntComb.Narrow).lin.asSimpleVar()).toLong(),
                checkNotNull((trueValue as IntComb.Narrow).lin.asSimpleVar()).toLong())
            factors.add(Element(index, column, entries, arrIsVars = true, indexOffset = 0))
        }
        is IntComb.Wide -> {
            val trueColumns = (trueValue as IntComb.Wide).lin.coeffs.entries.associate { it.value to it.key }
            val falseColumns = (falseValue as IntComb.Wide).lin.coeffs.entries.associate { it.value to it.key }
            for ((column, weight) in result.lin.coeffs) {
                val entries = longArrayOf(falseColumns.getValue(weight).toLong(), trueColumns.getValue(weight).toLong())
                factors.add(Element(index, column, entries, arrIsVars = true, indexOffset = 0))
            }
        }
    }
}

private fun Compiler.Builder.selectionWideValue(term: IntComb, magnitude: BigInt?): IntComb {
    val value = freshWideInt(magnitude)
    val source = term.toWide()
    if (source.coeffs.isNotEmpty() || (value is IntComb.Narrow && !source.constant.fitsLong())) {
        assertRelation("=", value, term)
        return value
    }
    val columns = selectionColumns(value)
    val digits = selectionDigits(source.constant, columns)
    for ((position, column) in columns.withIndex()) {
        intDomains[column] = PresolveDomain.Finite(IntDomain(digits[position], digits[position]))
    }
    return value
}

private fun selectionColumns(value: IntComb): List<Int> = when (value) {
    is IntComb.Narrow -> listOf(checkNotNull(value.lin.asSimpleVar()))
    is IntComb.Wide -> value.lin.coeffs.entries.sortedWith { a, b -> a.value.compareTo(b.value) }.map { it.key }
}

private fun Compiler.Builder.selectionDigits(constant: BigInt, columns: List<Int>): LongArray {
    var remaining = constant
    return LongArray(columns.size) { position ->
        if (position == columns.lastIndex) remaining.toLongExact() else {
            val column = columns[position]
            val domain = (intDomains[column] as PresolveDomain.Finite).domain
            val radix = bigIntOf(domain.max) + BIG_ONE
            val remainder = remaining.truncatedRemainder(radix)
            val normalized = if (remainder.signum() < 0) remainder + radix else remainder
            remaining = (remaining - normalized) / radix
            normalized.toLongExact()
        }
    }
}

private fun Compiler.Builder.selectionIndex(conditions: List<Int>): Int {
    val index = newInt(0, conditions.size.toLong())
    val start = factors.size
    for ((position, condition) in conditions.withIndex()) {
        val relation = if (Lit.isPositive(condition)) LinearOp.EQ else LinearOp.NE
        factors.add(ReifiedLinear(Lit.variable(condition), longArrayOf(1), intArrayOf(index),
            relation, position.toLong() + 1))
    }
    conditionalEqualities.define(index, conditions, List(conditions.size) { LinComb(emptyMap(), it.toLong() + 1) },
        LinComb(emptyMap(), 0), factors.subList(start, factors.size), conditions.map(iteChains::guardEquality))
    return index
}

private fun Compiler.Builder.selectionColumn(term: LinComb): Int {
    term.asSimpleVar()?.let { return it }
    if (term.coeffs.isEmpty()) return iteChains.fixedVar(term.constant) {
        newInt(it, it).also { variable ->
            conditionalEqualities.define(variable, emptyList(), emptyList(), term, emptyList(), emptyList())
        }
    }
    val (lower, upper) = linCombRange(term)
    val result = newInt(lower, upper)
    val start = factors.size
    postLinearRel(LinComb(mapOf(result to 1L), 0), term, LinearOp.EQ)
    conditionalEqualities.define(result, emptyList(), emptyList(), term,
        factors.subList(start, factors.size), emptyList())
    return result
}
