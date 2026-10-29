package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.ExactBasisMetrics
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpCertificationObserver
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpCertifierCost
import com.eignex.klause.lp.engine.LpClaim
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPhaseMetrics
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpScopedRow
import com.eignex.klause.lp.engine.LpScopedSolver
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.LpSolvePhase
import com.eignex.klause.lp.engine.LpSolver
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.engine.SimplexPhase
import com.eignex.klause.lp.engine.SimplexProfilePhase
import com.eignex.klause.lp.engine.SimplexProgressTrace
import com.eignex.klause.lp.engine.TableauCutSolver
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.simplex.exact.ExactContinuationMetrics
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource.Monotonic
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal expect fun profileAllocatedBytes(): Long?

/** Runs bounded cold and retained LP lifecycle observations for one hash-pinned source. */
fun main(args: Array<String>) {
    val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
    require(options.keys - "mode" == setOf("source64", "source", "sha256", "repeats"))
    val mode = options["mode"] ?: "lifecycle"
    val claims = mode == "claims" || mode == "precision"
    require(claims || mode == "lifecycle")
    val bytes = Base64.decode(options.getValue("source64"))
    require(bytes.size in 1..1_048_576)
    val repeats = options.getValue("repeats").toInt().also { require(it in 1..5) }
    val stop = Cancellation.after(120.seconds)
    val start = Monotonic.markNow()
    val preparationAllocated = profileAllocatedBytes()
    val compiled = Mps.parse(bytes.decodeToString()).toProblem()
    val objective = compiled.objective?.toLinearObjective()?.let { if (compiled.maximize) it.negated() else it }
    val projection = CpToLpRelaxation(compiled.model, objective)
        .build(PropagationSession(compiled.model, cancellation = stop), cancellation = stop)
    val initial = LpExactState(requireNotNull(projection.model.authoritativeModel()))
    val column = (0 until initial.model.n).firstOrNull {
        val lo = initial.activeSide(it, false)?.side?.number?.value
        val hi = initial.activeSide(it, true)?.side?.number?.value
        lo != null && (hi == null || hi > lo)
    }
    requireNotNull(column) { "a structural lower side is required for the edit control" }
    val lower = requireNotNull(initial.activeSide(column, false)).side.number.value
    val upper = initial.activeSide(column, true)?.side?.number?.value
    val edited = if (upper != null && upper > lower) {
        (lower + upper) * BigFraction.ofLong(2L).reciprocal()
    } else {
        lower + BigFraction.ONE
    }
    val side = ExactLpSide(ExactLpNumber.of(edited))
    println(
        buildJsonObject {
            put("kind", "source-preparation")
            put("source", options.getValue("source"))
            put("sha256", options.getValue("sha256"))
            put("elapsedNs", start.elapsedNow().inWholeNanoseconds)
            put(
                "allocatedBytes",
                preparationAllocated?.let { allocated ->
                    profileAllocatedBytes()?.minus(allocated)?.let(::JsonPrimitive)
                } ?: JsonNull,
            )
            put("rows", initial.model.m)
            put("columns", initial.model.n)
            put("editColumn", column)
            put("editLower", edited.toString())
            put("sourceVerdictClaim", false)
            put("kotlinVersion", KotlinVersion.CURRENT.toString())
        },
    )
    for (round in -1 until repeats) {
        if (mode == "precision") {
            val arms = if (round % 2 == 0) listOf(false, true) else listOf(true, false)
            for (arbitrary in arms) {
                for (claim in LpClaim.entries) {
                    if (profileExpired(stop)) return
                    lifecycle(initial, column, side, round, true, true, stop, claim, true, arbitrary)
                }
            }
            continue
        }
        if (claims) {
            for (claim in LpClaim.entries) {
                if (profileExpired(stop)) return
                lifecycle(initial, column, side, round, true, true, stop, claim, true)
            }
            continue
        }
        for (retained in if (round % 2 == 0) listOf(true, false) else listOf(false, true)) {
            for (observed in listOf(false, true)) {
                if (profileExpired(stop)) return
                lifecycle(initial, column, side, round, retained, observed, stop)
            }
        }
    }
    if (!profileExpired(stop)) sourceWorker(compiled.model, requireNotNull(objective), stop)
}

