package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.CutExpression
import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpRowPremises
import com.eignex.klause.lp.relaxation.CutColumnSource
import com.eignex.klause.lp.relaxation.CutSourceMap
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.addExact
import com.eignex.klause.util.mulExact
import com.ionspin.kotlin.bignum.integer.BigInteger

internal enum class RelaxationTidyRule {
    FIXED_SUBSTITUTION,
    EMPTY_ROW,
    REDUNDANT_ROW,
    SINGLETON_BOUND,
    PARALLEL_SIDE,
}

internal enum class RelaxationTidyDecline {
    NOT_ROOT,
    DISABLED,
    CANCELLED,
    CONTINUOUS_MODEL,
    EXACT_STATE,
    GATED_ROWS,
    UNSUPPORTED_COLUMN,
    UNSUPPORTED_SOURCE_MAP,
    UNSUPPORTED_STRICT_EQUALITY,
    ARITHMETIC_OVERFLOW,
    UNBOUNDED_ACTIVITY,
}

internal data class RelaxationTidyStats(
    val eligible: Map<RelaxationTidyRule, Int> = emptyMap(),
    val applied: Map<RelaxationTidyRule, Int> = emptyMap(),
    val declined: Map<RelaxationTidyDecline, Int> = emptyMap(),
) {
    fun eligible(rule: RelaxationTidyRule): Int = eligible[rule] ?: 0
    fun applied(rule: RelaxationTidyRule): Int = applied[rule] ?: 0
    fun declined(reason: RelaxationTidyDecline): Int = declined[reason] ?: 0
}

internal data class RelaxationTidyScope(
    val root: Any,
    val epoch: Long,
    val objective: Any?,
    val atRoot: Boolean,
    val assumptions: Set<String> = emptySet(),
    val cutoff: CutPremise.ObjectiveCutoff? = null,
    val searchRoot: LpEpochRoot? = null,
)

internal data class RelaxationTidyFixing(
    val column: Int,
    val coefficient: Long,
    val value: Long,
    val lower: CutPremise.Bound,
    val upper: CutPremise.Bound,
    val global: Boolean = true,
)

internal data class RelaxationTidyRounding(
    val sourceValue: BigFraction,
    val roundedValue: BigFraction?,
    val sourceUpper: Boolean?,
    val columnUpper: Boolean?,
    val strict: Boolean,
    val infeasibleEquality: Boolean = false,
)

internal data class RelaxationTidyRowMap(
    val outputRow: Int,
    val sourceRow: Int,
    val sourceMultiplier: BigFraction,
    val fixings: List<RelaxationTidyFixing> = emptyList(),
    val rounding: RelaxationTidyRounding? = null,
)

internal enum class RelaxationTidyRemovalReason { EMPTY, REDUNDANT, PARALLEL }

internal data class RelaxationTidyRemovedRow(
    val sourceRow: Int,
    val reason: RelaxationTidyRemovalReason,
    val supplyingSourceRow: Int? = null,
    val boundUses: List<RelaxationTidyBoundUse> = emptyList(),
    val fixings: List<RelaxationTidyFixing> = emptyList(),
)

internal data class RelaxationTidyBoundUse(
    val column: Int,
    val upper: Boolean,
    val coefficient: Long,
    val sourceRow: Int,
)

/** A representable source bound proved by a singleton row. It remains an explicit row in 6.1. */
internal data class RelaxationTidyBound(
    val column: Int,
    val sourceUpper: Boolean,
    val columnUpper: Boolean,
    val sourceValue: BigFraction,
    val columnValue: Long,
    val sourceRow: Int,
    val rounded: Boolean,
    val global: Boolean,
    val premises: Set<CutPremise>,
)

internal data class RelaxationTidyLift(
    val sourceRows: Map<Int, BigFraction>,
    /** Multiplier of the exact fixing equation `x[column] - value = 0`. */
    val fixingEqualities: Map<Int, BigFraction>,
)

