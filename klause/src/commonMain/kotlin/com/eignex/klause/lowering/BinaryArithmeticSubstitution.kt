package com.eignex.klause.lowering

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.linearRows
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.magnitudeBitLength
import com.eignex.klause.util.minus

internal fun substituteBinaryArithmetic(
    factors: MutableList<Factor>,
    eligible: Set<Int>,
    binaryDomains: Set<Int>,
    fixedValues: Map<Int, Long>,
    cancellation: Cancellation,
): Set<Int> {
    if (eligible.isEmpty()) return emptySet()
    fun spend(units: Long) {
        if (cancellation()) throw PolynomialSourceInterrupted()
        cancellation.charge(units)
        if (cancellation()) throw PolynomialSourceInterrupted()
    }
    val channels = HashMap<Int, Int>()
    val protected = HashSet<Int>()
    val rows = Array<LinearRow?>(factors.size) { null }
    for ((index, factor) in factors.withIndex()) {
        spend(1L + factor.variables.ints.size)
        val row = when (factor) {
            is ExactLinear -> factor
            is Linear -> factor.takeIf { it.constants is IntegralConstants }
            is ReifiedLinear -> factor.linearRows.single()
            is Element -> {
                if (factor.idx !in binaryDomains || factor.indexOffset != 0 || factor.arr.size != 2) null else {
                    val values = if (factor.arrIsVars) factor.arr.map { fixedValues[it.toInt()] } else
                        factor.arr.map { it }
                    if (values.any { it == null }) null else {
                        val first = bigIntOf(checkNotNull(values[0]))
                        val second = bigIntOf(checkNotNull(values[1]))
                        ExactLinear.of(intArrayOf(Term.ofIntVar(factor.result), Term.ofIntVar(factor.idx)),
                            listOf(BigFraction.ONE, BigFraction.of(first - second, BIG_ONE)),
                            LinearOp.EQ, BigFraction.of(first, BIG_ONE))
                    }
                }
            }
            else -> null
        }
        rows[index] = row
        if (row == null) {
            protected.addAll(factor.variables.ints.toList())
            continue
        }
        spend(row.size.toLong())
        if (row.size != 1 || row.activator == LinearRow.ALWAYS || !Term.isInt(row.ref(0))) continue
        val variable = Term.intVar(row.ref(0))
        if (variable !in eligible) continue
        val constants = row.constants as IntegralConstants
        fun holds(activity: BigInt): Boolean = when (row.relation) {
            LinearOp.LE -> activity <= constants.exactBound
            LinearOp.GE -> activity >= constants.exactBound
            LinearOp.EQ -> activity == constants.exactBound
            LinearOp.NE -> activity != constants.exactBound
        }
        val atZero = holds(BIG_ZERO)
        val atOne = holds(constants.exactCoeff(0))
        if (atZero != atOne && variable !in channels) channels[variable] = Lit.make(row.activator, atOne)
    }
    val selected = channels.keys.filterTo(HashSet()) { it !in protected }
    if (selected.isEmpty()) return emptySet()
    val replacements = ArrayList<Factor>(factors.size)
    for ((index, factor) in factors.withIndex()) {
        spend(1L + factor.variables.ints.size)
        val row = rows[index]
        if (row == null || factor.variables.ints.none { it in selected }) {
            replacements.add(factor)
            continue
        }
        val constants = row.constants as IntegralConstants
        val references = IntArray(row.size)
        val coefficients = ArrayList<BigFraction>(row.size)
        for (term in 0 until row.size) {
            spend(1L + constants.exactCoeff(term).magnitudeBitLength() / 64L)
            val reference = row.ref(term)
            val literal = if (Term.isInt(reference)) channels[Term.intVar(reference)] else null
            references[term] = if (Term.isInt(reference) && Term.intVar(reference) in selected) {
                Term.ofLit(checkNotNull(literal))
            } else reference
            coefficients.add(BigFraction.of(constants.exactCoeff(term), BIG_ONE))
        }
        replacements.add(ExactLinear.of(references, coefficients, row.relation,
            BigFraction.of(constants.exactBound, BIG_ONE), row.strict, row.activator))
    }
    spend(replacements.size.toLong())
    check(replacements.none { factor -> factor.variables.ints.any { it in selected } })
    factors.clear()
    factors.addAll(replacements)
    return selected
}
