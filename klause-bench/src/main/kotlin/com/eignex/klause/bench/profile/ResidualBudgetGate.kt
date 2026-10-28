package com.eignex.klause.bench.profile

import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Lit
import com.eignex.klause.model.PbOp
import java.math.BigInteger

internal object ResidualBudgetGate {
    fun impliedByCutoff(
        restriction: PseudoBoolean,
        weights: List<Long>,
        constant: BigInteger,
        cutoff: BigInteger,
        fixed: Map<Int, Boolean>,
        allowance: ComponentAllowance,
    ): Boolean {
        if (restriction.op == PbOp.EQ || !allowance.retain(weights.size * 32L) ||
            !allowance.visit(weights.size * 2L)
        ) {
            return false
        }
        val coefficients = Array(weights.size) { BigInteger.ZERO }
        var rhs = restriction.constants.exactBound
        if (rhs.bitLength() > 512) return false
        for ((i, literal) in restriction.literals.withIndex()) {
            if (!allowance.visit()) return false
            val variable = Lit.variable(literal)
            if (literal < 0 || variable !in weights.indices) return false
            val coefficient = restriction.constants.exactCoeff(i)
            if (coefficient.bitLength() > 256) return false
            if (!Lit.isPositive(literal)) rhs -= coefficient
            coefficients[variable] += if (Lit.isPositive(literal)) coefficient else -coefficient
        }
        if (restriction.op == PbOp.GE) {
            rhs = -rhs
            for (i in coefficients.indices) {
                if (!allowance.visit()) return false
                coefficients[i] = -coefficients[i]
            }
        }
        var objectiveConstant = constant
        for ((v, value) in fixed) {
            if (!allowance.visit() || v !in weights.indices) return false
            if (value) {
                rhs -= coefficients[v]
                objectiveConstant += BigInteger.valueOf(weights[v])
            }
            coefficients[v] = BigInteger.ZERO
        }
        var maximum = BigInteger.ZERO
        for (coefficient in coefficients) {
            if (!allowance.visit()) return false
            if (coefficient.signum() > 0) maximum += coefficient
        }
        if (maximum <= rhs) return true
        val pivot = weights.indices.firstOrNull { it !in fixed && weights[it] != 0L } ?: return false
        var numerator = coefficients[pivot]
        var denominator = BigInteger.valueOf(weights[pivot])
        if (denominator.signum() < 0) {
            numerator = -numerator
            denominator = -denominator
        }
        if (numerator.signum() <= 0) return false
        for (i in weights.indices) {
            if (!allowance.visit()) return false
            if (i !in fixed && coefficients[i] * denominator != BigInteger.valueOf(weights[i]) * numerator) return false
        }
        // Exact integral objectives turn strict improvement into objective <= cutoff - 1.
        return allowance.visit() && rhs * denominator >= (cutoff - BigInteger.ONE - objectiveConstant) * numerator
    }
}