/**
 * Immutable postsolve/proof map for one root tidy construction. Structural columns are retained, so
 * primal reconstruction is the identity; row and fixing multipliers remain explicit for proof lifts.
 */
internal class RelaxationTidyDerivation(
    val sourceModel: LpModel,
    val transformedModel: LpModel,
    val scope: RelaxationTidyScope,
    rowMaps: List<RelaxationTidyRowMap>,
    removedRows: List<RelaxationTidyRemovedRow>,
    bounds: List<RelaxationTidyBound>,
    columnSources: List<CutColumnSource?>,
    val stats: RelaxationTidyStats,
    private val sourceMap: CutSourceMap? = null,
) {
    private val rowMapSnapshot = rowMaps.toList()
    private val removedSnapshot = removedRows.toList()
    private val boundSnapshot = bounds.toList()
    private val columnSourceSnapshot = columnSources.toList()

    val rowMaps: List<RelaxationTidyRowMap> get() = rowMapSnapshot.toList()
    val removedRows: List<RelaxationTidyRemovedRow> get() = removedSnapshot.toList()
    val bounds: List<RelaxationTidyBound> get() = boundSnapshot.toList()
    val columnSources: List<CutColumnSource?> get() = columnSourceSnapshot.toList()

    fun appliesTo(candidate: RelaxationTidyScope): Boolean =
        scope.root === candidate.root && scope.objective === candidate.objective &&
            scope.epoch == candidate.epoch && scope.atRoot == candidate.atRoot &&
            scope.assumptions == candidate.assumptions &&
            scope.cutoff == candidate.cutoff && scope.searchRoot === candidate.searchRoot

    /** Lift algebraic row weights. Integer-rounded singleton rows use a lattice proof, not a real Farkas map. */
    fun liftRowMultipliers(weights: List<BigFraction>): RelaxationTidyLift? {
        if (weights.size != transformedModel.m) return null
        val source = HashMap<Int, BigFraction>()
        val fixed = HashMap<Int, BigFraction>()
        for (map in rowMapSnapshot) {
            val weight = weights[map.outputRow]
            if (weight.isZero) continue
            if (map.rounding != null) return null
            source[map.sourceRow] = (source[map.sourceRow] ?: BigFraction.ZERO) + weight * map.sourceMultiplier
            for (fixing in map.fixings) {
                val multiplier = weight * map.sourceMultiplier * BigFraction.ofLong(fixing.coefficient).negated()
                fixed[fixing.column] = (fixed[fixing.column] ?: BigFraction.ZERO) + multiplier
            }
        }
        return RelaxationTidyLift(source.filterValues { !it.isZero }, fixed.filterValues { !it.isZero })
    }

    /** Independent structural and algebraic validation; publication is rejected unless this succeeds. */
    fun validate(): Boolean {
        if (sourceModel.exactState != null || transformedModel.exactState != null ||
            sourceModel.doubleView != null || transformedModel.doubleView != null ||
            sourceModel.hasContinuous || transformedModel.hasContinuous ||
            sourceModel.colContinuous.any { it } || transformedModel.colContinuous.any { it }
        ) {
            return false
        }
        if (!sameColumnsAndObjective(sourceModel, transformedModel)) return false
        if (!hasNormalizedSlacks(sourceModel) || !hasNormalizedSlacks(transformedModel)) return false
        if (!hasNormalizedRhs(sourceModel) || !hasNormalizedRhs(transformedModel)) return false
        if (columnSourceSnapshot.size != sourceModel.n) return false
        if (rowMapSnapshot.size != transformedModel.m ||
            rowMapSnapshot.map { it.outputRow } != (0 until transformedModel.m).toList() ||
            rowMapSnapshot.any { it.sourceRow !in 0 until sourceModel.m }
        ) {
            return false
        }
        val removed = removedSnapshot.map { it.sourceRow }
        if (removed.distinct().size != removed.size || removed.any { it !in 0 until sourceModel.m }) return false
        if ((rowMapSnapshot.map { it.sourceRow } + removed).toSet() != (0 until sourceModel.m).toSet()) return false

        for (map in rowMapSnapshot) {
            if (!validateRow(map)) return false
        }
        for (removedRow in removedSnapshot) {
            if (removedRow.reason == RelaxationTidyRemovalReason.PARALLEL &&
                removedRow.supplyingSourceRow !in 0 until sourceModel.m
            ) {
                return false
            }
            if (!validateRemovedRow(removedRow)) return false
            if (removedRow.boundUses.any { use ->
                    use.column !in 0 until sourceModel.n ||
                        boundSnapshot.none {
                            it.column == use.column && it.columnUpper == use.upper && it.sourceRow == use.sourceRow
                        }
                }
            ) {
                return false
            }
        }
        return boundSnapshot.all(::validateBound)
    }

    /** A transformed primal is already in source coordinates because tidy never eliminates a column. */
    fun verifySourceWitness(values: LongArray): Boolean {
        if (values.size != sourceModel.n || !withinBounds(sourceModel, values) ||
            !withinBounds(transformedModel, values)
        ) {
            return false
        }
        return rowsHold(transformedModel, values) && rowsHold(sourceModel, values) &&
            objectiveValue(sourceModel, values) == objectiveValue(transformedModel, values)
    }

    private fun validateRow(map: RelaxationTidyRowMap): Boolean {
        val sourceCoefficients = rowCoefficients(sourceModel, map.sourceRow)
        val outputCoefficients = rowCoefficients(transformedModel, map.outputRow)
        val substituted = map.fixings.associateBy { it.column }
        if (substituted.size != map.fixings.size) return false
        for (fixing in map.fixings) {
            if (!validFixing(sourceCoefficients, fixing)) return false
        }
        val conditionalFixing = map.fixings.any { !it.global }
        val expectedGlobal = sourceModel.rowGlobal[map.sourceRow] && !conditionalFixing
        val expectedPremises = if (conditionalFixing) null else sourceModel.rowPremises[map.sourceRow]
        if (expectedGlobal != transformedModel.rowGlobal[map.outputRow] ||
            expectedPremises !== transformedModel.rowPremises[map.outputRow]
        ) {
            return false
        }

        val sourceNaturalRhs = BigFraction.ofLong(sourceModel.flippedRhs[map.sourceRow])
        var reducedRhs = sourceNaturalRhs
        for (fixing in map.fixings) {
            reducedRhs -= BigFraction.ofLong(fixing.coefficient) * BigFraction.ofLong(fixing.value)
        }
        val outputNaturalRhs = BigFraction.ofLong(transformedModel.flippedRhs[map.outputRow])
        val rounding = map.rounding
        if (rounding == null) {
            val equality = sourceModel.hasUpper[sourceModel.slackCol(map.sourceRow)]
            if ((equality && map.sourceMultiplier.isZero) || (!equality && map.sourceMultiplier.signum() <= 0)) {
                return false
            }
            if (sourceModel.rowStrict[map.sourceRow] != transformedModel.rowStrict[map.outputRow] ||
                sourceModel.hasUpper[sourceModel.slackCol(map.sourceRow)] !=
                transformedModel.hasUpper[transformedModel.slackCol(map.outputRow)]
            ) {
                return false
            }
            if (outputNaturalRhs != reducedRhs * map.sourceMultiplier) return false
            for (column in 0 until sourceModel.n) {
                val expected = if (column in substituted) {
                    BigFraction.ZERO
                } else {
                    BigFraction.ofLong(sourceCoefficients[column] ?: 0L) * map.sourceMultiplier
                }
                if (expected != BigFraction.ofLong(outputCoefficients[column] ?: 0L)) return false
            }
        } else {
            if (transformedModel.rowStrict[map.outputRow] ||
                transformedModel.hasUpper[transformedModel.slackCol(map.outputRow)]
            ) {
                return false
            }
            if (!validateRoundedSingleton(map, sourceCoefficients, outputCoefficients, reducedRhs, outputNaturalRhs)) {
                return false
            }
        }
        return true
    }

    private fun validateRemovedRow(removed: RelaxationTidyRemovedRow): Boolean {
        val coefficients = rowCoefficients(sourceModel, removed.sourceRow).toMutableMap()
        for (fixing in removed.fixings) {
            if (!validFixing(coefficients, fixing)) return false
            coefficients.remove(fixing.column)
        }
        return when (removed.reason) {
            RelaxationTidyRemovalReason.EMPTY -> {
                coefficients.isEmpty() && if (sourceModel.hasUpper[sourceModel.slackCol(removed.sourceRow)]) {
                    !sourceModel.rowStrict[removed.sourceRow] && sourceModel.rhs[removed.sourceRow] == 0L
                } else if (sourceModel.rowStrict[removed.sourceRow]) {
                    0L < sourceModel.rhs[removed.sourceRow]
                } else {
                    0L <= sourceModel.rhs[removed.sourceRow]
                }
            }

            RelaxationTidyRemovalReason.REDUNDANT -> validateRedundant(removed, coefficients)

            RelaxationTidyRemovalReason.PARALLEL -> validateParallel(removed, coefficients)
        }
    }

    private fun validFixing(coefficients: Map<Int, Long>, fixing: RelaxationTidyFixing): Boolean {
        if (fixing.column !in 0 until sourceModel.n || coefficients[fixing.column] != fixing.coefficient ||
            sourceModel.loShift[fixing.column] != fixing.value || sourceModel.upper[fixing.column] != 0L ||
            !sourceModel.hasUpper[fixing.column]
        ) {
            return false
        }
        val columnSource = columnSourceSnapshot[fixing.column] ?: return false
        val expected = CutPremise.Bound(
            columnSource.expression(),
            false,
            BigFraction.ofLong(fixing.value),
        )
        if (fixing.lower != expected || fixing.upper != expected.copy(upper = true)) return false
        val sources = sourceMap ?: return fixing.global
        val global = sources.isGlobal(fixing.lower) && sources.isGlobal(fixing.upper)
        return fixing.global == global && (
            global || (
                scope.searchRoot != null &&
            sources.isActive(fixing.lower) && sources.isActive(fixing.upper)
            )
        )
    }

    private fun validateRedundant(removed: RelaxationTidyRemovedRow, coefficients: Map<Int, Long>): Boolean {
        val lower = LongArray(sourceModel.n)
        val upper = sourceModel.upper.copyOfRange(0, sourceModel.n)
        val hasUpper = sourceModel.hasUpper.copyOfRange(0, sourceModel.n)
        for (use in removed.boundUses) {
            if (coefficients[use.column] != use.coefficient) return false
            val bound = boundSnapshot.firstOrNull {
                it.column == use.column && it.columnUpper == use.upper && it.sourceRow == use.sourceRow
            } ?: return false
            val shifted = try {
                com.eignex.klause.util.subExact(bound.columnValue, sourceModel.loShift[use.column])
            } catch (_: ArithmeticException) {
                return false
            }
            if (use.upper) {
                if (!hasUpper[use.column] || shifted < upper[use.column]) {
                    upper[use.column] = shifted
                    hasUpper[use.column] = true
                }
            } else if (shifted > lower[use.column]) {
                lower[use.column] = shifted
            }
        }
        var min = BigInteger.ZERO
        var max = BigInteger.ZERO
        for ((column, coefficient) in coefficients) {
            val c = BigInteger.fromLong(coefficient)
            if (coefficient > 0L) {
                min += c * BigInteger.fromLong(lower[column])
                if (!hasUpper[column]) return false
                max += c * BigInteger.fromLong(upper[column])
            } else {
                if (!hasUpper[column]) return false
                min += c * BigInteger.fromLong(upper[column])
                max += c * BigInteger.fromLong(lower[column])
            }
        }
        val rhs = BigInteger.fromLong(sourceModel.rhs[removed.sourceRow])
        return if (sourceModel.hasUpper[sourceModel.slackCol(removed.sourceRow)]) {
            !sourceModel.rowStrict[removed.sourceRow] && min == rhs && max == rhs
        } else if (sourceModel.rowStrict[removed.sourceRow]) {
            max < rhs
        } else {
            max <= rhs
        }
    }

    private fun validateParallel(removed: RelaxationTidyRemovedRow, coefficients: Map<Int, Long>): Boolean {
        val supplier = removed.supplyingSourceRow ?: return false
        val output = rowMapSnapshot.firstOrNull { it.sourceRow == supplier }?.outputRow ?: return false
        if (sourceModel.hasUpper[sourceModel.slackCol(removed.sourceRow)] ||
            transformedModel.hasUpper[transformedModel.slackCol(output)]
        ) {
            return false
        }
        val supplying = rowCoefficients(transformedModel, output)
        if (coefficients.keys != supplying.keys || coefficients.isEmpty()) return false
        val first = coefficients.keys.first()
        val scale = BigFraction.ofLong(coefficients.getValue(first)) *
            BigFraction.ofLong(supplying.getValue(first)).reciprocal()
        if (scale.signum() <= 0 || coefficients.any { (column, value) ->
                BigFraction.ofLong(supplying.getValue(column)) * scale != BigFraction.ofLong(value)
            }
        ) {
            return false
        }
        val supplierRhs = BigFraction.ofLong(transformedModel.rhs[output]) * scale
        val removedRhs = BigFraction.ofLong(sourceModel.rhs[removed.sourceRow])
        return supplierRhs < removedRhs ||
            (
                supplierRhs == removedRhs &&
                    (!sourceModel.rowStrict[removed.sourceRow] || transformedModel.rowStrict[output])
                )
    }

    private fun validateRoundedSingleton(
        map: RelaxationTidyRowMap,
        sourceCoefficients: Map<Int, Long>,
        outputCoefficients: Map<Int, Long>,
        reducedRhs: BigFraction,
        outputNaturalRhs: BigFraction,
    ): Boolean {
        val live = sourceCoefficients.filterKeys { column -> map.fixings.none { it.column == column } }
        if (live.size != 1) return false
        val (column, coefficient) = live.entries.single()
        val rounding = requireNotNull(map.rounding)
        val columnThreshold = reducedRhs * BigFraction.ofLong(coefficient).reciprocal()
        val sourceColumn = columnSourceSnapshot[column] ?: return false
        if (!integralSource(sourceColumn.source)) return false
        val sourceThreshold = (columnThreshold - sourceColumn.offset) * sourceColumn.scale.reciprocal()
        val equality = sourceModel.hasUpper[sourceModel.slackCol(map.sourceRow)]
        if (sourceThreshold != rounding.sourceValue || rounding.strict != sourceModel.rowStrict[map.sourceRow]) {
            return false
        }
        if (rounding.infeasibleEquality) {
            return outputCoefficients.isEmpty() && outputNaturalRhs == BigFraction.MINUS_ONE &&
                rounding.sourceUpper == null && rounding.columnUpper == null && rounding.roundedValue == null &&
                equality && !rounding.strict && sourceThreshold.den != BigInteger.ONE && map.sourceMultiplier.isZero
        }
        if (equality) return false
        if (outputCoefficients.size != 1) return false
        val outputCoefficient = outputCoefficients[column] ?: return false
        val upper = coefficient > 0L
        val sourceUpper = if (sourceColumn.scale.signum() > 0) upper else !upper
        if (rounding.sourceUpper != sourceUpper || rounding.columnUpper != upper) return false
        val rounded = rounding.roundedValue ?: return false
        if (rounded != independentlyRound(sourceThreshold, sourceUpper, rounding.strict)) return false
        if (outputCoefficient != if (upper) 1L else -1L) return false
        if (map.sourceMultiplier != BigFraction.ofLong(kotlin.math.abs(coefficient)).reciprocal()) return false
        val columnValue = sourceColumn.scale * rounded + sourceColumn.offset
        val expectedNatural = if (upper) columnValue else columnValue.negated()
        return outputNaturalRhs == expectedNatural && rounded.den == BigInteger.ONE
    }

    private fun validateBound(bound: RelaxationTidyBound): Boolean {
        if (bound.column !in 0 until sourceModel.n || bound.sourceRow !in 0 until sourceModel.m ||
            bound.sourceValue.den.signum() <= 0
        ) {
            return false
        }
        val map = rowMapSnapshot.firstOrNull { it.sourceRow == bound.sourceRow } ?: return false
        val coefficients = rowCoefficients(sourceModel, bound.sourceRow)
        if (map.fixings.any { !validFixing(coefficients, it) }) return false
        val live = coefficients.filterKeys { column -> map.fixings.none { it.column == column } }
        if (live.size != 1) return false
        val (column, coefficient) = live.entries.single()
        if (column != bound.column || coefficient == 0L || coefficient == Long.MIN_VALUE) return false
        var rhs = BigFraction.ofLong(sourceModel.flippedRhs[bound.sourceRow])
        for (fixing in map.fixings) {
            rhs -= BigFraction.ofLong(fixing.coefficient) * BigFraction.ofLong(fixing.value)
        }
        val columnSource = columnSourceSnapshot[column] ?: return false
        if (!integralSource(columnSource.source)) return false
        val columnThreshold = rhs * BigFraction.ofLong(coefficient).reciprocal()
        val sourceThreshold = (columnThreshold - columnSource.offset) * columnSource.scale.reciprocal()
        val equality = sourceModel.hasUpper[sourceModel.slackCol(bound.sourceRow)]
        val expectedSourceUpper: Boolean
        val expectedColumnUpper: Boolean
        val expectedSourceValue: BigFraction
        if (equality) {
            if (sourceModel.rowStrict[bound.sourceRow] || sourceThreshold.den != BigInteger.ONE) return false
            expectedSourceUpper = bound.sourceUpper
            expectedColumnUpper = if (columnSource.scale.signum() > 0) bound.sourceUpper else !bound.sourceUpper
            expectedSourceValue = sourceThreshold
        } else {
            expectedColumnUpper = coefficient > 0L
            expectedSourceUpper = if (columnSource.scale.signum() > 0) expectedColumnUpper else !expectedColumnUpper
            expectedSourceValue = independentlyRound(
                sourceThreshold,
                expectedSourceUpper,
                sourceModel.rowStrict[bound.sourceRow],
            )
        }
        val columnValue = columnSource.scale * expectedSourceValue + columnSource.offset
        if (bound.sourceUpper != expectedSourceUpper || bound.columnUpper != expectedColumnUpper ||
            bound.sourceValue != expectedSourceValue || columnValue != BigFraction.ofLong(bound.columnValue) ||
            bound.rounded != (expectedSourceValue != sourceThreshold) ||
            bound.global != (sourceModel.rowGlobal[bound.sourceRow] && map.fixings.all { it.global })
        ) {
            return false
        }
        return bound.premises == expectedPremises(sourceModel.rowPremises[bound.sourceRow], map.fixings)
    }

    private fun integralSource(source: CutSource): Boolean = source.kind == CutSourceKind.INTEGER ||
        source.kind == CutSourceKind.BOOLEAN || (
            source.kind == CutSourceKind.TERM &&
            sourceMap?.isGlobal(CutPremise.Integral(CutExpression(mapOf(source to BigFraction.ONE)))) == true
        )

    private fun expectedPremises(rowPremises: LpRowPremises?, fixings: List<RelaxationTidyFixing>): Set<CutPremise> =
        buildSet {
            rowPremises?.let { premises ->
                for (index in premises.vars.indices) {
                    val source = CutSource(CutSourceKind.INTEGER, premises.vars[index])
                    val expression = CutExpression(mapOf(source to BigFraction.ONE))
                    add(
                        CutPremise.Bound(
                            expression,
                            premises.isUpper[index],
                            BigFraction.ofLong(premises.thresholds[index]),
                        ),
                    )
                }
                for (literal in premises.boolLits) add(CutPremise.Literal(literal))
            }
            for (fixing in fixings) {
                add(fixing.lower)
                add(fixing.upper)
            }
        }
}

