package com.eignex.klause.formats.flatzinc

/** Reified bool comparison used by `bool_{eq,le,lt}_reif`. */
internal enum class BoolCmpOp { EQ, LE, LT }

/** `(strictly_)(in|de)creasing_{int,bool}` variants. */
internal enum class MonotoneOp(val ascending: Boolean, val strict: Boolean) {
    INCREASING(ascending = true, strict = false),
    DECREASING(ascending = false, strict = false),
    STRICTLY_INCREASING(ascending = true, strict = true),
    STRICTLY_DECREASING(ascending = false, strict = true),
}

/** `global_cardinality*` variants. */
internal enum class GccVariant(val lowUp: Boolean, val closed: Boolean) {
    STANDARD(lowUp = false, closed = false),
    CLOSED(lowUp = false, closed = true),
    LOW_UP(lowUp = true, closed = false),
    LOW_UP_CLOSED(lowUp = true, closed = true),
}
