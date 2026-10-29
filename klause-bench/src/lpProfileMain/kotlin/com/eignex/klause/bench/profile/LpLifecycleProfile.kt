package com.eignex.klause.bench.profile

import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.ExactBasisMetrics
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpCertificationObserver
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpCertifierCost
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPhaseMetrics
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpScopedRow
import com.eignex.klause.lp.engine.LpScopedSolver
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.LpSolvePhase
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.engine.SimplexPhase
import com.eignex.klause.lp.engine.SimplexProgressTrace
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactContinuationMetrics
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.util.Cancellation
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource.Monotonic

internal expect fun profileAllocatedBytes(): Long?

internal fun main(args: Array<String>) {
    val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
    require(options.keys == setOf("source64", "source", "sha256", "repeats"))
    val bytes = Base64.decode(options.getValue("source64"))
    require(bytes.size in 1..1_048_576)
    val repeats = options.getValue("repeats").toInt().also { require(it in 1..5) }
    val stop = Cancellation.after(120.seconds)
    val start = Monotonic.markNow()
    val compiled = Mps.parse(bytes.decodeToString()).toProblem()
    val objective = compiled.objective?.toLinearObjective()?.let { if (compiled.maximize) it.negated() else it }
    val projection = CpToLpRelaxation(compiled.model, objective)
        .build(PropagationSession(compiled.model, cancellation = stop), cancellation = stop)
    val initial = LpExactState(requireNotNull(projection.model.authoritativeModel()))
    val column = (0 until initial.model.n).firstOrNull { initial.activeSide(it, false) != null }
    requireNotNull(column) { "a structural lower side is required for the edit control" }
    val lower = requireNotNull(initial.activeSide(column, false)).side.number.value
    val upper = initial.activeSide(column, true)?.side?.number?.value
    val edited = if (upper != null && upper > lower) (lower + upper) * BigFraction.ofLong(2L).reciprocal()
        else lower + BigFraction.ONE
    val side = ExactLpSide(ExactLpNumber.of(edited))
    println(buildJsonObject {
        put("kind", "source-preparation")
        put("source", options.getValue("source"))
        put("sha256", options.getValue("sha256"))
        put("elapsedNs", start.elapsedNow().inWholeNanoseconds)
        put("rows", initial.model.m)
        put("columns", initial.model.n)
        put("editColumn", column)
        put("editLower", edited.toString())
        put("sourceVerdictClaim", false)
    })
    for (round in -1 until repeats) {
        for (retained in if (round % 2 == 0) listOf(true, false) else listOf(false, true)) {
            for (observed in listOf(false, true)) {
                check(!stop()) { "containing profile allowance exhausted" }
                lifecycle(initial, column, side, round, retained, observed, stop)
            }
        }
    }
}

private fun lifecycle(
    initial: LpExactState,
    column: Int,
    side: ExactLpSide,
    round: Int,
    retained: Boolean,
    observed: Boolean,
    cancellation: Cancellation,
) {
    val factory = LifecycleFactory(observed)
    val context = LpSolveContext(engineFactory = factory)
    var state = initial
    var owner: LpScopedSolver? = null
    val started = Monotonic.markNow()
    val allocated = profileAllocatedBytes()
    var closeNs = 0L
    try {
        for ((index, stage) in listOf("cold", "bound", "bound-pop", "row", "row-pop").withIndex()) {
            val before = factory.floatingNanos
            val stageStart = Monotonic.markNow()
            val stageAllocated = profileAllocatedBytes()
            val creationStart = Monotonic.markNow()
            if (owner == null) owner = LpScopedSolver(state, cancellation, context,
                iterationLimit = 4096, workLimit = 50_000_000L)
            val current = requireNotNull(owner)
            val constructionNs = creationStart.elapsedNow().inWholeNanoseconds
            val editStart = Monotonic.markNow()
            when (index) {
                1 -> {
                    check(current.push())
                    check(current.assertBound(column, false, side, 1L))
                }
                2, 4 -> check(current.pop(0))
                3 -> {
                    check(current.push())
                    check(current.append(LpScopedRow(current.state.rows.lastId + 1L,
                        listOf(column to ExactLpNumber.of(-1L)), ExactLpNumber.of(side.number.value.negated()),
                        ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L))))), scoped = true))
                }
            }
            val editNs = editStart.elapsedNow().inWholeNanoseconds
            val observer = LifecycleCertificates()
            val queryStart = Monotonic.markNow()
            val result = current.solve(observer = observer.takeIf { observed })
            val queryNs = queryStart.elapsedNow().inWholeNanoseconds
            val stageNs = stageStart.elapsedNow().inWholeNanoseconds
            val stageBytes = stageAllocated?.let { start -> profileAllocatedBytes()?.minus(start) }
            state = current.state
            val numerical = factory.lastEngine
            println(buildJsonObject {
                put("kind", "query")
                put("round", round)
                put("warmup", round < 0)
                put("retained", retained)
                put("observed", observed)
                put("stage", stage)
                put("stageNs", stageNs)
                put("constructionNs", constructionNs)
                put("editNs", editNs)
                put("queryNs", queryNs)
                put("floatingNs", factory.floatingNanos - before)
                put("queryNonFloatNs", queryNs - (factory.floatingNanos - before))
                put("allocatedBytes", stageBytes?.let(::JsonPrimitive) ?: JsonNull)
                put("cancelled", cancellation())
                put("verdict", result?.verdict?.name)
                put("bound", result?.lowerBound?.toString())
                put("exactPoint", result?.exactPrimal?.toString())
                put("termination", numerical?.lastTermination?.name)
                put("pivots", current.lastMetrics.pivots)
                put("work", current.lastMetrics.workOps)
                put("preparationWork", current.metrics.preparationWork)
                put("createdOwners", current.metrics.createdOwners)
                put("closedOwners", current.metrics.closedOwners)
                put("retainedRows", current.metrics.retainedRows)
                put("activeRows", current.metrics.activeRows)
                put("certificateWork", observer.work)
                put("certificatePhases", numbers(observer.phases))
                put("sourceResidualNs", current.lastMetrics.sourceResidualNanos)
                put("luMaxFill", result?.float?.luMaxFill?.let(::JsonPrimitive) ?: JsonNull)
                put("luMaxDensity", result?.float?.luMaxDensity?.let(::JsonPrimitive) ?: JsonNull)
                put("refactorTriggers", numerical?.lastRefactorPolicyMetrics?.triggers?.let { triggers ->
                    numbers(triggers)
                } ?: JsonNull)
                put("qualitySamples", numerical?.lastRefactorPolicyMetrics?.qualitySamples?.let(::JsonPrimitive) ?: JsonNull)
                put("trace", numerical?.progressTrace?.let(::traceJson) ?: JsonNull)
            })
            if (!retained) {
                val closing = Monotonic.markNow()
                current.close()
                closeNs += closing.elapsedNow().inWholeNanoseconds
                owner = null
            }
        }
    } finally {
        val closing = Monotonic.markNow()
        owner?.close()
        closeNs += closing.elapsedNow().inWholeNanoseconds
        val elapsed = started.elapsedNow().inWholeNanoseconds
        val bytes = allocated?.let { start -> profileAllocatedBytes()?.minus(start) }
        println(buildJsonObject {
            put("kind", "lifecycle")
            put("round", round)
            put("retained", retained)
            put("observed", observed)
            put("elapsedNs", elapsed)
            put("closeNs", closeNs)
            put("allocatedBytes", bytes?.let(::JsonPrimitive) ?: JsonNull)
            put("nativeAllocationAvailable", false)
            put("cancelled", cancellation())
        })
    }
}