private fun sameColumnsAndObjective(source: LpModel, output: LpModel): Boolean {
    if (source.n != output.n || source.sense != output.sense || source.objConstant != output.objConstant ||
        !source.loShift.contentEquals(output.loShift) || !source.tag.contentEquals(output.tag) ||
        !source.probeClampedLo.contentEquals(output.probeClampedLo) ||
        !source.probeClampedHi.contentEquals(output.probeClampedHi) ||
        !source.colContinuous.contentEquals(output.colContinuous)
    ) {
        return false
    }
    return (0 until source.n).all { column ->
        source.cost[column] == output.cost[column] && source.upper[column] == output.upper[column] &&
            source.hasUpper[column] == output.hasUpper[column]
    }
}

private fun hasNormalizedRhs(model: LpModel): Boolean = (0 until model.m).all { row ->
    var expected = BigInteger.fromLong(model.flippedRhs[row])
    for (column in 0 until model.n) {
        model.forEachInColumn(column) { entryRow, coefficient ->
            if (entryRow == row) {
                expected -= BigInteger.fromLong(coefficient) * BigInteger.fromLong(model.loShift[column])
            }
        }
    }
    expected == BigInteger.fromLong(model.rhs[row])
}

private fun hasNormalizedSlacks(model: LpModel): Boolean = (0 until model.m).all { row ->
    val slack = model.slackCol(row)
    model.cost[slack] == 0L && (!model.hasUpper[slack] || model.upper[slack] == 0L)
}

