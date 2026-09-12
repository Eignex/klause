package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.cut.retainReferencedDefinitions
import com.eignex.klause.lp.engine.CutExpression
import com.eignex.klause.lp.engine.CutFixing
import com.eignex.klause.lp.engine.CutLatticeResult
import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutProofFact
import com.eignex.klause.lp.engine.CutProvenance
import com.eignex.klause.lp.engine.CutRowTransform
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.exactBounds
import com.eignex.klause.lp.engine.exactConstant
import com.eignex.klause.lp.engine.exactCost
import com.eignex.klause.lp.engine.exactRhs
import com.eignex.klause.lp.engine.exactShift
import com.eignex.klause.lp.engine.finiteExactInput
import com.eignex.klause.lp.relaxation.CutSourceMap
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction

internal class LpEpochProof private constructor(
    val derivation: RelaxationTidyDerivation,
    val model: LpModel,
    val sources: CutSourceMap,
    private val construction: NaturalModel,
) {
    fun active(session: PropagationSession): Boolean = sources.model === session.problem &&
        derivation.scope.assumptions.isEmpty() &&
        (derivation.scope.searchRoot?.admits(session) != false)

    fun bind(candidate: LpModel, mapping: CutSourceMap?): LpEpochProof? {
        if (mapping == null || mapping.model !== sources.model || mapping.columns != sources.columns ||
            mapping.assumptions != sources.assumptions ||
            mapping.auxiliaryDefinitions != sources.auxiliaryDefinitions ||
            derivation.scope.searchRoot?.admitsCurrent() == false ||
            candidate.hasContinuous || candidate.doubleView != null || !candidate.finiteExactInput() ||
            naturalModel(candidate, mapping) != construction || !narrows(candidate, derivation.transformedModel)
        ) {
            return null
        }
        return LpEpochProof(derivation, candidate, mapping, construction)
    }

    fun rowProof(outputRow: Int): CutProvenance? {
        val map = derivation.rowMaps.getOrNull(outputRow) ?: return null
        val original = row(derivation.sourceModel, map.sourceRow) ?: return null
        val conclusion = row(derivation.transformedModel, outputRow) ?: return null
        val facts = ArrayList<CutProofFact>()
        val parent = sources.parent(outputRow)
        if (parent != null) {
            if (parent.model !== sources.model || !sources.assumptions.containsAll(parent.assumptions) ||
                parent.facts.any { !it.global && !sources.isActive(it.premise) }
            ) {
                return null
            }
            facts.addAll(parent.facts)
        } else if (derivation.sourceModel.rowGlobal[map.sourceRow]) {
            facts.add(CutProofFact(original, true))
        } else {
            val premises = derivation.sourceModel.rowPremises[map.sourceRow] ?: return null
            for (index in premises.vars.indices) {
                val expression = CutExpression(
                    mapOf(CutSource(CutSourceKind.INTEGER, premises.vars[index]) to BigFraction.ONE),
                )
                facts.add(
                    CutProofFact(
                        CutPremise.Bound(
                    expression,
                    premises.isUpper[index],
                    BigFraction.ofLong(premises.thresholds[index]),
                ),
                    false
                    )
                )
            }
            for (literal in premises.boolLits) facts.add(CutProofFact(CutPremise.Literal(literal), false))
        }
        for (fixing in map.fixings) {
            for (side in listOf(fixing.lower, fixing.upper)) {
                val global = sources.isGlobal(side)
                val term = side.expression.terms.keys.singleOrNull()
                val premise = if (!global && term?.kind == CutSourceKind.TERM) {
                    sources.presenceGuard(
                    side,
                ) ?: return null
                } else {
                    side
                }
                facts.add(CutProofFact(premise, global))
            }
        }
        val transforms = parent?.transformations?.toMutableList() ?: ArrayList()
        val rounded = map.rounding
        val algebraic = if (rounded == null) {
            conclusion
        } else {
            row(derivation.sourceModel, map.sourceRow, map.fixings)
            ?: return null
        }
        transforms.add(
            CutRowTransform.Algebraic(
            original,
            algebraic,
            if (rounded == null) map.sourceMultiplier else BigFraction.ONE,
            derivation.sourceModel.rowStrict[map.sourceRow],
            if (rounded ==
                null
            ) {
                    derivation.transformedModel.rowStrict[outputRow]
                } else {
                    derivation.sourceModel.rowStrict[map.sourceRow]
                },
            map.fixings.map { CutFixing(it.lower, it.upper) },
        )
        )
        if (rounded != null) {
            val remaining = algebraic.expression.terms.entries.singleOrNull() ?: return null
            val integral = CutPremise.Integral(CutExpression(mapOf(remaining.key to BigFraction.ONE)))
            if (!sources.isGlobal(integral)) return null
            facts.add(CutProofFact(integral, true))
            val result = if (rounded.infeasibleEquality) {
                CutLatticeResult.InfeasibleEquality(rounded.sourceValue)
            } else {
                CutLatticeResult.RoundedBound(
                    rounded.sourceUpper ?: return null,
                    rounded.sourceValue,
                    rounded.roundedValue ?: return null,
                    rounded.strict,
                )
            }
            transforms.add(CutRowTransform.Lattice(algebraic, conclusion, integral, result))
        }
        return CutProvenance(
            sources.model,
            derivation.scope.epoch,
            facts,
            sources.assumptions,
            parent?.rules ?: emptyList(),
            conclusion,
            transforms,
            sources.auxiliaryDefinitions,
        ).retainReferencedDefinitions()
    }

    private fun row(model: LpModel, row: Int, fixings: List<RelaxationTidyFixing> = emptyList()): CutPremise.Row? {
        val terms = HashMap<CutSource, BigFraction>()
        var constant = BigFraction.ZERO
        var rhs = BigFraction.ofLong(model.flippedRhs[row])
        val fixed = fixings.associateBy { it.column }
        for (column in 0 until model.n) {
            var coefficient = 0L
            model.forEachInColumn(column) { index, value -> if (index == row) coefficient = value }
            if (coefficient == 0L) continue
            val value = BigFraction.ofLong(coefficient)
            val fixing = fixed[column]
            if (fixing != null) {
                rhs -= value * BigFraction.ofLong(fixing.value)
                continue
            }
            val source = sources.column(column) ?: return null
            terms[source.source] = (terms[source.source] ?: BigFraction.ZERO) + value * source.scale
            constant += value * source.offset
        }
        return CutPremise.Row(
            CutExpression(terms, constant),
            if (model.hasUpper[model.slackCol(row)]) Relation.EQ else Relation.LE,
            rhs,
        )
    }

    companion object {
        fun create(derivation: RelaxationTidyDerivation, sources: CutSourceMap): LpEpochProof? {
            if (derivation.scope.root !== sources.model || derivation.scope.epoch != sources.epoch ||
                derivation.scope.assumptions != sources.assumptions || derivation.columnSources != sources.columns ||
                derivation.scope.searchRoot?.admitsCurrent() == false || !derivation.validate()
            ) {
                return null
            }
            val model = derivation.transformedModel
            return LpEpochProof(derivation, model, sources, naturalModel(model, sources))
        }
    }
}

