package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object RewriteStructureProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys == setOf("suite", "problem", "sha256", "presolve"))
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        val containing = Cancellation.after(120.seconds)
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        out.resolve("request.txt").writeText(args.joinToString("\n"))
        val bytes = trajectorySourceBytes(options.getValue("suite"), options.getValue("problem"),
            options.getValue("sha256"), null)
        require(bytes.size <= 1024 * 1024 && !containing())
        out.resolve("source.xml").writeBytes(bytes)
        for (preserve in listOf(false, true)) {
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
                presolveConfig = PresolveConfig.parse(options.getValue("presolve")).withEffort(
                    preserveStructure = preserve, structuralImpactLimit = 65536,
                ),
                explicitPresolveConfig = true, cancellation = stop, solveCancellation = stop,
                presolveBudget = budget, preparedPlan = plan, randomSeed = 3,
            ))
            val preparationNs = System.nanoTime() - started
            val diagnostics = PresolveBudget(1_000_000)
            val diagnosticStop = diagnostics.orSpent(stop)
            val hints = LocalSearchTrajectory.heuristicHints(invocation, prepared, diagnosticStop)
            val sweep = hints?.let { get(it, "Sweep") as DefinitionalSweep? }
            val network = sweep?.network(prepared.problem.numIntVars, prepared.problem.numBoolVars)
            var inputEdges = 0L
            var maxDepth = 0
            val integerDepth = IntArray(prepared.problem.numIntVars)
            val booleanDepth = IntArray(prepared.problem.numBoolVars)
            if (network != null) for (k in 0 until network.size) {
                diagnosticStop.charge(1)
                check(!diagnosticStop())
                val node = network.node(k)
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
                put("maintainedInputEdges", inputEdges)
                put("maintainedMaxDepth", maxDepth)
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
            out.resolve("view-$preserve.json").writeText(report.toString())
            out.resolve("invocations-$preserve.txt").writeText(budget.invocations.joinToString("\n") {
                "${it.lane} ${it.pass} ${it.outcome} ${it.work} ${it.structuralImpact}"
            })
            println(report)
            check(!diagnosticStop())
        }
    }

    private fun get(owner: Any, property: String): Any? =
        owner.javaClass.getMethod("get$property").invoke(owner)
}
