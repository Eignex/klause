package com.eignex.klause.arithmetic.quadratic

import com.eignex.klause.simplex.exact.BigFraction

internal class QuadraticCertification(private val control: QuadraticControl = QuadraticControl()) {
    private val half = BigFraction.ofLong(2).reciprocal()

    fun evaluate(model: QuadraticModel, point: List<BigFraction>): BigFraction = control.operation {
        control.step()
        require(point.size == model.dimension)
        val hessian = model.hessian
        var value = model.constant + dot(model.linear, point)
        for (i in point.indices) for (j in point.indices) {
            control.exactStep(point[i], hessian[i][j], point[j])
            value += half * point[i] * hessian[i][j] * point[j]
        }
        return@operation value
    }

    fun feasible(model: QuadraticModel, point: List<BigFraction>): Boolean = control.operation {
        control.step()
        if (point.size != model.dimension) return@operation false
        return@operation model.rows.all { row ->
            val residual = dot(row.coefficients, point) - row.rhs
            when (row.relation) {
                QuadraticRowRelation.LE -> residual.signum() <= 0
                QuadraticRowRelation.LT -> residual.signum() < 0
                QuadraticRowRelation.EQ -> residual.isZero
            }
        }
    }

    fun bound(model: QuadraticModel, certificate: QuadraticStationaryCertificate): CheckedQuadraticBound? =
        control.operation {
        control.step()
        val point = certificate.anchor
        val multipliers = certificate.multipliers
        val rows = model.rows
        val hessian = model.hessian
        val linear = model.linear
        if (point.size != model.dimension || multipliers.size != rows.size) return@operation null
        if (!positiveSemidefinite(model)) return@operation null
        for (i in rows.indices) {
            control.step()
            if (rows[i].relation != QuadraticRowRelation.EQ && multipliers[i].signum() < 0) return@operation null
        }
        for (i in point.indices) {
            var gradient = linear[i] + dot(hessian[i], point)
            for (j in rows.indices) {
                val coefficient = rows[j].coefficients[i]
                control.exactStep(multipliers[j], coefficient)
                gradient += multipliers[j] * coefficient
            }
            if (!gradient.isZero) return@operation null
        }
        var value = evaluate(model, point)
        for (i in rows.indices) {
            control.exactStep(multipliers[i])
            value += multipliers[i] * (dot(rows[i].coefficients, point) - rows[i].rhs)
        }
        control.poll()
        return@operation CheckedQuadraticBound(model, value, certificate)
    }

    fun optimum(bound: CheckedQuadraticBound): CheckedQuadraticOptimum? = control.operation {
        val point = bound.certificate.anchor
        if (!feasible(bound.model, point) || evaluate(bound.model, point) != bound.value) return@operation null
        control.poll()
        return@operation CheckedQuadraticOptimum(bound)
    }

    fun infimum(
        bound: CheckedQuadraticBound,
        direction: List<BigFraction>,
        step: BigFraction,
    ): CheckedQuadraticInfimum? = control.operation {
        control.step()
        val model = bound.model
        val anchor = bound.certificate.anchor
        if (direction.size != model.dimension || step.signum() <= 0) return@operation null
        if (evaluate(model, anchor) != bound.value) return@operation null
        if (feasible(model, anchor)) return@operation null
        val point = anchor.indices.map { control.step(); anchor[it] + step * direction[it] }
        if (!feasible(model, point)) return@operation null
        for (row in model.rows) {
            val residual = dot(row.coefficients, anchor) - row.rhs
            if (row.relation == QuadraticRowRelation.EQ && !residual.isZero) return@operation null
            if (row.relation != QuadraticRowRelation.EQ && residual.signum() > 0) return@operation null
        }
        // Strict complementarity or a unique Lagrangian minimizer excludes every feasible point at the bound.
        if (!positiveSemidefinite(model, requirePositive = true) && model.rows.indices.none {
                control.step()
                model.rows[it].relation == QuadraticRowRelation.LT && bound.certificate.multipliers[it].signum() > 0
            }) return@operation null
        control.poll()
        return@operation CheckedQuadraticInfimum(bound, direction, step)
    }

    fun unbounded(
        model: QuadraticModel,
        base: List<BigFraction>,
        direction: List<BigFraction>,
    ): CheckedQuadraticUnbounded? = control.operation {
        control.step()
        if (direction.size != model.dimension || !feasible(model, base)) return@operation null
        for (row in model.rows) {
            val slope = dot(row.coefficients, direction)
            if (row.relation == QuadraticRowRelation.EQ && !slope.isZero) return@operation null
            if (row.relation != QuadraticRowRelation.EQ && slope.signum() > 0) return@operation null
        }
        val hessian = model.hessian
        var curvature = BigFraction.ZERO
        var slope = dot(model.linear, direction)
        for (i in direction.indices) {
            control.exactStep(direction[i])
            curvature += direction[i] * dot(hessian[i], direction)
            slope += direction[i] * dot(hessian[i], base)
        }
        if (curvature.signum() > 0 || (curvature.isZero && slope.signum() >= 0)) return@operation null
        control.poll()
        return@operation CheckedQuadraticUnbounded(model, base, direction)
    }

    fun positiveSemidefinite(model: QuadraticModel, requirePositive: Boolean = false): Boolean = control.operation {
        control.matrix(model.dimension)
        val matrix = model.hessian.map { it.toMutableList() }
        for (i in matrix.indices) {
            control.step()
            val pivot = matrix[i][i]
            if (pivot.signum() < 0) return@operation false
            if (pivot.isZero) {
                if (requirePositive) return@operation false
                for (j in i + 1 until matrix.size) {
                    control.step()
                    if (!matrix[i][j].isZero) return@operation false
                }
            } else {
                for (j in i + 1 until matrix.size) for (k in j until matrix.size) {
                    control.exactStep(matrix[j][k], matrix[j][i], matrix[i][k], pivot)
                    val entry = matrix[j][k] - matrix[j][i] * matrix[i][k] * pivot.reciprocal()
                    matrix[j][k] = entry
                    matrix[k][j] = entry
                }
            }
        }
        control.poll()
        return@operation true
    }

    private fun dot(left: List<BigFraction>, right: List<BigFraction>): BigFraction {
        require(left.size == right.size)
        var value = BigFraction.ZERO
        for (i in left.indices) {
            control.exactStep(left[i], right[i])
            value += left[i] * right[i]
        }
        return value
    }
}
