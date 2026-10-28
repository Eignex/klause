package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.impliedLinearRows
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.movesource.MoveSource
import com.eignex.klause.localsearch.movesource.MoveSourceCatalog
import com.eignex.klause.localsearch.movesource.SatisfiedStructured
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.util.Cancellation
import java.io.File
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object EqualityMoveReplay {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        val bytes = trajectorySourceBytes(options.getValue("suite"), options.getValue("problem"),
            options.getValue("sha256"), options["source64"], options["sourceGzip64"])
        out.resolve("source.xml").writeBytes(bytes)
        out.resolve("request.txt").writeText(args.joinToString("\n"))
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        val started = System.nanoTime()
        val allocated = trajectoryThreadBytes()
        val stop = Cancellation.after(30.seconds)
        val parsed = Xcsp3.parse(bytes.decodeToString())
        require(parsed.problem.numIntVars <= 128 && parsed.problem.numFactors <= 256)
        require(parsed.problem.numBoolVars == 0 && parsed.problem.numRealVars == 0)
        require(parsed.sense == ObjectiveSense.MINIMIZE)
        val source = parsed.problem.bake(stop)
        val objective = requireNotNull(parsed.objective).toLinearObjective()
        val center = Sample(booleanArrayOf(), options.getValue("center").split(',').map(String::toLong).toLongArray())
        check(FiniteConflictSelection.checkSource(source, center, stop))
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(parsed.problem, parsed.definitionHints), ExecutionOperation.OPTIMIZE,
            FiniteEngine.LOCAL_SEARCH, objective = objective, cancellation = stop))
        val plan = requireNotNull(invocation.preparedPlan)
        val prepared = FinitePipeline.prepare(FinitePipelineRequest(requireNotNull(plan.model),
            FiniteEngine.LOCAL_SEARCH, objective, cancellation = stop, solveCancellation = stop,
            presolveBudget = PresolveBudget(1_375_000_000L),
            presolveConfig = PresolveConfig.parse(options["presolve"]), preparedPlan = plan))
        check(!stop() && prepared.presolve?.infeasible != true)
        val problem = prepared.problem as BakedProblem
        val coordinates = requireNotNull(LocalSearchTrajectory.trajectoryCoordinates(invocation, prepared, stop))
        val hints = LocalSearchTrajectory.heuristicHints(invocation, prepared, stop)
        val sweep = hints?.let { LocalSearchTrajectory.getter(it, "getSweep") as DefinitionalSweep? }
        val gradient = hints?.let { LocalSearchTrajectory.getter(it, "getObjective") as IncrementalObjective? }
        val state = LocalSearchState(problem, Random(options["seed"]?.toLong() ?: 1L))
        state.invariants = sweep?.network(problem.numIntVars, problem.numBoolVars)
        val mapped = (0 until coordinates.intCount).map(coordinates::int).toSet()
        val coefficients = prepared.objective?.intCoefficients ?: longArrayOf()
        val unused = if (gradient != null) emptyList() else (0 until problem.numIntVars).filter {
            it !in mapped && state.projection.intOccurrences[it].isEmpty() &&
                state.invariants?.isDefinedInt(it) != true &&
                (coefficients.getOrNull(it) ?: 0L) == 0L
        }
        state.assumptions = Assumptions(ints = unused.associateWith { state.rootDomains[it].min })
        state.moveSink.setAssumptions(state.assumptions)
        state.shaping.javaClass.methods.single {
            it.name.substringBefore('$') == "setObjective" && it.parameterCount == 1
        }.invoke(state.shaping, gradient ?: prepared.objective)
        val view = TrajectoryView("prepared", state, coordinates, state.shaping.objective,
            prepared.reconstruct, { sweep?.sweep(state.assignment, state.rootDomains) })
        val decline = view.load(center)
        out.resolve("preparation.json").writeText(buildJsonObject {
            put("decline", decline)
            put("elapsedNs", System.nanoTime() - started)
            put("threadBytes", trajectoryThreadBytes() - allocated)
            put("presolve", options["presolve"] ?: "default")
            put("workingObjective", prepared.objective.toString())
            put("gradient", gradient.toString())
            put("definitions", sweep?.size ?: 0)
            put("unusedSlotPins", JsonArray(unused.map(::JsonPrimitive)))
            put("snapshot", view.snapshot())
            put("domains", JsonArray(state.rootDomains.map { JsonPrimitive(it.toString()) }))
            put("factors", buildJsonArray {
                for (factor in problem.factors) add(buildJsonObject {
                    put("kind", factor::class.simpleName)
                    put("rows", buildJsonArray {
                        for (row in factor.impliedLinearRows) add(buildJsonObject {
                            put("relation", row.relation.toString())
                            put("activator", row.activator)
                            put("strict", row.strict)
                            put("refs", JsonArray((0 until row.size).map { JsonPrimitive(row.ref(it)) }))
                            val constants = row.constants as? IntegralConstants
                            put("coefficients", constants?.let { exact -> JsonArray((0 until row.size).map {
                                JsonPrimitive(exact.exactCoeff(it).toString())
                            }) } ?: JsonNull)
                            put("bound", constants?.exactBound?.toString())
                        })
                    })
                })
            })
            put("sourceCheck", "native original lowered source; no independent witness/proof checker")
        }.toString())
        if (decline != null) return
        check(state.cost == 0L)
        val sources = linkedMapOf<String, MoveSource>("all-structured" to SatisfiedStructured.all())
        for (label in (options["sources"] ?: "objective,extended-structured,pair-swap").split(',')) {
            sources[label] = MoveSourceCatalog.configured(label).source
        }
        out.resolve("proposals.jsonl").bufferedWriter().use { writer ->
            for ((label, generator) in sources) {
                val sink = view.proposalSink()
                val generationStarted = System.nanoTime()
                val generationBytes = trajectoryThreadBytes()
                generator.generate(state, sink)
                val generationNs = System.nanoTime() - generationStarted
                val allocation = trajectoryThreadBytes() - generationBytes
                val moves = sink.list
                require(moves.size <= 4096)
                writer.appendLine(buildJsonObject {
                    put("source", label)
                    put("generationNs", generationNs)
                    put("generationThreadBytes", allocation)
                    put("candidates", buildJsonArray {
                        for (move in moves) {
                            check(!stop())
                            val inspection = state.inspectMove(move, state.shaping.objective)
                            val point = prepared.reconstruct(inspection.assignment)
                            add(buildJsonObject {
                                put("move", move.toString())
                                put("netDelta", inspection.netDelta)
                                put("objectiveDelta", inspection.objectiveDelta.toString())
                                put("source", trajectorySource(point))
                                put("sourceChanges", (0 until center.numIntVars).count {
                                    center.intValue(it) != point.intValue(it)
                                })
                                put("nativeSourceCheck", FiniteConflictSelection.checkSource(source, point, stop))
                                put("sourceObjective", objective.evaluateExact(point).toString())
                            })
                        }
                    })
                }.toString())
            }
        }
        if (options["kernel"] == "true") EqualityKernelReplay.capture(view, out.resolve("kernel.json"),
            objective::evaluateExact) {
            point, token -> FiniteConflictSelection.checkSource(source, point, token)
        }
        if (options["completion"] == "true") equalityRowCompletion(state, requireNotNull(prepared.objective),
            state.assignment.snapshot(), stop, out.resolve("completion.json"))
        if (options["owners"] == "true") {
            val controls = out.resolve("controls.jsonl")
            controls.writeText(buildJsonObject {
                put("center", trajectorySource(state.assignment.snapshot()))
                put("centerObjective", requireNotNull(prepared.objective).evaluate(state.assignment))
                put("effectiveInts", JsonArray((0 until problem.numIntVars).map(::JsonPrimitive)))
                put("selector", "whole-prepared-block")
            }.toString() + "\n")
            RepairOwnerReplay.replay(controls, problem, requireNotNull(prepared.objective), prepared, source,
                parsed.intVarNames.values.distinct().toIntArray(), out.resolve("owners.jsonl"),
                options["seed"]?.toLong() ?: 1L, allowance = 10_000L, fragmentAllowance = 2_000L)
        }
        out.resolve("runtime.json").writeText(buildJsonObject {
            put("java", System.getProperty("java.runtime.version"))
            put("os", System.getProperty("os.name"))
            put("arch", System.getProperty("os.arch"))
            put("totalElapsedNs", System.nanoTime() - started)
            put("totalThreadBytes", trajectoryThreadBytes() - allocated)
            put("independentCheck", false)
        }.toString())
    }
}
