package com.eignex.klause.factor.bool.internals

import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.UnitConsts
import com.eignex.klause.ir.WideConstants
import com.eignex.klause.ir.WideConsts
import com.eignex.klause.ir.constsOf
import com.eignex.klause.model.PbOp
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.addExact
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.subExact
import com.eignex.klause.util.toLongExact

internal fun exactPbConstants(weights: Array<BigInt>, bound: BigInt): IntegralConstants =
    if (bound.fitsLong() && weights.all { it.fitsLong() }) {
        IntegerConstants(constsOf(LongArray(weights.size) { weights[it].toLongExact() }), bound.toLongExact())
    } else {
        WideConstants(WideConsts(weights.copyOf()), bound)
    }

internal fun validatePseudoBoolean(constants: IntegralConstants, literals: IntArray) {
    val size = when (constants) {
        is IntegerConstants -> constants.coefficients.size
        is WideConstants -> constants.coefficients.size
    }
    require(size == literals.size) {
        "pseudo-Boolean weights/literals length mismatch: $size weights for ${literals.size} literals"
    }
}

internal fun pseudoBooleanKey(sink: KeySink, constants: IntegralConstants, literals: IntArray, op: PbOp) {
    sink.enum(op)
    sink.bool(constants is WideConstants)
    when (constants) {
        is IntegerConstants -> {
            sink.long(constants.bound)
            sink.pairsByLitKey(literals) { constants.coeff(it) }
        }
        is WideConstants -> {
            sink.constLongs(constants.keyDigits[0])
            val order = literals.indices.sortedWith(compareBy<Int> { literals[it] }
                .thenBy { constants.exactCoeff(it).toString() })
            sink.int(order.size)
            for (k in order) {
                sink.boolLit(literals[k])
                sink.constLongs(constants.keyDigits[k + 1])
            }
        }
    }
}

// Long kernels require representable activities, signed flips and residual magnitudes.
internal fun permitsLongPseudoBoolean(constants: IntegralConstants): Boolean {
    val c = constants as? IntegerConstants ?: return false
    if (c.coefficients is UnitConsts && c.bound in 0L..c.coefficients.size.toLong()) return true
    return try {
        var lo = 0L
        var hi = 0L
        for (k in 0 until c.coefficients.size) {
            val weight = c.coeff(k)
            if (weight < 0L) lo = addExact(lo, weight) else hi = addExact(hi, weight)
        }
        if (subExact(hi, lo) < 0L) return false
        subExact(lo, c.bound) != Long.MIN_VALUE && subExact(hi, c.bound) != Long.MIN_VALUE
    } catch (_: CheckedLongOverflowException) {
        false
    }
}
