package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.CutExpression
import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpRowPremises
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.relaxation.CutSourceMap
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.lp.relaxation.withTidy
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.addExact
import com.eignex.klause.util.mulExact
import com.eignex.klause.util.subExact
import com.ionspin.kotlin.bignum.integer.BigInteger

internal data class RelaxationTidyConfig(
    val enabled: Boolean = false,
    val cancellation: Cancellation = Cancellation.Never,
    val assumptions: Set<String> = emptySet(),
    val cutoff: CutPremise.ObjectiveCutoff? = null,
)

internal sealed interface RelaxationTidyResult {
    data class Applied(val relaxation: LpRelaxation, val derivation: RelaxationTidyDerivation) : RelaxationTidyResult
    data class Declined(val reason: RelaxationTidyDecline, val stats: RelaxationTidyStats) : RelaxationTidyResult
}

/** Root-only, proof-mapped cleanup of relaxation-generated rows. Structural columns are never removed. */
internal object RelaxationTidy {
    fun apply(
        relaxation: LpRelaxation,
        scope: RelaxationTidyScope,
        config: RelaxationTidyConfig,
    ): RelaxationTidyResult {
        val counts = Counts()
        if (!config.enabled) return counts.declineResult(RelaxationTidyDecline.DISABLED)
        if (!scope.atRoot) return counts.declineResult(RelaxationTidyDecline.NOT_ROOT)
        if (config.cancellation()) return counts.declineResult(RelaxationTidyDecline.CANCELLED)
        val source = relaxation.model
        if (source.exactState != null) return counts.declineResult(RelaxationTidyDecline.EXACT_STATE)
        if (source.doubleView != null || source.hasContinuous || source.colContinuous.any { it }) {
            return counts.declineResult(RelaxationTidyDecline.CONTINUOUS_MODEL)
        }
        if (relaxation.gatedRows.isNotEmpty()) return counts.declineResult(RelaxationTidyDecline.GATED_ROWS)
        val sources = relaxation.sourceMap
            ?: return counts.declineResult(RelaxationTidyDecline.UNSUPPORTED_SOURCE_MAP)
        if (sources.model !== scope.root || sources.epoch != scope.epoch || sources.assumptions != scope.assumptions) {
            return counts.declineResult(RelaxationTidyDecline.UNSUPPORTED_SOURCE_MAP)
        }

        val rows = sourceRows(source)
        substituteFixed(source, sources, scope, rows, counts, config.cancellation)
        if (config.cancellation()) return counts.declineResult(RelaxationTidyDecline.CANCELLED)

        val removed = ArrayList<RelaxationTidyRemovedRow>()
        classifyEmpty(rows, removed, counts)
        val lower = LongArray(source.n)
        val upper = source.upper.copyOfRange(0, source.n)
        val hasUpper = source.hasUpper.copyOfRange(0, source.n)
        removeInitiallyRedundant(rows, lower, upper, hasUpper, removed, counts)
        selectParallelSides(rows, removed, counts)

        val bounds = ArrayList<RelaxationTidyBound>()
        canonicalizeSingletons(source, sources, rows, lower, upper, hasUpper, bounds, counts)
        if (config.cancellation()) return counts.declineResult(RelaxationTidyDecline.CANCELLED)

        removeDerivedRedundant(source, rows, lower, upper, hasUpper, bounds, removed, counts)
        if (config.cancellation()) return counts.declineResult(RelaxationTidyDecline.CANCELLED)

        val retained = rows.filter { !it.removed }.sortedBy { it.sourceRow }
        val output = buildModel(
            source,
            retained,
        ) ?: return counts.declineResult(RelaxationTidyDecline.ARITHMETIC_OVERFLOW)
        val rowMaps = retained.mapIndexed { outputRow, row ->
            RelaxationTidyRowMap(
                outputRow,
                row.sourceRow,
                row.sourceMultiplier,
                row.fixings.toList(),
                row.rounding,
            )
        }
        val derivation = RelaxationTidyDerivation(
            source,
            output,
            scope,
            rowMaps,
            removed,
            bounds,
            sources.columns,
            counts.stats(),
        )
        if (!derivation.validate() || config.cancellation()) {
            return counts.declineResult(
                if (config.cancellation()) {
                    RelaxationTidyDecline.CANCELLED
                } else {
                    RelaxationTidyDecline.UNSUPPORTED_SOURCE_MAP
                },
            )
        }
        val rowSources = IntArray(retained.size) { retained[it].sourceRow }
        val mapped = sources.remapRows(rowSources)
        return RelaxationTidyResult.Applied(relaxation.withTidy(output, mapped, derivation), derivation)
    }
}

