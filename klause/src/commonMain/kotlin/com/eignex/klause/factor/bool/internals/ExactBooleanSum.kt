package com.eignex.klause.factor.bool.internals

import com.eignex.klause.factor.compressViolation
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.Lit
import com.eignex.klause.model.PbOp
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.magnitudeBitLength
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.signum
import com.eignex.klause.util.toLong
import com.eignex.klause.util.unaryMinus

internal class ExactBooleanSum(constants: IntegralConstants, literals: IntArray) {
    val vars: IntArray
    val coeffs: Array<BigInt>
    val bound: BigInt

    init {
        val sums = LinkedHashMap<Int, BigInt>()
        var constant = BIG_ZERO
        for (k in literals.indices) {
            val literal = literals[k]
            val coefficient = constants.exactCoeff(k)
            val variable = Lit.variable(literal)
            val signed = if (Lit.isPositive(literal)) coefficient else -coefficient
            if (!Lit.isPositive(literal)) constant += coefficient
            sums[variable] = (sums[variable] ?: BIG_ZERO) + signed
        }
        vars = sums.keys.toIntArray()
        coeffs = sums.values.toTypedArray()
        bound = constants.exactBound - constant
    }

    fun sum(valueOf: (Int) -> Boolean): BigInt {
        var sum = BIG_ZERO
        for (k in vars.indices) if (valueOf(vars[k])) sum += coeffs[k]
        return sum
    }

    fun holds(sum: BigInt, op: PbOp): Boolean = when (op) {
        PbOp.LE -> (sum - bound).signum() <= 0
        PbOp.GE -> (sum - bound).signum() >= 0
        PbOp.EQ -> sum == bound
    }

    fun degree(sum: BigInt, op: PbOp, softCap: Int): Int {
        if (holds(sum, op)) return 0
        val residual = sum - bound
        val magnitude = if (residual.magnitudeBitLength() >= Long.SIZE_BITS) Long.MAX_VALUE
            else if (residual.signum() < 0) (-residual).toLong() else residual.toLong()
        return compressViolation(magnitude, softCap)
    }
}
