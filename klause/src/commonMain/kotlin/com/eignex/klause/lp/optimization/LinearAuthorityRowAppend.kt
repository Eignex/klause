package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpScopedRow
import com.eignex.klause.lp.engine.LpScopedSolver
import com.eignex.klause.util.Cancellation
import com.eignex.util.numbers.magnitudeBitLength

internal class LinearAuthorityRowAppend(
    private val budget: SourceLpBudget,
    private val cancellation: Cancellation,
    private val observe: (String, Long) -> Unit,
) {
    private var work = 0L
    private var allocation = 0L

    private fun charge(units: Long, bytes: Long = 0L): Boolean {
        val funding = budget.funding
        if (cancellation() || funding?.paused == true || units > 65_536L - work ||
            bytes > 1_048_576L - allocation || funding?.availableWork?.let { units > it } == true ||
            funding?.remainingAllocation?.let { bytes > it } == true) return false
        work += units
        allocation += bytes
        budget.observe(preparation = units)
        funding?.charge(0L, bytes)
        observe("OwnerAppendInspectionWork", units)
        observe("OwnerAppendAllocation", bytes)
        return true
    }

    private fun same(left: ExactLpNumber, right: ExactLpNumber): Boolean {
        val bits = left.value.num.magnitudeBitLength().toLong() + left.value.den.magnitudeBitLength() +
            right.value.num.magnitudeBitLength() + right.value.den.magnitudeBitLength()
        return charge(1L + (bits + 63L) / 64L) && left == right
    }

    private fun same(left: ExactLpSide?, right: ExactLpSide?): Boolean {
        if (!charge(1L)) return false
        if (left == null || right == null) return left == right
        return left.premises == null && right.premises == null &&
            left.strict == right.strict && same(left.number, right.number)
    }

    private fun same(left: ExactLpColumn, right: ExactLpColumn): Boolean =
        charge(1L) && left.integral == right.integral && left.tag == right.tag &&
            same(left.origin, right.origin) && same(left.bounds.lower, right.bounds.lower) &&
            same(left.bounds.upper, right.bounds.upper)

    fun append(owner: LpScopedSolver, after: ExactLpModel): Boolean {
        val before = owner.state.baseModel
        if (owner.floatPaused || before.n != after.n || after.m <= before.m ||
            after.m - before.m > 64 || !charge(1L, 256L)) return false
        val first = before.objective
        val second = after.objective
        if (first.sense != second.sense || !same(first.constant, second.constant) ||
            !same(first.scale, second.scale) || !same(first.externalConstant, second.externalConstant)) return false
        for (column in 0 until before.numVars) {
            if (!same(before.column(column), after.column(column)) ||
                !same(first.cost(column), second.cost(column))) return false
        }
        for (row in 0 until before.m) {
            if (!charge(1L) || before.row(row).premises != null || after.row(row).premises != null ||
                before.row(row) != after.row(row) || !same(before.rhs(row), after.rhs(row))) return false
        }
        val added = after.m - before.m
        if (!charge(added.toLong(), 128L + added * 192L)) return false
        val coefficients = List(added) { ArrayList<Pair<Int, ExactLpNumber>>() }
        var entries = 0L
        for (column in 0 until before.n) {
            val old = before.columnEntries(column)
            val next = after.columnEntries(column)
            if (!charge(1L) || next.size < old.size) return false
            for (index in old.indices) {
                if (!charge(1L) || old[index].row != next[index].row ||
                    !same(old[index].number, next[index].number)) return false
            }
            for (index in old.size until next.size) {
                val entry = next[index]
                if (!charge(2L, 64L) || entry.row < before.m) return false
                coefficients[entry.row - before.m] += column to entry.number
            }
            entries += next.size
        }
        // Append stages immutable layout copies while the warm owner remains live.
        if (!charge(1L, 1024L + (after.numVars.toLong() + after.m) * 64L + entries * 64L)) return false
        val rows = (before.m until after.m).map { row ->
            LpScopedRow(row.toLong(), coefficients[row - before.m], after.rhs(row),
                after.column(after.n + row), after.row(row), second.cost(after.n + row))
        }
        val previous = owner.metrics
        val accepted = try {
            owner.pop(0, cancellation) && owner.append(emptyList(), rows, false, cancellation)
        } finally {
            val metrics = owner.metrics
            val preparation = metrics.preparationWork - previous.preparationWork
            val append = metrics.appendBasisWork - previous.appendBasisWork
            budget.observe(preparation = preparation + append)
            observe("OwnerAppendPreparationWork", preparation)
            observe("OwnerAppendBasisWork", append)
            observe("OwnerAppendUnknownWork", metrics.appendUnknownWork - previous.appendUnknownWork)
        }
        return accepted && !cancellation() && budget.funding?.paused != true
    }
}
