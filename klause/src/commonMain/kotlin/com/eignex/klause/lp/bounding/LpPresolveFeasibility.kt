package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpComponentLayout
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPhaseTimer
import com.eignex.klause.lp.engine.LpProjectionMeter
import com.eignex.klause.lp.engine.LpProjectionStop
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.LpSolvePhase
import com.eignex.klause.lp.engine.acceptNullable
import com.eignex.klause.lp.engine.componentLayout
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
        solvePresolveComponents(model, token, projection, iterations)
    } else null
    val route = when {
        components != null -> "SPLIT"
        split -> "FALLBACK"
        else -> "UNSPLIT"
    }
    if (components == null && token()) {
        timer.finish("${route}_STOPPED", projection.work)
        return false
    }
    val result = components ?: solvePresolveFeasibility(model, token, iterations)
    observeRootWork(result.metrics)
    val refuted = result.ray?.let { !token() && verifyPresolveFeasibilityRay(model, it, token) } == true
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
    val optimal: Boolean,
    val metrics: LpSolveMetrics,
    val ray: DoubleArray?,
)

private fun LpEngine.solvePresolveFeasibility(
    model: LpModel,
    token: ExecutionControl,
    iterations: Int,
): PresolveFeasibilityOutcome = newTableauCutSolver(
    model, token, iterationLimit = iterations, factory = solveContext.engineFactory, pricing = pricingOptions,
).use { solver ->
    val result = solver.solve()
    PresolveFeasibilityOutcome(result?.optimal == true, solver.lastMetrics, solver.infeasibleRay.takeIf { result == null })
}

private fun LpEngine.solvePresolveComponents(
    model: LpModel,
    token: ExecutionControl,
    projection: LpProjectionMeter,
    iterations: Int,
): PresolveFeasibilityOutcome? {
    var metrics = LpSolveMetrics()
    var ray: DoubleArray? = null
    val completed = try {
        val layout = model.componentLayout(token, projection) ?: return null
        if (!admitPresolveBlocks(model, layout)) return null
        projection.reserve(4L * layout.size, 32L * layout.size, matrix = false)
        val order = (0 until layout.size).sortedBy { layout.rows(it).toLong() + layout.columns(it) }
        layout.visit(token, projection, order, copyCosts = false) { part ->
            val remaining = iterations - metrics.pivots
            if (remaining <= 0) return@visit false
            val result = solvePresolveFeasibility(part.model, token, remaining)
            metrics += result.metrics.copy(componentSolves = 1)
            result.ray?.let { candidate ->
                projection.reserve(model.m.toLong() + candidate.size, 8L * model.m, matrix = false)
                val lifted = DoubleArray(model.m)
                for (row in candidate.indices) lifted[part.rows[row]] = candidate[row]
                ray = lifted
            }
            result.optimal
        }
    } catch (_: LpProjectionStop) {
        false
    }
    return if (metrics.componentSolves == 0) null else PresolveFeasibilityOutcome(completed, metrics, ray)
}

private fun admitPresolveBlocks(model: LpModel, blocks: LpComponentLayout): Boolean {
    val indexes = 0 until blocks.size
    if (blocks.size > MAX_COMPONENTS || indexes.any { 4L * blocks.rows(it) > 3L * model.m }) return false
    val parent = model.tableauCells()
    val children = indexes.sumOf {
        val rows = blocks.rows(it).toLong()
        rows * (rows + blocks.columns(it) + 1L)
    }
    return children <= parent - parent / 4L
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
