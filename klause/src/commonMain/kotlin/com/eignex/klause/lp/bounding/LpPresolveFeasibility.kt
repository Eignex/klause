package com.eignex.klause.lp.bounding

import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.LpCertificationObserver
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpComponentLayout
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPhaseTimer
import com.eignex.klause.lp.engine.LpProjectionMeter
import com.eignex.klause.lp.engine.LpProjectionStop
import com.eignex.klause.lp.engine.LpScalingView
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.LpSolvePhase
import com.eignex.klause.lp.engine.acceptNullable
import com.eignex.klause.lp.engine.certifyLpFarkas
import com.eignex.klause.lp.engine.componentLayout
import com.eignex.klause.lp.engine.defaultSimplexIterations
import com.eignex.klause.lp.engine.floatFarkasRay
import com.eignex.klause.lp.engine.newTableauCutSolver
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.lp.relaxation.RelaxationDomains
import com.eignex.klause.solver.result.LpRoute
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import com.eignex.util.numbers.CheckedLongOverflowException
import com.eignex.util.numbers.saturatedAdd
import com.eignex.util.numbers.saturatedMul

private const val MIN_COMPONENT_QUERY_CELLS = 4096L
private const val MAX_COMPONENTS = 64
private const val MAX_ROWS_PER_COLUMN = 4L
private const val MAX_COMPONENT_PREPARATION_WORK = 1_048_576L
private const val MAX_COMPONENT_PREPARATION_BYTES = 32L * 1024 * 1024

internal fun Problem.admitsPresolveFeasibility(): Boolean {
    if (numRealVars != 0 || intBounds.hasOpenSides) return false
    val columns = numIntVars.toLong() + numBoolVars
    val rows = numFactors.toLong()
    return rows <= MAX_ROWS_PER_COLUMN * columns &&
        saturatedMul(rows, columns + rows + 1L) >= MIN_COMPONENT_QUERY_CELLS
}

internal class LpPresolveFeasibility(
    val split: Boolean,
    val token: ExecutionControl,
    observer: LpCertificationObserver,
) {
    private val timer = LpPhaseTimer(observer, LpSolvePhase.PRESOLVE_FEASIBILITY)
    private var extraWork = 0L
    private var numerical = LpSolveMetrics()
    var complete = false
    var attemptedSplit = false

    fun charge(work: Long) {
        token.charge(work)
        extraWork = saturatedAdd(extraWork, work)
    }

    fun build(relaxer: CpToLpRelaxation, domains: RelaxationDomains): LpRelaxation {
        var built: LpRelaxation? = null
        relaxer.prepareBuild(domains, token, onWork = { charge(it * LpNodeOverhead.BUILD) },
            onComplete = { built = it }).forEach { }
        return checkNotNull(built)
    }

    fun solved(metrics: LpSolveMetrics) {
        numerical += metrics
        charge(metrics.preparationOps)
    }

    fun finish(refuted: Boolean) {
        val route = when {
            attemptedSplit -> "SPLIT"
            split -> "FALLBACK"
            else -> "UNSPLIT"
        }
        val outcome = when {
            refuted -> "REFUTED"
            complete -> "COMPLETE"
            else -> "INCONCLUSIVE"
        }
        timer.finish("${route}_$outcome", saturatedAdd(extraWork, numerical.workOps),
            numerical.pivots, numerical.componentSolves)
    }
}