private class WorkingRow(
    val sourceRow: Int,
    var coefficients: MutableMap<Int, Long>,
    var rhs: Long,
    var flippedRhs: Long,
    var relation: Relation,
    var strict: Boolean,
    val global: Boolean,
    val premises: LpRowPremises?,
) {
    var sourceMultiplier: BigFraction = BigFraction.ONE
    val fixings = ArrayList<RelaxationTidyFixing>()
    var rounding: RelaxationTidyRounding? = null
    var removed: Boolean = false
    var protectedSingleton: Boolean = false
}

private class Counts {
    private val eligible = HashMap<RelaxationTidyRule, Int>()
    private val applied = HashMap<RelaxationTidyRule, Int>()
    private val declined = HashMap<RelaxationTidyDecline, Int>()

    fun eligible(rule: RelaxationTidyRule) {
        eligible[rule] = (eligible[rule] ?: 0) + 1
    }
    fun applied(rule: RelaxationTidyRule) {
        applied[rule] = (applied[rule] ?: 0) + 1
    }
    fun recordDecline(reason: RelaxationTidyDecline) {
        declined[reason] = (declined[reason] ?: 0) + 1
    }
    fun stats(): RelaxationTidyStats = RelaxationTidyStats(eligible.toMap(), applied.toMap(), declined.toMap())
    fun declineResult(reason: RelaxationTidyDecline): RelaxationTidyResult.Declined {
        recordDecline(reason)
        return RelaxationTidyResult.Declined(reason, stats())
    }
}

private fun sourceRows(model: LpModel): MutableList<WorkingRow> {
    val coefficients = Array(model.m) { linkedMapOf<Int, Long>() }
    for (column in 0 until model.n) {
        model.forEachInColumn(column) { row, value -> coefficients[row][column] = value }
    }
    return MutableList(model.m) { row ->
        WorkingRow(
            row,
            coefficients[row],
            model.rhs[row],
            model.flippedRhs[row],
            if (model.hasUpper[model.slackCol(row)]) Relation.EQ else Relation.LE,
            model.rowStrict[row],
            model.rowGlobal[row],
            model.rowPremises[row],
        )
    }
}

private fun substituteFixed(
    model: LpModel,
    sources: CutSourceMap,
    scope: RelaxationTidyScope,
    rows: MutableList<WorkingRow>,
    counts: Counts,
    cancellation: Cancellation,
) {
    for (row in rows) {
        if (cancellation()) return
        val candidates = row.coefficients.keys.filter { column ->
            model.hasUpper[column] && model.upper[column] == 0L
        }
        if (candidates.isEmpty()) continue
        val replacements = ArrayList<RelaxationTidyFixing>()
        var flipped = row.flippedRhs
        var overflow = false
        for (column in candidates) {
            counts.eligible(RelaxationTidyRule.FIXED_SUBSTITUTION)
            val sourceColumn = sources.column(column)
            if (sourceColumn == null) {
                counts.recordDecline(RelaxationTidyDecline.UNSUPPORTED_COLUMN)
                continue
            }
            val value = model.loShift[column]
            val expression = sourceColumn.expression()
            val lower = CutPremise.Bound(expression, false, BigFraction.ofLong(value))
            val upper = CutPremise.Bound(expression, true, BigFraction.ofLong(value))
            val allowed = if (scope.assumptions.isEmpty() && scope.cutoff == null) {
                sources.isGlobal(lower) && sources.isGlobal(upper)
            } else {
                sources.isActive(lower) && sources.isActive(upper)
            }
            if (!allowed) {
                counts.recordDecline(RelaxationTidyDecline.UNSUPPORTED_SOURCE_MAP)
                continue
            }
            val coefficient = requireNotNull(row.coefficients[column])
            try {
                flipped = subExact(flipped, mulExact(coefficient, value))
            } catch (_: ArithmeticException) {
                overflow = true
                break
            }
            replacements.add(RelaxationTidyFixing(column, coefficient, value, lower, upper))
        }
        if (overflow) {
            counts.recordDecline(RelaxationTidyDecline.ARITHMETIC_OVERFLOW)
            continue
        }
        for (fixing in replacements) row.coefficients.remove(fixing.column)
        if (replacements.isNotEmpty()) {
            row.flippedRhs = flipped
            row.fixings.addAll(replacements)
            repeat(replacements.size) { counts.applied(RelaxationTidyRule.FIXED_SUBSTITUTION) }
        }
    }
}

