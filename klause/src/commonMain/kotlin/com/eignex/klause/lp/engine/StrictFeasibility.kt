package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction

internal fun LpModel.hasStrictSides(meter: RefinementMeter): Boolean {
    if (numVars > meter.limits.maxCoordinates) meter.stop(LpRefinementDecline.DIMENSION)
    meter.charge(numVars.toLong() + m)
    val source = requireNotNull(exactState).model
    return (0 until numVars).any { column ->
        val bounds = source.column(column).bounds
        bounds.lower?.strict == true || bounds.upper?.strict == true
    } || (0 until m).any { source.row(it).strict }
}

// Auxiliary structural coordinates include source logicals; source origins are restored only on export.
internal class StrictFeasibility(private val source: RefinementAuthority) {
    private val meter = source.meter
    val marginColumn = source.source.numVars
    val model: ExactLpModel

    init {
        val count = source.source.numVars
        val strictCount = source.bounds.sumOf { bounds ->
            (if (bounds.lower?.strict == true) 1 else 0) + (if (bounds.upper?.strict == true) 1 else 0)
        }
        val rows = source.source.m.toLong() + strictCount
        val entries = source.matrix.sumOf { it.size.toLong() } + 2L * strictCount
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
        meter.charge(count.toLong() + rows + entries, 128L * (count + 1L + rows) + 32L * entries)
        val matrix = List(count + 1) { column ->
            if (column < count) {
                source.matrix[column].map { (row, value) -> ExactLpEntry(row, ExactLpNumber.of(value)) }.toMutableList()
            } else {
                ArrayList<ExactLpEntry>()
            }
        }
        val rhs = source.rhs.map(ExactLpNumber::of).toMutableList()
        for (column in 0 until count) {
            for (upper in listOf(false, true)) {
                val side = (if (upper) source.bounds[column].upper else source.bounds[column].lower)
                    ?.takeIf { it.strict } ?: continue
                val row = rhs.size
                matrix[column] += ExactLpEntry(row, ExactLpNumber.of(if (upper) 1L else -1L))
                matrix[count] += ExactLpEntry(row, ExactLpNumber.of(1L))
                rhs += ExactLpNumber.of(meter.number(if (upper) side.number.value else side.number.value.negated()))
            }
        }
        val zero = ExactLpSide(ExactLpNumber.of(0L))
        val columns = List(count + 1 + rows.toInt()) { column ->
            val bounds = when {
                column < count -> source.bounds[column].let { bounds ->
                    ExactLpBounds(bounds.lower?.copy(strict = false), bounds.upper?.copy(strict = false))
                }

                column == count -> ExactLpBounds(zero, ExactLpSide(ExactLpNumber.of(1L)))

                column < count + 1 + source.source.m -> ExactLpBounds(zero, zero)

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
        return dual.take(source.source.m)
    }
}