private fun numbers(values: Map<*, Long>): JsonElement = buildJsonObject {
    values.forEach { (key, value) -> put(key.toString(), value) }
}

private fun traceJson(trace: SimplexProgressTrace): JsonElement = buildJsonObject {
    put("inclusivePhaseNs", numbers(trace.phaseNanos))
    put("phaseCalls", numbers(trace.phaseCalls))
    put("pricedColumns", trace.pricedColumns)
    put("degenerateSteps", trace.degenerateSteps.values.sum())
    put("basisWork", numbers(trace.basisWork))
    put("progressSamples", trace.samples.size)
    put("primalFeasibilityIterations", trace.iterations[SimplexPhase.PRIMAL_FEASIBILITY] ?: 0)
    put("sourcePrimalResidual", trace.sourcePrimalResidual?.let(::JsonPrimitive) ?: JsonNull)
    put("sourceBoundViolation", trace.sourceBoundViolation?.let(::JsonPrimitive) ?: JsonNull)
    put("sourceBasicDualResidual", trace.sourceBasicDualResidual?.let(::JsonPrimitive) ?: JsonNull)
}

private class LifecycleCertificates : LpCertificationObserver {
    var work = 0L
    val phases = mutableMapOf<LpSolvePhase, Long>()
    override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) {
        if (cost is LpCertifierCost.Metered) work += cost.work
    }
    override fun observeExactInput(accepted: Boolean) = Unit
    override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
    override fun observeBasisVerification(metrics: ExactBasisMetrics) { work += metrics.work }
    override fun observeContinuation(metrics: ExactContinuationMetrics) { work += metrics.work }
    override fun observePhase(metrics: LpPhaseMetrics) {
        phases[metrics.phase] = phases.getOrElse(metrics.phase) { 0L } + metrics.nanos
    }
}

private class LifecycleFactory(private val observed: Boolean) : LpEngineFactory by ProductionLpEngineFactory {
    var lastEngine: RevisedSimplex? = null
    var floatingNanos = 0L
    override fun newPersistentSolver(model: LpModel, cancellation: Cancellation, refactorUpdateLimit: Int,
        iterationLimit: Int, workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions): PersistentLpSolver {
        val solver = RevisedSimplex(model, cancellation, refactorUpdateLimit = refactorUpdateLimit,
            iterationLimit = iterationLimit, workLimit = workLimit, trackDegeneracy = trackDegeneracy, pricing = pricing)
        if (observed) solver.enableProgressTrace(profile = true)
        lastEngine = solver
        return object : PersistentLpSolver by solver {
            override fun solve(warm: Basis?): FloatLpResult? = timed { solver.solve(warm) }
            override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? = timed { solver.resolveBounds(allowance) }
        }
    }
    private inline fun <T> timed(block: () -> T): T {
        val started = Monotonic.markNow()
        try { return block() } finally { floatingNanos += started.elapsedNow().inWholeNanoseconds }
    }
}
