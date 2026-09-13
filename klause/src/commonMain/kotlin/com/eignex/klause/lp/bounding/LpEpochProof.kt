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
import com.eignex.klause.util.Cancellation

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
            !construction.matches(candidate, mapping) || !narrows(candidate, derivation.transformedModel)
        ) {
            return null
        }
        return LpEpochProof(derivation, candidate, mapping, construction)
    }

    fun rowProof(outputRow: Int, cancellation: Cancellation = Cancellation.Never): CutProvenance? =
        rowProof(outputRow, cancellation, null)

    fun forEachRowProof(
        cancellation: Cancellation = Cancellation.Never,
        consume: (Int, CutProvenance) -> Unit,
    ): Boolean = try {
        val rows = LpProofRows.create(derivation.sourceModel, derivation.transformedModel, cancellation)
        var complete = true
        for (row in 0 until derivation.transformedModel.m) {
            val proof = rowProof(row, cancellation, rows)
            if (proof == null) {
                complete = false
                break
            }
            consume(row, proof)
        }
        complete && rows.unchanged(cancellation)
    } catch (_: LpProofRowsCancelled) {
        false
    } catch (_: LpProofRowsInvalidated) {
        false
    }

    private fun rowProof(outputRow: Int, cancellation: Cancellation, rows: LpProofRows?): CutProvenance? {
        if (cancellation()) return null
        val map = derivation.rowMap(outputRow) ?: return null
        val original = row(
            derivation.sourceModel,
            map.sourceRow,
            cancellation = cancellation,
            index = rows?.source,
        ) ?: return null
        val conclusion = row(
            derivation.transformedModel,
            outputRow,
            cancellation = cancellation,
            index = rows?.transformed,
        ) ?: return null
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
                        false,
                    ),
                )
            }
            for (literal in premises.boolLits) facts.add(CutProofFact(CutPremise.Literal(literal), false))
        }
        for (fixing in map.fixings) {
            if (cancellation()) return null
            for (side in listOf(fixing.lower, fixing.upper)) {
                val global = sources.isGlobal(side)
                val term = side.expression.terms.keys.singleOrNull()
                val premise = if (!global && term?.kind == CutSourceKind.AUXILIARY) {
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
            row(derivation.sourceModel, map.sourceRow, map.fixings, cancellation, rows?.source)
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
            ),
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

    private fun row(
        model: LpModel,
        row: Int,
        fixings: List<RelaxationTidyFixing> = emptyList(),
        cancellation: Cancellation,
        index: LpProofRowIndex? = null,
    ): CutPremise.Row? {
        val terms = HashMap<CutSource, BigFraction>()
        var constant = BigFraction.ZERO
        var rhs = BigFraction.ofLong(model.flippedRhs[row])
        val fixed = fixings.associateBy { it.column }
        fun append(column: Int, coefficient: Long): Boolean {
            val value = BigFraction.ofLong(coefficient)
            val fixing = fixed[column]
            if (fixing != null) {
                rhs -= value * BigFraction.ofLong(fixing.value)
                return true
            }
            val source = sources.column(column) ?: return false
            terms[source.source] = (terms[source.source] ?: BigFraction.ZERO) + value * source.scale
            constant += value * source.offset
            return true
        }
        if (index != null) {
            if (!index.forEachCoefficient(row, cancellation, ::append)) return null
        } else {
            for (column in 0 until model.n) {
                if (cancellation()) return null
                var coefficient = 0L
                for (entry in model.csc.colPtr[column] until model.csc.colPtr[column + 1]) {
                    if (cancellation()) return null
                    if (model.csc.rowIdx[entry] == row) coefficient = model.csc.colVal[entry]
                }
                if (coefficient != 0L && !append(column, coefficient)) return null
            }
        }
        if (cancellation()) return null
        return CutPremise.Row(
            CutExpression(terms, constant),
            if (model.hasUpper[model.slackCol(row)]) Relation.EQ else Relation.LE,
            rhs,
        )
    }

    companion object {
        fun create(
            derivation: RelaxationTidyDerivation,
            sources: CutSourceMap,
            cancellation: Cancellation = Cancellation.Never,
        ): LpEpochProof? {
            if (cancellation()) return null
            if (derivation.scope.root !== sources.model || derivation.scope.epoch != sources.epoch ||
                derivation.scope.assumptions != sources.assumptions || derivation.columnSources != sources.columns ||
                derivation.scope.searchRoot?.admitsCurrent() == false || !derivation.validate(cancellation)
            ) {
                return null
            }
            val model = derivation.transformedModel
            val construction = naturalModel(model, sources, cancellation) ?: return null
            return LpEpochProof(derivation, model, sources, construction)
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

private fun NaturalModel.matches(model: LpModel, sources: CutSourceMap): Boolean {
    if (model.n != columns.size || model.m != rows.size || model.numVars != costs.size) return false
    val rhs = MutableList(model.m) { model.exactRhs(it) }
    for (column in 0 until model.n) {
        val start = model.csc.colPtr[column]
        val end = model.csc.colPtr[column + 1]
        val saved = columns[column]
        if (end - start != saved.size) return false
        val shift = model.exactShift(column)
        for (entry in start until end) {
            val row = model.csc.rowIdx[entry]
            val value = BigFraction.ofLong(model.csc.colVal[entry])
            val expected = saved[entry - start]
            if (row != expected.first || value != expected.second) return false
            if (!value.isZero && !shift.isZero) rhs[row] += value * shift
        }
    }
    var currentConstant = model.exactConstant()
    for (column in 0 until model.n) {
        val cost = model.exactCost(column)
        val shift = model.exactShift(column)
        if (!cost.isZero && !shift.isZero) currentConstant -= cost * shift
    }
    if (currentConstant != constant || model.sense != sense || !model.tag.matches(tags) ||
        !model.colContinuous.matches(continuous) || !model.probeClampedLo.matches(clampedLower) ||
        !model.probeClampedHi.matches(clampedUpper)
    ) {
        return false
    }
    for (column in 0 until model.numVars) if (model.exactCost(column) != costs[column]) return false
    return matchesRows(model, sources, rhs)
}

private fun NaturalModel.matchesRows(model: LpModel, sources: CutSourceMap, rhs: List<BigFraction>): Boolean {
    for (row in 0 until model.m) {
        val saved = rows[row]
        val premise = model.rowPremises[row]
        val slack = model.exactBounds(model.slackCol(row))
        if (rhs[row] != saved[0] || slack.lower != saved[1] || slack.upper != saved[2] ||
            model.rowStrict[row] != saved[3] || model.rowGlobal[row] != saved[4] ||
            !premise?.vars.matches(saved[5]) || !premise?.isUpper.matches(saved[6]) ||
            !premise?.thresholds.matches(saved[7]) || !premise?.boolLits.matches(saved[8]) ||
            sources.parent(row) != saved[9]
        ) {
            return false
        }
    }
    return true
}

private fun IntArray?.matches(snapshot: Any?): Boolean = if (this == null) {
    snapshot == null
} else {
    snapshot is List<*> && size == snapshot.size && indices.all { this[it] == snapshot[it] }
}

private fun LongArray?.matches(snapshot: Any?): Boolean = if (this == null) {
    snapshot == null
} else {
    snapshot is List<*> && size == snapshot.size && indices.all { this[it] == snapshot[it] }
}

private fun BooleanArray?.matches(snapshot: Any?): Boolean = if (this == null) {
    snapshot == null
} else {
    snapshot is List<*> && size == snapshot.size && indices.all { this[it] == snapshot[it] }
}

private fun naturalModel(
    model: LpModel,
    sources: CutSourceMap,
    cancellation: Cancellation = Cancellation.Never,
): NaturalModel? {
    if (cancellation()) return null
    val rhs = MutableList(model.m) { model.exactRhs(it) }
    val columns = ArrayList<List<Pair<Int, BigFraction>>>()
    for (column in 0 until model.n) {
        if (cancellation()) return null
        val entries = model.exactState?.model?.entries(column)?.map { it.row to it.number.value } ?: buildList {
            model.forEachInColumn(column) { row, value -> add(row to BigFraction.ofLong(value)) }
        }
        for ((row, value) in entries) {
            if (cancellation()) return null
            rhs[row] += value * model.exactShift(column)
        }
        columns.add(entries.sortedBy { it.first })
    }
    var constant = model.exactConstant()
    for (column in 0 until model.n) constant -= model.exactCost(column) * model.exactShift(column)
    if (cancellation()) return null
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
