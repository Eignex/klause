package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.symmetry.SymmetryHandling
import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.formats.dimacs.toProblem
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

internal object ConditionalSymmetryProfile {
    private const val SOURCE_HASH = "989c4a4a8b7381f374b2dacf00784af81f2a8dd5cb17850a9094e141661c5b56"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty())
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        val bytes = trajectorySourceBytes("wcnf-core", "conditional-cost", SOURCE_HASH, null)
        out.resolve("source.wcnf").writeBytes(bytes)
        val source = Dimacs.parseWcnf(bytes.decodeToString()).toProblem()
        val objective = source.objective.toLinearObjective()
        for (openingPins in listOf(false, true)) {
            val problem = if (openingPins) source.problem.withFactors(source.problem.factors +
                arrayOf(Clause(intArrayOf(Lit.make(2, true))), Clause(intArrayOf(Lit.make(3, true)))))
            else source.problem
            for (mode in listOf("default", "default,+objective-symmetry")) {
                val stop = Cancellation.after(8.seconds)
                val budget = PresolveBudget(550_000_000L)
                val start = System.nanoTime()
                val prepared = FinitePipeline.prepare(FinitePipelineRequest(
                    problem, FiniteEngine.BACKTRACK, objective, PresolveConfig.parse(mode),
                    explicitPresolveConfig = true, cancellation = stop, solveCancellation = stop,
                    presolveBudget = budget, randomSeed = 3,
                ))
                val preparationNs = System.nanoTime() - start
                val model = prepared.problem
                val report = buildJsonObject {
                    put("revision", Reports.readGitSha())
                    put("sourceSha256", SOURCE_HASH)
                    put("openingPins", openingPins)
                    put("mode", mode)
                    put("sourceObjective", objective.toString())
                    put("preparedObjective", prepared.objective.toString())
                    put("preparationNs", preparationNs)
                    put("preparationWork", budget.spent())
                    put("stopped", stop())
                    put("passes", JsonArray(prepared.presolve?.passes.orEmpty().map(::JsonPrimitive)))
                    put("factors", factors(model))
                    put("generators", model.factors.filterIsInstance<SymmetryHandling>().sumOf { it.generators.size })
                    if (!openingPins && model is BakedProblem && !stop() && prepared.presolve?.infeasible != true) {
                        val session = PropagationSession(model, stop)
                        put("root", snapshot(session))
                        session.pinBool(2, true)
                        put("s", snapshot(session))
                        session.pinBool(3, true)
                        put("st", snapshot(session))
                        session.popToLevel(0)
                        put("undo", snapshot(session))
                        put("solves", buildJsonArray {
                            for (arm in listOf("ordinary", "lp-bound", "lp-off", "pb-cutoff", "prefix-first")) {
                                val token = Cancellation.after(5.seconds)
                                val trace = ArrayList<JsonObject>()
                                val base = BacktrackPresets.conflictDriven(randomSeed = 3, cancellation = token)
                                val delegate = if (arm == "prefix-first") InputOrder else base.variableSelector
                                val selector = object : VariableSelector by delegate {
                                    override fun fresh(): VariableSelector = this
                                    override fun pick(session: PropagationSession, rng: Random): VarRef? {
                                        if (trace.size < 32) trace += snapshot(session)
                                        if (arm == "prefix-first") {
                                            for (v in intArrayOf(2, 3)) if (session.boolValue(v) == null) {
                                                return VarRef.Bool(v)
                                            }
                                        }
                                        return delegate.pick(session, rng)
                                    }
                                }
                                val lp = if (arm == "ordinary" || arm == "lp-bound") LpConfig.AUTO else LpConfig.OFF
                                val params: BacktrackParams = base.copy(
                                    variableSelector = selector,
                                    lpConfig = lp,
                                    lpPlan = LpPlan(bounding = arm == "lp-bound"),
                                    pbObjectiveCutoff = arm == "pb-cutoff",
                                    objectiveGuidedValues = arm == "prefix-first",
                                )
                                val solveStart = System.nanoTime()
                                val result = BacktrackSolver(model).minimize(requireNotNull(prepared.objective), params)
                                val solveNs = System.nanoTime() - solveStart
                                add(buildJsonObject {
                                    put("arm", arm)
                                    put("result", result::class.simpleName)
                                    put("objective", result.objectiveValue)
                                    put("stopped", token())
                                    put("solveNs", solveNs)
                                    put("nodes", result.stats.search.nodes.sum)
                                    put("fails", result.stats.search.fails.sum)
                                    put("restarts", result.stats.search.restarts.sum)
                                    put("lpBound", result.stats.lp.rootBound.takeIf { it.isFinite() })
                                    put("lpWork", result.stats.lp.workOps.sum)
                                    result.assignment?.let(prepared.reconstruct)?.let { sample ->
                                        put("sourceObjective", objective.evaluateExact(sample).toString())
                                        put("sourceBooleans", (0..3).joinToString("") {
                                            if (sample.boolValue(it)) "1" else "0"
                                        })
                                    }
                                    put("branchStates", JsonArray(trace))
                                })
                            }
                        })
                    }
                    put("independentSourceCheck", false)
                    put("conditionalPruningInstalled", false)
                    put("completeResidualCertificate", false)
                }
                val scope = if (openingPins) "opening" else "retained"
                val admission = if (mode == "default") "protected" else "objective"
                val name = "$scope-$admission"
                out.resolve("$name.json").writeText(report.toString())
                println("$name: ${model.factors.size} factors, stopped=${stop()}")
            }
        }
    }

    private fun factors(problem: Problem): JsonArray = buildJsonArray {
        for (factor in problem.factors) add(buildJsonObject {
            put("kind", factor::class.simpleName)
            if (factor is Clause) put("literals", JsonArray(factor.literals.map(::JsonPrimitive)))
            else put("structure", factor.structuralKey().toString())
        })
    }

    private fun snapshot(session: PropagationSession): JsonObject = buildJsonObject {
        put("level", session.decisionLevel)
        put("booleans", (0 until session.problem.numBoolVars).joinToString("") {
            when (session.boolValue(it)) {
                true -> "1"
                false -> "0"
                null -> "?"
            }
        })
        put("factors", factors(session.problem))
    }
}
