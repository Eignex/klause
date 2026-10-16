package com.eignex.klause.ir

import com.eignex.klause.simplex.exact.BigFraction

/** Syntax owner of a source model; this identity selects no execution mechanism. */
enum class SourceFormat {
    IR, SCHEMA, FLATZINC, SMTLIB, MPS, XCSP3, OPB, DIMACS, WCNF,
}

/** Interpretation of real literals before any numerical execution projection. */
enum class NumericAuthority {
    EXACT_VALUES, BINARY64_LITERALS, DECIMAL_LITERALS, RATIONAL_LITERALS,
}

/** Default range of an integer declaration with no source range; null endpoints remain open. */
data class UndeclaredIntegerBounds(
    /** Inclusive lower endpoint, or null for no lower bound. */
    val lower: Long? = null,
    /** Inclusive upper endpoint, or null for no upper bound. */
    val upper: Long? = null,
) {
    init {
        require(lower == null || upper == null || lower <= upper) { "integer lower bound exceeds upper bound" }
    }
}

/** Default range of a real declaration with no source range; null endpoints remain open. */
data class UndeclaredRealBounds(
    /** Inclusive finite lower endpoint, or null for no lower bound. */
    val lower: BigFraction? = null,
    /** Inclusive finite upper endpoint, or null for no upper bound. */
    val upper: BigFraction? = null,
) {
    init {
        require(lower == null || upper == null || lower <= upper) { "real lower bound exceeds upper bound" }
    }
}

/** Declared interpretation of float variables, independent of their execution owner. */
sealed interface FloatSemantics {
    /** Continuous values under the source's numeric authority. */
    data object Continuous : FloatSemantics

    /** Bounded grid defaults for declarations without an explicit per-variable grid. */
    data class Grid(
        /** Number of grid points. */
        val buckets: Int,
        /** Fixed-point scale of float-linear source lowering. */
        val linearScale: Long,
        /** Approximation window for a float without a declared finite range. */
        val unrangedBounds: UndeclaredRealBounds = UndeclaredRealBounds(
            BigFraction.ofLong(-1_000_000), BigFraction.ofLong(1_000_000),
        ),
    ) : FloatSemantics {
        init {
            require(buckets > 0) { "float buckets must be positive" }
            require(linearScale > 0) { "float linear scale must be positive" }
            require(unrangedBounds.lower != null && unrangedBounds.upper != null) {
                "a float grid requires a finite approximation window"
            }
        }
    }
}

/**
 * Source feasibility slack `max(absolute, relative * scale)`. For a row, scale is the maximum
 * of the finite bound magnitude and the sum of absolute term activities; for a variable bound
 * it is the finite bound magnitude. Integrality is checked exactly, independently of this slack.
 */
data class SourceTolerance(
    /** Minimum permitted absolute slack in source units. */
    val absolute: BigFraction = BigFraction.ZERO,
    /** Permitted slack relative to the checked source scale. */
    val relative: BigFraction = BigFraction.ZERO,
) {
    init {
        require(absolute.signum() >= 0) { "absolute tolerance must be nonnegative" }
        require(relative.signum() >= 0) { "relative tolerance must be nonnegative" }
    }

    /** Whether no source feasibility slack is permitted. */
    val isExact: Boolean get() = absolute.isZero && relative.isZero
}

/**
 * Declared source defaults. Optional policies are absent when that semantic has no meaning
 * for the source. Bounds apply only to declarations without their own range.
 * This value contains no engine, theory, route or resource policy.
 */
