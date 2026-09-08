package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation

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

    fun newPersistentSolver(
        model: LpModel,
        cancellation: Cancellation,
        refactorUpdateLimit: Int,
        iterationLimit: Int,
        workLimit: Long,
        trackDegeneracy: Boolean,
    ): PersistentLpSolver
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
