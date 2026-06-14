package com.eignex.klause.formats.flatzinc

/*
 * Operator/variant enums for the FlatZinc builtin dispatch in FlatZincConstraints. Each
 * replaces a cluster of exclusive boolean emitter parameters with a single self-documenting
 * argument, so the `when`-arm reads as the builtin it handles (e.g. BoolCmpOp.LE) rather
 * than a `(eq = false, le = true, lt = false)` flag tuple. The product-shaped variants carry
 * their decomposed flags as properties so the emitter bodies stay byte-for-byte the same.
 */

/** The reified bool comparison behind `bool_{eq,le,lt}_reif`: `r ↔ (a ⟨op⟩ b)`. The three
 *  cases are mutually exclusive, so the emitter's `when (op)` is exhaustive. */
internal enum class BoolCmpOp { EQ, LE, LT }

/**
 * The four `(strictly_)(in|de)creasing_{int,bool}` ordering builtins. [ascending] selects
 * increasing over decreasing; [strict] requires a strict (`>`) rather than non-strict (`≥`)
 * step between adjacent elements.
 */
internal enum class MonotoneOp(val ascending: Boolean, val strict: Boolean) {
    INCREASING(ascending = true, strict = false),
    DECREASING(ascending = false, strict = false),
    STRICTLY_INCREASING(ascending = true, strict = true),
    STRICTLY_DECREASING(ascending = false, strict = true),
}

/**
 * The four `global_cardinality*` builtin shapes. [lowUp] selects the explicit lower/upper
 * count-bound form (4 args) over the plain counts form (3 args); [closed] forbids `xs`
 * values outside the `cover` set.
 */
internal enum class GccVariant(val lowUp: Boolean, val closed: Boolean) {
    STANDARD(lowUp = false, closed = false),
    CLOSED(lowUp = false, closed = true),
    LOW_UP(lowUp = true, closed = false),
    LOW_UP_CLOSED(lowUp = true, closed = true),
}