private fun classifyEmpty(
    rows: MutableList<WorkingRow>,
    removed: MutableList<RelaxationTidyRemovedRow>,
    counts: Counts,
) {
    for (row in rows) {
        if (row.coefficients.isNotEmpty()) continue
        counts.eligible(RelaxationTidyRule.EMPTY_ROW)
        val tautology = when (row.relation) {
            Relation.EQ -> !row.strict && row.rhs == 0L
            Relation.LE -> if (row.strict) 0L < row.rhs else 0L <= row.rhs
            Relation.GE -> error("normalized tidy rows cannot be GE")
        }
        if (tautology) {
            row.removed = true
            removed.add(
                RelaxationTidyRemovedRow(
                    row.sourceRow,
                    RelaxationTidyRemovalReason.EMPTY,
                    fixings = row.fixings.toList(),
                ),
            )
        }
        counts.applied(RelaxationTidyRule.EMPTY_ROW)
    }
}

private fun removeInitiallyRedundant(
    rows: MutableList<WorkingRow>,
    lower: LongArray,
    upper: LongArray,
    hasUpper: BooleanArray,
    removed: MutableList<RelaxationTidyRemovedRow>,
    counts: Counts,
) {
    for (row in rows) {
        if (row.removed || row.coefficients.size <= 1) continue
        counts.eligible(RelaxationTidyRule.REDUNDANT_ROW)
        when (val redundant = boundImplied(row, lower, upper, hasUpper)) {
            ActivityResult.TRUE -> {
                row.removed = true
                removed.add(
                    RelaxationTidyRemovedRow(
                        row.sourceRow,
                        RelaxationTidyRemovalReason.REDUNDANT,
                        fixings = row.fixings.toList(),
                    ),
                )
                counts.applied(RelaxationTidyRule.REDUNDANT_ROW)
            }

            ActivityResult.OVERFLOW -> counts.recordDecline(RelaxationTidyDecline.ARITHMETIC_OVERFLOW)

            ActivityResult.UNBOUNDED -> counts.recordDecline(RelaxationTidyDecline.UNBOUNDED_ACTIVITY)

            ActivityResult.FALSE -> Unit
        }
    }
}

