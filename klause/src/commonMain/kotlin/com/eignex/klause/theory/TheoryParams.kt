package com.eignex.klause.theory

import com.eignex.klause.solver.Cancellation

/** Cooperative limits shared by complete open-model theories. */
data class TheoryParams(
    /** Complete leaf checks allowed before returning an unknown verdict. */
    val maxLeaves: Long = Long.MAX_VALUE,
    /** Stops the theory without coupling it to a finite-domain search engine. */
    val cancellation: Cancellation = Cancellation.Never,
)
