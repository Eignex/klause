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
}

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
