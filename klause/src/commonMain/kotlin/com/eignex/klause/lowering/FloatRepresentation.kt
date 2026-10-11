package com.eignex.klause.lowering

/** Representation and approximation provenance for a connected float component. */
data class FloatRepresentation(
    /** Component identity local to one compiled source model. */
    val component: Int,
    /** Why this component uses a finite grid; null preserves source binary64 rationals. */
    val approximation: String? = null,
) {
    /** Whether solving this component requires source checks and carries no source proof. */
    val approximate: Boolean get() = approximation != null
}
