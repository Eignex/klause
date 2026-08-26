package com.eignex.klause.theory

import com.eignex.klause.util.Cancellation

/** Cooperative limits shared by complete open-model theories. */
data class TheoryParams(
    /** Maximum complete leaf checks. */
    val maxLeaves: Long = Long.MAX_VALUE,
    /** Cooperative cancellation token. */
    val cancellation: Cancellation = Cancellation.Never,
)
