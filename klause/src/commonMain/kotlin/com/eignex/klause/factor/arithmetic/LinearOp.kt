package com.eignex.klause.factor.arithmetic

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
