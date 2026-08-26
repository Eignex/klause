package com.eignex.klause.ir

/**
 * The optimisation direction of a parsed objective, shared across the input formats (MPS `OBJSENSE`,
 * SMT-LIB and XCSP3 `minimize`/`maximize`). Satisfaction/decision instances carry no objective and so
 * no sense. Front-ends convert to the solver pipeline's boolean at their CLI boundary
 * (`sense == MAXIMIZE`).
 */
enum class ObjectiveSense {
    /** Minimise the objective. */
    MINIMIZE,

    /** Maximise the objective. */
    MAXIMIZE,
}