private fun profileExpired(cancellation: Cancellation): Boolean {
    if (!cancellation()) return false
    println(buildJsonObject {
        put("kind", "profile-stop")
        put("complete", false)
        put("reason", "containing-allowance")
        put("cancelled", true)
    })
    return true
}

private fun lifecycle(
    initial: LpExactState,
    column: Int,
    side: ExactLpSide,
    round: Int,
    retained: Boolean,
    observed: Boolean,
    cancellation: Cancellation,
    claim: LpClaim = LpClaim.ATTAINED_OPTIMUM,
    claims: Boolean = false,
    arbitraryPrecision: Boolean = false,
) {
    val factory = LifecycleFactory(observed)
    val context = LpSolveContext(engineFactory = factory)
    var state = initial
    var owner: LpScopedSolver? = null
    val started = Monotonic.markNow()
    val allocated = profileAllocatedBytes()
    var closeNs = 0L
    var activeNs = 0L
    var previousState: LpExactState? = null
    var previousHeadings: List<Int>? = null
    var previousStatuses: List<String>? = null
    val stages = if (claims) {
        listOf("cold", "unchanged", "authority", "authority-repeat", "bound", "bound-repeat", "bound-pop",
            "row", "row-repeat", "row-pop", "objective", "objective-repeat", "activation", "activation-repeat")
    } else listOf("cold", "bound", "bound-pop", "row", "row-pop")
    try {
        for (stage in stages) {
            if (cancellation()) return
            val stageStart = Monotonic.markNow()
            val stageAllocated = profileAllocatedBytes()
            val creationStart = Monotonic.markNow()
            if (owner == null) {
                owner = LpScopedSolver(
                    state,
                    cancellation,
                    context,
                    iterationLimit = 4096,
                    workLimit = 50_000_000L,
                )
            }
            val current = requireNotNull(owner)
            val constructionNs = creationStart.elapsedNow().inWholeNanoseconds
            val previousEngine = factory.lastEngine
            val previousTrace = previousEngine?.progressTrace
            val previousPhases = previousTrace?.phaseNanos?.toMap().orEmpty()
            val editStart = Monotonic.markNow()
            when (stage) {
                "authority" -> check(current.adopt(LpExactState(current.state.baseModel)) || cancellation())

                "objective" -> {
                    val objective = current.state.model.objective
                    check(current.replaceObjective(objective.withConstant(
                        ExactLpNumber.of(objective.constant.value + BigFraction.ONE),
                    )) || cancellation())
                }

                "activation" -> check(current.deactivate(initial.rows.row(0).id) || cancellation())

                "bound" -> {
                    check(current.push() || cancellation())
                    check(current.assertBound(column, false, side, 1L) || cancellation())
                }

                "bound-pop", "row-pop" -> check(current.pop(0) || cancellation())

                "row" -> {
                    check(current.push() || cancellation())
                    check(
                        current.append(
                            LpScopedRow(
                                current.state.rows.lastId + 1L,
                                listOf(column to ExactLpNumber.of(-1L)),
                                ExactLpNumber.of(side.number.value.negated()),
                                ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L)))),
                            ),
                            scoped = true,
                        ) || cancellation(),
                    )
                }
            }
            val editNs = editStart.elapsedNow().inWholeNanoseconds
            val preparationStart = Monotonic.markNow()
            val prepared = current.prepare()
            val preparationNs = preparationStart.elapsedNow().inWholeNanoseconds
            val editPhases = mutableMapOf<SimplexProfilePhase, Long>()
            for (engine in listOfNotNull(previousEngine, factory.lastEngine).distinct()) {
                val trace = engine.progressTrace ?: continue
                for ((phase, nanos) in trace.phaseNanos) {
                    val prior = if (trace === previousTrace) previousPhases[phase] ?: 0L else 0L
                    editPhases[phase] = editPhases.getOrElse(phase) { 0L } + nanos - prior
                }
            }
            val observer = LifecycleCertificates()
            val primaryEngine = factory.lastEngine
            val before = factory.floatingNanos
            val firstInvocation = factory.invocations.size
            val queryStart = Monotonic.markNow()
            factory.queryActive = true
            val result = try {
                if (prepared) current.solve(
                    observer = observer.takeIf { observed },
                    claim = claim,
                    continuationLimits = ExactContinuationLimits(startArbitraryPrecision = arbitraryPrecision),
                ) else null
            } finally {
                factory.queryActive = false
            }
            val queryNs = queryStart.elapsedNow().inWholeNanoseconds
            val stageNs = stageStart.elapsedNow().inWholeNanoseconds
            activeNs += stageNs
            val stageBytes = stageAllocated?.let { start -> profileAllocatedBytes()?.minus(start) }
            state = current.state
            val numerical = primaryEngine.takeIf { prepared }
            val metrics = if (prepared) current.lastMetrics else LpSolveMetrics()
            println(
                buildJsonObject {
                    put("kind", "query")
                    put("round", round)
                    put("warmup", round < 0)
                    put("retained", retained)
                    put("observed", observed)
                    put("stage", stage)
                    put("claim", claim.name)
                    put("startArbitraryPrecision", arbitraryPrecision)
                    put("sameAuthority", previousState?.let { JsonPrimitive(it === current.state) } ?: JsonNull)
                    put("matrixRevision", current.state.matrixRevision)
                    put("boundRevision", current.state.boundRevision)
                    put("objectiveRevision", current.state.objectiveRevision)
                    put("rowRevision", current.state.rowRevision)
                    put("depth", current.state.depth)
                    put("sameBasisHeadings", result?.float?.basis?.basicVars?.toList()?.let {
                        previousHeadings?.let { previous -> JsonPrimitive(previous == it) }
                    } ?: JsonNull)
                    put("sameBasisStatuses", result?.float?.basis?.status?.map { it.name }?.let {
                        previousStatuses?.let { previous -> JsonPrimitive(previous == it) }
                    } ?: JsonNull)
                    put("certificationReused", result?.certificationReused)
                    put("checkedResultStorageDeclined", current.checkedResultStorageDeclined)
                    put("structuralBasisColumns", result?.float?.basis?.basicVars?.count {
                        it < current.state.model.n
                    })
                    put("stageNs", stageNs)
                    put("constructionNs", constructionNs)
                    put("editNs", editNs)
                    put("preparationNs", preparationNs)
                    put("prepared", prepared)
                    put("editPreparationInclusivePhaseNs", numbers(editPhases))
                    put("queryNs", queryNs)
                    put("floatingNs", if (observed) JsonPrimitive(factory.floatingNanos - before) else JsonNull)
                    put("numericalInvocations", JsonArray(factory.invocations.drop(firstInvocation)))
                    put("droppedInvocations", factory.droppedInvocations)
                    put("queryNonFloatNs",
                        if (observed) JsonPrimitive(queryNs - (factory.floatingNanos - before)) else JsonNull)
                    put("allocatedBytes", stageBytes?.let(::JsonPrimitive) ?: JsonNull)
                    put("cancelled", cancellation())
                    put("verdict", result?.verdict?.name)
                    put("bound", result?.lowerBound?.toString())
                    put("witnessObjective", result?.witness?.objective?.toString())
                    put("floatObjective", result?.float?.objective?.let(::profileNumber) ?: JsonNull)
                    put("exactPoint", result?.exactPrimal?.toString())
                    put("termination", numerical?.lastTermination?.name)
                    put("pivots", metrics.pivots)
                    put("work", metrics.workOps)
                    put("preparationWork", current.metrics.preparationWork)
                    put("createdOwners", current.metrics.createdOwners)
                    put("closedOwners", current.metrics.closedOwners)
                    put("retainedRows", current.metrics.retainedRows)
                    put("activeRows", current.metrics.activeRows)
                    put("certificateWork", if (observed) JsonPrimitive(observer.work) else JsonNull)
                    put("certificateAttempts", if (observed) numbers(observer.attempts) else JsonNull)
                    put("exactInputAttempts", if (observed) JsonPrimitive(observer.exactInputAttempts) else JsonNull)
                    put("exactInputAccepted", if (observed) JsonPrimitive(observer.exactInputAccepted) else JsonNull)
                    put("certificatePhases", if (observed) numbers(observer.phases) else JsonNull)
                    put("reconstructionDecline", result?.reconstruction?.decline?.name)
                    put("reconstructionWork", result?.reconstruction?.work)
                    put("reconstructionAllocation", result?.reconstruction?.allocation)
                    put("reconstructionPointChecks", result?.reconstruction?.pointChecks)
                    put("reconstructionDualChecks", result?.reconstruction?.dualChecks)
                    put("reconstructionVectorRejection", result?.reconstruction?.vectorRejection?.toString())
                    put("reconstructionEntryRejection", result?.reconstruction?.entryRejection?.toString())
                    put("basisDecline", result?.basisVerification?.decline?.name)
                    put("continuationDecline", result?.continuation?.decline?.name)
                    put("continuation", result?.continuation?.let { metrics -> buildJsonObject {
                        put("phase", metrics.phase.name)
                        put("work", metrics.work)
                        put("allocation", metrics.allocation)
                        put("elapsedNs", metrics.elapsedNs)
                        put("builds", metrics.builds)
                        put("imports", metrics.imports)
                        put("importPosition", metrics.importPosition)
                        put("maxImportPivots", ExactContinuationLimits().maxImportPivots)
                        put("restarts", metrics.restarts)
                        put("resumed", metrics.resumed)
                        put("invalidated", metrics.invalidated)
                        put("workByPhase", numbers(metrics.workByPhase))
                    } } ?: JsonNull)
                    put("refinementDecline", result?.refinement?.decline?.name)
                    put("refinementWork", result?.refinement?.work)
                    put("refinementAllocation", result?.refinement?.allocation)
                    put("refinementElapsedNs", result?.refinement?.elapsed?.inWholeNanoseconds)
                    put("refinementReconstructionWork", result?.refinement?.reconstructionWork)
                    put("refinementLuWork", result?.refinement?.luWork)
                    put("refinementFloatWork", result?.refinement?.floatWork)
                    put("refinementLifetimeWork", current.refinementCache.work)
                    put("refinementLifetimeAllocation", current.refinementCache.allocation)
                    put("sourceResidualNs", metrics.sourceResidualNanos)
                    put("luMaxFill", result?.float?.luMaxFill?.let(::JsonPrimitive) ?: JsonNull)
                    put("luMaxDensity", result?.float?.luMaxDensity?.let(::JsonPrimitive) ?: JsonNull)
                    put(
                        "refactorTriggers",
                        numerical?.lastRefactorPolicyMetrics?.triggers?.let { triggers ->
                            numbers(triggers)
                        } ?: JsonNull,
                    )
                    put(
                        "qualitySamples",
                        numerical?.lastRefactorPolicyMetrics?.qualitySamples?.let(::JsonPrimitive) ?: JsonNull,
                    )
                    put(
                        "pivotSpread",
                        numerical?.lastRefactorPolicyMetrics?.pivotSpread?.let(::profileNumber) ?: JsonNull,
                    )
                    put("trace", numerical?.progressTrace?.let(::traceJson) ?: JsonNull)
                },
            )
            previousState = current.state
            previousHeadings = result?.float?.basis?.basicVars?.toList()
            previousStatuses = result?.float?.basis?.status?.map { it.name }
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
        println(
            buildJsonObject {
                put("kind", "lifecycle")
                put("claim", claim.name)
                    put("startArbitraryPrecision", arbitraryPrecision)
                put("round", round)
                put("retained", retained)
                put("observed", observed)
                put("elapsedNs", elapsed)
                put("activeNs", activeNs + closeNs)
                put("closeNs", closeNs)
                put("allocatedBytes", bytes?.let(::JsonPrimitive) ?: JsonNull)
                put("nativeAllocationAvailable", false)
                put("cancelled", cancellation())
            },
        )
    }
}

