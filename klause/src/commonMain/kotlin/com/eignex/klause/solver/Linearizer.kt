package com.eignex.klause.solver

/**
 * A factor's contribution to the LP relaxation — the LP-engine analogue of [Propagator] (the CP
 * engine) and [Invariant] (the local-search engine). A factor with linear structure returns one
 * from [Factor.asLinearizer]; factors with no linear relaxation keep the [NoLinearizer] default and
 * emit nothing.
 *
 * The relaxation driver calls [linearize] once per relaxation build, passing the [RelaxationBuilder]
 * the factor emits into and the factor's index in [Problem.factors] as `factorId`.
 */
interface Linearizer {
    /**
     * How the root relaxation treats this factor's rows. [Contribution.CORE] rows define the
     * relaxation's feasible region and are always kept; [Contribution.HULL] rows only strengthen the
     * bound and may be dropped when they add no root strength. Dropping rows from a valid relaxation
     * only loosens it — it never invalidates it — so this gates effort, not soundness.
     */
    val contribution: Contribution get() = Contribution.HULL

    /** Emit this factor's rows, columns, and auxiliary variables into [builder]. Default: nothing. */
    fun linearize(builder: RelaxationBuilder, factorId: Int) {}
}

/** Whether a [Linearizer]'s rows define the relaxation ([CORE]) or only strengthen it ([HULL]). */
enum class Contribution {
    /** Feasibility-defining rows, always kept. */
    CORE,

    /** Bound-strengthening rows the root pruner may drop when they add no strength. */
    HULL,
}

/** The default [Linearizer] for factors with no linear relaxation: contributes nothing. */
object NoLinearizer : Linearizer
