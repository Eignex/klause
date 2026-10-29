package com.eignex.klause.portfolio

internal enum class WorkerFragment { FINITE, AFFINE_MIXED, AFFINE_BINARY_MIXED }
internal enum class WorkerObjectiveView { CANONICAL, CANONICAL_WITH_GUIDANCE }
internal enum class WorkerProofAuthority { CHECKED_TERMINALS, WITNESS_ONLY }
internal enum class WorkerSourceMapping { WORKING_COORDINATES }
internal enum class WorkerRetainedState { PRIVATE_RESUMABLE }

internal data class WorkerConstructionCost(
    val columns: Long,
    val factors: Int,
    val lpWorkLimit: Long? = null,
    val allocationLimit: Long? = null,
    val activeMillis: Long? = null,
    val sliceMillis: Long? = null,
    val sliceWork: Long? = null,
    val maxColumns: Long? = null,
    val maxFactors: Int? = null,
)

internal data class WorkerRequirements(
    val fragment: WorkerFragment,
    val objective: WorkerObjectiveView,
    val authority: WorkerProofAuthority,
    val construction: WorkerConstructionCost,
    val needs: Set<ArmNeed> = emptySet(),
    val sourceMapping: WorkerSourceMapping = WorkerSourceMapping.WORKING_COORDINATES,
    val retainedState: WorkerRetainedState = WorkerRetainedState.PRIVATE_RESUMABLE,
)

internal data class WorkerEligibility(val requirements: WorkerRequirements, val declines: List<String>) {
    val admitted: Boolean get() = declines.isEmpty()
}
