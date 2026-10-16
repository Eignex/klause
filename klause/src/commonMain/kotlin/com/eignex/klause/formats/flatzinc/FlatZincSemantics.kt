package com.eignex.klause.formats.flatzinc

import com.eignex.klause.config.DEFAULT_FLOAT_BUCKETS
import com.eignex.klause.config.DEFAULT_FLOAT_SCALE
import com.eignex.klause.config.DEFAULT_UNBOUNDED_FLOAT_HI
import com.eignex.klause.config.DEFAULT_UNBOUNDED_FLOAT_LO
import com.eignex.klause.config.MINIZINC_UNBOUNDED_DEFAULT
import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.NumericAuthority
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.UndeclaredIntegerBounds
import com.eignex.klause.ir.UndeclaredRealBounds
import com.eignex.klause.lowering.approximateGridValue
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.magnitudeBitLength
import com.eignex.klause.util.shl
import com.eignex.klause.util.toDouble

/** FlatZinc's declared defaults, independent of solver or resource selection. */
val flatZincSemantics: SourceSemantics = SourceSemantics(
    format = SourceFormat.FLATZINC,
    numericAuthority = NumericAuthority.BINARY64_LITERALS,
    floats = FloatSemantics.Grid(DEFAULT_FLOAT_BUCKETS, DEFAULT_FLOAT_SCALE),
    undeclaredIntegers = UndeclaredIntegerBounds(-MINIZINC_UNBOUNDED_DEFAULT, MINIZINC_UNBOUNDED_DEFAULT),
    undeclaredReals = UndeclaredRealBounds(),
)

internal class FlatZincSemantics(
    private val floatBuckets: Int = DEFAULT_FLOAT_BUCKETS,
    private val floatScale: Long = DEFAULT_FLOAT_SCALE,
    private val unboundedIntLo: Long? = null,
    private val unboundedIntHi: Long? = null,
    private val unboundedFloatLo: Double = DEFAULT_UNBOUNDED_FLOAT_LO,
    private val unboundedFloatHi: Double = DEFAULT_UNBOUNDED_FLOAT_HI,
    private val exactFloats: Boolean = false,
) {
    fun resolve(overrides: SemanticOverrides): EffectiveSourceSemantics {
        val defaults = checkNotNull(flatZincSemantics.undeclaredIntegers)
        val legacy = flatZincSemantics.copy(
            floats = if (exactFloats) FloatSemantics.Continuous else FloatSemantics.Grid(
                floatBuckets, floatScale, UndeclaredRealBounds(
                    semanticFloat(unboundedFloatLo), semanticFloat(unboundedFloatHi),
                ),
            ),
            undeclaredIntegers = UndeclaredIntegerBounds(
                unboundedIntLo ?: defaults.lower,
                unboundedIntHi ?: defaults.upper,
            ),
        )
        val effective = legacy.resolve(overrides).effective
        return EffectiveSourceSemantics(flatZincSemantics, effective)
    }
}

private fun semanticFloat(value: Double): SemanticRational {
    val rational = requireNotNull(BigFraction.ofDouble(value)) { "float grid window must be finite" }
    return SemanticRational.of(rational.num, rational.den)
}

internal fun SemanticRational.gridFloat(): Double = approximateGridValue(BigFraction.of(numerator, denominator))

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

internal fun EffectiveSourceSemantics.gridDefaults(): FloatSemantics.Grid =
    effective.floats as? FloatSemantics.Grid ?: FloatSemantics.Grid(
        DEFAULT_FLOAT_BUCKETS, DEFAULT_FLOAT_SCALE,
        UndeclaredRealBounds(semanticFloat(DEFAULT_UNBOUNDED_FLOAT_LO), semanticFloat(DEFAULT_UNBOUNDED_FLOAT_HI)),
    )
