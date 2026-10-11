package com.eignex.klause.lp.engine

import kotlin.jvm.JvmInline

/** Column handle in one LP layout; remapping or rebuilding a layout requires a fresh mapping. */
@JvmInline
value class LpColumnId(
    /** Position in the owning LP column layout. */
    val index: Int,
) {
    init {
        require(index >= 0) { "LP column id must not be negative" }
    }
}
