package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.RationalBasisBuild
import com.eignex.klause.simplex.basis.RationalBasisDecline
import com.eignex.klause.simplex.basis.RationalBasisFactors
import com.eignex.klause.simplex.basis.RationalBasisSolve
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.IntArrayList

internal data class SparseBasisPoint(
    val witness: ExactLpWitness?,
    val peeled: Int = 0,
    val core: Int = 0,
    val outcome: String = "CANDIDATE",
)

private class BasisEquation(val coefficients: MutableMap<Int, BigFraction>, var rhs: BigFraction)

internal fun recoverSparseBasisPoint(model: LpModel, basis: Basis, meter: RefinementMeter): SparseBasisPoint {
    meter.poll()
    meter.charge(model.numVars.toLong(), 32L * model.numVars + 16L * model.m + 32L * model.n)
    if (model.n > meter.limits.maxCoordinates || basis.basicVars.size != model.m ||
        basis.status.size != model.numVars
    ) return SparseBasisPoint(null)
    val basic = BooleanArray(model.numVars)
    val retained = BooleanArray(model.m) { true }
    for (column in basis.basicVars) {
        meter.charge()
        if (column !in basic.indices || basic[column] || basis.status[column] != VarStatus.BASIC) {
            return SparseBasisPoint(null)
        }
        basic[column] = true
        if (column >= model.n) retained[column - model.n] = false
    }
    val seats = arrayOfNulls<BigFraction>(model.numVars)
    for (column in seats.indices) {
        meter.charge()
        if (basic[column]) continue
        val bounds = model.exactBounds(column)
        val value = when (basis.status[column]) {
            VarStatus.BASIC -> null
            VarStatus.AT_LOWER -> bounds.lower?.number?.value
            VarStatus.AT_UPPER -> bounds.upper?.number?.value
            VarStatus.FIXED -> if (bounds.fixed) bounds.lower?.number?.value else null
            VarStatus.FREE -> if (bounds.lower == null && bounds.upper == null) BigFraction.ZERO else null
        } ?: return SparseBasisPoint(null)
        seats[column] = meter.number(value)
    }
    val equations = ArrayList<BasisEquation>()
    val rowIndex = IntArray(model.m) { -1 }
    for (row in retained.indices) {
        meter.charge()
        if (!retained[row]) continue
        val seat = seats[model.slackCol(row)] ?: return SparseBasisPoint(null)
        rowIndex[row] = equations.size
        meter.charge(bytes = 128L)
        equations += BasisEquation(HashMap(), meter.pointAdd(
            meter.number(model.exactRhs(row)), meter.number(seat.negated()),
        ))
    }
    // Logical basics have unit columns, so their rows do not constrain the structural basics.
    if (equations.size != (0 until model.n).count { basic[it] }) return SparseBasisPoint(null)
    var entries = 0
    for (column in 0 until model.n) {
        meter.charge()
        model.forEachRationalColumn(column) { row, raw ->
            meter.charge()
            if (++entries > meter.limits.maxEntries) meter.stop(LpRefinementDecline.DIMENSION)
            val index = rowIndex[row]
            if (index >= 0) {
                val equation = equations[index]
                val coefficient = meter.number(raw)
                if (basic[column]) {
                    val value = meter.pointAdd(equation.coefficients[column] ?: BigFraction.ZERO, coefficient)
                    if (value.isZero) equation.coefficients.remove(column) else {
                        meter.charge(bytes = 64L)
                        equation.coefficients[column] = value
                    }
                } else {
                    val product = meter.pointMultiply(coefficient, checkNotNull(seats[column]))
                    equation.rhs = meter.pointAdd(equation.rhs, meter.number(product.negated()))
                }
            }
        }
    }
    meter.charge(bytes = 64L * model.n + 16L * entries)
    val incidence = Array(model.n) { IntArrayList() }
    val pending = ArrayDeque<Int>()
    for ((row, equation) in equations.withIndex()) {
        meter.charge(equation.coefficients.size.toLong() + 1L)
        for (column in equation.coefficients.keys) incidence[column].add(row)
        if (equation.coefficients.size == 1) pending.addLast(row)
    }
    var peeled = 0
    while (pending.isNotEmpty()) {
        meter.charge()
        val equation = equations[pending.removeFirst()]
        if (equation.coefficients.size != 1) continue
        val (column, coefficient) = equation.coefficients.entries.single()
        val value = meter.divide(equation.rhs, coefficient)
        seats[column] = value
        peeled++
        for (position in 0 until incidence[column].size) {
            meter.charge()
            val row = incidence[column][position]
            val affected = equations[row]
            val multiplier = affected.coefficients.remove(column) ?: continue
            val product = meter.pointMultiply(multiplier, value)
            affected.rhs = meter.pointAdd(affected.rhs, meter.number(product.negated()))
            if (affected.coefficients.size == 1) pending.addLast(row)
        }
    }
    meter.charge(model.n.toLong() + equations.size)
    if (equations.any { it.coefficients.isEmpty() && !it.rhs.isZero }) return SparseBasisPoint(null, peeled)
    val columns = (0 until model.n).filter { basic[it] && seats[it] == null }
    val rows = equations.filter { it.coefficients.isNotEmpty() }
    val core = columns.size
    if (rows.size != core) return SparseBasisPoint(null, peeled, core)
    if (core > meter.basisLimits().factor.dimension) return SparseBasisPoint(null, peeled, core, "CORE_DIMENSION")
    if (core > 0) {
        meter.charge(core.toLong() * core, 64L * core + 8L * core * core)
        val matrix = rows.map { equation -> columns.map { equation.coefficients[it] ?: BigFraction.ZERO } }
        val build = RationalBasisFactors.factor(matrix, limits = meter.basisLimits().factor, cancellation = meter.token)
        meter.completed(build.stats.work, build.stats.allocationBytes)
        val factors = when (build) {
            is RationalBasisBuild.Ready -> build.factors
            is RationalBasisBuild.Singular -> return SparseBasisPoint(null, peeled, core, "SINGULAR")
            is RationalBasisBuild.Declined -> return basisPointDeclined(build.reason, peeled, core, meter)
        }
        val solved = factors.solve(rows.map { it.rhs }, limits = meter.basisLimits().factor, cancellation = meter.token)
        meter.completed(solved.stats.work, solved.stats.allocationBytes)
        when (solved) {
            is RationalBasisSolve.Solved -> columns.forEachIndexed { index, column -> seats[column] = solved.values[index] }
            is RationalBasisSolve.Declined -> return basisPointDeclined(solved.reason, peeled, core, meter)
        }
    }
    meter.charge(model.n.toLong(), 32L * model.n)
    val point = List(model.n) { column ->
        meter.pointAdd(checkNotNull(seats[column]), meter.number(model.exactShift(column)))
    }
    val witness = checkedLpWitness(model, point, meter)
    meter.poll()
    return SparseBasisPoint(witness, peeled, core, if (witness == null) "REJECTED" else "WITNESS")
}

private fun basisPointDeclined(
    reason: RationalBasisDecline,
    peeled: Int,
    core: Int,
    meter: RefinementMeter,
): SparseBasisPoint {
    when (reason) {
        RationalBasisDecline.WORK -> meter.stop(LpRefinementDecline.WORK)
        RationalBasisDecline.MEMORY -> meter.stop(LpRefinementDecline.ALLOCATION)
        RationalBasisDecline.BITS -> meter.stop(LpRefinementDecline.BITS)
        RationalBasisDecline.TIME -> meter.stop(LpRefinementDecline.TIME)
        RationalBasisDecline.CANCELLED -> meter.stop(LpRefinementDecline.CANCELLED)
        else -> Unit
    }
    return SparseBasisPoint(null, peeled, core, reason.name)
}