private fun canonicalizeSingletons(
    model: LpModel,
    sources: CutSourceMap,
    rows: MutableList<WorkingRow>,
    lower: LongArray,
    upper: LongArray,
    hasUpper: BooleanArray,
    bounds: MutableList<RelaxationTidyBound>,
    counts: Counts,
) {
    for (row in rows) {
        if (row.removed || row.coefficients.size != 1) continue
        counts.eligible(RelaxationTidyRule.SINGLETON_BOUND)
        val (column, coefficient) = row.coefficients.entries.single()
        if (coefficient == Long.MIN_VALUE) {
            counts.recordDecline(RelaxationTidyDecline.ARITHMETIC_OVERFLOW)
            continue
        }
        val sourceColumn = sources.column(column)
        if (sourceColumn == null || sourceColumn.source.kind !in setOf(CutSourceKind.INTEGER, CutSourceKind.BOOLEAN)) {
            counts.recordDecline(RelaxationTidyDecline.UNSUPPORTED_COLUMN)
            continue
        }
        if (row.relation == Relation.EQ && row.strict) {
            counts.recordDecline(RelaxationTidyDecline.UNSUPPORTED_STRICT_EQUALITY)
            continue
        }
        val naturalThreshold = BigFraction.ofLong(row.flippedRhs) * BigFraction.ofLong(coefficient).reciprocal()
        val mapped = mapSourceThreshold(sourceColumn.scale, sourceColumn.offset, naturalThreshold) ?: run {
            counts.recordDecline(RelaxationTidyDecline.UNSUPPORTED_COLUMN)
            continue
        }
        if (row.relation == Relation.EQ) {
            if (mapped.den != BigInteger.ONE) {
                row.coefficients.clear()
                row.rhs = -1L
                row.flippedRhs = -1L
                row.relation = Relation.LE
                row.sourceMultiplier = BigFraction.ZERO
                row.rounding = RelaxationTidyRounding(
                    mapped,
                    null,
                    null,
                    null,
                    false,
                    infeasibleEquality = true,
                )
                row.protectedSingleton = true
                counts.applied(RelaxationTidyRule.SINGLETON_BOUND)
                continue
            }
            val sourceValue = mapped.num.longOrNull() ?: run {
                counts.recordDecline(RelaxationTidyDecline.ARITHMETIC_OVERFLOW)
                continue
            }
            val columnValue = sourceColumnValue(sourceColumn.scale, sourceColumn.offset, sourceValue) ?: run {
                counts.recordDecline(RelaxationTidyDecline.ARITHMETIC_OVERFLOW)
                continue
            }
            val shifted = try {
                subExact(columnValue, model.loShift[column])
            } catch (_: ArithmeticException) {
                counts.recordDecline(RelaxationTidyDecline.ARITHMETIC_OVERFLOW)
                continue
            }
            row.coefficients = linkedMapOf(column to 1L)
            row.rhs = shifted
            row.flippedRhs = columnValue
            row.sourceMultiplier = BigFraction.ofLong(coefficient).reciprocal()
            row.rounding = null
            row.protectedSingleton = true
            lower[column] = maxOf(lower[column], shifted)
            if (!hasUpper[column] || shifted < upper[column]) {
                upper[column] = shifted
                hasUpper[column] = true
            }
            bounds.addAll(
                listOf(
                    tidyBound(
                        row,
                        column,
                        false,
                        sourceColumn.scale.signum() < 0,
                        mapped,
                        columnValue,
                    ),
                    tidyBound(
                        row,
                        column,
                        true,
                        sourceColumn.scale.signum() > 0,
                        mapped,
                        columnValue,
                    ),
                ),
            )
            counts.applied(RelaxationTidyRule.SINGLETON_BOUND)
            continue
        }

        val columnUpper = coefficient > 0L
        val sourceUpper = if (sourceColumn.scale.signum() > 0) columnUpper else !columnUpper
        val originalStrict = row.strict
        val roundedSource = roundInteger(mapped, sourceUpper, originalStrict)
        val sourceValue = roundedSource.longOrNull() ?: run {
            counts.recordDecline(RelaxationTidyDecline.ARITHMETIC_OVERFLOW)
            continue
        }
        val columnValue = sourceColumnValue(sourceColumn.scale, sourceColumn.offset, sourceValue) ?: run {
            counts.recordDecline(RelaxationTidyDecline.ARITHMETIC_OVERFLOW)
            continue
        }
        val shifted = try {
            subExact(columnValue, model.loShift[column])
        } catch (_: ArithmeticException) {
            counts.recordDecline(RelaxationTidyDecline.ARITHMETIC_OVERFLOW)
            continue
        }
        val outputUpper = columnUpper
        val outputRhs = if (outputUpper) {
            shifted
        } else {
            try {
                subExact(0L, shifted)
            } catch (_: ArithmeticException) {
                counts.recordDecline(RelaxationTidyDecline.ARITHMETIC_OVERFLOW)
                continue
            }
        }
        val outputNatural = if (outputUpper) {
            columnValue
        } else {
            try {
                subExact(
                    0L,
                    columnValue,
                )
            } catch (_: ArithmeticException) {
                counts.recordDecline(RelaxationTidyDecline.ARITHMETIC_OVERFLOW)
                continue
            }
        }
        row.coefficients = linkedMapOf(column to if (outputUpper) 1L else -1L)
        row.rhs = outputRhs
        row.flippedRhs = outputNatural
        row.relation = Relation.LE
        row.sourceMultiplier = BigFraction.ofLong(kotlin.math.abs(coefficient)).reciprocal()
        row.rounding = if (roundedSource != mapped || originalStrict) {
            RelaxationTidyRounding(mapped, roundedSource, sourceUpper, outputUpper, originalStrict)
        } else {
            null
        }
        row.strict = false
        row.protectedSingleton = true
        if (outputUpper) {
            if (!hasUpper[column] || shifted < upper[column]) {
                upper[column] = shifted
                hasUpper[column] = true
            }
        } else if (shifted > lower[column]) {
            lower[column] = shifted
        }
        bounds.add(
            tidyBound(
                row,
                column,
                sourceUpper,
                outputUpper,
                roundedSource,
                columnValue,
                roundedSource != mapped,
            ),
        )
        counts.applied(RelaxationTidyRule.SINGLETON_BOUND)
    }
}