private data class NaturalModel(
    val columns: List<List<Pair<Int, BigFraction>>>,
    val rows: List<List<Any?>>,
    val costs: List<BigFraction>,
    val constant: BigFraction,
    val sense: Any,
    val tags: List<Int>,
    val continuous: List<Boolean>,
    val clampedLower: List<Boolean>,
    val clampedUpper: List<Boolean>,
)

private fun naturalModel(model: LpModel, sources: CutSourceMap): NaturalModel {
    val rhs = MutableList(model.m) { model.exactRhs(it) }
    val columns = List(model.n) { column ->
        val entries = model.exactState?.model?.entries(column)?.map { it.row to it.number.value } ?: buildList {
            model.forEachInColumn(column) { row, value -> add(row to BigFraction.ofLong(value)) }
        }
        for ((row, value) in entries) rhs[row] += value * model.exactShift(column)
        entries.sortedBy { it.first }
    }
    var constant = model.exactConstant()
    for (column in 0 until model.n) constant -= model.exactCost(column) * model.exactShift(column)
    return NaturalModel(
        columns,
        List(model.m) { row ->
            val premise = model.rowPremises[row]
            val slack = model.exactBounds(model.slackCol(row))
            listOf(
                rhs[row], slack.lower, slack.upper, model.rowStrict[row], model.rowGlobal[row],
                premise?.vars?.toList(), premise?.isUpper?.toList(), premise?.thresholds?.toList(),
                premise?.boolLits?.toList(), sources.parent(row),
            )
        },
        List(model.numVars) { model.exactCost(it) },
        constant,
        model.sense,
        model.tag.toList(),
        model.colContinuous.toList(),
        model.probeClampedLo.toList(),
        model.probeClampedHi.toList(),
    )
}

private fun narrows(candidate: LpModel, source: LpModel): Boolean = (0 until source.n).all { column ->
    val saved = source.exactBounds(column)
    val live = candidate.exactBounds(column)
    val savedOrigin = source.exactShift(column)
    val liveOrigin = candidate.exactShift(column)
    fun side(upper: Boolean): Boolean {
        val before = (if (upper) saved.upper else saved.lower) ?: return true
        val after = (if (upper) live.upper else live.lower) ?: return false
        val beforeValue = before.number.value + savedOrigin
        val afterValue = after.number.value + liveOrigin
        return (if (upper) afterValue < beforeValue else afterValue > beforeValue) ||
            (afterValue == beforeValue && (!before.strict || after.strict))
    }
    side(false) && side(true)
}
