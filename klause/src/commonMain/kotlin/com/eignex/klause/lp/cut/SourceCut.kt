package com.eignex.klause.lp.cut

import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.CutExpression
import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutProofFact
import com.eignex.klause.lp.engine.CutProvenance
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutRoundingRule
import com.eignex.klause.lp.engine.CutWeightedRow
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.exactRhs
import com.eignex.klause.lp.engine.exactShift
import com.eignex.klause.lp.relaxation.CutSourceMap
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.simplex.exact.BigFraction
import com.ionspin.kotlin.bignum.integer.BigInteger

internal enum class CutMappingDecline { MISSING_SOURCE, MISSING_PROVENANCE, MODEL_SCOPE, INACTIVE_GUARD, ARITHMETIC_LIMIT, LONG_RANGE }

internal sealed interface CutMapping<out T> {
    data class Mapped<T>(val value: T) : CutMapping<T>
    data class Declined(val reason: CutMappingDecline) : CutMapping<Nothing>
}

internal fun <T> CutMapping<T>.orNull(): T? = (this as? CutMapping.Mapped)?.value

internal class CutMappingLimits(val terms: Int = 4096, val bits: Int = 4096) {
    init { require(terms > 0 && bits > 0) }
    fun accepts(value: BigFraction): Boolean = value.num.bitLength() <= bits && value.den.bitLength() <= bits
}

internal class SourceCut(
    val expression: CutExpression,
    val relation: Relation,
    val rhs: BigFraction,
    val provenance: CutProvenance,
) {
    val key: String get() = expression.terms.entries.sortedWith(compareBy({ it.key.kind.ordinal }, { it.key.id }))
        .joinToString(",") { "${it.key.kind}:${it.key.id}:${it.value}" } + "|$relation|${rhs - expression.constant}"

    fun toCut(map: CutSourceMap, limits: CutMappingLimits = CutMappingLimits()): CutMapping<Cut> {
        if (provenance.model !== map.model || !map.assumptions.containsAll(provenance.assumptions)) {
            return CutMapping.Declined(CutMappingDecline.MODEL_SCOPE)
        }
        if (provenance.facts.any { !it.global && !map.isActive(it.premise) }) {
            return CutMapping.Declined(CutMappingDecline.INACTIVE_GUARD)
        }
        if (expression.terms.size > limits.terms || provenance.facts.size > limits.terms ||
            !limits.accepts(rhs) || !limits.accepts(expression.constant) || expression.terms.values.any { !limits.accepts(it) }
        ) return CutMapping.Declined(CutMappingDecline.ARITHMETIC_LIMIT)
        val coefficients = LinkedHashMap<Int, BigFraction>()
        var bound = rhs - expression.constant
        val facts = provenance.facts.toMutableList()
        val columns = map.columns
        for ((source, coefficient) in expression.terms) {
            val col = columns.indexOfFirst { it?.source == source }
            if (col >= 0) {
                val definition = checkNotNull(columns[col])
                val scaled = coefficient * definition.scale.reciprocal()
                coefficients[col] = (coefficients[col] ?: BigFraction.ZERO) + scaled
                bound += scaled * definition.offset
            } else {
                val fixed = map.fixed[source] ?: return CutMapping.Declined(CutMappingDecline.MISSING_SOURCE)
                val premise = CutPremise.Fixed(source, fixed)
                if (!map.isActive(premise)) return CutMapping.Declined(CutMappingDecline.INACTIVE_GUARD)
                facts.add(CutProofFact(premise, map.isGlobal(premise)))
                bound -= coefficient * fixed
            }
            if (coefficients.size > limits.terms || !limits.accepts(bound) || coefficients.values.any { !limits.accepts(it) }) {
                return CutMapping.Declined(CutMappingDecline.ARITHMETIC_LIMIT)
            }
        }
        val values = coefficients.filterValues { !it.isZero }
        val all = values.values.toList() + bound
        var scale = BigInteger.ONE
        for (value in all) {
            scale = scale / scale.gcd(value.den) * value.den
            if (scale.bitLength() > limits.bits) return CutMapping.Declined(CutMappingDecline.ARITHMETIC_LIMIT)
        }
        val integers = all.map { it.num * (scale / it.den) }
        val min = BigInteger.fromLong(Long.MIN_VALUE)
        val max = BigInteger.fromLong(Long.MAX_VALUE)
        if (integers.any { it < min || it > max }) return CutMapping.Declined(CutMappingDecline.LONG_RANGE)
        val proof = CutProvenance(map.model, provenance.epoch, facts, provenance.assumptions, provenance.rules)
        return CutMapping.Mapped(
            Cut(values.keys.toIntArray(), integers.dropLast(1).map { it.longValue() }.toLongArray(), relation,
                integers.last().longValue(), global = proof.global, provenance = proof),
        )
    }

    companion object {
        fun fromCut(cut: Cut, relaxation: LpRelaxation, limits: CutMappingLimits = CutMappingLimits()): CutMapping<SourceCut> {
            val map = relaxation.sourceMap ?: return CutMapping.Declined(CutMappingDecline.MISSING_SOURCE)
            return try {
                SourceCutMapper(relaxation.model, map, limits).map(cut)
            } catch (decline: CutMappingFailure) {
                CutMapping.Declined(decline.reason)
            }
        }
    }
}

