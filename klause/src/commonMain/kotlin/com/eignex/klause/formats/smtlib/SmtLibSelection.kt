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
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.div
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.rem
import com.eignex.klause.util.signum
import com.eignex.klause.util.toLongExact

internal fun Compiler.Builder.exclusiveIntegerSelection(
    result: Int,
    conditions: List<Int>,
    arms: List<LinComb>,
    default: LinComb,
): List<Factor> {
    require(conditions.size == arms.size)
    val entries = longArrayOf(selectionColumn(default).toLong()) +
        arms.map { selectionColumn(it).toLong() }.toLongArray()
    val index = selectionIndex(conditions)
    val relation = Element(index, result, entries, arrIsVars = true, indexOffset = 0)
    factors.add(relation)
    return listOf(relation)
}

internal fun Compiler.Builder.wideIntegerSelection(
    condition: Int,
    result: IntComb,
    whenTrue: IntComb,
    whenFalse: IntComb,
    magnitude: BigInt?,
) {
    val trueValue = selectionWideValue(whenTrue, magnitude)
    val falseValue = selectionWideValue(whenFalse, magnitude)
    val index = selectionIndex(listOf(condition))
    when (result) {
        is IntComb.Narrow -> factors.add(Element(index, checkNotNull(result.lin.asSimpleVar()), longArrayOf(
            checkNotNull((falseValue as IntComb.Narrow).lin.asSimpleVar()).toLong(),
            checkNotNull((trueValue as IntComb.Narrow).lin.asSimpleVar()).toLong(),
        ), arrIsVars = true, indexOffset = 0))
        is IntComb.Wide -> {
            val trueColumns = (trueValue as IntComb.Wide).lin.coeffs.entries.associate { it.value to it.key }
            val falseColumns = (falseValue as IntComb.Wide).lin.coeffs.entries.associate { it.value to it.key }
            for ((column, weight) in result.lin.coeffs) {
                factors.add(Element(index, column, longArrayOf(falseColumns.getValue(weight).toLong(),
                    trueColumns.getValue(weight).toLong()), arrIsVars = true, indexOffset = 0))
            }
        }
    }
}

private fun Compiler.Builder.selectionWideValue(term: IntComb, magnitude: BigInt?): IntComb {
    val value = freshWideInt(magnitude)
    val source = term.toWide()
    if (source.coeffs.isNotEmpty()) {
        assertRelation("=", value, term)
        return value
    }
    var remaining = source.constant
    val columns = when (value) {
        is IntComb.Narrow -> listOf(checkNotNull(value.lin.asSimpleVar()))
        is IntComb.Wide -> value.lin.coeffs.entries.sortedBy { it.value }.map { it.key }
    }
    for ((position, column) in columns.withIndex()) {
        val digit = if (position == columns.lastIndex) remaining.toLongExact() else {
            val domain = (intDomains[column] as PresolveDomain.Finite).domain
            val radix = bigIntOf(domain.max) + BIG_ONE
            val remainder = remaining % radix
            val normalized = if (remainder.signum() < 0) remainder + radix else remainder
            remaining = (remaining - normalized) / radix
            normalized.toLongExact()
        }
        intDomains[column] = PresolveDomain.Finite(IntDomain(digit, digit))
    }
    return value
}

private fun Compiler.Builder.selectionIndex(conditions: List<Int>): Int {
    val index = newInt(0, conditions.size.toLong())
    val start = factors.size
    for ((position, condition) in conditions.withIndex()) {
        val alias = newBool()
        val literal = Lit.make(alias, true)
        factors.add(Clause(intArrayOf(Lit.negate(condition), literal)))
        factors.add(Clause(intArrayOf(condition, Lit.negate(literal))))
        factors.add(ReifiedLinear(alias, longArrayOf(1), intArrayOf(index), LinearOp.EQ, position.toLong() + 1))
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