private fun rowCoefficients(model: LpModel, row: Int): Map<Int, Long> {
    val result = HashMap<Int, Long>()
    for (column in 0 until model.n) {
        model.forEachInColumn(column) { entryRow, value -> if (entryRow == row) result[column] = value }
    }
    return result
}

private fun withinBounds(model: LpModel, values: LongArray): Boolean = values.indices.all { column ->
    val value = values[column]
    value >= model.loShift[column] && (
        !model.hasUpper[column] ||
            value <= addExact(model.loShift[column], model.upper[column])
        )
}

private fun rowsHold(model: LpModel, values: LongArray): Boolean {
    val activity = LongArray(model.m)
    return try {
        for (column in values.indices) {
            model.forEachInColumn(column) { row, coefficient ->
                activity[row] = addExact(activity[row], mulExact(coefficient, values[column]))
            }
        }
        (0 until model.m).all { row ->
            val rhs = model.flippedRhs[row]
            if (model.hasUpper[model.slackCol(row)]) {
                !model.rowStrict[row] && activity[row] == rhs
            } else if (model.rowStrict[row]) {
                activity[row] < rhs
            } else {
                activity[row] <= rhs
            }
        }
    } catch (_: ArithmeticException) {
        false
    }
}

private fun objectiveValue(model: LpModel, values: LongArray): Long? = try {
    var value = 0L
    for (column in values.indices) value = addExact(value, mulExact(model.cost[column], values[column]))
    value
} catch (_: ArithmeticException) {
    null
}

private fun independentlyRound(value: BigFraction, upper: Boolean, strict: Boolean): BigFraction {
    val quotient = value.num / value.den
    val floor = if (value.num < BigInteger.ZERO && value.num % value.den != BigInteger.ZERO) {
        quotient - BigInteger.ONE
    } else {
        quotient
    }
    val integral = value.den == BigInteger.ONE
    val result = if (upper) {
        if (strict && integral) floor - BigInteger.ONE else floor
    } else {
        val ceiling = if (integral) floor else floor + BigInteger.ONE
        if (strict && integral) ceiling + BigInteger.ONE else ceiling
    }
    return BigFraction.of(result, BigInteger.ONE)
}