private class CutMappingFailure(val reason: CutMappingDecline) : RuntimeException()

private class SourceCutMapper(private val model: LpModel, private val sources: CutSourceMap, private val limits: CutMappingLimits) {
    private val definitions = sources.columns
    private fun decline(reason: CutMappingDecline): Nothing = throw CutMappingFailure(reason)
    private fun checked(value: BigFraction): BigFraction {
        if (!limits.accepts(value)) decline(CutMappingDecline.ARITHMETIC_LIMIT)
        return value
    }

    private fun expression(columns: List<Int>, coefficients: List<BigFraction>, constant: BigFraction = BigFraction.ZERO): CutExpression {
        if (columns.size > limits.terms) decline(CutMappingDecline.ARITHMETIC_LIMIT)
        val terms = LinkedHashMap<CutSource, BigFraction>()
        var offset = checked(constant)
        for (k in columns.indices) {
            val coefficient = checked(coefficients[k])
            if (coefficient.isZero) continue
            val definition = definitions.getOrNull(columns[k]) ?: decline(CutMappingDecline.MISSING_SOURCE)
            terms[definition.source] = checked((terms[definition.source] ?: BigFraction.ZERO) + coefficient * definition.scale)
            offset = checked(offset + coefficient * definition.offset)
        }
        return CutExpression(terms, offset)
    }

    private fun entries(column: Int): List<Pair<Int, BigFraction>> {
        model.exactState?.model?.let { return it.entries(column).map { e -> e.row to e.number.value } }
        val view = model.doubleView
        if (view != null) return (view.colPtr[column] until view.colPtr[column + 1]).map {
            view.rowIdx[it] to (BigFraction.ofDouble(view.colVal[it]) ?: decline(CutMappingDecline.ARITHMETIC_LIMIT))
        }
        return (model.csc.colPtr[column] until model.csc.colPtr[column + 1]).map {
            model.csc.rowIdx[it] to BigFraction.ofLong(model.csc.colVal[it])
        }
    }

