package com.eignex.klause.lowering.smtlib

import com.eignex.klause.formats.smtlib.*

import com.eignex.klause.ir.LinearOp

/** The strict-inequality bound offset for a folded relation operator (`< / >` tighten by ∓1). */
internal fun strictDelta(op: String): Int = when (op) {
    "<" -> -1
    ">" -> 1
    else -> 0
}

/** The linear operator a folded relation lowers to (before the [strictDelta] bound offset). */
internal fun relLinearOp(op: String): LinearOp = when (op) {
    "<=", "<" -> LinearOp.LE
    ">=", ">" -> LinearOp.GE
    "=" -> LinearOp.EQ
    "distinct" -> LinearOp.NE
    else -> throw UnsupportedSmtException("relation '$op'")
}
