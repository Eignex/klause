package com.eignex.klause.theory

import com.eignex.klause.util.Cancellation

/** Cooperative limits shared by complete open-model theories. */
data class TheoryParams(
    /** Legacy maximum theory-check allowance; this is not a complete leaf count. */
    val maxLeaves: Long = Long.MAX_VALUE,
    /** Solve-wide deterministic open-theory work allowance. */
    val openWorkLimit: Long = Long.MAX_VALUE,
    /** Wall-clock timeout token, kept separate from external cancellation for result reporting. */
    val timeout: Cancellation = Cancellation.Never,
    /** Cooperative cancellation token. */
    val cancellation: Cancellation = Cancellation.Never,
)
