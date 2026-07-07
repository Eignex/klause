package com.eignex.klause.lp

/**
 * A gated convex-hull family a factor's LP relaxation can belong to. A factor names its family once via
 * [com.eignex.klause.solver.Factor.hullFamily]; the relaxation driver gates it with [HullFlags] and the
 * LP auto-config groups factors by it — so neither pattern-matches the concrete factor type.
 */
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

/**
 * The per-family convex-hull switches for one relaxation build — each names a gated hull the driver
 * ([com.eignex.klause.lp.relaxation.CpToLpRelaxation]) can turn on. A factor's
 * [com.eignex.klause.solver.Factor.hullFamilyEnabled] reads its own family's flag through [enabled]; the
 * driver owns the values (from its plan) and never pattern-matches the factor type to pick one.
 */
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

/** The LP columns and rows a factor's [com.eignex.klause.solver.Factor.lpSizeEstimate] predicts its
 *  hull adds (upper bounds). */
class LinearizerEstimate(
    /** Upper bound on the LP columns the hull contribution adds. */
    val cols: Long,
    /** Upper bound on the LP rows the hull contribution adds. */
    val rows: Long,
)

/**
 * How the root relaxation treats an emitted row. [CORE] rows define the relaxation's feasible region
 * and are always kept; [HULL] rows only strengthen the bound and may be dropped when they add no root
 * strength. Dropping rows from a valid relaxation only loosens it — it never invalidates it — so this
 * gates effort, not soundness.
 */
enum class Contribution {
    /** A feasibility-defining row, always kept. */
    CORE,

    /** A bound-strengthening row the root pruner may drop when it adds no strength. */
    HULL,
}
