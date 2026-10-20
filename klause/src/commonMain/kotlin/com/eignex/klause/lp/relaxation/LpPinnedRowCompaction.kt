package com.eignex.klause.lp.relaxation

import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpEntry
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.ExactLpPremise
import com.eignex.klause.lp.engine.ExactLpPremises
import com.eignex.klause.lp.engine.ExactLpRow
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpProjectionMeter
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.magnitudeBitLength

internal fun LpRelaxation.compactPinnedRows(
    session: PropagationSession,
    cancellation: Cancellation,
    onDecline: ((String) -> Unit)? = null,
    onWork: (Long) -> Unit,
): LpRelaxation? {
    val state = model.exactState ?: return null
    val source = state.model
    if (source.n !in 0..10000 || source.m !in 0..10000) return null
    val work = PinnedRowArithmetic(cancellation, onWork)
    return try {
        work.step(source.numVars.toLong() + 1L)
        for (column in 0 until source.numVars) {
            if (!work.read(source.objective.cost(column).value).isZero) return null
        }
        var entries = 0L
        for (column in 0 until source.n) {
            work.step(1L)
            entries += source.columnEntries(column).size
            if (entries > 65536L) return null
        }
        work.step(12L * source.n + 24L * source.m + 16L * entries)
        val fixed = arrayOfNulls<BigFraction>(source.n)
        for (column in fixed.indices) {
            val bounds = source.column(column).bounds
            bounds.lower?.let { work.read(it.number.value) }
            bounds.upper?.let { work.read(it.number.value) }
            if (bounds.fixed) fixed[column] = checkNotNull(bounds.lower).number.value
        }
        val rhs = MutableList(source.m) { work.read(source.rhs(it).value) }
        val rows = Array(source.m) { ArrayList<PinnedRowTerm>() }
        for (column in 0 until source.n) {
            for (entry in source.columnEntries(column)) {
                work.step(1L)
                if (!state.rows.row(entry.row).active) continue
                val coefficient = work.read(entry.number.value)
                if (coefficient.isZero) continue
                val value = fixed[column]
                if (value == null) rows[entry.row].add(PinnedRowTerm(column, coefficient))
                else rhs[entry.row] = work.subtractProduct(rhs[entry.row], coefficient, value)
            }
        }
        val groups = LinkedHashMap<List<PinnedRowTerm>, PinnedRowGroup>()
        var premiseEntries = 0L
        for (row in rows.indices) {
            work.step(rows[row].size.toLong() + 4L)
            if (!state.rows.row(row).active) continue
            val slack = source.column(source.n + row).bounds
            var lower = slack.upper?.let { work.side(work.subtract(rhs[row], it.number.value), it.strict) }
            var upper = slack.lower?.let { work.side(work.subtract(rhs[row], it.number.value), it.strict) }
            if (lower == null && upper == null) continue
            val reversed = rows[row].firstOrNull()?.value?.let { work.compare(it, BigFraction.ZERO) < 0 } == true
            val terms = rows[row].map { if (reversed) it.copy(value = work.negate(it.value)) else it }
            if (reversed) {
                val previousLower = lower
                lower = upper?.let { work.side(work.negate(it.number.value), it.strict) }
                upper = previousLower?.let { work.side(work.negate(it.number.value), it.strict) }
            }
            val group = groups.getOrPut(terms) { PinnedRowGroup(terms) }
            group.lower = work.stronger(group.lower, lower, upper = false)
            group.upper = work.stronger(group.upper, upper, upper = true)
            if (lower != null && group.lower === lower) group.lowerRow = row
            if (upper != null && group.upper === upper) group.upperRow = row
            val metadata = source.row(row)
            group.global = group.global && metadata.global
            metadata.premises?.let { premises ->
                premiseEntries += premises.size
                if (premiseEntries > 65536L) return null
                work.step(premises.size + 1L)
                group.bounds.addAll(premises.boundEntries())
                group.literals.addAll(premises.literalEntries())
            }
        }
        val matrix = List(source.n) { ArrayList<ExactLpEntry>() }
        val columns = MutableList(source.n) { source.column(it) }
        val compactRows = ArrayList<ExactLpRow>()
        val compactRhs = ArrayList<ExactLpNumber>()
        val sourceRows = ArrayList<IntArray>()
        for (group in groups.values) {
            work.step(8L + group.terms.size + group.bounds.size + group.literals.size)
            val lower = group.lower
            val upper = group.upper
            val inconsistent = lower != null && upper != null && work.compare(lower.number.value,
                upper.number.value).let { it > 0 || (it == 0 && (lower.strict || upper.strict)) }
            val zeroFits = group.terms.isEmpty() && !inconsistent && (lower == null || work.compare(BigFraction.ZERO,
                lower.number.value).let { it > 0 || (it == 0 && !lower.strict) }) &&
                (upper == null || work.compare(BigFraction.ZERO, upper.number.value)
                    .let { it < 0 || (it == 0 && !upper.strict) })
            if (group.terms.isEmpty() && zeroFits) continue
            val index = compactRows.size
            val target = if (inconsistent) BigFraction.MINUS_ONE else
                upper?.number?.value ?: work.negate(checkNotNull(lower).number.value)
            if (!inconsistent) for (term in group.terms) {
                val value = if (upper == null) work.negate(term.value) else term.value
                matrix[term.column].add(ExactLpEntry(index, ExactLpNumber.of(value)))
            }
            val strict = !inconsistent && (upper?.strict ?: checkNotNull(lower).strict)
            val slackUpper = if (!inconsistent && lower != null && upper != null) {
                work.side(work.subtract(upper.number.value, lower.number.value), lower.strict)
            } else null
            columns.add(ExactLpColumn(ExactLpBounds(work.side(BigFraction.ZERO, strict), slackUpper), integral = false))
            compactRhs.add(ExactLpNumber.of(target))
            val premises = if (group.bounds.isEmpty() && group.literals.isEmpty()) null else
                ExactLpPremises(group.bounds, group.literals.toList())
            compactRows.add(ExactLpRow(group.global, strict, premises))
            sourceRows.add(intArrayOf(group.lowerRow, group.upperRow).filter { it >= 0 }.distinct().toIntArray())
        }
        work.step(8L * (columns.size + compactRows.size) + 4L * entries)
        val objective = source.objective
        val compact = ExactLpModel(matrix, compactRhs, columns, compactRows,
            ExactLpObjective(List(columns.size) { ExactLpNumber.of(0L) }, objective.constant, objective.scale,
                objective.externalConstant, objective.sense))
        val projection = LpProjectionMeter(cancellation = cancellation)
        val working = LpExactState(compact).toWorkingModel(projection)
        work.step(projection.work)
        if (working == null) return null
        LpRelaxation(working, colVarId, colIsBool, objectiveConstant, intColOf, boolColOf,
            pinnedExplanation = LpPinnedExplanation.capture(this, sourceRows, session, cancellation,
                onDecline = onDecline, onWork = onWork),
            circuitArcs = circuitArcs, colReq = colReq, colPresentUpper = colPresentUpper,
            colRealId = colRealId, colRealSign = colRealSign, colPresence = colPresence,
            rowFactorIds = IntArray(compact.m) { -1 })
    } catch (_: PinnedRowStop) {
        null
    }
}

