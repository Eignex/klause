package com.eignex.klause.propagation

/**
 * Three-tier learned-clause database classification (C. Oh, "Between SAT and UNSAT: The Fundamental
 * Difference in CDCL SAT", SAT 2015, https://doi.org/10.1007/978-3-319-24318-4_23). The reduction policy
 * in the backtrack engine assigns and ages each learned clause through these tiers; the tier is
 * persistent per-clause state (rather than a pure function of LBD) so reuse can promote a
 * clause and idleness demote it.
 */
internal enum class ClauseTier {
    /** Not yet classified — the reduction policy assigns a tier by LBD on first encounter. */
    UNSET,

    /** Permanent core: very low LBD, never deleted. */
    CORE,

    /** Mid tier: kept across reductions, demoted to [LOCAL] when idle. */
    MID,

    /** Local tier: aggressively deleted; promoted to [MID] on reuse. */
    LOCAL,
}
