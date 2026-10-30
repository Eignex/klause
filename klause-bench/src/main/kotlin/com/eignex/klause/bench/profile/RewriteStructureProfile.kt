package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.linearRows
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.presolve.AffinePivotOrder
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.objective.LinearObjective
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
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object RewriteStructureProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        val required = setOf("suite", "problem", "sha256", "presolve")
        require(options.keys.containsAll(required) &&
            (options.keys - required - setOf("orders", "source64", "sourceGzip64")).isEmpty())
        val arms = options["orders"]?.split(',')?.map { name ->
            val order = AffinePivotOrder.valueOf(name.uppercase())
            Triple(order.name.lowercase(), false, order)
        } ?: listOf(
            Triple("false", false, AffinePivotOrder.MARKOWITZ),
            Triple("true", true, AffinePivotOrder.MARKOWITZ),
        )
        require(arms.size in 1..2 && arms.distinct().size == arms.size)
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        val containing = Cancellation.after(120.seconds)
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        out.resolve("request.txt").writeText(args.joinToString("\n"))
        val sourceStarted = System.nanoTime()
        val bytes = trajectorySourceBytes(options.getValue("suite"), options.getValue("problem"),
            options.getValue("sha256"), options["source64"], options["sourceGzip64"])
        out.resolve("input.json").writeText(buildJsonObject {
            put("sourceSha256", options.getValue("sha256"))
            put("sourceBytes", bytes.size)
            put("sourceLoadNs", System.nanoTime() - sourceStarted)
            put("cancelled", containing())
        }.toString())
        require(bytes.size <= 1024 * 1024 && !containing())
        out.resolve("source.xml").writeBytes(bytes)
        for ((label, preserve, order) in arms) {
            val started = System.nanoTime()
            val stop = containing or Cancellation.after(30.seconds)
            val parsed = Xcsp3.parse(bytes.decodeToString(), recoveryCancellation = stop)
            require(parsed.problem.numIntVars + parsed.problem.numBoolVars <= 4096 &&
                parsed.problem.numFactors <= 4096)
            require(parsed.problem.factors.sumOf { it.structuralKeyWeight.toLong() } <= 65536 && !stop())
            val objective = parsed.objective?.toLinearObjective()
            val budget = PresolveBudget(5_000_000)
            val invocation = SourceSetup.prepare(SourceSetupRequest(
                if (parsed.definedVars.isEmpty()) SourceAttachment(parsed.problem, parsed.definitionHints)
                else SourceAttachment(parsed.problem, parsed.definedVars, emptyList(), parsed.definitionHints),
                if (objective == null) ExecutionOperation.SATISFY else ExecutionOperation.OPTIMIZE,
                FiniteEngine.MIXED, objective = objective, cancellation = stop,
            ))
            val plan = requireNotNull(invocation.preparedPlan)
            val prepared = FinitePipeline.prepare(FinitePipelineRequest(
                requireNotNull(plan.model), FiniteEngine.MIXED, objective,
                presolveConfig = PresolveConfig.parse(options.getValue("presolve"))
                    .withAffinePivotOrder(order)
                    .withEffort(preserveStructure = preserve, structuralImpactLimit = 65536),
                explicitPresolveConfig = true, cancellation = stop, solveCancellation = stop,
                presolveBudget = budget, preparedPlan = plan, randomSeed = 3,
            ))
            val preparationNs = System.nanoTime() - started
            val diagnostics = PresolveBudget(1_000_000)
            val diagnosticStop = diagnostics.orSpent(stop)
            writeModel(out.resolve("before-$label.json"), requireNotNull(plan.model), objective, diagnosticStop)
            writeModel(out.resolve("after-$label.json"), prepared.problem, prepared.objective, diagnosticStop)
            val hints = LocalSearchTrajectory.heuristicHints(invocation, prepared, diagnosticStop)
            val sweep = hints?.let { get(it, "Sweep") as DefinitionalSweep? }
            val network = sweep?.network(prepared.problem.numIntVars, prepared.problem.numBoolVars)
            var inputEdges = 0L
            var elementDefinitions = 0
            var maxDepth = 0
            val integerDepth = IntArray(prepared.problem.numIntVars)
            val booleanDepth = IntArray(prepared.problem.numBoolVars)
            if (network != null) for (k in 0 until network.size) {
                diagnosticStop.charge(1)
                check(!diagnosticStop())
                val node = network.node(k)
                if (node::class.simpleName == "ElementResultDefinition") elementDefinitions++
                var depth = 1
                for (variable in node.intInputs) {
                    diagnosticStop.charge(1)
                    check(!diagnosticStop())
                    inputEdges++
                    depth = maxOf(depth, integerDepth[variable] + 1)
                }
                for (variable in node.boolInputs) {
                    diagnosticStop.charge(1)
                    check(!diagnosticStop())
                    inputEdges++
                    depth = maxOf(depth, booleanDepth[variable] + 1)
                }
                if (node.outIsBool) booleanDepth[node.out] = depth else integerDepth[node.out] = depth
                maxDepth = maxOf(maxDepth, depth)
            }
            val report = buildJsonObject {
                put("sourceSha256", options.getValue("sha256"))
                put("revision", Reports.readGitSha())
                put("preserveStructure", preserve)
                put("affinePivotOrder", order.name)
                put("presolve", options.getValue("presolve"))
                put("declaredSemantics", parsed.semantics.declared.toString())
                put("effectiveSemantics", parsed.semantics.effective.toString())
                put("preparationNs", preparationNs)
                put("preparationWork", budget.spent())
                put("rootPropagationWork", budget.rootPropagationWork)
                put("preparedFactors", prepared.problem.numFactors)
                put("workingBooleans", prepared.problem.numBoolVars)
                put("workingIntegers", prepared.problem.numIntVars)
                put("maintainedDefinitions", network?.size ?: 0)
                put("maintainedElementDefinitions", elementDefinitions)
                put("maintainedInputEdges", inputEdges)
                put("maintainedMaxDepth", maxDepth)
                put("functionalObjectiveAvailable", hints?.let { get(it, "Objective") } != null)
                for (table in listOf(false, true)) {
                    val relaxation = MpsLpAdmission.rootRelaxation(prepared.problem, prepared.objective,
                        false, diagnosticStop, element = true, table = table)
                    val model = requireNotNull(get(relaxation, "Model"))
                    val matrix = get(model, "DoubleView") ?: requireNotNull(get(model, "Csc"))
                    val entries = requireNotNull(get(matrix, "RowIdx")) as IntArray
                    val prefix = if (table) "tableHull" else "core"
                    put(prefix + "Rows", get(model, "M") as Int)
                    put(prefix + "Columns", get(model, "N") as Int)
                    put(prefix + "Nonzeros", entries.size)
                }
                put("consumerDiagnosticWork", diagnostics.spent())
                put("containingNs", System.nanoTime() - started)
                put("cancelled", stop())
                put("diagnosticStopped", diagnosticStop())
                put("sourceVerdictClaim", false)
                put("lpPolicy", "forced core and Table hull projections; ordinary admission is separate")
                put("architecture", System.getProperty("os.arch"))
                put("runtime", System.getProperty("java.runtime.version"))
            }
            out.resolve("view-$label.json").writeText(report.toString())
            out.resolve("invocations-$label.txt").writeText(budget.invocations.joinToString("\n") {
                "${it.lane} ${it.pass} ${it.outcome} ${it.work} ${it.structuralImpact}"
            })
            println(report)
            check(!diagnosticStop())
        }
    }

    private fun writeModel(file: File, problem: Problem, objective: LinearObjective?, stop: Cancellation) {
        val snapshot = buildJsonObject {
            put("objective", objective.toString())
            put("booleans", problem.numBoolVars)
            put("integers", problem.numIntVars)
            put("domains", JsonArray((0 until problem.numIntVars).map { variable ->
                stop.charge(1)
                check(!stop())
                JsonPrimitive(problem.intDomainOrNull(variable).toString())
            }))
            put("factors", buildJsonArray {
                for (factor in problem.factors) {
                    stop.charge(1L + factor.intVars.size + factor.boolVars.size)
                    check(!stop())
                    add(buildJsonObject {
                        put("kind", factor::class.simpleName)
                        put("integers", JsonArray(factor.intVars.map { JsonPrimitive(it) }))
                        put("booleans", JsonArray(factor.boolVars.map { JsonPrimitive(it) }))
                        put("rows", buildJsonArray {
                            for (row in factor.linearRows) {
                                stop.charge(1L + 2L * row.size)
                                check(!stop())
                                add(buildJsonObject {
                                    put("relation", row.relation.name)
                                    put("activator", row.activator)
                                    put("constants", row.constants::class.simpleName)
                                    put("refs", JsonArray((0 until row.size).map { JsonPrimitive(row.ref(it)) }))
                                    if (row.constants is IntegerConstants) {
                                        put("bound", row.bound)
                                        put("coefficients", JsonArray((0 until row.size).map {
                                            JsonPrimitive(row.coeff(it))
                                        }))
                                    }
                                })
                            }
                        })
                    })
                }
            })
        }
        check(!stop())
        file.writeText(snapshot.toString())
    }

    private fun get(owner: Any, property: String): Any? =
        owner.javaClass.getMethod("get$property").invoke(owner)
}