private data class PinnedRowTerm(val column: Int, val value: BigFraction)

private class PinnedRowGroup(val terms: List<PinnedRowTerm>) {
    var lowerRow = -1
    var upperRow = -1
    var lower: ExactLpSide? = null
    var upper: ExactLpSide? = null
    var global = true
    val bounds = ArrayList<ExactLpPremise>()
    val literals = LinkedHashSet<Int>()
}

private class PinnedRowStop : RuntimeException()

private class PinnedRowArithmetic(private val stop: Cancellation, private val onWork: (Long) -> Unit) {
    fun step(units: Long) {
        onWork(units)
        stop.charge(units)
        if (stop()) throw PinnedRowStop()
    }

    fun read(value: BigFraction): BigFraction {
        val bits = maxOf(value.num.magnitudeBitLength(), value.den.magnitudeBitLength())
        step(1L + bits / 64L)
        if (bits > 4096) throw PinnedRowStop()
        return value
    }

    private fun preflight(a: BigFraction, b: BigFraction) {
        val bits = maxOf(read(a).num.magnitudeBitLength(), a.den.magnitudeBitLength()).toLong() +
            maxOf(read(b).num.magnitudeBitLength(), b.den.magnitudeBitLength()) + 2L
        val words = bits / 64L + 1L
        step(bits + words * words)
    }

    fun negate(value: BigFraction): BigFraction = read(read(value).negated())
    fun subtract(a: BigFraction, b: BigFraction): BigFraction {
        preflight(a, b)
        return read(a - b)
    }

    fun subtractProduct(a: BigFraction, b: BigFraction, c: BigFraction): BigFraction {
        preflight(b, c)
        return subtract(a, read(b * c))
    }

    fun compare(a: BigFraction, b: BigFraction): Int {
        preflight(a, b)
        return a.compareTo(b)
    }

    fun side(value: BigFraction, strict: Boolean): ExactLpSide = ExactLpSide(ExactLpNumber.of(read(value)), strict)

    fun stronger(first: ExactLpSide?, second: ExactLpSide?, upper: Boolean): ExactLpSide? {
        if (first == null || second == null) return first ?: second
        val order = compare(first.number.value, second.number.value)
        return when {
            order == 0 -> if (second.strict && !first.strict) second else first
            (order < 0) == upper -> first
            else -> second
        }
    }
}
