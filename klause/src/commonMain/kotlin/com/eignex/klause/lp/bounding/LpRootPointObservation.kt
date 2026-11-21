package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.LpFloatTermination

internal fun interface LpRootPointObserver {
    fun onRootPoint(event: LpRootPointObservation)
}

internal enum class LpRootPointStage {
    ADMISSION, OWNER, QUERY_CALL, COPY, GUIDANCE, TARGETS, HANDOFF, CONSUMER, CLOSE_BEGIN, CLOSE_END,
}

internal enum class LpRootPointDisposition {
    ENTERED, OPTIMAL, NON_OPTIMAL, NO_RESULT, RETAINED, REPLACED, CLEARED,
    PROBE_DISABLED, CANCELLED, STORAGE, LP_WORK, ROOT_WORK, BRANCH_DISABLED, REAL_VARIABLES,
    OBJECTIVE, SHAPE, MASK, OWNER, NO_GUIDANCE, NO_POINT, NOT_PAUSABLE, NO_DECISIONS,
    TREE_SEARCH, NOT_RETAINED, COMPONENT_TRIAL, HANDED_OFF, VALUE_OFFERED, COMPONENT_OFFERED,
    NOT_CONFIGURED, NOT_CONSIDERED, PROJECTION_SKIPPED, ATTEMPTED_DECLINED, ADMITTED, BEGIN, END,
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
    val admission: LpRootAdmissionObservation? = null,
)

internal enum class LpRootAdmissionDisposition {
    NOT_CONFIGURED, NOT_CONSIDERED, PROJECTION_SKIPPED, ATTEMPTED_DECLINED, ADMITTED,
}

// Masks describe operand evaluation; later callback values do not change their provenance.
internal class LpRootAdmissionObservation(
    val disposition: LpRootAdmissionDisposition,
    val schema: Int = 1,
    val emphasis: LpEmphasis? = null,
    val sparseOverride: Boolean? = null,
    val sparsePermitted: Boolean? = null,
    val automaticEligible: Boolean? = null,
    val automaticEvaluated: Long = 0L,
    val automaticPassed: Long = 0L,
    val outerEvaluated: Long = 0L,
    val outerPassed: Long = 0L,
    val estimatedRows: Long? = null,
    val estimatedColumns: Long? = null,
    val estimatedCells: Long? = null,
    val baseCellCap: Long? = null,
    val ceilingCellCap: Long? = null,
    val boundingPermitted: Boolean? = null,
    val withinCeiling: Boolean? = null,
    val structurallyApplicable: Boolean? = null,
    val projectionAllowed: Boolean? = null,
    val producerQueryWork: Long? = null,
    val producerPreparationWork: Long? = null,
    val helperQueryWork: Long? = null,
    val helperPreparationWork: Long? = null,
    val resolvedBounding: Boolean? = null,
    val resolvedProbe: Boolean? = null,
    val resolvedBranching: Boolean? = null,
    val resolvedComponentOwnership: Boolean? = null,
    val resolvedLbTreeSearch: Boolean? = null,
    val resolvedRootMaxWork: Long? = null,
    val resolvedBoundMaxDepth: Int? = null,
    val resolvedRootBudgetMillis: Long? = null,
    val resolvedRootBudgetFraction: Double? = null,
    val resolvedLpWorkBudgetFraction: Double? = null,
    val metrics: Map<String, Long> = emptyMap(),
)
