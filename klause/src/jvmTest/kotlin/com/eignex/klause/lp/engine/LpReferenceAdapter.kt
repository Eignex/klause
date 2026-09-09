package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalFeasibilityModel
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.simplex.exact.RationalFeasibility
import com.eignex.klause.simplex.exact.bigRationalMinimum
import com.eignex.klause.simplex.exact.bigRationalOutcome
import com.eignex.klause.util.Cancellation

internal sealed interface LpReferenceResult {
    class Feasible(val witness: List<BigFraction>, val objective: LpReferenceObjective) : LpReferenceResult

    data object Infeasible : LpReferenceResult

    class Declined(val reason: LpReferenceDecline) : LpReferenceResult
}

internal enum class LpReferenceDecline {
    CANCELLED_OR_PIVOT_LIMIT,
    NON_FINITE_INPUT,
    PROBE_BOUND_FEASIBILITY,
}

internal sealed interface LpReferenceObjective {
    class Bound(val lower: BigFraction, val attained: Boolean) : LpReferenceObjective

    data object Unbounded : LpReferenceObjective

    data object ProbeBoundDecline : LpReferenceObjective
}

/*
 * Test-only exact reference for the normalized [LpModel] seam.
 *
 * Integer data is lifted from its `Long` storage and continuous data from its exact IEEE value. The
 * adapter rebuilds an exact model rather than calling a float solver or any production certifier. A
 * finite probe box may provide a valid feasible witness, but cannot prove infeasibility or an objective
 * bound for the open model, so those claims decline. Objective attainment is decided by an independent
 * exact feasibility run with the computed infimum imposed as an equality.
 */
internal class LpReferenceAdapter(
    private val cancellation: Cancellation = Cancellation.Never,
    private val maxPivots: Int = Int.MAX_VALUE,
) {
    fun solve(model: LpModel, enforcedRows: BooleanArray? = null): LpReferenceResult {
        val data = exactData(model, enforcedRows)
            ?: return LpReferenceResult.Declined(LpReferenceDecline.NON_FINITE_INPUT)
        val referenceModel = ExactRationalFeasibilityModel(model.n, data.rows, data.upper)
        val feasibility = bigRationalOutcome(referenceModel, cancellation, maxPivots)
        when (feasibility.feasibility) {
            RationalFeasibility.INFEASIBLE -> {
                return if (model.hasProbeBounds()) {
                    LpReferenceResult.Declined(LpReferenceDecline.PROBE_BOUND_FEASIBILITY)
                } else {
                    LpReferenceResult.Infeasible
                }
            }

            RationalFeasibility.UNKNOWN -> return interrupted()

            RationalFeasibility.FEASIBLE -> Unit
        }
        val feasibleShifted = checkNotNull(feasibility.witness)
        check(data.accepts(feasibleShifted)) { "exact feasibility returned an infeasible witness" }
        if (model.hasProbeBounds()) {
            return LpReferenceResult.Feasible(
                data.unshift(feasibleShifted),
                LpReferenceObjective.ProbeBoundDecline,
            )
        }

        val optimum = bigRationalMinimum(referenceModel, data.costs, cancellation, maxPivots)
        when (optimum.feasibility) {
            RationalFeasibility.INFEASIBLE -> error("exact optimization contradicted exact feasibility")
            RationalFeasibility.UNKNOWN -> return interrupted()
            RationalFeasibility.FEASIBLE -> Unit
        }
        if (optimum.unbounded) {
            return LpReferenceResult.Feasible(
                data.unshift(feasibleShifted),
                LpReferenceObjective.Unbounded,
            )
        }

        val shiftedInfimum = checkNotNull(optimum.infimum)
        val attainment = bigRationalOutcome(data.withObjectiveEquality(shiftedInfimum), cancellation, maxPivots)
        val attainedShifted = when (attainment.feasibility) {
            RationalFeasibility.FEASIBLE -> checkNotNull(attainment.witness)
            RationalFeasibility.INFEASIBLE -> null
            RationalFeasibility.UNKNOWN -> return interrupted()
        }
        if (attainedShifted != null) {
            check(data.accepts(attainedShifted)) { "exact attainment returned an infeasible witness" }
            check(data.objective(attainedShifted) == shiftedInfimum) {
                "exact attainment witness misses the imposed objective"
            }
        }
        val witness = attainedShifted ?: feasibleShifted
        return LpReferenceResult.Feasible(
            data.unshift(witness),
            LpReferenceObjective.Bound(
                shiftedInfimum + data.objectiveConstant,
                attained = attainedShifted != null,
            ),
        )
    }

    fun accepts(model: LpModel, primalBits: LongArray?, enforcedRows: BooleanArray? = null): Boolean? {
        if (primalBits == null || primalBits.size < model.n) return false
        val data = exactData(model, enforcedRows) ?: return null
        val shifted = List(model.n) { column ->
            val value = BigFraction.ofDouble(Double.fromBits(primalBits[column])) ?: return null
            value - data.shifts[column]
        }
        return data.accepts(shifted)
    }

    fun objective(model: LpModel, primalBits: LongArray?, enforcedRows: BooleanArray? = null): BigFraction? {
        if (primalBits == null || primalBits.size < model.n) return null
        val data = exactData(model, enforcedRows) ?: return null
        val shifted = List(model.n) { column ->
            val value = BigFraction.ofDouble(Double.fromBits(primalBits[column])) ?: return null
            value - data.shifts[column]
        }
        return data.objective(shifted) + data.objectiveConstant
    }

    fun acceptsExact(model: LpModel, witness: List<BigFraction>, enforcedRows: BooleanArray? = null): Boolean? {
        if (witness.size != model.n) return false
        val data = exactData(model, enforcedRows) ?: return null
        return data.accepts(witness.mapIndexed { column, value -> value - data.shifts[column] })
    }

    fun exactObjective(model: LpModel, witness: List<BigFraction>, enforcedRows: BooleanArray? = null): BigFraction? {
        if (witness.size != model.n) return null
        val data = exactData(model, enforcedRows) ?: return null
        return data.objective(
            witness.mapIndexed { column, value -> value - data.shifts[column] },
        ) + data.objectiveConstant
    }

    private fun interrupted(): LpReferenceResult.Declined =
        LpReferenceResult.Declined(LpReferenceDecline.CANCELLED_OR_PIVOT_LIMIT)
}

