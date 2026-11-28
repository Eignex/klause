package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.util.ExecutionControl
import kotlin.math.abs
import kotlin.math.max

/** Entering-column policy for feasibility solves with an exactly zero objective. */
enum class LpZeroObjectivePricing {
    /** Select the finishing candidate with the largest pivot magnitude. */
    LARGEST_PIVOT,

    /** Prefer fewer disturbed non-free basics, then source-column sparsity and seeded ties. */
    MIN_BOUND_SUPPORT,
}

// The amount a relaxation side `s` moves by: `max(absolute, relative * abs(s))`.
internal data class LpBoundSlack(val absolute: Double, val relative: Double) {
    init {
        require(absolute >= 0.0 && relative >= 0.0)
    }

    fun at(side: Double): Double = if (relative == 0.0 || side == 0.0) absolute
    else max(absolute, relative * abs(side))

    companion object {
        val NONE = LpBoundSlack(0.0, 0.0)
    }
}

// Nonzero objectives ignore [zeroObjective].
internal data class LpPricingOptions(
    val zeroObjective: LpZeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
    val tieSeed: Long = 0L,
    val basisEntryLimit: Long = Long.MAX_VALUE,
    val basisWorkLimit: Long = Long.MAX_VALUE,
)

// Each `workLimit` bounds one solve in [LpWork] operations; 0 leaves it unbounded.
internal interface LpEngineFactory {
    fun newGeneralSolver(
        model: LpModel,
        cancellation: ExecutionControl,
        workLimit: Long,
        pricing: LpPricingOptions,
    ): LpSolver

    fun newComponentSolver(
        model: LpModel,
        parts: List<LpNeighborhood>,
        solvers: List<LpSolver>,
        isolated: IntArray,
    ): ComponentLpSolverCapability

    fun newRetainedComponentSolver(
        model: LpModel,
        parts: List<LpNeighborhood>,
        solvers: List<LpSolver>,
        isolated: IntArray,
    ): RetainedComponentLpSolverCapability

    fun newTableauSolver(
        model: LpModel,
        cancellation: ExecutionControl,
        iterationLimit: Int,
        workLimit: Long,
        trackDegeneracy: Boolean,
        pricing: LpPricingOptions,
    ): TableauCutSolver

    fun newPersistentSolver(
        model: LpModel,
        cancellation: ExecutionControl,
        refactorUpdateLimit: Int,
        iterationLimit: Int,
        workLimit: Long,
        trackDegeneracy: Boolean,
        pricing: LpPricingOptions,
    ): PersistentLpSolver
}

internal object ProductionLpEngineFactory : LpEngineFactory by ScalingLpEngineFactory(LpScalingOptions())

// For callers comparing against or recovering with the unscaled float view.
internal object UnscaledLpEngineFactory : LpEngineFactory by ScalingLpEngineFactory(LpScalingOptions(enabled = false))

private class ScalingLpEngineFactory(private val scalingOptions: LpScalingOptions) : LpEngineFactory {
    override fun newGeneralSolver(
        model: LpModel,
        cancellation: ExecutionControl,
        workLimit: Long,
        pricing: LpPricingOptions,
    ): LpSolver = RevisedSimplex(
        model,
        cancellation,
        workLimit = workLimit,
        pricing = pricing,
        scalingOptions = scalingOptions,
    )

    override fun newComponentSolver(
        model: LpModel,
        parts: List<LpNeighborhood>,
        solvers: List<LpSolver>,
        isolated: IntArray,
    ): ComponentLpSolverCapability = ComponentLpSolver(model, parts, solvers, isolated)

    override fun newRetainedComponentSolver(model: LpModel, parts: List<LpNeighborhood>, solvers: List<LpSolver>,
        isolated: IntArray): RetainedComponentLpSolverCapability = ComponentLpSolver(model, parts, solvers, isolated)

    override fun newTableauSolver(
        model: LpModel,
        cancellation: ExecutionControl,
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
        scalingOptions = scalingOptions,
    )

    override fun newPersistentSolver(
        model: LpModel,
        cancellation: ExecutionControl,
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
        scalingOptions = scalingOptions,
    )
}

// Per-solve or per-consumer LP dependencies. Production callers share no mutable override.
internal data class LpSolveContext(
    val engineFactory: LpEngineFactory = ProductionLpEngineFactory,
    val certificationPolicy: LpCertificationPolicy = ProductionLpCertificationPolicy,
    val onRefinementBasisVerification: ((ExactBasisMetrics) -> Unit)? = null,
    val sparsePointRecovery: Boolean = false,
    val startArbitraryPrecision: Boolean = false,
    val preserveOverflowCheckpoint: Boolean = true,
    val onQuery: ((LpQueryObservation) -> Unit)? = null,
    // Deductions consume float results within tolerances instead of exact certificates. Only the finite pipeline
    // selects it from source semantics; open theories and source LP optimization keep exact certification.
    val numericalDeductions: Boolean = false,
    // How far each relaxation side moves in the region the caller decides; numerical bounds hold over it.
    val boundSlack: LpBoundSlack = LpBoundSlack.NONE,
    // A numerical refutation holds over this side movement, so it refutes only a model infeasible by more than it.
    val refutationSlack: LpBoundSlack = LpBoundSlack.NONE,
    val refinementLimits: LpRefinementLimits = LpRefinementLimits(),
    val exactContinuationLimits: ExactContinuationLimits? = null,
    val reconstructionLimits: ReconstructionLimits = ReconstructionLimits(),
    val basisLimits: ExactBasisLimits = ExactBasisLimits(),
) {
    val continuationLimits get() = exactContinuationLimits ?: ExactContinuationLimits(
        startArbitraryPrecision = startArbitraryPrecision,
        preserveOverflowCheckpoint = preserveOverflowCheckpoint,
    )

    companion object {
        val Production = LpSolveContext()

        fun finite(numerical: Boolean): LpSolveContext =
            if (numerical) LpSolveContext(numericalDeductions = true) else Production
    }
}