private fun tidyBound(
    row: WorkingRow,
    column: Int,
    sourceUpper: Boolean,
    columnUpper: Boolean = sourceUpper,
    sourceValue: BigFraction,
    columnValue: Long,
    rounded: Boolean = false,
): RelaxationTidyBound {
    val premises = buildSet<CutPremise> {
        row.premises?.let { premise ->
            for (i in premise.vars.indices) {
                val source = CutSource(CutSourceKind.INTEGER, premise.vars[i])
                val expression = CutExpression(mapOf(source to BigFraction.ONE))
                add(CutPremise.Bound(expression, premise.isUpper[i], BigFraction.ofLong(premise.thresholds[i])))
            }
            for (literal in premise.boolLits) add(CutPremise.Literal(literal))
        }
        for (fixing in row.fixings) {
            add(fixing.lower)
            add(fixing.upper)
        }
    }
    return RelaxationTidyBound(
        column,
        sourceUpper,
        columnUpper,
        sourceValue,
        columnValue,
        row.sourceRow,
        rounded,
        row.global,
        premises,
    )
}

private fun removeDerivedRedundant(
    model: LpModel,
    rows: MutableList<WorkingRow>,
    lower: LongArray,
    upper: LongArray,
    hasUpper: BooleanArray,
    bounds: List<RelaxationTidyBound>,
    removed: MutableList<RelaxationTidyRemovedRow>,
    counts: Counts,
) {
    if (bounds.isEmpty()) return
    for (row in rows) {
        if (row.removed || row.protectedSingleton || row.coefficients.isEmpty()) continue
        counts.eligible(RelaxationTidyRule.REDUNDANT_ROW)
        when (val redundant = boundImplied(row, lower, upper, hasUpper)) {
            ActivityResult.TRUE -> {
                row.removed = true
                removed.add(
                    RelaxationTidyRemovedRow(
                        row.sourceRow,
                        RelaxationTidyRemovalReason.REDUNDANT,
                        boundUses = derivedBoundUses(model, row, lower, upper, bounds),
                        fixings = row.fixings.toList(),
                    ),
                )
                counts.applied(RelaxationTidyRule.REDUNDANT_ROW)
            }

            ActivityResult.OVERFLOW -> counts.recordDecline(RelaxationTidyDecline.ARITHMETIC_OVERFLOW)

            ActivityResult.UNBOUNDED -> counts.recordDecline(RelaxationTidyDecline.UNBOUNDED_ACTIVITY)

            ActivityResult.FALSE -> Unit
        }
    }
}