private class ExactModelData(
    val costs: List<BigFraction>,
    val shifts: List<BigFraction>,
    val objectiveConstant: BigFraction,
    val rows: List<ExactRationalInequality>,
    val upper: List<BigFraction?>,
) {
    fun unshift(witness: List<BigFraction>): List<BigFraction> =
        witness.mapIndexed { column, value -> value + shifts[column] }

    fun accepts(witness: List<BigFraction>): Boolean {
        if (witness.size != costs.size) return false
        for (column in witness.indices) {
            if (witness[column] < BigFraction.ZERO) return false
            if (upper[column]?.let { witness[column] > it } == true) return false
        }
        return rows.all { row ->
            var activity = BigFraction.ZERO
            for (entry in row.columns.indices) {
                activity += witness[row.columns[entry]] * row.coefficients[entry]
            }
            if (row.strict) activity < row.rhs else activity <= row.rhs
        }
    }

    fun objective(witness: List<BigFraction>): BigFraction =
        witness.foldIndexed(BigFraction.ZERO) { column, total, value -> total + costs[column] * value }

    fun withObjectiveEquality(infimum: BigFraction): ExactRationalFeasibilityModel {
        val columns = costs.indices.filter { !costs[it].isZero }.toIntArray()
        val coefficients = columns.map(costs::get)
        val equality = listOf(
            ExactRationalInequality(columns, coefficients, infimum),
            ExactRationalInequality(columns, coefficients.map(BigFraction::negated), infimum.negated()),
        )
        return ExactRationalFeasibilityModel(costs.size, rows + equality, upper)
    }
}

private fun exactData(model: LpModel, enforcedRows: BooleanArray?): ExactModelData? {
    require(enforcedRows == null || enforcedRows.size == model.m) {
        "active row mask has ${enforcedRows?.size} entries for ${model.m} rows"
    }
    val view = model.doubleView
    val costs = if (view == null) {
        List(model.n) { BigFraction.ofLong(model.cost[it]) }
    } else {
        view.cost.take(model.n).exact() ?: return null
    }
    val shifts = if (view == null) {
        List(model.n) { BigFraction.ofLong(model.loShift[it]) }
    } else {
        view.loShift.toList().exact() ?: return null
    }
    val objectiveConstant = if (view == null) {
        BigFraction.ofLong(model.objConstant)
    } else {
        BigFraction.ofDouble(view.objConstant) ?: return null
    }
    val rhs = if (view == null) {
        List(model.m) { BigFraction.ofLong(model.rhs[it]) }
    } else {
        view.rhs.toList().exact() ?: return null
    }
    val allUpper = if (view == null) {
        List(model.numVars) { BigFraction.ofLong(model.upper[it]) }
    } else {
        view.upper.toList().exact() ?: return null
    }
    val rowColumns = Array(model.m) { ArrayList<Int>() }
    val rowCoefficients = Array(model.m) { ArrayList<BigFraction>() }
    for (column in 0 until model.n) {
        val from = view?.colPtr?.get(column) ?: model.csc.colPtr[column]
        val until = view?.colPtr?.get(column + 1) ?: model.csc.colPtr[column + 1]
        for (entry in from until until) {
            val row = view?.rowIdx?.get(entry) ?: model.csc.rowIdx[entry]
            val coefficient = if (view == null) {
                BigFraction.ofLong(model.csc.colVal[entry])
            } else {
                BigFraction.ofDouble(view.colVal[entry]) ?: return null
            }
            if (!coefficient.isZero) {
                rowColumns[row].add(column)
                rowCoefficients[row].add(coefficient)
            }
        }
    }
    val rows = ArrayList<ExactRationalInequality>(2 * model.m)
    for (row in 0 until model.m) {
        if (enforcedRows?.get(row) == false) continue
        val columns = rowColumns[row].toIntArray()
        val coefficients = rowCoefficients[row].toList()
        rows.add(ExactRationalInequality(columns, coefficients, rhs[row], model.rowStrict[row]))
        val slack = model.n + row
        if (model.hasUpper[slack]) {
            rows.add(
                ExactRationalInequality(
                    columns,
                    coefficients.map(BigFraction::negated),
                    allUpper[slack] - rhs[row],
                ),
            )
        }
    }
    val structuralUpper = List(model.n) { column ->
        if (model.hasUpper[column]) allUpper[column] else null
    }
    return ExactModelData(costs, shifts, objectiveConstant, rows, structuralUpper)
}

private fun List<Double>.exact(): List<BigFraction>? {
    val values = ArrayList<BigFraction>(size)
    for (value in this) values.add(BigFraction.ofDouble(value) ?: return null)
    return values
}

private fun LpModel.hasProbeBounds(): Boolean = probeClampedLo.any { it } || probeClampedHi.any { it }
