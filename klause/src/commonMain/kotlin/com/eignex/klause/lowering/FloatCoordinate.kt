package com.eignex.klause.lowering

/** Source real coordinate and declared interval retained by the frontend. */
data class FloatCoordinate(
    /** Real variable id. */
    val varId: Int,
    /** Inclusive lower bound, negative infinity when open. */
    val lo: Double,
    /** Inclusive upper bound, positive infinity when open. */
    val hi: Double,
)