private fun derivedBoundUses(
    model: LpModel,
    row: WorkingRow,
    lower: LongArray,
    upper: LongArray,
    bounds: List<RelaxationTidyBound>,
): List<RelaxationTidyBoundUse> {
    val uses = ArrayList<RelaxationTidyBoundUse>()
    for ((column, coefficient) in row.coefficients) {
        val sides = when (row.relation) {
            Relation.LE -> listOf(coefficient > 0L)
            Relation.EQ -> listOf(false, true)
            Relation.GE -> error("normalized tidy rows cannot be GE")
        }
        for (upperSide in sides) {
            val shifted = if (upperSide) upper[column] else lower[column]
            val absolute = try {
                addExact(model.loShift[column], shifted)
            } catch (_: ArithmeticException) {
                continue
            }
            val proof = bounds.firstOrNull {
                it.column == column && it.columnUpper == upperSide && it.columnValue == absolute
            } ?: continue
            uses.add(RelaxationTidyBoundUse(column, upperSide, coefficient, proof.sourceRow))
        }
    }
    return uses
}

private enum class ActivityResult { TRUE, FALSE, OVERFLOW, UNBOUNDED }

private fun boundImplied(row: WorkingRow, lower: LongArray, upper: LongArray, hasUpper: BooleanArray): ActivityResult {
    var min = 0L
    var max = 0L
    try {
        for ((column, coefficient) in row.coefficients) {
            if (coefficient > 0L) {
                min = addExact(min, mulExact(coefficient, lower[column]))
                if (!hasUpper[column]) return ActivityResult.UNBOUNDED
                max = addExact(max, mulExact(coefficient, upper[column]))
            } else {
                if (!hasUpper[column]) return ActivityResult.UNBOUNDED
                min = addExact(min, mulExact(coefficient, upper[column]))
                max = addExact(max, mulExact(coefficient, lower[column]))
            }
        }
    } catch (_: ArithmeticException) {
        return ActivityResult.OVERFLOW
    }
    return when (row.relation) {
        Relation.EQ -> if (!row.strict && min == row.rhs &&
            max == row.rhs
        ) {
            ActivityResult.TRUE
        } else {
            ActivityResult.FALSE
        }

        Relation.LE -> if (if (row.strict) max < row.rhs else max <= row.rhs) {
            ActivityResult.TRUE
        } else {
            ActivityResult.FALSE
        }

        Relation.GE -> error("normalized tidy rows cannot be GE")
    }
}

private data class ParallelKey(val columns: List<Int>, val coefficients: List<Long>)
private data class ParallelCandidate(val row: WorkingRow, val upper: Boolean, val threshold: BigFraction)

private fun selectParallelSides(
    rows: MutableList<WorkingRow>,
    removed: MutableList<RelaxationTidyRemovedRow>,
    counts: Counts,
) {
    val groups = LinkedHashMap<ParallelKey, MutableList<ParallelCandidate>>()
    for (row in rows) {
        if (row.removed || row.relation != Relation.LE || row.coefficients.isEmpty()) continue
        val normalized = normalizeParallel(row.coefficients)
        if (normalized == null) {
            counts.recordDecline(RelaxationTidyDecline.ARITHMETIC_OVERFLOW)
            continue
        }
        counts.eligible(RelaxationTidyRule.PARALLEL_SIDE)
        val (key, factor) = normalized
        groups.getOrPut(key) { ArrayList() }.add(
            ParallelCandidate(row, factor > 0L, BigFraction.ofLong(row.rhs) * BigFraction.ofLong(factor).reciprocal()),
        )
    }
    for (candidates in groups.values) {
        for (upperSide in listOf(true, false)) {
            val side = candidates.filter { it.upper == upperSide }
            if (side.size <= 1) continue
            val winner = side.reduce { best, next ->
                val comparison = next.threshold.compareTo(best.threshold)
                val stronger = if (upperSide) comparison < 0 else comparison > 0
                if (stronger || (comparison == 0 && next.row.strict && !best.row.strict)) next else best
            }
            for (candidate in side) {
                if (candidate === winner) continue
                candidate.row.removed = true
                removed.add(
                    RelaxationTidyRemovedRow(
                        candidate.row.sourceRow,
                        RelaxationTidyRemovalReason.PARALLEL,
                        winner.row.sourceRow,
                        fixings = candidate.row.fixings.toList(),
                    ),
                )
                counts.applied(RelaxationTidyRule.PARALLEL_SIDE)
            }
        }
    }
}

