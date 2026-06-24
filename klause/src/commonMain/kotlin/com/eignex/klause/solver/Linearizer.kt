package com.eignex.klause.solver

/**
 * A factor's contribution to the LP relaxation — the LP-engine analogue of [Propagator] (the CP
 * engine) and [Invariant] (the local-search engine). A factor with linear structure returns one
 * from [Factor.asLinearizer]; factors with no linear relaxation keep the [NoLinearizer] default and
 * emit nothing.
 *
 * The relaxation driver calls [linearize] once per relaxation build, passing the [RelaxationBuilder]
 * the factor emits into and the factor's index in [Problem.factors] as `factorId`. A single
 * linearize pass may mix [Contribution.CORE] and [Contribution.HULL] rows — the kind is chosen per
 * row at emit time, not per factor.
 */
interface Linearizer {
    /** Emit this factor's rows, columns, and auxiliary variables into [builder]. Default: nothing. */
    fun linearize(builder: RelaxationBuilder, factorId: Int) {}

    /**
     * An upper-bound estimate of the LP columns and rows this factor's convex-hull contribution would
     * add under the declared [domains], or null when it contributes no sized hull — over its size cap,
     * no applicable structure, or no hull at all. The LP auto-config sums these to keep the per-node
     * tableau under budget, so the estimate must track [linearize]'s own caps and structure (the two
     * live in the same class so they stay in step). Default: null.
     */
    fun sizeEstimate(domains: Array<IntDomain>): LinearizerEstimate? = null
}

/** The LP columns and rows a [Linearizer.sizeEstimate] predicts its hull adds (upper bounds). */
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

/** The default [Linearizer] for factors with no linear relaxation: contributes nothing. */
object NoLinearizer : Linearizer
