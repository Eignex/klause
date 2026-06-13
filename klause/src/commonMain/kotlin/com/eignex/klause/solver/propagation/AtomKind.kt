package com.eignex.klause.solver.propagation

/**
 * The three relational forms a bound/value atom over an int var can take. Ordinals are
 * load-bearing: they index the per-var atom memo (`intVar * 3 + kind.ordinal`) and occupy
 * bits 32–33 of the atom key, so the declaration order [GE], [LE], [EQ] must not change.
 */
internal enum class AtomKind {
    /** `[x ≥ k]`. */
    GE,

    /** `[x ≤ k]`. */
    LE,

    /** `[x = k]`. */
    EQ,
}
