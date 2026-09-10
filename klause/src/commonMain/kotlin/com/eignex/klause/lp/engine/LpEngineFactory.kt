package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation

/** Entering-column policy for feasibility solves with an exactly zero objective. */
enum class LpZeroObjectivePricing {
    /** Preserve the ordinary Harris largest-pivot selection. */
    DEFAULT,

    /** Prefer fewer disturbed non-free basics, then source-column sparsity and seeded ties. */
    THEORY,
}

/** Construction-time pricing configuration. Nonzero objectives ignore [zeroObjective]. */
internal data class LpPricingOptions(
    val zeroObjective: LpZeroObjectivePricing = LpZeroObjectivePricing.DEFAULT,
    val tieSeed: Long = 0L,
)

/** Immutable construction seam shared by standalone solves and one [com.eignex.klause.lp.bounding.LpEngine]. */
internal interface LpEngineFactory {
    fun newGeneralSolver(model: LpModel, cancellation: Cancellation): LpSolver

    fun newComponentSolver(
        model: LpModel,
        parts: List<LpNeighborhood>,
        solvers: List<LpSolver>,
        isolated: IntArray,
    ): ComponentLpSolverCapability

    fun newTableauSolver(
        model: LpModel,
        cancellation: Cancellation,
        iterationLimit: Int,
        workLimit: Long,
        trackDegeneracy: Boolean,
    ): TableauCutSolver

    fun newTableauSolver(
        model: LpModel,
        cancellation: Cancellation,
        iterationLimit: Int,
        workLimit: Long,
        trackDegeneracy: Boolean,
        pricing: LpPricingOptions,
    ): TableauCutSolver = newTableauSolver(model, cancellation, iterationLimit, workLimit, trackDegeneracy)

    fun newPersistentSolver(
        model: LpModel,
        cancellation: Cancellation,
        refactorUpdateLimit: Int,
        iterationLimit: Int,
        workLimit: Long,
        trackDegeneracy: Boolean,
    ): PersistentLpSolver

    fun newPersistentSolver(
        model: LpModel,
        cancellation: Cancellation,
        refactorUpdateLimit: Int,
        iterationLimit: Int,
        workLimit: Long,
        trackDegeneracy: Boolean,
        pricing: LpPricingOptions,
    ): PersistentLpSolver = newPersistentSolver(
        model,
        cancellation,
        refactorUpdateLimit,
        iterationLimit,
        workLimit,
        trackDegeneracy,
    )
}

internal object ProductionLpEngineFactory : LpEngineFactory {
    override fun newGeneralSolver(model: LpModel, cancellation: Cancellation): LpSolver =
        RevisedSimplex(model, cancellation)

    override fun newComponentSolver(
        model: LpModel,
        parts: List<LpNeighborhood>,
        solvers: List<LpSolver>,
        isolated: IntArray,
    ): ComponentLpSolverCapability = ComponentLpSolver(model, parts, solvers, isolated)

    override fun newTableauSolver(
        model: LpModel,
        cancellation: Cancellation,
        iterationLimit: Int,
        workLimit: Long,
        trackDegeneracy: Boolean,
    ): TableauCutSolver = RevisedSimplex(
        model,
        cancellation,
        iterationLimit = iterationLimit,
        workLimit = workLimit,
        trackDegeneracy = trackDegeneracy,
    )

    override fun newTableauSolver(
        model: LpModel,
        cancellation: Cancellation,
        iterationLimit: Int,
        workLimit: Long,
        trackDegeneracy: Boolean,
        pricing: LpPricingOptions,
    ): TableauCutSolver = RevisedSimplex(
        model,
        cancellation,
        iterationLimit = iterationLimit,
        workLimit = workLimit,
        trackDegeneracy = trackDegeneracy,
        pricing = pricing,
    )

    override fun newPersistentSolver(
        model: LpModel,
        cancellation: Cancellation,
        refactorUpdateLimit: Int,
        iterationLimit: Int,
        workLimit: Long,
        trackDegeneracy: Boolean,
    ): PersistentLpSolver = RevisedSimplex(
        model,
        cancellation,
        refactorUpdateLimit = refactorUpdateLimit,
        iterationLimit = iterationLimit,
        workLimit = workLimit,
        trackDegeneracy = trackDegeneracy,
    )

    override fun newPersistentSolver(
        model: LpModel,
        cancellation: Cancellation,
        refactorUpdateLimit: Int,
        iterationLimit: Int,
        workLimit: Long,
        trackDegeneracy: Boolean,
        pricing: LpPricingOptions,
    ): PersistentLpSolver = RevisedSimplex(
        model,
        cancellation,
        refactorUpdateLimit = refactorUpdateLimit,
        iterationLimit = iterationLimit,
        workLimit = workLimit,
        trackDegeneracy = trackDegeneracy,
        pricing = pricing,
    )
}

/** Per-solve or per-consumer-instance LP dependencies. Production callers share no mutable override. */
internal data class LpSolveContext(
    val engineFactory: LpEngineFactory = ProductionLpEngineFactory,
    val certificationPolicy: LpCertificationPolicy = ProductionLpCertificationPolicy,
) {
    companion object {
        val Production = LpSolveContext()
    }
}