    fun map(cut: Cut): CutMapping<SourceCut> {
        if (cut.cols.size > limits.terms || model.n > limits.terms) decline(CutMappingDecline.ARITHMETIC_LIMIT)
        val columns = LinkedHashMap<Int, BigFraction>()
        var constant = BigFraction.ZERO
        for (k in cut.cols.indices) {
            val col = cut.cols[k]
            val coefficient = BigFraction.ofLong(cut.coeffs[k])
            if (col in 0 until model.n) {
                columns[col] = checked((columns[col] ?: BigFraction.ZERO) + coefficient)
            } else if (col in model.n until model.numVars) {
                val row = col - model.n
                constant = checked(constant + coefficient * model.exactRhs(row))
                for (j in 0 until model.n) {
                    for ((r, value) in entries(j)) {
                        if (r != row) continue
                        columns[j] = checked((columns[j] ?: BigFraction.ZERO) - coefficient * value)
                        constant = checked(constant + coefficient * value * model.exactShift(j))
                    }
                }
            } else decline(CutMappingDecline.MISSING_SOURCE)
        }
        val inequality = expression(columns.keys.toList(), columns.values.toList(), constant)
        val facts = ArrayList<CutProofFact>()
        val rules = ArrayList<CutRoundingRule>()
        val inherited = cut.provenance
        val tableau = cut.tableau
        if (inherited != null) {
            if (inherited.model !== sources.model) decline(CutMappingDecline.MODEL_SCOPE)
            facts.addAll(inherited.facts)
            rules.addAll(inherited.rules)
        } else if (tableau != null) {
            if (tableau.model !== model) decline(CutMappingDecline.MODEL_SCOPE)
            for (column in tableau.columns) {
                val expr = expression(listOf(column.column), listOf(BigFraction.ONE))
                val bound = CutPremise.Bound(expr, false, BigFraction.ofLong(column.lower))
                facts.add(CutProofFact(bound, sources.isGlobal(bound)))
                if (column.integral) {
                    val integral = CutPremise.Integral(expr)
                    if (!sources.isGlobal(integral) && !sources.isActive(integral)) decline(CutMappingDecline.MISSING_PROVENANCE)
                    facts.add(CutProofFact(integral, sources.isGlobal(integral)))
                }
            }
            val weightedRows = ArrayList<CutWeightedRow>()
            if (tableau.rows.size > limits.terms) decline(CutMappingDecline.ARITHMETIC_LIMIT)
            for (row in tableau.rows) {
                val rowExpression = expression(row.columns.toList(), row.coefficients.map { BigFraction.ofLong(it) })
                val rowFact = CutPremise.Row(rowExpression, row.relation, row.rhs)
                val parent = sources.parent(row.index)
                if (parent != null) {
                    if (parent.model !== sources.model || !sources.assumptions.containsAll(parent.assumptions)) decline(CutMappingDecline.MODEL_SCOPE)
                    if (facts.size + parent.facts.size > limits.terms) decline(CutMappingDecline.ARITHMETIC_LIMIT)
                    facts.addAll(parent.facts)
                    rules.addAll(parent.rules)
                } else if (row.global) {
                    facts.add(CutProofFact(rowFact, true))
                } else {
                    val premises = row.premises ?: decline(CutMappingDecline.MISSING_PROVENANCE)
                    if (premises.vars.isEmpty() && premises.boolLits.isEmpty()) decline(CutMappingDecline.MISSING_PROVENANCE)
                    for (i in premises.vars.indices) {
                        val expr = CutExpression(mapOf(CutSource(CutSourceKind.INTEGER, premises.vars[i]) to BigFraction.ONE))
                        facts.add(CutProofFact(CutPremise.Bound(expr, premises.isUpper[i], BigFraction.ofLong(premises.thresholds[i])), false))
                    }
                    for (literal in premises.boolLits) facts.add(CutProofFact(CutPremise.Literal(literal), false))
                }
                weightedRows.add(CutWeightedRow(rowFact, row.multiplier))
            }
            rules.add(CutRoundingRule(tableau.divisor, tableau.mir, tableau.reduction, weightedRows))
        } else if (cut.global) {
            facts.add(CutProofFact(CutPremise.Row(inequality, cut.rel, BigFraction.ofLong(cut.rhs)), true))
        } else decline(CutMappingDecline.MISSING_PROVENANCE)
        if (facts.size > limits.terms) decline(CutMappingDecline.ARITHMETIC_LIMIT)
        val assumptions = sources.assumptions + (inherited?.assumptions ?: emptySet())
        val proof = CutProvenance(sources.model, sources.epoch, facts, assumptions, rules)
        return CutMapping.Mapped(SourceCut(inequality, cut.rel, BigFraction.ofLong(cut.rhs), proof))
    }
}