data class SourceSemantics(
    /** Originating source syntax. */
    val format: SourceFormat,
    /** Authority of numeric source values. */
    val numericAuthority: NumericAuthority = NumericAuthority.EXACT_VALUES,
    /** Float interpretation, or null when the source has no configurable float interpretation. */
    val floats: FloatSemantics? = null,
    /** Default undeclared integer range, or null when integer declarations require a range. */
    val undeclaredIntegers: UndeclaredIntegerBounds? = null,
    /** Default undeclared real range, or null when real declarations require a range. */
    val undeclaredReals: UndeclaredRealBounds? = null,
    /** Source feasibility slack; zero components require exact checking, null makes this inapplicable. */
    val feasibilityTolerance: SourceTolerance? = null,
    /** Canonicalization of absent optional values, or null when the source has no optional values. */
    val pinAbsentOptionalValues: Boolean? = null,
) {
    /** Resolve caller semantics before lowering, rejecting every inapplicable override. */
    fun resolve(overrides: SemanticOverrides = SemanticOverrides()): EffectiveSourceSemantics {
        require(overrides.floats == null || floats != null) { "float interpretation does not apply to $format" }
        require(overrides.undeclaredIntegers == null || undeclaredIntegers != null) {
            "undeclared integer bounds do not apply to $format"
        }
        require(overrides.undeclaredReals == null || undeclaredReals != null) {
            "undeclared real bounds do not apply to $format"
        }
        require(overrides.feasibilityTolerance == null || feasibilityTolerance != null) {
            "feasibility tolerance does not apply to $format"
        }
        require(overrides.pinAbsentOptionalValues == null || pinAbsentOptionalValues != null) {
            "absent optional values do not apply to $format"
        }
        return EffectiveSourceSemantics(
            declared = this,
            effective = copy(
                floats = overrides.floats ?: floats,
                undeclaredIntegers = overrides.undeclaredIntegers?.let { bounds ->
                    UndeclaredIntegerBounds(
                        bounds.lower.resolveEndpoint(undeclaredIntegers?.lower),
                        bounds.upper.resolveEndpoint(undeclaredIntegers?.upper),
                    )
                } ?: undeclaredIntegers,
                undeclaredReals = overrides.undeclaredReals?.let { bounds ->
                    UndeclaredRealBounds(
                        bounds.lower.resolveEndpoint(undeclaredReals?.lower),
                        bounds.upper.resolveEndpoint(undeclaredReals?.upper),
                    )
                } ?: undeclaredReals,
                feasibilityTolerance = overrides.feasibilityTolerance ?: feasibilityTolerance,
                pinAbsentOptionalValues = overrides.pinAbsentOptionalValues ?: pinAbsentOptionalValues,
            ),
        )
    }
}

/** Explicit endpoint replacement, including opening an endpoint that a format bounds by default. */
sealed interface BoundOverride<out T> {
    /** Leave the endpoint unbounded. */
    data object Open : BoundOverride<Nothing>

    /** Replace the endpoint with the supplied source value. */
    data class Value<T>(
        /** Inclusive source endpoint. */
        val value: T,
    ) : BoundOverride<T>
}

/** Independent range endpoint replacements; null preserves that endpoint's declared default. */
data class BoundsOverride<T>(
    /** Replacement lower endpoint. */
    val lower: BoundOverride<T>? = null,
    /** Replacement upper endpoint. */
    val upper: BoundOverride<T>? = null,
)

private fun <T> BoundOverride<T>?.resolveEndpoint(default: T?): T? = when (this) {
    null -> default
    BoundOverride.Open -> null
    is BoundOverride.Value -> value
}

/** Uniform caller replacements for applicable source policies; null leaves a policy unchanged. */
data class SemanticOverrides(
    /** Replacement float interpretation. */
    val floats: FloatSemantics? = null,
    /** Replacement default integer endpoints, including explicit opening. */
    val undeclaredIntegers: BoundsOverride<Long>? = null,
    /** Replacement default real endpoints, including explicit opening. */
    val undeclaredReals: BoundsOverride<BigFraction>? = null,
    /** Replacement absolute and relative source feasibility slack. */
    val feasibilityTolerance: SourceTolerance? = null,
    /** Replacement absent-value canonicalization. */
    val pinAbsentOptionalValues: Boolean? = null,
)

/** Declared defaults and the effective caller contract retained with a lowered source and its results. */
class EffectiveSourceSemantics internal constructor(
    /** Original format defaults. */
    val declared: SourceSemantics,
    /** Semantics used to lower and check this source. */
    val effective: SourceSemantics,
) {
    /** Whether caller policy changed any part of the source interpretation. */
    val hasOverrides: Boolean get() = declared != effective

    /**
     * Whether source checking requires a grid or a positive tolerance. False does not establish
     * witness validity, completeness, refutation authority or an independently checked proof.
     */
    val isApproximate: Boolean
        get() = effective.floats is FloatSemantics.Grid || effective.feasibilityTolerance?.isExact == false
}

/** Source payload paired with the effective semantics under which it was lowered. */
data class SemanticSource<out M>(
    /** Immutable or borrowed-read-only source representation. */
    val model: M,
    /** Declared defaults and caller replacements captured before lowering. */
    val semantics: EffectiveSourceSemantics,
)
