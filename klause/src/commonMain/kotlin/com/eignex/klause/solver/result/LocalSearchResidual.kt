package com.eignex.klause.solver.result

/** Graded violations of one committed local-search assignment; this observation is not a feasible witness. */
data class LocalSearchResidual(
    /** Sum of the assignment's per-factor graded violation degrees. */
    val cost: Long,
    /**
     * Nonzero graded violation totals from the same assignment as [cost], keyed by factor class name.
     * Reified linear rows use `ReifiedLinear.<op>.<shape>`: `binary` for one input bounded to 0..1,
     * `unary` for another single input, and `multi` for multiple inputs. Each degree appears once.
     */
    val byKind: Map<String, Long>,
)
