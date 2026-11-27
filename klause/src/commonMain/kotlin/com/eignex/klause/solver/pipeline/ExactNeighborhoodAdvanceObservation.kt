package com.eignex.klause.solver.pipeline

internal enum class ExactNeighborhoodAdvancePhase { PreparingRoot, CopyingBooleans, CopyingCoordinates, Ready }

internal enum class ExactNeighborhoodAdvanceGuard {
    Cancelled, SliceInstructions, MoveLimit, InstructionLimit, ImprovementLimit, NodeLimit, CompletionLimit,
    RootCallbackCancelled, RootPending, CompletionPausedWithoutProgress, CompletionPausedAtSliceLimit,
    CompletionPausedCancelled, CompletionPausedWithoutContinuation, Satisfied, NoCoordinates,
}

internal data class ExactNeighborhoodAdvanceState(
    val work: Long,
    val phase: ExactNeighborhoodAdvancePhase,
    val copiedBooleans: Int,
    val copiedCoordinates: Int,
    val moves: Long,
    val pendingOperator: Int?,
    val pendingCutoff: Boolean,
)

internal data class ExactNeighborhoodAdvanceObservation(
    val offeredInstructions: Long,
    val entry: ExactNeighborhoodAdvanceState,
    val exit: ExactNeighborhoodAdvanceState,
    val guard: ExactNeighborhoodAdvanceGuard,
    val done: Boolean,
) {
    val chargedInstructions: Long get() = exit.work - entry.work
}

internal interface ExactNeighborhoodAdvanceObserver {
    fun reserveAdvance(): Boolean
    fun recordAdvance(observation: ExactNeighborhoodAdvanceObservation)
}
