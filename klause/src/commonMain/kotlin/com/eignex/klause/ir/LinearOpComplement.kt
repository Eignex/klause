package com.eignex.klause.ir

/**
 * The relation of the negated comparison: `=` and `≠` swap, and `≤` and `≥` swap with the caller flipping
 * strictness (the negation of `≤ b` is `> b`).
 */
fun LinearOp.complemented(): LinearOp = when (this) {
    LinearOp.LE -> LinearOp.GE
    LinearOp.GE -> LinearOp.LE
    LinearOp.EQ -> LinearOp.NE
    LinearOp.NE -> LinearOp.EQ
}
