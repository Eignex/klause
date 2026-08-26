package com.eignex.klause.ir

/** Relational operator for a linear constraint. */
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
