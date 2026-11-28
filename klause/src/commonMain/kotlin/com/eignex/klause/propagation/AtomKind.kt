package com.eignex.klause.propagation

// A [SharedClause] carries an atom's kind as its ordinal, decoded by PropagationSession.importClause,
// so the declaration order must not change.
internal enum class AtomKind {
    GE,

    LE,

    EQ,
}
