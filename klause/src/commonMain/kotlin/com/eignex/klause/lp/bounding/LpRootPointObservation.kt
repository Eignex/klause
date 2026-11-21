package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.LpFloatTermination

internal fun interface LpRootPointObserver {
    fun onRootPoint(event: LpRootPointObservation)
}

internal enum class LpRootPointStage {
    OWNER, QUERY_CALL, COPY, GUIDANCE, TARGETS, HANDOFF, CONSUMER, CLOSE_BEGIN, CLOSE_END,
}

internal enum class LpRootPointDisposition {
    ENTERED, OPTIMAL, NON_OPTIMAL, NO_RESULT, RETAINED, REPLACED, CLEARED,
    PROBE_DISABLED, CANCELLED, STORAGE, LP_WORK, ROOT_WORK, BRANCH_DISABLED, REAL_VARIABLES,
    OBJECTIVE, SHAPE, MASK, OWNER, NO_GUIDANCE, NO_POINT, NOT_PAUSABLE, NO_DECISIONS,
    TREE_SEARCH, NOT_RETAINED, COMPONENT_TRIAL, HANDED_OFF, VALUE_OFFERED, COMPONENT_OFFERED,
    BEGIN, END,
}

// Identity references are borrowed for the synchronous callback; receivers must not retain them.
// Query ordinals identify model-facing calls, not numerical cursors or actual numerical advancement.
internal class LpRootPointObservation(
    val owner: Any,
    val source: Any,
    val objective: Any,
    val sequence: Long,
    val query: Long,
    val stage: LpRootPointStage,
    val disposition: LpRootPointDisposition,
    val model: Any?,
    val point: Any?,
    val previousPoint: Any?,
    val automatic: Boolean,
    val auxiliary: Boolean,
    val partial: Boolean,
    // Only the ordinary consumer's evaluated eligibility predicate supplies this fact.
    val sourceRoot: Boolean?,
    val paused: Boolean?,
    val termination: LpFloatTermination?,
    val pointObjective: Any?,
)
