package com.eignex.klause.propagation

/**
 * The three relational forms a bound/value atom over an int var can take. Ordinals are
 * load-bearing: a [SharedClause] carries an atom's kind as its ordinal, decoded back by
 * [PropagationSession.importClause], so the declaration order [GE], [LE], [EQ] must not change.
 */
internal enum class AtomKind {
    /** `[x ≥ k]`. */
    GE,

    /** `[x ≤ k]`. */
    LE,

    /** `[x = k]`. */
    EQ,
}
