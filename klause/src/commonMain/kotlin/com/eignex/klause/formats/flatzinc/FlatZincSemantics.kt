package com.eignex.klause.formats.flatzinc

import com.eignex.klause.config.MINIZINC_UNBOUNDED_DEFAULT
import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.NumericAuthority
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.ir.UndeclaredIntegerBounds
import com.eignex.klause.ir.UndeclaredRealBounds
import com.eignex.klause.ir.numericalSourceTolerance
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.magnitudeBitLength
import com.eignex.util.numbers.shl
import com.eignex.util.numbers.toDouble

/** FlatZinc's declared defaults, independent of solver or resource selection. */
val flatZincSemantics: SourceSemantics = SourceSemantics(
    format = SourceFormat.FLATZINC,
    numericAuthority = NumericAuthority.BINARY64_LITERALS,
    floats = FloatSemantics.Continuous,
    undeclaredIntegers = UndeclaredIntegerBounds(-MINIZINC_UNBOUNDED_DEFAULT, MINIZINC_UNBOUNDED_DEFAULT),
    undeclaredReals = UndeclaredRealBounds(),
    feasibilityTolerance = numericalSourceTolerance,
)

internal class FlatZincSemantics(
    private val unboundedIntLo: Long? = null,
    private val unboundedIntHi: Long? = null,
    private val exactFloats: Boolean = false,
) {
    fun resolve(overrides: SemanticOverrides): EffectiveSourceSemantics {
        val defaults = checkNotNull(flatZincSemantics.undeclaredIntegers)
        val legacy = flatZincSemantics.copy(
            feasibilityTolerance = if (exactFloats) SourceTolerance() else flatZincSemantics.feasibilityTolerance,
            undeclaredIntegers = UndeclaredIntegerBounds(
                unboundedIntLo ?: defaults.lower,
                unboundedIntHi ?: defaults.upper,
            ),
        )
        val effective = legacy.resolve(overrides).effective
        return EffectiveSourceSemantics(flatZincSemantics, effective)
    }
}

internal fun SemanticRational.sourceRealBound(): Double = binary64("undeclared real bound")

private fun SemanticRational.binary64(role: String): Double {
    val exact = BigFraction.of(numerator, denominator)
    val exponent = denominator.magnitudeBitLength() - 1
    require(exponent in 0..1074 && denominator == BIG_ONE shl exponent) {
        "$role cannot be represented exactly in binary64"
    }
    val scale = if (exponent <= 1022) Double.fromBits((1023L - exponent) shl 52)
    else Double.fromBits(1L shl (1074 - exponent))
    val value = numerator.toDouble() * scale
    require(value.isFinite() && BigFraction.ofDouble(value) == exact) {
        "$role cannot be represented exactly in binary64"
    }
    return value
}