private fun numbers(values: Map<*, Long>): JsonElement = buildJsonObject {
    values.forEach { (key, value) -> put(key.toString(), value) }
}

private fun profileNumber(value: Double): JsonElement =
    if (value.isFinite()) JsonPrimitive(value) else JsonPrimitive(value.toString())

private fun traceJson(trace: SimplexProgressTrace): JsonElement = buildJsonObject {
    put("inclusivePhaseNs", numbers(trace.phaseNanos))
    put("phaseCalls", numbers(trace.phaseCalls))
    put("pricedColumns", trace.pricedColumns)
    put("degenerateSteps", trace.degenerateSteps.values.sum())
    put("basisWork", numbers(trace.basisWork))
    put("progressSamples", trace.samples.size)
    put("iterations", numbers(trace.iterations.mapValues { it.value.toLong() }))
    put("checkpointNs", trace.checkpointNanos)
    put(
        "samples",
        JsonArray(
            trace.samples.map { sample ->
                buildJsonObject {
                    put("phase", sample.phase.name)
                    put("pivots", sample.pivots)
                    put("work", sample.work)
                    put("scaledObjective", profileNumber(sample.scaledObjective))
                    put("scaledInfeasibility", profileNumber(sample.scaledInfeasibility))
                    put("scaledMaxViolation", profileNumber(sample.scaledMaxViolation))
                    put("violatedBasics", sample.violatedBasics)
                }
            },
        ),
    )
    put("primalFeasibilityIterations", trace.iterations[SimplexPhase.PRIMAL_FEASIBILITY] ?: 0)
    put("sourcePrimalResidual", trace.sourcePrimalResidual?.let(::profileNumber) ?: JsonNull)
    put("sourceBoundViolation", trace.sourceBoundViolation?.let(::profileNumber) ?: JsonNull)
    put("sourceBasicDualResidual", trace.sourceBasicDualResidual?.let(::profileNumber) ?: JsonNull)
}

