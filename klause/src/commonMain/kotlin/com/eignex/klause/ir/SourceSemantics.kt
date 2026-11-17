package com.eignex.klause.ir

import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.isZero
import com.eignex.util.numbers.signum

/** Syntax owner of a source model; this identity selects no execution mechanism. */
enum class SourceFormat {
    /** Programmatically constructed engine-neutral models. */
    IR,

    /** Typed schema declarations. */
    SCHEMA,

    /** FlatZinc source syntax. */
    FLATZINC,

    /** SMT-LIB source syntax. */
    SMTLIB,

    /** MPS decimal linear models. */
    MPS,

    /** XCSP3 integer constraint syntax. */
    XCSP3,

    /** OPB and WBO pseudo-Boolean syntax. */
    OPB,

    /** DIMACS conjunctive normal form. */
    DIMACS,

    /** Weighted conjunctive normal form. */
    WCNF,
}

/** Interpretation of real literals before any numerical execution projection. */
enum class NumericAuthority {
    /** Exact values explicitly supplied by the model producer. */
    EXACT_VALUES,

    /** Exact rational interpretation of parsed binary64 values. */
    BINARY64_LITERALS,

    /** Exact rational interpretation of decimal source tokens. */
    DECIMAL_LITERALS,

    /** Exact rational interpretation of rational source terms. */
    RATIONAL_LITERALS,
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
    val lower: SemanticRational? = null,
    /** Inclusive finite upper endpoint, or null for no upper bound. */
    val upper: SemanticRational? = null,
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
            SemanticRational.ofLong(-1_000_000), SemanticRational.ofLong(1_000_000),
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
 * it is the finite bound magnitude. A zero relative component makes the slack the same absolute
 * amount at every magnitude. Integrality is checked exactly, independently of this slack.
 */
data class SourceTolerance(
    /** Minimum permitted absolute slack in source units. */
    val absolute: SemanticRational = SemanticRational.ZERO,
    /** Permitted slack relative to the checked source scale. */
    val relative: SemanticRational = SemanticRational.ZERO,
) {
    init {
        require(absolute.numerator.signum() >= 0) { "absolute tolerance must be nonnegative" }
        require(relative.numerator.signum() >= 0) { "relative tolerance must be nonnegative" }
    }

    /** Whether no source feasibility slack is permitted: both components are zero. */
    val isExact: Boolean get() = absolute.numerator.isZero() && relative.numerator.isZero()
}

/**
 * Optimality gap within which two evaluations of one point's objective agree: a source value `v` and a native
 * value `p` agree when `abs(v - p) <= max(absolute, relative * scale)`, where `scale` is the sum of the absolute
 * objective term activities at that point, constant excluded. It decides whether a point is reported as the optimum
 * the solver proved there; it bounds no search, makes no unproved bound an optimum and makes no other point optimal.
 * Zero in both components requires equal values, and a zero feasibility tolerance makes the effective gap zero.
 */
data class OptimalityTolerance(
    /** Minimum permitted objective difference in source units. */
    val absolute: SemanticRational = SemanticRational.ZERO,
    /** Permitted objective difference relative to the objective term magnitudes at the point. */
    val relative: SemanticRational = SemanticRational.ZERO,
) {
    init {
        require(absolute.numerator.signum() >= 0) { "absolute optimality tolerance must be nonnegative" }
        require(relative.numerator.signum() >= 0) { "relative optimality tolerance must be nonnegative" }
    }

    /** Whether objective values must be equal: both components are zero. */
    val isExact: Boolean get() = absolute.numerator.isZero() && relative.numerator.isZero()
}

// Slack of the formats that accept numerical results by default. It is absolute only, so a row's slack does not
// grow with the magnitude of its terms; a caller who wants a scale-dependent slack overrides the relative part.
internal val numericalSourceTolerance: SourceTolerance = SourceTolerance(
    absolute = SemanticRational.of(BIG_ONE, bigIntOf(10_000_000L)),
)

// Optimality gap of the same formats. Its absolute floor is a separate constant equal to the default absolute
// feasibility slack, the amount by which an accepted point's rows, and so a small objective, are already uncertain.
internal val numericalOptimalityTolerance: OptimalityTolerance = OptimalityTolerance(
    absolute = numericalSourceTolerance.absolute,
    relative = SemanticRational.of(BIG_ONE, bigIntOf(10_000L)),
)

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
    /** Numerical optimality gap; zero components require equal objective values, null makes this inapplicable. */
    val optimalityTolerance: OptimalityTolerance? = null,
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
        require(overrides.optimalityTolerance == null || optimalityTolerance != null) {
            "optimality tolerance does not apply to $format"
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
                optimalityTolerance = effectiveOptimality(overrides),
            ),
        )
    }

    // Exact feasibility certifies exactly, so its objective values must agree exactly too: the declared gap becomes
    // zero, and a caller's positive gap beside exact feasibility is a contradiction.
    private fun effectiveOptimality(overrides: SemanticOverrides): OptimalityTolerance? {
        val feasibility = overrides.feasibilityTolerance ?: feasibilityTolerance
        val gap = overrides.optimalityTolerance ?: optimalityTolerance ?: return null
        if (feasibility != null && !feasibility.isExact) return gap
        require(overrides.optimalityTolerance?.isExact != false) {
            "a positive optimality tolerance requires a positive feasibility tolerance"
        }
        return OptimalityTolerance()
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
    val undeclaredReals: BoundsOverride<SemanticRational>? = null,
    /** Replacement absolute and relative source feasibility slack. */
    val feasibilityTolerance: SourceTolerance? = null,
    /** Replacement absent-value canonicalization. */
    val pinAbsentOptionalValues: Boolean? = null,
    /** Replacement absolute and relative optimality gap. */
    val optimalityTolerance: OptimalityTolerance? = null,
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
     * Whether policy declares grid defaults or a positive tolerance. Individual components can
     * retain exact representations. This flag establishes no witness validity, completeness,
     * refutation authority or independently checked proof.
     */
    val isApproximate: Boolean
        get() = effective.floats is FloatSemantics.Grid || effective.feasibilityTolerance?.isExact == false

    // A format that types its floats states integer and Boolean relations exactly, so its slack reaches only
    // relations over a real coordinate; an untyped decimal format reads every row within the slack.
    internal fun relaxesRows(realCoordinates: Int): Boolean =
        effective.feasibilityTolerance?.isExact == false && (effective.floats == null || realCoordinates > 0)
}

/** Immutable source declaration policy, independent of a strategy or execution projection. */
interface SourceSemanticsOwner {
    /** Effective policy retained unchanged for this source's lifetime. */
    val semantics: EffectiveSourceSemantics
}

internal val defaultIrSemantics: EffectiveSourceSemantics = SourceSemantics(SourceFormat.IR).resolve()

/** Source payload paired with the effective semantics under which it was lowered. */
data class SemanticSource<out M>(
    /** Immutable or borrowed-read-only source representation. */
    val model: M,
    /** Declared defaults and caller replacements captured before lowering. */
    override val semantics: EffectiveSourceSemantics,
) : SourceSemanticsOwner
