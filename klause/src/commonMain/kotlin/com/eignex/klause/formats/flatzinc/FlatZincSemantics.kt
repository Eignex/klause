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
import com.eignex.klause.simplex.exact.BigFraction

/** FlatZinc's declared defaults, independent of solver or resource selection. */
val flatZincSemantics: SourceSemantics = SourceSemantics(
    format = SourceFormat.FLATZINC,
    numericAuthority = NumericAuthority.BINARY64_LITERALS,
    floats = FloatSemantics.Grid(DEFAULT_FLOAT_BUCKETS, DEFAULT_FLOAT_SCALE),
    undeclaredIntegers = UndeclaredIntegerBounds(-MINIZINC_UNBOUNDED_DEFAULT, MINIZINC_UNBOUNDED_DEFAULT),
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
        return EffectiveSourceSemantics(flatZincSemantics, legacy.resolve(overrides).effective)
    }
}

private fun semanticFloat(value: Double): SemanticRational {
    val rational = requireNotNull(BigFraction.ofDouble(value)) { "float grid window must be finite" }
    return SemanticRational.of(rational.num, rational.den)
}

internal fun SemanticRational.gridFloat(): Double = BigFraction.of(numerator, denominator).toDouble().also {
    require(it.isFinite()) { "float grid window cannot be represented in binary64" }
}

internal fun EffectiveSourceSemantics.gridDefaults(): FloatSemantics.Grid =
    effective.floats as? FloatSemantics.Grid ?: FloatSemantics.Grid(
        DEFAULT_FLOAT_BUCKETS, DEFAULT_FLOAT_SCALE,
        UndeclaredRealBounds(semanticFloat(DEFAULT_UNBOUNDED_FLOAT_LO), semanticFloat(DEFAULT_UNBOUNDED_FLOAT_HI)),
    )
