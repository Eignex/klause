package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioExecutor
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelinePreparation
import com.eignex.klause.solver.pipeline.PreparedSourceInvocation
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.search.AssembledSearchModel
import com.eignex.klause.solver.search.SearchBranchAttemptObserver
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchLearnedConflict
import com.eignex.klause.util.Cancellation
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object ObjectivePortfolioReplay {
    fun run(
        out: File,
        invocation: PreparedSourceInvocation,
        prepared: FinitePipelinePreparation,
        originalObjective: LinearObjective,
        objective: LinearObjective,
        names: Map<String, Int>,
        seed: Long,
        timeout: Long,
        containing: Cancellation,
    ) {
        val captures = ArrayList<PreparedArithmeticLearningProfile.Capture>()
        var omittedSessions = 0
        var bytes = 0L
        var omittedRecords = 0L
        var points = 0
        out.resolve("portfolio-replay.jsonl").bufferedWriter().use { output ->
            fun publish(record: JsonObject) {
                val encoded = record.toString()
                val size = encoded.encodeToByteArray().size + 1L
                val limit = when (record["event"]) {
                    JsonPrimitive("native-trace") -> 4_193_280L
                    JsonPrimitive("native-trace-summary") -> 4_194_304L
                    else -> 2_097_152L
                }
                if (size > 131_072L || size > limit - bytes) {
                    omittedRecords++
                    return
                }
                bytes += size
                output.appendLine(encoded)
                output.flush()
            }
            val hints = LocalSearchTrajectory.heuristicHints(invocation, prepared, containing)
            val sweep = hints?.let { get(it, "Sweep") as DefinitionalSweep? }
            val functional = hints?.let { get(it, "Objective") as IncrementalObjective? }
            val scenario = PortfolioScenario.sequential(Kind.COP, EngineMix.MIXED, seed).copy(
                reportPortfolioProgress = true,
                btEdit = { params -> observing(params, captures) { omittedSessions++ } },
            )
            val plan = prepared.javaClass.getDeclaredField("executionPlan").apply { isAccessible = true }.get(prepared)
            requireNotNull(plan)
            val stop = containing or Cancellation.after(timeout.milliseconds)
            val events: (String, SearchEvent) -> Unit = { worker, event ->
                publish(buildJsonObject {
                    put("event", "engine-event")
                    put("worker", worker)
                    val description = event.toString()
                    put("observation", description.take(8192))
                    put("observationTruncated", description.length > 8192)
                })
            }
            val executor = Class.forName("com.eignex.klause.solver.pipeline.FinitePortfolioExecutorKt")
                .declaredMethods.single { it.name == "portfolioExecutor" && it.parameterCount == 10 }
                .invoke(null, FinitePipeline, prepared.problem as BakedProblem, scenario, objective, functional,
                    sweep, events, null, plan, stop) as PortfolioExecutor
            executor.use {
                val portfolio = executor.javaClass.getDeclaredField("portfolio").apply { isAccessible = true }
                    .get(executor) as Portfolio
                val observer: (Any) -> Unit = { observation -> publish(completion(portfolio, observation)) }
                portfolio.javaClass.getDeclaredField("completionObserver").apply { isAccessible = true }
                    .set(portfolio, observer)
                publish(buildJsonObject {
                    put("event", "composition")
                    put("workers", JsonArray(portfolio.workers.map { JsonPrimitive(it.label) }))
                    put("sourceBoundExecutor", true)
                    put("initialExternalCutoff", "none; uses actual pooled incumbents")
                    put("btEdit", "observer only; verify actual composition")
                    put("timeoutMs", timeout)
                })
                val result = executor.minimize(stop) { offered ->
                    val native = offered.result as? MinimizeResult.WithSample
                    if (native != null && points++ < 128) {
                        val source = prepared.reconstruct(native.sample)
                        publish(buildJsonObject {
                            put("event", "point")
                            put("worker", offered.workerLabel)
                            put("preparedCost", objective.evaluateExact(native.sample).toString())
                            put("sourceCost", originalObjective.evaluateExact(source).toString())
                            put("namedIntegers", buildJsonObject {
                                for ((name, variable) in names) put(name, source.intValue(variable))
                            })
                            put("independentlyChecked", false)
                        })
                    }
                }
                publish(buildJsonObject {
                    put("event", "result")
                    put("nativeResult", result::class.simpleName)
                    put("reason", reason(result))
                    put("objective", result.assignment?.let { objective.evaluateExact(it).toString() })
                    put("capturedSessions", captures.size)
                    put("omittedSessions", omittedSessions)
                    put("omittedRecords", omittedRecords)
                    put("points", points)
                    put("independentlyCheckedProof", false)
                })
                val stats = result.stats.toString()
                require(stats.encodeToByteArray().size <= 1_048_576)
                out.resolve("portfolio-stats.txt").writeText(stats)
            }
            captures.forEachIndexed { index, capture ->
                publish(buildJsonObject {
                    put("event", "native-trace")
                    put("session", index)
                    put("capture", capture.export())
                })
                capture.release()
            }
            publish(buildJsonObject {
                put("event", "native-trace-summary")
                put("omittedRecords", omittedRecords)
                put("omittedSessions", omittedSessions)
                put("outputBytesBeforeSummary", bytes)
            })
        }
    }

    private fun observing(
        params: BacktrackParams,
        captures: MutableList<PreparedArithmeticLearningProfile.Capture>,
        omitted: () -> Unit,
    ): BacktrackParams {
        var capture: PreparedArithmeticLearningProfile.Capture? = null
        fun selector(delegate: VariableSelector): VariableSelector = object : VariableSelector by delegate {
            override fun fresh(): VariableSelector = selector(delegate.fresh())
            override fun onSessionCreated(session: PropagationSession) {
                val existing = captures.firstOrNull { it.owns(session) }
                if (existing != null) {
                    capture = existing
                } else if (captures.size < 8) {
                    capture = PreparedArithmeticLearningProfile.Capture(session, maxWords = 2048, retainBoundaries = true)
                        .also { recorder ->
                            captures.add(recorder)
                            recorder.boundary("session.created", params.randomSeed ?: -1L)
                        }
                } else {
                    capture = null
                    omitted()
                }
                delegate.onSessionCreated(session)
            }
        }
        val delegate = params.branchObserver
        return params.copy(variableSelector = selector(params.variableSelector), branchObserver =
            object : SearchBranchAttemptObserver {
                override fun onAttempt(decision: SearchDecision, context: SearchContext) {
                    delegate?.onAttempt(decision, context)
                    when (decision) {
                        is SearchDecision.Bool -> capture?.boundary("traversal.attempt-bool",
                            context.decisionLevel.toLong(), decision.literal.toLong())
                        is SearchDecision.IntAtMost -> capture?.boundary("traversal.attempt-upper",
                            context.decisionLevel.toLong(), decision.variable.toLong(), decision.upper)
                        is SearchDecision.IntAtLeast -> capture?.boundary("traversal.attempt-lower",
                            context.decisionLevel.toLong(), decision.variable.toLong(), decision.lower)
                        is SearchDecision.IntEqual -> capture?.boundary("traversal.attempt-equal",
                            context.decisionLevel.toLong(), decision.variable.toLong(), decision.value)
                        is SearchDecision.Theory -> capture?.boundary("traversal.attempt-opaque",
                            context.decisionLevel.toLong())
                    }
                }
                override fun onCommit(decision: SearchDecision, decisionLevel: Int) {
                    delegate?.onCommit(decision, decisionLevel)
                }
                override fun onConflict(decision: SearchDecision?) { delegate?.onConflict(decision) }
                override fun onLearnedNodeBackjump() { delegate?.onLearnedNodeBackjump() }
                override fun onLearnedConflict(conflict: SearchLearnedConflict) {
                    delegate?.onLearnedConflict(conflict)
                    capture?.boundary("traversal.learned-before-pop", conflict.decisionLevel.toLong(),
                        conflict.lbd.toLong(), conflict.guardLiterals.size.toLong())
                    val guards = conflict.guardLiterals
                    if (guards.size <= 16) {
                        capture?.boundary("traversal.learned-guards", *guards.map(Int::toLong).toLongArray())
                    } else capture?.boundary("traversal.learned-guards-omitted", guards.size.toLong())
                }
                override fun onRestart(decisions: Long) { delegate?.onRestart(decisions) }
                override fun onModel(model: AssembledSearchModel) { delegate?.onModel(model) }
            })
    }

    private fun completion(portfolio: Portfolio, observation: Any): JsonObject = buildJsonObject {
        val actual = get(observation, "NativeResult") as? MinimizeResult
        val completed = get(observation, "Completion")
        val result = completed?.let { get(it, "Result") as MinimizeResult }
        val producer = completed?.let { get(it, "Invocation") }
        val verifier = portfolio.javaClass.getDeclaredField("evidenceVerification").apply { isAccessible = true }
            .get(portfolio)
        val consumer = verifier?.javaClass?.getDeclaredField("invocation")?.apply { isAccessible = true }?.get(verifier)
        put("event", "proof-completion")
        put("arm", get(observation, "Arm") as Int)
        put("worker", (get(observation, "Worker") as PortfolioWorker).label)
        put("nativeResult", actual?.let { it::class.simpleName })
        put("nativeReason", actual?.let(::reason))
        put("completedResult", result?.let { it::class.simpleName })
        put("completedReason", result?.let(::reason))
        put("observedCutoff", get(observation, "Cutoff") as Double?)
        put("executionCutoff", completed?.let { get(it, "ExecutionCutoff") as Double? })
        put("executionBound", completed?.let { get(it, "ExecutionBound") as Double? })
        put("producerInvocation", producer != null)
        put("consumerInvocation", consumer != null)
        put("invocationMatches", if (producer != null && consumer != null) {
            producer.javaClass.getMethod("matches", producer.javaClass).invoke(producer, consumer) as Boolean
        } else null)
        put("verification", get(observation, "Verification")?.let { it::class.simpleName })
        put("independentlyCheckedProof", false)
    }

    private fun reason(result: MinimizeResult): String? = when (result) {
        is MinimizeResult.Unknown -> result.reason.name
        is MinimizeResult.BestFound -> result.reason.name
        else -> null
    }

    private fun get(owner: Any, property: String): Any? = owner.javaClass.methods.single {
        it.name.startsWith("get$property") && it.parameterCount == 0
    }.invoke(owner)
}
