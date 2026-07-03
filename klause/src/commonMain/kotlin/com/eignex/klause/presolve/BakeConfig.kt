package com.eignex.klause.presolve

/**
 * Which root-bake probing tiers [RootBaker.bake] runs, and their budgets. Formerly the six `probe*`
 * parameters on the kernel `Problem`; now threaded through the presolve / backtrack lanes so the kernel
 * carries no probing policy. [NONE] disables all probing (the common case — a plain [RootBaker.bake] is
 * then a no-op returning the base bake).
 */
class BakeConfig(
    /**
     * Failed-literal probing. When `true`, every free bool variable is tested with both polarities: if
     * pinning one polarity propagates Unsat, the other polarity is forced. Iterated to a fixed point.
     */
    val probeFailedLiterals: Boolean = false,
    /**
     * Bound-SAC (singleton arc consistency). After [probeFailedLiterals] settles, every int var with a
     * multi-value domain has its min and max probed: pin the bound, propagate, and if Unsat, tighten the
     * bound by one and loop. Captures bound-level deductions the per-call propagator misses.
     */
    val probeIntBounds: Boolean = false,
    /**
     * Interior-hole SAC. Builds on [probeIntBounds]: after bound-SAC settles, each multi-value int var
     * has its interior values (strictly between current min and max) probed; on Unsat the value is
     * recorded as an interior hole. Implies [probeIntBounds].
     */
    val probeIntHoles: Boolean = false,
    /**
     * Cap on per-var probe calls during SAC. After this many `propagate` calls targeting one var (across
     * both bound and hole probing), the loop stops probing that var. Unlimited by default.
     */
    val probeBudgetPerVar: Int = Int.MAX_VALUE,
    /**
     * Cap on total probe calls across all vars and all SAC passes. Once exceeded, the SAC loops exit
     * gracefully with whatever tightenings they've accumulated. Unlimited by default.
     */
    val probeTotalBudget: Int = Int.MAX_VALUE,
    /**
     * Seed for the RNG that breaks ties in the wdeg-weighted SAC probe order. Deterministic for a given
     * seed.
     */
    val probeSeed: Long = 0L,
) {
    /** True iff any probing tier is enabled — when false, [RootBaker.bake] returns the base bake as-is. */
    val anyEnabled: Boolean get() = probeFailedLiterals || probeIntBounds || probeIntHoles

    /** Shared config and the [PresolveConfig]-derived factory. */
    companion object {
        /** No probing — the kernel base bake stands alone. */
        val NONE: BakeConfig = BakeConfig()

        /**
         * Resolve the root-bake probing tiers from a [PresolveConfig] under [context]. The construction-
         * time SAC probes are solution-preserving, so they resolve independently of query intent (the
         * compile sites use [PresolveContext.EMPTY]); interior-hole SAC implies bound SAC.
         */
        fun from(config: PresolveConfig, context: PresolveContext = PresolveContext.EMPTY): BakeConfig {
            val holes = config.resolved(PresolvePass.PROBE_INT_HOLES, context)
            return BakeConfig(
                probeFailedLiterals = config.resolved(PresolvePass.PROBE_FAILED_LITERALS, context),
                probeIntBounds = holes || config.resolved(PresolvePass.PROBE_INT_BOUNDS, context),
                probeIntHoles = holes,
                probeBudgetPerVar = config.probeBudgetPerVar(),
                probeTotalBudget = config.probeTotalBudget(),
            )
        }
    }
}
