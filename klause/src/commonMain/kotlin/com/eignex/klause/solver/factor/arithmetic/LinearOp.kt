package com.eignex.klause.solver.factor.arithmetic

/** Relational operator for a [Linear] constraint. */
enum class LinearOp {
    /** `≤`. */
    LE,

    /** `=`. */
    EQ,

    /** `≥`. */
    GE,

    /** `≠`. */
    NE,
}
