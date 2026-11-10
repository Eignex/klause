package com.eignex.klause.solver.search

/** First component-terminal observation; it carries no completeness or retry authority. */
data class SearchComponentStop(
    /** Shared traversal boundary producing the terminal. */
    val site: Site,
    /** Stop visible before the runner reports a fallback representation decline. */
    val stopCause: SearchStopCause?,
    /** Cancellation observed at the terminal boundary. */
    val cancelled: Boolean,
    /** Whether the traversal retained genuinely unresolved coverage. */
    val unresolvedCoverage: Boolean,
    /** Shared decision level at the terminal boundary. */
    val decisionLevel: Int,
) {
    /** Operations that can produce a component-terminal verdict. */
    enum class Site {
        /** Exhaustion with retained unresolved coverage. */
        Coverage,
        /** Run-start lifecycle callback. */
        Start,
        /** Run-resume lifecycle callback. */
        Resume,
        /** Root-model blocking or its propagation. */
        ModelBlock,
        /** Lifecycle callback after a completed root-model block. */
        ModelBlocked,
        /** Completed-model policy. */
        ModelPolicy,
        /** Policy before branching at a node. */
        NodePolicy,
        /** Applying an asserting learned node conflict. */
        LearnedConflict,
        /** A required leaf check without a resumable continuation. */
        LeafCheck,
        /** Restart lifecycle callback. */
        Restart,
        /** Shared propagation during restart. */
        RestartPropagation,
        /** Cancellation lifecycle callback. */
        Cancellation,
    }
}
