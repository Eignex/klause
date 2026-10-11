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
    /**
     * Up to eight highest-degree representatives, one per factor kind, from the same assignment as [cost].
     * Factor and variable ids refer to the local-search model after lowering and presolve.
     */
    val factors: List<LocalSearchResidualFactor> = emptyList(),
)

/** One graded factor observation from an infeasible or feasible committed assignment, not a source witness. */
data class LocalSearchResidualFactor(
    /** Zero-based factor id in the local-search model. */
    val id: Int,
    /** Factor class and, for a reified linear row, comparison and input shape. */
    val kind: String,
    /** Graded violation degree of this factor at the observed assignment. */
    val degree: Long,
    /** Up to sixteen distinct Boolean coordinates and their committed values. */
    val bools: Map<Int, Boolean>,
    /** Up to sixteen distinct integer coordinates and their committed values. */
    val ints: Map<Int, Long>,
    /** Number of distinct Boolean coordinates omitted from [bools]. */
    val omittedBools: Int = 0,
    /** Number of distinct integer coordinates omitted from [ints]. */
    val omittedInts: Int = 0,
)