internal fun LpEngine.splitPresolveFeasibility(
    model: LpModel,
    scope: LpPresolveFeasibility,
): Boolean? {
    if (!scope.split || model.m > MAX_ROWS_PER_COLUMN * model.n ||
        model.tableauCells() < MIN_COMPONENT_QUERY_CELLS || model.rowStrict.any { it }
    ) return null
    val offer = nodeWorkBudget()
    fun callerRemaining(): Long = scope.token.admission?.quotaRemaining ?: Long.MAX_VALUE
    val projection = LpProjectionMeter(
        minOf(offer.takeIf { it > 0L } ?: Long.MAX_VALUE, MAX_COMPONENT_PREPARATION_WORK, callerRemaining()),
        MAX_COMPONENT_PREPARATION_BYTES,
        scope.token,
        WorkMeter(scope::charge),
    )
    val iterations = nodePivotBudget().takeIf { it > 0 } ?: defaultSimplexIterations(model.m, model.numVars)
    val size = model.numVars.toLong() + model.m
    var numerical = LpSolveMetrics()
    var scalingWork = 0L
    var refuted = false
    fun remainingWork(): Long {
        val spent = saturatedAdd(projection.work, saturatedAdd(scalingWork, numerical.workOps))
        val available = if (offer > 0L) offer - minOf(offer, spent) else Long.MAX_VALUE
        return minOf(available, callerRemaining())
    }
    try {
        val layout = model.componentLayout(scope.token, projection) ?: return null
        if (!admitPresolveBlocks(model, layout)) return null
        projection.reserve(4L * layout.size, 32L * layout.size, matrix = false)
        val order = (0 until layout.size).sortedBy { layout.rows(it).toLong() + layout.columns(it) }
        scope.complete = layout.visit(scope.token, projection, order, copyCosts = false) { part ->
            val remaining = remainingWork()
            val childSize = part.model.numVars.toLong() + part.model.m
            val childIterations = (childSize * iterations / size).toInt()
            if (childIterations == 0) return@visit false
            // Scaling is prepared before the numerical solver has a chance to poll.
            val scaling = 2L * LpScalingView.constructionWorkBound(part.model)
            if (scaling >= remaining) return@visit false
            scope.attemptedSplit = true
            scalingWork = saturatedAdd(scalingWork, scaling)
            newTableauCutSolver(
                part.model, scope.token, iterationLimit = childIterations,
                workLimit = if (remaining == Long.MAX_VALUE) 0L else remaining - scaling,
                factory = solveContext.engineFactory, pricing = pricingOptions,
            ).use { solver ->
                val fallbacks = solver.scalingMetrics.fallbacks
                val point = solver.solve()
                val measured = solver.lastMetrics
                val scalingAfter = solver.scalingMetrics
                val retryWork = scalingAfter.work.takeIf { scalingAfter.fallbacks > fallbacks } ?: 0L
                val metrics = measured.copy(
                    preparationOps = saturatedAdd(measured.preparationOps, retryWork),
                    componentSolves = 1,
                )
                numerical += metrics
                scope.solved(metrics)
                solver.infeasibleRay?.takeIf { !scope.token() }?.let { candidate ->
                    val liftWork = model.m.toLong() + candidate.size
                    if (liftWork < remainingWork()) {
                        projection.reserve(liftWork, 8L * model.m, matrix = false)
                        val lifted = DoubleArray(model.m)
                        for (row in candidate.indices) lifted[part.rows[row]] = candidate[row]
                        refuted = verifyPresolveFeasibilityRay(model, lifted, scope)
                    }
                }
                projection.capAdditionalWork(remainingWork())
                point?.optimal == true && !scope.token()
            }
        }
    } catch (_: LpProjectionStop) {
        scope.complete = false
    } catch (_: CheckedLongOverflowException) {
        scope.complete = false
    }
    if (!scope.attemptedSplit) return null
    noteSolveOps(numerical.workOps)
    sink.lp.observeEngineCost(LpRoute.ROOT, numerical)
    return refuted && !scope.token()
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

internal fun LpEngine.verifyPresolveFeasibilityRay(
    model: LpModel,
    ray: DoubleArray,
    scope: LpPresolveFeasibility,
): Boolean {
    val token = scope.token
    if (token()) return false
    val accepted = if (numericalDeductions) {
        scope.charge(4L * model.extent())
        !token() && floatFarkasRay(model, ray, solveContext.refutationSlack, refutationOnly = true) != null
    } else {
        solveContext.certificationPolicy.acceptNullable(
            LpCertifier.EXACT_FARKAS,
            certifyLpFarkas(model, ray, observer = rootCertificationObserver(), cancellation = token,
                accounting = WorkMeter(scope::charge)),
        ) != null
    }
    return accepted && !token()
}