private class LifecycleCertificates : LpCertificationObserver {
    var work = 0L
    val phases = mutableMapOf<LpSolvePhase, Long>()
    val attempts = mutableMapOf<String, Long>()
    var exactInputAttempts = 0
    var exactInputAccepted = 0
    override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) {
        val key = "$certifier:$success"
        attempts[key] = attempts.getOrElse(key) { 0L } + 1L
        if (cost is LpCertifierCost.Metered) work += cost.work
    }
    override fun observeExactInput(accepted: Boolean) {
        exactInputAttempts++
        if (accepted) exactInputAccepted++
    }
    override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
    override fun observeBasisVerification(metrics: ExactBasisMetrics) {
        work += metrics.work
    }
    override fun observeContinuation(metrics: ExactContinuationMetrics) {
        work += metrics.work
    }
    override fun observePhase(metrics: LpPhaseMetrics) {
        phases[metrics.phase] = phases.getOrElse(metrics.phase) { 0L } + metrics.nanos
    }
}

private class LifecycleFactory(
    private val observed: Boolean,
    private val classifyOwners: Boolean = true,
) : LpEngineFactory by ProductionLpEngineFactory {
    var lastEngine: RevisedSimplex? = null
    var queryActive = false
    var floatingNanos = 0L
    var completedInvocations = 0L
    var modeledWork = 0L
    var pivots = 0L
    val invocations = mutableListOf<JsonElement>()
    var droppedInvocations = 0

    override fun newGeneralSolver(
        model: LpModel,
        cancellation: Cancellation,
        workLimit: Long,
        pricing: LpPricingOptions,
    ): LpSolver = observe(RevisedSimplex(model, cancellation, workLimit = workLimit, pricing = pricing), false)

    override fun newTableauSolver(
        model: LpModel,
        cancellation: Cancellation,
        iterationLimit: Int,
        workLimit: Long,
        trackDegeneracy: Boolean,
        pricing: LpPricingOptions,
    ): TableauCutSolver = observe(RevisedSimplex(model, cancellation, iterationLimit = iterationLimit,
        workLimit = workLimit, trackDegeneracy = trackDegeneracy, pricing = pricing), true)

    override fun newPersistentSolver(
        model: LpModel,
        cancellation: Cancellation,
        refactorUpdateLimit: Int,
        iterationLimit: Int,
        workLimit: Long,
        trackDegeneracy: Boolean,
        pricing: LpPricingOptions,
    ): PersistentLpSolver = observe(RevisedSimplex(model, cancellation, refactorUpdateLimit = refactorUpdateLimit,
        iterationLimit = iterationLimit, workLimit = workLimit, trackDegeneracy = trackDegeneracy,
        pricing = pricing), true)

    private fun observe(solver: RevisedSimplex, primary: Boolean): RevisedSimplex {
        if (primary && !queryActive) lastEngine = solver
        if (observed) solver.profileTo { metrics, trace ->
            val elapsed = trace.phaseNanos.getValue(SimplexProfilePhase.SOLVE)
            floatingNanos += elapsed
            completedInvocations++
            modeledWork += metrics.workOps
            pivots += metrics.pivots
            if (invocations.size < 256) invocations.add(buildJsonObject {
                put("elapsedNs", elapsed)
                put("primary", if (classifyOwners) JsonPrimitive(solver === lastEngine) else JsonNull)
                put("work", metrics.workOps)
                put("pivots", metrics.pivots)
                put("termination", solver.lastTermination?.name)
                put("trace", traceJson(trace))
            }) else droppedInvocations++
        }
        return solver
    }
}

