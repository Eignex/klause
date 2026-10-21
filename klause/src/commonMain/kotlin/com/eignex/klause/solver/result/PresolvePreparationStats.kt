package com.eignex.klause.solver.result

import kotlin.time.Duration

/** Preparation operations whose costs are independent of the elective pass catalogue. */
enum class PresolvePreparationStage {
    /** Feasibility LP over coupled wide columns before root propagation. */
    PRE_BAKE_LP_FEASIBILITY,
    /** Certified LP bound closing before root propagation. */
    PRE_BAKE_LP_BOUNDS,
    /** The root bake and any reseed surrounding elective construction tiers. */
    ROOT_BAKE,
    /** Construction of the persistent finite presolve state. */
    FINITE_SESSION,
    /** Materialization shared by the finite reductions. */
    FINITE_MATERIALIZATION,
    /** The bake handed to the selected engine under its containing controls. */
    ENGINE_BAKE,
    /** Reconstruction of source encoders and component ownership. */
    SOURCE_ENCODER,
    /** Assembly of exact-real difference guidance for a finite projection. */
    REAL_DIFFERENCE,
}

/** One preparation operation, separate from elective pass analysis. */
class PresolvePreparationStats internal constructor(
    /** Operation performed. */
    val stage: PresolvePreparationStage,
    /** Preparation lane owning the operation. */
    val lane: PresolveLane,
    /** Completion or interruption of the operation. */
    val outcome: PresolveInvocationOutcome,
    /** Active elapsed time, excluding nested observed operations. */
    val elapsed: Duration,
    /** Shared presolve phase units, excluding nested observed operations. */
    val work: Long,
    /** Native visits outside the phase currency; existing containing charges are forwarded unchanged. */
    val nativeWork: Long,
    /** Dimensions at entry. */
    val input: PresolveSizeStats,
    /** Dimensions after retaining completed preparation. */
    val output: PresolveSizeStats,
)
