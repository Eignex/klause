package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction

internal fun LpModel.hasStrictSides(meter: RefinementMeter): Boolean {
    val previousStage = meter.diagnostic?.stage
    meter.diagnostic?.stage = LpRefinementStage.STRICT_ELIGIBILITY
    if (numVars > meter.limits.maxCoordinates) meter.stop(LpRefinementDecline.DIMENSION)
    meter.charge(numVars.toLong() + m)
    val source = requireNotNull(exactState).model
    val strict = (0 until numVars).any { column ->
        val bounds = source.column(column).bounds
        bounds.lower?.strict == true || bounds.upper?.strict == true
    } || (0 until m).any { source.row(it).strict }
    previousStage?.let { meter.diagnostic?.stage = it }
    return strict
}

// Substituting logical sides keeps the margin auxiliary in structural source coordinates.
internal class StrictFeasibility(private val source: RefinementAuthority) {
    private val meter = source.meter
    private val sourceRows: IntArray
    private val sourceSigns: IntArray
    val marginColumn = source.source.n
    val model: ExactLpModel

    init {
        val count = source.source.n
        meter.charge(source.bounds.size.toLong() + source.source.m, source.source.m * 4L)
        val rowSizes = IntArray(source.source.m)
        var structuralEntries = 0L
        for (column in 0 until count) for ((row, _) in source.matrix[column]) {
            meter.charge()
            rowSizes[row]++
            structuralEntries++
        }
        var strictCount = 0
        var entries = structuralEntries
        for (column in source.bounds.indices) {
            val bounds = source.bounds[column]
            val sides = (if (bounds.lower?.strict == true) 1 else 0) +
                (if (bounds.upper?.strict == true) 1 else 0)
            strictCount += sides
            entries += sides * if (column < count) 2L else rowSizes[column - count] + 1L
        }
        val rows = source.source.m.toLong() + strictCount
        if (count.toLong() + 1L + rows > meter.limits.maxCoordinates ||
            entries + rows > meter.limits.maxEntries
        ) {
            meter.stop(LpRefinementDecline.DIMENSION)
        }
        for (row in 0 until source.source.m) {
            val lower = source.bounds[source.source.n + row].lower
            if (source.source.row(row).strict && lower?.strict != true &&
                lower?.number?.value?.let { it > BigFraction.ZERO } != true
            ) {
                meter.stop(LpRefinementDecline.AUTHORITY)
            }
        }
        meter.charge(count.toLong() + rows + entries,
            128L * (count + 1L + rows) + 32L * (entries + structuralEntries) + source.source.m * 32L)
        val rowTerms = Array(source.source.m) { ArrayList<Pair<Int, BigFraction>>(rowSizes[it]) }
        val matrix = List(count + 1) { column ->
            if (column < count) {
                source.matrix[column].map { (row, value) ->
                    rowTerms[row].add(column to value)
                    ExactLpEntry(row, ExactLpNumber.of(value))
                }.toMutableList()
            } else {
                ArrayList<ExactLpEntry>()
            }
        }
        val rhs = source.rhs.map(ExactLpNumber::of).toMutableList()
        sourceRows = IntArray(rows.toInt()) { if (it < source.source.m) it else -1 }
        sourceSigns = IntArray(rows.toInt()) { 1 }
        fun appendMargin(terms: List<Pair<Int, BigFraction>>, bound: BigFraction, original: Int, sign: Int) {
            val row = rhs.size
            for ((column, value) in terms) {
                val coefficient = if (sign > 0) value else meter.number(value.negated())
                matrix[column] += ExactLpEntry(row, ExactLpNumber.of(coefficient))
            }
            matrix[count] += ExactLpEntry(row, ExactLpNumber.of(1L))
            rhs += ExactLpNumber.of(meter.number(bound))
            sourceRows[row] = original
            sourceSigns[row] = sign
        }
        for (column in source.bounds.indices) {
            for (upper in listOf(false, true)) {
                val side = (if (upper) source.bounds[column].upper else source.bounds[column].lower)
                    ?.takeIf { it.strict } ?: continue
                if (column < count) {
                    appendMargin(listOf(column to BigFraction.ONE),
                        if (upper) side.number.value else meter.number(side.number.value.negated()),
                        -1, if (upper) 1 else -1)
                } else {
                    val original = column - count
                    val bound = if (upper) {
                        meter.add(side.number.value, meter.number(source.rhs[original].negated()))
                    } else {
                        meter.add(source.rhs[original], meter.number(side.number.value.negated()))
                    }
                    appendMargin(rowTerms[original], bound, original, if (upper) -1 else 1)
                }
            }
        }
        val zero = ExactLpSide(ExactLpNumber.of(0L))
        val columns = List(count + 1 + rows.toInt()) { column ->
            val bounds = when {
                column < count || column in count + 1 until count + 1 + source.source.m -> {
                    val original = if (column < count) column else column - 1
                    val bounds = source.bounds[original]
                    ExactLpBounds(bounds.lower?.copy(strict = false), bounds.upper?.copy(strict = false))
                }

                column == count -> ExactLpBounds(zero, ExactLpSide(ExactLpNumber.of(1L)))

                else -> ExactLpBounds(zero)
            }
            ExactLpColumn(bounds, integral = false)
        }
        model = ExactLpModel(
            matrix,
            rhs,
            columns,
            List(rows.toInt()) { ExactLpRow() },
            ExactLpObjective(List(columns.size) { ExactLpNumber.of(if (it == count) -1L else 0L) }),
        )
    }

    fun positive(point: ExactLpWitness?): Boolean = point != null &&
        point.primal.size == model.n && point.primal[marginColumn].signum() > 0

    fun sourcePoint(point: ExactLpWitness): List<BigFraction> {
        meter.charge(source.source.n.toLong(), source.source.n * 8L)
        return source.sourcePoint(point.primal)
    }

    fun sourceRay(dual: List<BigFraction>): List<BigFraction>? {
        if (dual.size != model.m) return null
        meter.charge(source.source.m.toLong(), source.source.m * 8L)
        val ray = MutableList(source.source.m) { BigFraction.ZERO }
        for (row in dual.indices) {
            meter.charge()
            val original = sourceRows[row]
            if (original < 0 || dual[row].isZero) continue
            val value = if (sourceSigns[row] > 0) dual[row] else meter.number(dual[row].negated())
            ray[original] = meter.add(ray[original], value)
        }
        return ray
    }
}
