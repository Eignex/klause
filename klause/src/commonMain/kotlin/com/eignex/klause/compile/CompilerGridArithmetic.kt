package com.eignex.klause.compile

import com.eignex.klause.factor.arithmetic.GridCoordinate
import com.eignex.klause.factor.arithmetic.GridPower
import com.eignex.klause.factor.arithmetic.GridProduct
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.model.ContinuousRealEquality
import com.eignex.klause.model.ContinuousRealExpr
import kotlin.math.pow
import kotlin.math.round

internal fun Lowering.assertGridContinuousEquality(equality: ContinuousRealEquality) {
    val columns = HashMap<ContinuousRealExpr, GridCoordinate>()
    fun fresh(lower: Double, upper: Double, buckets: Int): GridCoordinate {
        require(lower.isFinite() && upper.isFinite() && (upper - lower).isFinite()) {
            "Continuous grid expression range overflows binary64"
        }
        val count = if (lower == upper) 1 else buckets
        return GridCoordinate(newIntVar(IntDomain(0L, count - 1L)), lower, upper, count)
    }
    fun named(name: String): GridCoordinate {
        val id = floatVarIdByName.getValue(name)
        val interval = floatMetaIntervals[id]
        return GridCoordinate(floatMetaIntVarIds[id], interval.lo, interval.hi, floatMetaBuckets[id])
    }
    fun affine(inputs: List<Pair<GridCoordinate, Double>>, constant: Double, output: GridCoordinate) {
        val coordinates = inputs + (output to -1.0)
        val coefficients = LongArray(coordinates.size) { index ->
            val (coordinate, coefficient) = coordinates[index]
            val step = if (coordinate.buckets > 1) (coordinate.upper - coordinate.lower) / (coordinate.buckets - 1)
                else 0.0
            gridCoefficient(coefficient * step * GRID_SCALE)
        }
        val bound = -constant - coordinates.sumOf { (coordinate, coefficient) -> coefficient * coordinate.lower }
        factors += Linear(coefficients, coordinates.map { it.first.variable }.toIntArray(), LinearOp.EQ,
            gridCoefficient(bound * GRID_SCALE))
    }
    fun lift(expression: ContinuousRealExpr): GridCoordinate = columns.getOrPut(expression) {
        when (expression) {
            is ContinuousRealExpr.Affine -> {
                val terms = expression.coefficients.filterValues { it != 0.0 }
                    .map { (name, coefficient) -> named(name) to coefficient }
                if (terms.size == 1 && terms.single().second == 1.0 && expression.constant == 0.0) {
                    terms.single().first
                } else {
                    var lower = expression.constant
                    var upper = expression.constant
                    for ((coordinate, coefficient) in terms) {
                        val first = coordinate.lower * coefficient
                        val last = coordinate.upper * coefficient
                        lower += minOf(first, last)
                        upper += maxOf(first, last)
                    }
                    val output = fresh(lower, upper, terms.maxOfOrNull { it.first.buckets } ?: 1)
                    affine(terms, expression.constant, output)
                    output
                }
            }
            is ContinuousRealExpr.Multiply -> {
                val a = lift(expression.left)
                val b = lift(expression.right)
                val corners = listOf(a.lower * b.lower, a.lower * b.upper, a.upper * b.lower, a.upper * b.upper)
                val output = fresh(corners.min(), corners.max(), maxOf(a.buckets, b.buckets))
                factors += GridProduct(a, b, output)
                output
            }
            is ContinuousRealExpr.Power -> {
                val base = lift(expression.base)
                val first = base.lower.pow(expression.exponent)
                val last = base.upper.pow(expression.exponent)
                val lower = if (expression.exponent > 0 && expression.exponent and 1 == 0 &&
                    base.lower <= 0.0 && base.upper >= 0.0) 0.0 else minOf(first, last)
                val output = fresh(lower, maxOf(first, last), base.buckets)
                factors += GridPower(base, expression.exponent, output)
                output
            }
        }
    }
    fun equate(expression: ContinuousRealExpr, output: GridCoordinate) {
        when (expression) {
            is ContinuousRealExpr.Multiply -> factors.add(GridProduct(lift(expression.left), lift(expression.right), output))
            is ContinuousRealExpr.Power -> factors.add(GridPower(lift(expression.base), expression.exponent, output))
            is ContinuousRealExpr.Affine -> affine(listOf(lift(expression) to 1.0), 0.0, output)
        }
    }
    if (equality.left is ContinuousRealExpr.Affine && equality.right !is ContinuousRealExpr.Affine) {
        equate(equality.right, lift(equality.left))
    } else {
        equate(equality.left, lift(equality.right))
    }
}

private fun gridCoefficient(value: Double): Long {
    val rounded = round(value)
    require(rounded.isFinite() && rounded >= Long.MIN_VALUE.toDouble() && rounded < -Long.MIN_VALUE.toDouble()) {
        "Continuous grid affine coefficient overflows Long"
    }
    return rounded.toLong()
}

private const val GRID_SCALE = 1_000_000.0
