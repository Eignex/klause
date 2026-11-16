package com.eignex.klause.formats.smtlib

import com.eignex.klause.ir.BoundOverride
import com.eignex.klause.ir.BoundsOverride
import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.NumericAuthority
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.UndeclaredIntegerBounds
import com.eignex.klause.ir.UndeclaredRealBounds

/** Exact rational arithmetic with continuous reals and open numeric declarations. */
val smtLibSemantics: SourceSemantics = SourceSemantics(
    SourceFormat.SMTLIB,
    numericAuthority = NumericAuthority.RATIONAL_LITERALS,
    floats = FloatSemantics.Continuous,
    undeclaredIntegers = UndeclaredIntegerBounds(),
    undeclaredReals = UndeclaredRealBounds(),
)

internal fun resolveSmtLibSemantics(
    unboundedIntLo: Long,
    unboundedIntHi: Long,
    overrides: SemanticOverrides = SemanticOverrides(),
): EffectiveSourceSemantics {
    require(overrides.floats == null || overrides.floats == FloatSemantics.Continuous) {
        "SMT-LIB requires continuous real semantics"
    }
    val legacy = BoundsOverride(
        lower = if (unboundedIntLo == Long.MIN_VALUE) BoundOverride.Open else BoundOverride.Value(unboundedIntLo),
        upper = if (unboundedIntHi == Long.MAX_VALUE) BoundOverride.Open else BoundOverride.Value(unboundedIntHi),
    )
    return smtLibSemantics.resolve(overrides.copy(undeclaredIntegers = BoundsOverride(
        overrides.undeclaredIntegers?.lower ?: legacy.lower,
        overrides.undeclaredIntegers?.upper ?: legacy.upper,
    )))
}
