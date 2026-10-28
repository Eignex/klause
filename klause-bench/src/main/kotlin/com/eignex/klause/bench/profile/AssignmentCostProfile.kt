package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.BakeConfig
import com.eignex.klause.presolve.RootBaker
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import java.io.File
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object AssignmentCostProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys.all { it in setOf("order", "seed") })
        val order = options["order"] ?: "input"
        require(order in setOf("input", "default"))
        val seed = options["seed"]?.toLong() ?: 3L
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        val latin = trajectorySourceBytes("xcsp3-core", "evidence-latin4",
            "d805214f71a168095eb818281431968d000d4bac556c73e4fcd3333c084c3828", null)
        val controls = mapOf("direct" to control(false).encodeToByteArray(),
            "alias" to control(true).encodeToByteArray(), "latin4" to latin)
        out.resolve("screen.jsonl").bufferedWriter().use { output ->
            for ((name, bytes) in controls) {
                out.resolve("$name.xml").writeBytes(bytes)
                if (name != "latin4" && order == "input") cutoffControl(bytes, out.resolve("$name-cutoff.json"))
                for (mode in if (order == "input") listOf("off", "default", "aggressive") else listOf("default")) {
                    val stop = Cancellation.after(4.seconds)
                    val parsed = Xcsp3.parse(bytes.decodeToString(), recoveryCancellation = stop)
                    val objective = requireNotNull(parsed.objective).toLinearObjective()
                    val original = parsed.problem.bake(stop)
                    val budget = PresolveBudget(100_000_000L)
                    val started = System.nanoTime()
                    val prepared = FinitePipeline.prepare(FinitePipelineRequest(
                        parsed.problem, FiniteEngine.BACKTRACK, objective, PresolveConfig.parse(mode),
                        explicitPresolveConfig = true, cancellation = stop, solveCancellation = stop,
                        presolveBudget = budget, randomSeed = 3,
                    ))
                    val preparationNs = System.nanoTime() - started
                    val preparationStopped = stop()
                    for (lp in listOf(false, true)) {
                        val base = BacktrackParams(randomSeed = seed)
                        val delegate = if (order == "input") InputOrder else base.variableSelector
                        val trace = ArrayList<JsonObject>()
                        val selector = object : VariableSelector by delegate {
                            override fun fresh(): VariableSelector = this
                            override fun pick(session: PropagationSession, rng: Random): VarRef? {
                                if (trace.size < 32) trace += buildJsonObject {
                                    put("level", session.decisionLevel)
                                    put("domains", JsonArray((0 until prepared.problem.numIntVars).map {
                                        JsonPrimitive(session.intDomain(it).toString())
                                    }))
                                }
                                return delegate.pick(session, rng)
                            }
                        }
                        val solveStop = Cancellation.after(2.seconds)
                        val params = base.copy(variableSelector = selector,
                            valueSelector = if (order == "input") IndomainMin else base.valueSelector,
                            cancellation = solveStop, lpConfig = if (lp) LpConfig.AUTO else LpConfig.OFF,
                            lpPlan = LpPlan(bounding = lp, cuts = lp, lagrangian = lp))
                        val solveStart = System.nanoTime()
                        val improvements = BacktrackSolver(prepared.problem.bake())
                            .improvements(requireNotNull(prepared.objective), params).toList()
                        val solveNs = System.nanoTime() - solveStart
                        val report = buildJsonObject {
                            put("revision", Reports.readGitSha())
                            put("source", name)
                            put("sourceSha256", sha256(bytes))
                            put("presolve", mode)
                            put("order", order)
                            put("seed", seed)
                            put("lp", lp)
                            put("preparationNs", preparationNs)
                            put("preparationWork", budget.spent())
                            put("preparationStopped", preparationStopped)
                            put("preparationInfeasible", prepared.presolve?.infeasible)
                            put("preparedObjective", prepared.objective.toString())
                            put("preparedDomains", JsonArray(prepared.problem.bake().rootIntDomains().map {
                                JsonPrimitive(it.toString())
                            }))
                            put("preparedFactors", JsonArray(prepared.problem.factors.map {
                                JsonPrimitive(it::class.simpleName)
                            }))
                            put("allDifferentScopes", JsonArray(prepared.problem.factors
                                .filterIsInstance<AllDifferent>().map { JsonPrimitive(it.vars.joinToString(",")) }))
                            put("solveNs", solveNs)
                            put("solveStopped", solveStop())
                            put("trace", JsonArray(trace))
                            put("improvements", JsonArray(improvements.map { result -> buildJsonObject {
                                put("kind", result::class.simpleName)
                                put("objective", result.objectiveValue)
                                put("nodes", result.stats.search.nodes.sum)
                                put("fails", result.stats.search.fails.sum)
                                put("lpRootBound", result.stats.lp.rootBound.takeIf { it.isFinite() })
                                put("lpSolves", result.stats.lp.solves.sum)
                                put("lpPruned", result.stats.lp.pruned.sum)
                                put("lpFixed", result.stats.lp.fixed.sum)
                                put("lpCuts", result.stats.lp.cuts.sum)
                                put("lagrangianPruned", result.stats.scheduling.lagrangianPruned.sum)
                                put("lpWork", result.stats.lp.workOps.sum)
                                put("lpOverhead", result.stats.lp.overheadOps.sum)
                                result.assignment?.let(prepared.reconstruct)?.let { sample ->
                                    put("nativeSourceAccepted", FiniteConflictSelection.checkSource(original, sample,
                                        stop or solveStop))
                                    put("sourceCheckStopped", stop() || solveStop())
                                    put("sourceObjective", objective.evaluateExact(sample).toString())
                                    put("sourceIntegers", JsonArray((0 until parsed.problem.numIntVars)
                                        .map { JsonPrimitive(sample.intValue(it)) }))
                                }
                            } }))
                            put("independentSourceCheck", false)
                            put("conditionalPruningInstalled", false)
                        }
                        output.appendLine(report.toString())
                        output.flush()
                        println(report)
                    }
                }
            }
        }
    }

    private fun cutoffControl(bytes: ByteArray, output: File) {
        val stop = Cancellation.after(2.seconds)
        val source = Xcsp3.parse(bytes.decodeToString(), recoveryCancellation = stop)
        val objective = requireNotNull(source.objective).toLinearObjective()
        val sourceModel = source.problem.bake(stop)
        val witness = Sample(BooleanArray(0), if (sourceModel.numIntVars == 4) {
            longArrayOf(1, 3, 4, 11)
        } else longArrayOf(1, 3, 4))
        val coefficients = objective.intCoefficients
        val cutoff = Linear(coefficients, IntArray(coefficients.size) { it }, LinearOp.LE, 10L)
        val query = source.problem.withFactors(source.problem.factors + cutoff).bake(stop)
        val root = PropagationSession(query, stop)
        val before = root.intDomain(0).toString()
        val pinned = root.pinInt(0, 1)
        val started = System.nanoTime()
        val probed = RootBaker.reseed(query, BakeConfig(probeIntBounds = true, probeIntHoles = true,
            probeBudgetPerVar = 32, probeTotalBudget = 128, probeSeed = 3))
        val elapsed = System.nanoTime() - started
        val report = buildJsonObject {
            put("sourceSha256", sha256(bytes))
            put("queryMaximum", 10)
            put("nativeSourceWitnessAccepted", FiniteConflictSelection.checkSource(sourceModel, witness, stop))
            put("sourceWitnessObjective", objective.evaluateExact(witness).toString())
            put("rootX", before)
            put("pinX1", pinned::class.simpleName)
            put("probedX", PropagationSession(probed, stop).intDomain(0).toString())
            put("probingNs", elapsed)
            put("stopped", stop())
            put("independentSourceCheck", false)
            put("conditionalPruningInstalled", false)
        }
        output.writeText(report.toString())
        println(report)
    }

    private fun control(alias: Boolean): String = """
        <instance format="XCSP3" type="COP">
          <variables><var id="x">0 1 2</var><var id="y">1 3</var><var id="z">3 4</var>
            ${if (alias) "<var id=\"o\">5..12</var>" else ""}
          </variables>
          <constraints><allDifferent>x y z</allDifferent>
            ${if (alias) "<sum><list>x y z o</list><coeffs>1 2 1 -1</coeffs><condition>(eq,0)</condition></sum>" else ""}
          </constraints>
          <objectives>${if (alias) "<minimize>o</minimize>" else
              "<minimize type=\"sum\"><list>x y z</list><coeffs>1 2 1</coeffs></minimize>"}</objectives>
        </instance>
    """.trimIndent()
}
