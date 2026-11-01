package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.formats.json.ProblemJson
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.presolve.AffinePivotOrder
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import java.io.File
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object ObjectiveCutoffProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys.all { it in setOf("suite", "problem", "sha256", "source64", "sourceGzip64",
            "cutoff", "seed", "order", "presolve", "presolve-work", "work", "slice-work", "timeout-ms",
            "point64", "point-sha256", "point-only", "portfolio", "initial-bt-probe-ms", "defer-bt-continuation",
            "cache-audit", "initial-uncut-work", "linear-bound-audit") })
        require((options["point64"] == null) == (options["point-sha256"] == null))
        require(options["point-only"] == null || options["point64"] != null)
        val portfolio = (options["portfolio"] ?: "false").toBooleanStrict()
        require(!portfolio || options["cutoff"] == null)
        require(portfolio || options["initial-bt-probe-ms"] == null)
        val initialProbe = options["initial-bt-probe-ms"]?.toLong()?.also { require(it in 1L..1000L) }
        val deferContinuation = (options["defer-bt-continuation"] ?: "false").toBooleanStrict()
        require(!deferContinuation || (portfolio && initialProbe != null))
        val cutoff = (options["cutoff"] ?: if (portfolio) "0" else error("cutoff is required")).toLong().also {
            require(it in -9_007_199_254_740_991..9_007_199_254_740_991)
        }
        val seed = (options["seed"] ?: "1").toLong()
        val timeout = (options["timeout-ms"] ?: "10000").toLong().also { require(it in 1..30_000L) }
        val maximumWork = (options["work"] ?: "150000").toLong().also { require(it in 1..1_000_000L) }
        val cacheAudit = (options["cache-audit"] ?: "true").toBooleanStrict()
        val linearAudit = (options["linear-bound-audit"] ?: "false").toBooleanStrict()
        require(!linearAudit || (!portfolio && !cacheAudit))
        val initialUncutWork = (options["initial-uncut-work"] ?: "0").toLong().also {
            require(it in 0..maximumWork)
        }
        require(!portfolio || (options["cache-audit"] == null && initialUncutWork == 0L))
        val sliceWork = (options["slice-work"] ?: "5000").toLong().also { require(it in 1..maximumWork) }
        val preparationWork = options.getValue("presolve-work").toLong().also { require(it in 1..1_000_000_000L) }
        val order = AffinePivotOrder.valueOf((options["order"] ?: "rewrite_cost").uppercase())
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        val containing = Cancellation.after(120.seconds)
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        out.resolve("request.txt").writeText(args.joinToString("\n"))
        val bytes = trajectorySourceBytes(options.getValue("suite"), options.getValue("problem"),
            options.getValue("sha256"), options["source64"], options["sourceGzip64"])
        require(bytes.size <= 1_048_576 && !containing())
        out.resolve("source.xml").writeBytes(bytes)
        val opened = System.nanoTime()
        val parsed = Xcsp3.parse(bytes.decodeToString(), recoveryCancellation = containing)
        require(parsed.sense == ObjectiveSense.MINIMIZE && parsed.problem.numRealVars == 0)
        require(parsed.problem.numBoolVars + parsed.problem.numIntVars <= 4096 && parsed.problem.numFactors <= 4096)
        require(parsed.problem.factors.sumOf { it.structuralKeyWeight.toLong() } <= 65536 && !containing())
        val originalObjective = requireNotNull(parsed.objective).toLinearObjective()
        val setup = SourceSetup.prepare(SourceSetupRequest(
            if (parsed.definedVars.isEmpty()) SourceAttachment(parsed.problem, parsed.definitionHints)
            else SourceAttachment(parsed.problem, parsed.definedVars, emptyList(), parsed.definitionHints),
            ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED, objective = originalObjective, cancellation = containing,
        ))
        val plan = requireNotNull(setup.preparedPlan)
        val budget = PresolveBudget(preparationWork)
        val prepared = FinitePipeline.prepare(FinitePipelineRequest(
            requireNotNull(plan.model), FiniteEngine.MIXED, originalObjective,
            presolveConfig = PresolveConfig.parse(options["presolve"] ?: "default").withAffinePivotOrder(order),
            explicitPresolveConfig = true, cancellation = containing, solveCancellation = containing,
            presolveBudget = budget, preparedPlan = plan, randomSeed = seed,
        ))
        val preparationNs = System.nanoTime() - opened
        val problem = requireNotNull(prepared.problem as? BakedProblem)
        val objective = prepared.objective ?: originalObjective
        val snapshot = ProblemJson().encodeToString(problem).encodeToByteArray()
        require(snapshot.size <= 1_048_576)
        out.resolve("prepared.json").writeBytes(snapshot)
        out.resolve("preparation.json").writeText(buildJsonObject {
            put("modelSha256", sha256(snapshot))
            put("originalObjective", originalObjective.toString())
            put("preparedObjective", objective.toString())
            put("preparationNs", preparationNs)
            put("preparationWork", budget.spent())
            put("cutoffSpace", "prepared objective")
        }.toString())
        if (options["point64"] != null) {
            ObjectivePointReplay.run(options, out, requireNotNull(plan.model), parsed.intVarNames, prepared,
                originalObjective, objective, containing)
            if ((options["point-only"] ?: "true").toBooleanStrict()) return
        }
        if (portfolio) {
            ObjectivePortfolioReplay.run(out, setup, prepared, originalObjective, objective, parsed.intVarNames,
                ObjectivePortfolioReplay.Controls(seed, timeout, initialProbe, deferContinuation), containing)
            return
        }
        val audit = if (cacheAudit) BooleanBoundAudit(objective.boolWeights) else null
        val captures = mutableListOf<PreparedArithmeticLearningProfile.Capture>()
        var cutoffActive = initialUncutWork == 0L
        val stop = containing or Cancellation.after(timeout.milliseconds) or Cancellation { audit?.stopped == true }
        val base = BacktrackPresets.satOptimized(randomSeed = seed + 1000L, cancellation = stop)
        val observed = if (audit == null) ObjectivePortfolioReplay.observing(base, captures, linearAudit) {
            error("fixed-cutoff native capture allowance exhausted")
        } else base.copy(variableSelector = audit.observing(base.variableSelector))
        val params = observed.copy(
            objectiveBoundSupplier = { if (cutoffActive) cutoff.toDouble() else Double.POSITIVE_INFINITY },
            solveBudgetMillis = timeout,
        )
        var slices = 0
        var points = 0
        var outputBytes = 0L
        var terminal: MinimizeResult? = null
        val searchStarted = System.nanoTime()
        out.resolve("replay.jsonl").bufferedWriter().use { output ->
            fun publish(record: JsonObject) {
                val encoded = record.toString()
                val bytes = encoded.encodeToByteArray().size + 1L
                check(bytes <= 4_194_304L - outputBytes) { "replay output allowance exhausted" }
                outputBytes += bytes
                output.appendLine(encoded)
                output.flush()
            }
            BacktrackSolver(problem).resumable(objective, params).use { search ->
                while (terminal == null && !stop() && search.work < maximumWork && slices < 4096) {
                    slices++
                    val grant = if (cutoffActive) sliceWork else initialUncutWork
                    terminal = search.runSlice(stop, timeout, minOf(grant, maximumWork - search.work)) { result ->
                        if (points < 128) {
                            val source = prepared.reconstruct(result.sample)
                            publish(buildJsonObject {
                                put("event", "point")
                                put("slice", slices)
                                put("preparedCost", objective.evaluateExact(result.sample).toString())
                                put("sourceCost", originalObjective.evaluateExact(source).toString())
                                put("namedIntegers", buildJsonObject {
                                    for ((name, variable) in parsed.intVarNames) put(name, source.intValue(variable))
                                })
                                put("independentlyChecked", false)
                            })
                        }
                        points++
                    }
                    audit?.observe()
                    publish(buildJsonObject {
                        put("event", "slice")
                        put("slice", slices)
                        put("work", search.work)
                        put("nodes", search.stats.search.nodes.sum)
                        put("propagationWork", search.stats.search.propagationWork.sum)
                        put("finiteCutoffActive", cutoffActive)
                        put("audit", audit?.report() ?: JsonNull)
                    })
                    cutoffActive = true
                }
                publish(buildJsonObject {
                    put("event", "terminal")
                    put("nativeResult", terminal?.let { it::class.simpleName })
                    put("nativeReason", when (val result = terminal) {
                        is MinimizeResult.Unknown -> result.reason.toString()
                        is MinimizeResult.BestFound -> result.reason.toString()
                        else -> null
                    })
                    put("cutoff", cutoff)
                    put("initialUncutWork", initialUncutWork)
                    put("cacheAuditEnabled", cacheAudit)
                    put("linearBoundAuditEnabled", linearAudit)
                    put("preparationNs", preparationNs)
                    put("preparationWork", budget.spent())
                    put("searchNs", System.nanoTime() - searchStarted)
                    put("work", search.work)
                    put("slices", slices)
                    put("points", points)
                    put("pointsOmitted", (points - 128).coerceAtLeast(0))
                    put("cancelled", stop())
                    put("audit", audit?.report() ?: JsonNull)
                    put("independentlyCheckedProof", false)
                })
            }
            for ((index, capture) in captures.withIndex()) {
                publish(buildJsonObject {
                    put("event", "native-trace")
                    put("session", index)
                    put("capture", capture.export())
                })
                capture.release()
            }
        }
    }
}

