package com.eignex.klause.ir

/** The optimisation direction of a parsed objective, shared across the input formats. */
enum class ObjectiveSense {
    /** Minimise the objective. */
    MINIMIZE,

    /** Maximise the objective. */
    MAXIMIZE,
}
