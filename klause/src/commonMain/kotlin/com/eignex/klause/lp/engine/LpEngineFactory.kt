package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation

/** Entering-column policy for feasibility solves with an exactly zero objective. */
enum class LpZeroObjectivePricing {
    /** Select the finishing candidate with the largest pivot magnitude. */
    LARGEST_PIVOT,

    /** Prefer fewer disturbed non-free basics, then source-column sparsity and seeded ties. */
    MIN_BOUND_SUPPORT,
}

/** Construction-time pricing configuration. Nonzero objectives ignore [zeroObjective]. */
internal data class LpPricingOptions(
    val zeroObjective: LpZeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
    val tieSeed: Long = 0L,
)

/** Immutable construction seam shared by standalone solves and one [com.eignex.klause.lp.bounding.LpEngine]. */
internal interface LpEngineFactory {
    fun newGeneralSolver(model: LpModel, cancellation: Cancellation, pricing: LpPricingOptions): LpSolver

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
        pricing: LpPricingOptions,
    ): TableauCutSolver

    fun newPersistentSolver(
        model: LpModel,
        cancellation: Cancellation,
        refactorUpdateLimit: Int,
        iterationLimit: Int,
        workLimit: Long,
        trackDegeneracy: Boolean,
        pricing: LpPricingOptions,
    ): PersistentLpSolver
}

internal object ProductionLpEngineFactory : LpEngineFactory {
    override fun newGeneralSolver(model: LpModel, cancellation: Cancellation, pricing: LpPricingOptions): LpSolver =
        RevisedSimplex(model, cancellation, pricing = pricing)

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
