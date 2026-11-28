package com.eignex.klause.lp

/** An optional convex-hull family of a factor's LP relaxation, switched per build by [HullFlags]. */
enum class HullFamily {
    /** Constant-array Element one-hot selector hull. */
    ELEMENT,

    /** Table per-tuple selector hull. */
    TABLE,

    /** NValue one-hot value hull. */
    NVALUE,

    /** Regular layer-expanded DFA flow hull. */
    REGULAR,

    /** Mdd layered flow hull. */
    MDD,

    /** Count-variable GlobalCardinality one-hot selector hull. */
    GCC_COUNT,

    /** ArrayMinMax tight face (Anderson big-M) on top of the envelope. */
    ARRAY_MIN_MAX,

    /** Product McCormick envelope. */
    PRODUCT,
}

/** The per-family convex-hull switches for one relaxation build. */
class HullFlags(
    /** Constant-array Element one-hot selector hull. */
    val element: Boolean,
    /** Table per-tuple selector hull. */
    val table: Boolean,
    /** NValue one-hot value hull. */
    val nValue: Boolean,
    /** Regular layer-expanded DFA flow hull. */
    val regular: Boolean,
    /** Mdd layered flow hull. */
    val mdd: Boolean,
    /** Count-variable GlobalCardinality one-hot selector hull. */
    val gccCount: Boolean,
    /** ArrayMinMax tight face (Anderson big-M) on top of the envelope. */
    val arrayMinMax: Boolean,
    /** Product McCormick envelope. */
    val product: Boolean,
) {
    /** Whether [family]'s hull is switched on this build. */
    fun enabled(family: HullFamily): Boolean = when (family) {
        HullFamily.ELEMENT -> element
        HullFamily.TABLE -> table
        HullFamily.NVALUE -> nValue
        HullFamily.REGULAR -> regular
        HullFamily.MDD -> mdd
        HullFamily.GCC_COUNT -> gccCount
        HullFamily.ARRAY_MIN_MAX -> arrayMinMax
        HullFamily.PRODUCT -> product
    }
}

/** Upper bounds on the LP columns and rows a factor's optional hull adds. */
class LpSizeEstimate(
    /** Upper bound on the LP columns the hull contribution adds. */
    val cols: Long,
    /** Upper bound on the LP rows the hull contribution adds. */
    val rows: Long,
)

/**
 * How the root relaxation treats an emitted row. Dropping a [HULL] row only loosens the relaxation, so this
 * gates effort, not soundness.
 */
enum class Contribution {
    /** A feasibility-defining row, always kept. */
    CORE,

    /** A bound-strengthening row the root pruner may drop when it adds no strength. */
    HULL,
}
