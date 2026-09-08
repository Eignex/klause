package com.eignex.klause.ir

/** The comparison that holds when this one does not. An equality has no single complement. */
fun LinearOp.complemented(): LinearOp = when (this) {
    LinearOp.LE -> LinearOp.GE
    LinearOp.GE -> LinearOp.LE
    LinearOp.EQ -> LinearOp.NE
    LinearOp.NE -> LinearOp.EQ
}
