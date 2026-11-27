package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.ComponentLpSolver
import com.eignex.klause.lp.engine.LpBlockDimensions
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPhaseTimer
import com.eignex.klause.lp.engine.LpProjectionMeter
import com.eignex.klause.lp.engine.LpProjectionStop
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.LpSolvePhase
import com.eignex.klause.lp.engine.acceptNullable
import com.eignex.klause.lp.engine.componentLpSolverOrNull
import com.eignex.klause.lp.engine.componentShare
import com.eignex.klause.lp.engine.floatFarkasRay
import com.eignex.klause.lp.engine.integerFarkasRay
import com.eignex.klause.lp.engine.newTableauCutSolver
import com.eignex.klause.lp.relaxation.LpAssemblyCancelled
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.CheckedLongOverflowException

private const val MIN_COMPONENT_QUERY_CELLS = 4096L
private const val MAX_COMPONENTS = 64
private const val MAX_COMPONENT_PREPARATION_WORK = 1_048_576L
private const val MAX_COMPONENT_PREPARATION_BYTES = 32L * 1024 * 1024

internal fun LpEngine.presolveFeasibilityQuery(token: ExecutionControl, split: Boolean): Boolean {
    val observer = rootCertificationObserver()
    val timer = LpPhaseTimer(observer, LpSolvePhase.PRESOLVE_FEASIBILITY)
    val session = PropagationSession(problem, token)
    val model = try {
        if (session.isUnsatAtRoot || token()) null else
            primalRelaxer(LinearObjective()).build(session, cancellation = token).model
    } catch (_: CheckedLongOverflowException) {
        null
    } catch (_: LpAssemblyCancelled) {
        null
    }
    if (model == null || model.n == 0 || token()) {
        timer.finish("UNAVAILABLE")
        return false
    }
    val iterations = (50L * (model.m.toLong() + model.numVars) + 200).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val projection = LpProjectionMeter(
        MAX_COMPONENT_PREPARATION_WORK,
        MAX_COMPONENT_PREPARATION_BYTES,
        token,
        token.accounting,
    )
    val components = if (split && model.tableauCells() >= MIN_COMPONENT_QUERY_CELLS) {
        componentFeasibilitySolver(model, token, projection, iterations)
    } else null
    val route = when {
        components != null -> "SPLIT"
        split -> "FALLBACK"
        else -> "UNSPLIT"
    }
    if (token()) {
        components?.close()
        timer.finish("${route}_STOPPED", projection.work)
        return false
    }
    val solver = components ?: newTableauCutSolver(
        model, token, iterationLimit = iterations, factory = solveContext.engineFactory, pricing = pricingOptions,
    )
    val result = solver.use {
        val result = try {
            solver.solve()
        } finally {
            observeRootSolve(solver)
        }
        val ray = solver.infeasibleRay
        val infeasible = result == null && ray != null && !token() &&
            verifyPresolveFeasibilityRay(model, ray, token)
        PresolveFeasibilityOutcome(infeasible, result?.optimal == true, solver.lastMetrics)
    }
    val refuted = result.infeasible && !token()
    val outcome = when {
        refuted -> "REFUTED"
        result.optimal && !token() -> "COMPLETE"
        else -> "INCONCLUSIVE"
    }
    val metrics = result.metrics
    timer.finish("${route}_$outcome", projection.work + metrics.workOps, metrics.pivots, metrics.componentSolves)
    return refuted
}

private data class PresolveFeasibilityOutcome(
    val infeasible: Boolean,
    val optimal: Boolean,
    val metrics: LpSolveMetrics,
)

private fun LpEngine.componentFeasibilitySolver(
    model: LpModel,
    token: ExecutionControl,
    projection: LpProjectionMeter,
    iterations: Int,
): ComponentLpSolver? = try {
    componentLpSolverOrNull(
        model,
        token,
        projection,
        { part, stop, rowOnlyRows ->
            newTableauCutSolver(part, stop, factory = solveContext.engineFactory,
                pricing = pricingOptions.componentShare(part.n, model.n, rowOnlyRows))
        },
        { parent, parts, solvers, isolated ->
            ComponentLpSolver(parent, parts, solvers, isolated).apply {
                queryAllowance = LpFloatAllowance(work = 0L, iterations = iterations)
            }
        },
        admit = { blocks -> admitPresolveBlocks(model, blocks, projection) },
    )
} catch (_: LpProjectionStop) {
    null
}

private fun admitPresolveBlocks(
    model: LpModel,
    blocks: List<LpBlockDimensions>,
    projection: LpProjectionMeter,
): Boolean {
    if (blocks.size > MAX_COMPONENTS || blocks.any { 4L * it.rows > 3L * model.m }) return false
    val parent = model.tableauCells()
    val children = blocks.sumOf { it.rows.toLong() * (it.rows.toLong() + it.columns + 1L) }
    if (children > parent - parent / 4L) return false
    // Reserve the parent fingerprint, primal, basis and ray scatter before constructing child solvers.
    val entries = 4L * model.n + 6L * model.m + 2L * blocks.size
    projection.reserve(entries + model.extent(), 8L * entries, matrix = false)
    return true
}

private fun LpModel.tableauCells(): Long = m.toLong() * (m.toLong() + n + 1L)

private fun LpEngine.verifyPresolveFeasibilityRay(model: LpModel, ray: DoubleArray, token: ExecutionControl): Boolean {
    token.charge(model.extent())
    if (token()) return false
    val accepted = if (numericalDeductions) {
        floatFarkasRay(model, ray, solveContext.refutationSlack) != null
    } else {
        solveContext.certificationPolicy.acceptNullable(
            LpCertifier.EXACT_FARKAS,
            integerFarkasRay(model, ray, observer = rootCertificationObserver(), cancellation = token),
        ) != null
    }
    return accepted && !token()
}