private class BooleanBoundAudit(private val weights: LongArray) {
    private var session: PropagationSession? = null
    private var visits = 0L
    private var observations = 0L
    private var visitLimitReached = false
    private var failure: JsonObject? = null
    val stopped: Boolean get() = failure != null || visitLimitReached

    init {
        require(weights.all { it >= 0L })
        weights.fold(0L, Math::addExact)
    }

    fun observing(delegate: VariableSelector): VariableSelector = object : VariableSelector by delegate {
        override fun fresh(): VariableSelector = observing(delegate.fresh())
        override fun onSessionCreated(session: PropagationSession) {
            check(this@BooleanBoundAudit.session == null || this@BooleanBoundAudit.session === session)
            this@BooleanBoundAudit.session = session
            delegate.onSessionCreated(session)
        }
        override fun pick(session: PropagationSession, rng: Random): VarRef? {
            observe()
            return delegate.pick(session, rng)
        }
        override fun onPropagation(implied: PropagationResult.Implied) {
            observe()
            delegate.onPropagation(implied)
        }
    }

    fun observe() {
        val live = session ?: return
        if (stopped) return
        if (weights.size > 4_000_000L - visits) {
            visitLimitReached = true
            return
        }
        var fresh = 0L
        for (variable in weights.indices) if (live.boolValue(variable) == true) fresh += weights[variable]
        visits += weights.size
        visitLimitReached = visits == 4_000_000L
        observations++
        val cached = live.objectiveBoolLowerBound()
        // With nonnegative weights an uninstalled cache is zero, hence cannot cause an overshoot.
        if (cached > fresh) failure = buildJsonObject {
            put("cached", cached)
            put("fresh", fresh)
            put("level", live.decisionLevel)
            put("observation", observations)
        }
    }

    fun report(): JsonObject = buildJsonObject {
        put("attached", session != null)
        put("observations", observations)
        put("visits", visits)
        put("visitLimitReached", visitLimitReached)
        put("stopped", stopped)
        put("overshoot", failure ?: JsonNull)
    }
}