private fun sourceWorker(problem: Problem, objective: LinearObjective, containing: Cancellation) {
    val deadline = Cancellation.after(2.seconds)
    val stop = Cancellation { containing() || deadline() }
    val factory = LifecycleFactory(observed = true, classifyOwners = false)
    val started = Monotonic.markNow()
    val allocated = profileAllocatedBytes()
    val solver = BacktrackSolver(problem.bake(stop), LpSolveContext(engineFactory = factory))
    val controls = BacktrackPresets.satOptimized(randomSeed = 3L, cancellation = stop)
        .copy(maxInstructions = 1000L)
    val result = solver.minimize(objective, controls)
    val elapsed = started.elapsedNow().inWholeNanoseconds
    val bytes = allocated?.let { start -> profileAllocatedBytes()?.minus(start) }
    val lp = result.stats.lp
    println(buildJsonObject {
        put("kind", "source-worker")
        put("entry", "direct satOptimized BacktrackSolver; worker-native coordinates")
        put("sourceVerdictClaim", false)
        put("cliRoutingEquivalence", false)
        put("seed", 3)
        put("instructionLimit", 1000)
        put("containingMillis", 2000)
        put("elapsedNs", elapsed)
        put("allocatedBytes", bytes?.let(::JsonPrimitive) ?: JsonNull)
        put("result", result::class.simpleName)
        put("objective", result.objectiveValue?.let(::profileNumber) ?: JsonNull)
        put("cancelled", stop())
        put("numericalNs", factory.floatingNanos)
        put("numericalInvocations", factory.completedInvocations)
        put("numericalWork", factory.modeledWork)
        put("numericalPivots", factory.pivots)
        put("droppedInvocations", factory.droppedInvocations)
        put("invocations", JsonArray(factory.invocations))
        put("lpRootBound", lp.rootBound.takeIf { it.isFinite() }?.let(::JsonPrimitive) ?: JsonNull)
        put("lpPrunes", lp.pruned.sum)
        put("lpFixings", lp.fixed.sum)
        put("basisVerificationWork", numbers(lp.basisVerification.work))
        put("basisVerificationAllocation", numbers(lp.basisVerification.allocation))
        put("continuationWork", numbers(lp.continuation.work))
        put("continuationAllocation", numbers(lp.continuation.allocation))
        put("certificatePhases", buildJsonObject {
            lp.phases.forEach { (name, phase) -> put(name, buildJsonObject {
                put("calls", phase.calls)
                put("nanos", phase.nanos)
                put("work", phase.work)
                put("outcomes", numbers(phase.outcomes))
            }) }
        })
        put("witness", result.assignment?.let { sample -> buildJsonObject {
            put("bools", JsonArray(sample.bools.map(::JsonPrimitive)))
            put("ints", JsonArray(sample.exactInts?.map { JsonPrimitive(it.toString()) }
                ?: sample.ints.map(::JsonPrimitive)))
            put("reals", JsonArray(sample.reals.map(::profileNumber)))
            put("exactReals", sample.exactReals?.let { values ->
                JsonArray(values.map { JsonPrimitive(it.toString()) })
            } ?: JsonNull)
        } } ?: JsonNull)
    })
}