private fun normalizeParallel(coefficients: Map<Int, Long>): Pair<ParallelKey, Long>? {
    val entries = coefficients.entries.sortedBy { it.key }
    if (entries.any { it.value == Long.MIN_VALUE }) return null
    var gcd = 0L
    for ((_, value) in entries) gcd = gcd(gcd, kotlin.math.abs(value))
    if (gcd == 0L) return null
    val sign = if (entries.first().value > 0L) 1L else -1L
    val factor = if (sign > 0L) gcd else -gcd
    return ParallelKey(entries.map { it.key }, entries.map { it.value / factor }) to factor
}

private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

private fun buildModel(source: LpModel, rows: List<WorkingRow>): LpModel? = try {
    val matrixBuilder = LpBuilder()
    repeat(source.n) { matrixBuilder.addVar(0, 0) }
    for (row in rows) {
        val entries = row.coefficients.entries.sortedBy { it.key }
        matrixBuilder.addRow(
            IntArray(entries.size) { entries[it].key },
            LongArray(entries.size) { entries[it].value },
            Relation.LE,
            0,
        )
    }
    val matrix = matrixBuilder.build(Sense.MINIMIZE).csc
    val m = rows.size
    val cost = LongArray(source.n + m)
    val upper = LongArray(source.n + m)
    val hasUpper = BooleanArray(source.n + m)
    for (column in 0 until source.n) {
        cost[column] = source.cost[column]
        upper[column] = source.upper[column]
        hasUpper[column] = source.hasUpper[column]
    }
    for (row in rows.indices) hasUpper[source.n + row] = rows[row].relation == Relation.EQ
    LpModel(
        source.n,
        m,
        matrix,
        LongArray(m) { rows[it].rhs },
        cost,
        upper,
        hasUpper,
        source.loShift.copyOf(),
        source.objConstant,
        source.sense,
        source.tag.copyOf(),
        BooleanArray(m) { rows[it].global },
        BooleanArray(m) { rows[it].strict },
        Array(m) { rows[it].premises },
        LongArray(m) { rows[it].flippedRhs },
        source.probeClampedLo.copyOf(),
        source.probeClampedHi.copyOf(),
        source.colContinuous.copyOf(),
    )
} catch (_: ArithmeticException) {
    null
}

private fun mapSourceThreshold(scale: BigFraction, offset: BigFraction, columnValue: BigFraction): BigFraction? =
    if (scale.isZero) null else (columnValue - offset) * scale.reciprocal()

private fun roundInteger(value: BigFraction, upper: Boolean, strict: Boolean): BigFraction {
    val floor = value.floorInteger()
    val integral = value.den == BigInteger.ONE
    val rounded = if (upper) {
        if (strict && integral) floor - BigInteger.ONE else floor
    } else {
        val ceil = if (integral) floor else floor + BigInteger.ONE
        if (strict && integral) ceil + BigInteger.ONE else ceil
    }
    return BigFraction.of(rounded, BigInteger.ONE)
}

private fun BigFraction.floorInteger(): BigInteger {
    val quotient = num / den
    return if (num < BigInteger.ZERO && num % den != BigInteger.ZERO) quotient - BigInteger.ONE else quotient
}

private fun BigFraction.longOrNull(): Long? =
    if (den == BigInteger.ONE && num.bitLength() <= 63) num.longValue(exactRequired = true) else null

private fun BigInteger.longOrNull(): Long? = if (bitLength() <= 63) longValue(exactRequired = true) else null

private fun sourceColumnValue(scale: BigFraction, offset: BigFraction, sourceValue: Long): Long? {
    val value = scale * BigFraction.ofLong(sourceValue) + offset
    return value.longOrNull()
}
