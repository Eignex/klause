package com.eignex.klause.propagation

// Three-tier learned-clause classification (C. Oh, "Between SAT and UNSAT: The Fundamental Difference in CDCL
// SAT", SAT 2015, https://doi.org/10.1007/978-3-319-24318-4_23). The tier is persistent per-clause state rather
// than a function of LBD, so reuse can promote a clause and idleness demote it.
internal enum class ClauseTier {
    // Assigned by LBD on first encounter.
    UNSET,

    // Very low LBD, never deleted.
    CORE,

    // Kept across reductions, demoted to [LOCAL] when idle.
    MID,

    // Aggressively deleted; promoted to [MID] on reuse.
    LOCAL,
}
