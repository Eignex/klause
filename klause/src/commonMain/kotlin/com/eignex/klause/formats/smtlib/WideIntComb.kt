package com.eignex.klause.formats.smtlib

import com.eignex.klause.ir.LinearOp

internal fun strictDelta(op: String): Int = when (op) {
    "<" -> -1
    ">" -> 1
    else -> 0
}

internal fun relLinearOp(op: String): LinearOp = when (op) {
    "<=", "<" -> LinearOp.LE
    ">=", ">" -> LinearOp.GE
    "=" -> LinearOp.EQ
    "distinct" -> LinearOp.NE
    else -> throw UnsupportedSmtException("relation '$op'")
}
