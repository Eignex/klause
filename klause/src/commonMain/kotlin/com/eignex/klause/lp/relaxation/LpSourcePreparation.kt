package com.eignex.klause.lp.relaxation

/** Immutable source preparation retained between compatible LP consumers. */
enum class LpSourcePreparation {
    /** Build each relaxation without retaining or consuming source templates. */
    DISABLED,

    /** Retain immutable definitions and validate them on each consumer. */
    OBJECT,

    /** Reuse a complete matching template index. */
    INDEX,

    /** Admit selective metadata after completed construction earns its inspection cost. */
    ADMITTED,
}
