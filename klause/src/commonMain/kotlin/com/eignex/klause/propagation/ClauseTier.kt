package com.eignex.klause.propagation

/**
 * Three-tier learned-clause database classification (#201). The reduction policy in
 * `BacktrackSolver` assigns and ages each learned clause through these tiers; the tier is
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
