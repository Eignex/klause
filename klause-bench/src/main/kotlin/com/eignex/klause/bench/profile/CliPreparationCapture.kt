package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.bench.metric.SolveEvidence
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.baked
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.pipeline.*
import com.eignex.klause.solver.result.PresolveInvocationOutcome
import com.eignex.klause.solver.result.PresolvePreparationStage
import com.eignex.klause.util.ExecutionControl
import kotlinx.serialization.json.*
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.time.Duration.Companion.seconds

internal object CliPreparationCapture {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2)
        val request = Json.parseToJsonElement(File(args[0]).readText()).jsonObject
        val output = File(args[1]).apply { mkdirs() }
        val plain = File(request.getValue("plainPath").jsonPrimitive.content)
        require(plain.length() <= 64L * 1024 * 1024)
        require(SolveEvidence.fileHashes(mapOf("model" to plain)).getValue("model") ==
            request.getValue("plainSha256").jsonPrimitive.content)
        val cliArgs = request.getValue("cliArgs").jsonArray.map { it.jsonPrimitive.content }.toTypedArray()
        val reflection = CliPreparationReflection()
        val common = reflection.common
        val commonType = reflection.commonType
        val specs = reflection.top("CliModeKt", "commonFlagSpecs", commonType).invoke(null, common)
        val positional: (String) -> Unit = { path ->
            require(reflection.get(common, "InputPath") == null)
            reflection.set("InputPath", String::class.java, path)
        }
        reflection.top("CliModeKt", "parseArgs", Array<String>::class.java, List::class.java, Function1::class.java)
            .invoke(null, cliArgs, specs, positional)
        require(File(reflection.get(common, "InputPath") as String).canonicalFile == plain.canonicalFile)
        val installed = reflection.top("PlatformKt", "installCliConfig").invoke(null) as KlauseConfig
        reflection.set("Config", KlauseConfig::class.java, installed)
        val now = reflection.top("PlatformKt", "nowMillis")
        val budgetMs = reflection.get(common, "TimeLimitMs") as Long
        reflection.set("DeadlineAtMs", Long::class.javaObjectType, (now.invoke(null) as Long) + budgetMs)
        val debug: () -> Any = { reflection.top("PlatformKt", "platformDebugStats").invoke(null) }
        reflection.top("CliDebugStatsKt", "startDebugStatistics", commonType,
            Boolean::class.javaPrimitiveType!!, Function0::class.java).invoke(null, common, true, debug)
        reflection.top("CliInvocationStatsKt", "resetInvocationStatistics", commonType).invoke(null, common)
        val mode = reflection.type("MpsMode").getField("INSTANCE").get(null)
        val session = reflection.method(mode.javaClass, "newSession").invoke(mode)
        val loadStart = now.invoke(null) as Long
        val solvable = reflection.method(session.javaClass, "load", String::class.java, commonType)
            .invoke(session, plain.path, common)
        reflection.set("LoadElapsedMs", Long::class.javaObjectType, (now.invoke(null) as Long) - loadStart)
        @Suppress("UNCHECKED_CAST")
        val deadline = reflection.core("deadlineCancellation", commonType).invoke(reflection.core, common)
            as Pair<Long?, ExecutionControl>
        val invocation = reflection.get(solvable, "SourceInvocation") as PreparedSourceInvocation?
        val base = buildJsonObject {
            put("revision", Reports.readGitSha())
            put("scope", "installed-cli-code-path-preparation-diagnostic")
            put("searchExecuted", false)
            put("sourceVerdictClaim", false)
            put("productionApplicabilityEstablished", false)
            put("suite", request.getValue("suite"))
            put("problem", request.getValue("problem"))
            put("sourceSha256", request.getValue("plainSha256"))
            put("cliArgs", request.getValue("cliArgs"))
            put("solverBudgetMs", budgetMs)
            put("javaVersion", System.getProperty("java.version"))
            put("jvmOptions", JsonArray(ManagementFactory.getRuntimeMXBean().inputArguments.map(::JsonPrimitive)))
            put("route", invocation?.route?.javaClass?.simpleName)
        }
        if (invocation == null || invocation.route !is SourceProblemRoute.Finite) {
            write(output, "preparation.json", buildJsonObject {
                base.forEach { (key, value) -> put(key, value) }
                put("preparationReady", false)
                put("containingStopAtReturn", deadline.second())
                put("shapeInspectionComplete", false)
            })
            return
        }
        val engine = reflection.top("CliModeKt", "resolvedEngine", commonType).invoke(null, common) as FiniteEngine
        @Suppress("UNCHECKED_CAST")
        val allowance = reflection.core("presolveAllowance\$klause_klause_cli", commonType, ExecutionControl::class.java)
            .invoke(reflection.core, common, deadline.second) as Pair<ExecutionControl, PresolveBudget?>
        @Suppress("UNCHECKED_CAST")
        val parameters = reflection.get(common, "EngineParams") as List<String>
        require(parameters.count { it == "dry-run-presolve=true" } == 1)
        val emphasis = reflection.get(common, "Presolve") as String?
        val config = emphasis?.let(PresolveConfig::parse) ?: installed.presolveConfig()
        val policy = reflection.core("executionPolicy", commonType, Boolean::class.javaPrimitiveType!!)
            .invoke(reflection.core, common, emphasis != null) as SourceExecutionPolicy
        val factory = reflection.core("finiteRequest", FiniteSolveShape::class.java, commonType,
            FiniteEngine::class.java, Int::class.javaPrimitiveType!!, PresolveConfig::class.java,
            Boolean::class.javaPrimitiveType!!, ExecutionControl::class.java, PresolveBudget::class.java,
            ExecutionControl::class.java, Long::class.javaObjectType, NodeBudget::class.java,
            SourceExecutionPolicy::class.java, Boolean::class.javaPrimitiveType!!, Function1::class.java)
        val sensitive = !(reflection.get(solvable, "Optimize") as Boolean) &&
            (reflection.get(common, "AllSolutions") as Boolean ||
                ((reflection.get(common, "SolutionCap") as Long?) ?: 1L) > 1L)
        val controls = SourceExecutionRequest(invocation, policy, ExecutionOptions.parse(parameters),
            finiteRequest = { shape ->
                factory.invoke(reflection.core, shape, common, engine, 1, config, sensitive,
                    allowance.first, allowance.second, deadline.second, deadline.first,
                    reflection.internalGet(common, "NodeBudget"), policy,
                    reflection.get(solvable, "NumericalCompletion"), reflection.get(solvable, "ToleranceCheck"))
                    as FiniteSolveRequest
            }, cores = 1, randomSeed = reflection.get(common, "RandomSeed") as Long?,
            nodeBudget = reflection.internalGet(common, "NodeBudget") as NodeBudget?,
            timeout = deadline.second, presolveConfig = config,
            presolveCancellation = allowance.first, presolveBudget = allowance.second)
        reflection.method(commonType, "setNodeBudget\$klause_klause_cli", NodeBudget::class.java)
            .invoke(common, controls.nodeBudget)
        require(controls.prepareOnly)
        val result = SourceSetup.execute(controls) as SourceExecutionResult.Finite
        require(result.invocation === invocation && result.result.outcome == FiniteSolveOutcome.PreparedOnly)
        val preparation = result.result.preparation
        val stopped = deadline.second()
        val optionalTimeStopped = (reflection.internalGet(common, "PresolveTimeCancellation") as ExecutionControl)()
        val plan = libraryGet(preparation, "ExecutionPlan") as PreparedSourcePlan?
        val mapping = libraryGet(preparation, "SourceMapping")
        val problem = preparation.problem
        val shape = invocation.finiteShape()
        val projections = (problem as? BakedProblem)?.let { libraryGet(it, "FactorProjections") as PreparedFactorProjections? }
        val stages = preparation.presolve?.effort?.preparationStages.orEmpty()
        val required = setOf(PresolvePreparationStage.ENGINE_BAKE, PresolvePreparationStage.SOURCE_ENCODER)
        val checks = linkedMapOf(
            "baked" to (problem is BakedProblem),
            "notStopped" to !stopped,
            "notRefuted" to (preparation.presolve?.infeasible != true &&
                (problem as? BakedProblem)?.baked !is PropagationResult.Unsat),
            "planPresent" to (plan != null),
            "noPlanDecline" to (plan != null && plan.decline == null),
            "modelViewBound" to (plan?.model?.let { sameView(it, problem) } == true),
            "originalSourceBound" to (plan?.originalSource === invocation.source.originalSource),
            "sourceModelBound" to (plan != null && libraryGet(plan, "SourceModel") ===
                libraryGet(shape.preparedPlan, "SourceModel")),
            "planProjectionsBound" to (plan?.factorProjections?.problem === plan?.model && plan?.model != null),
            "engineProjectionsBound" to (projections?.problem === problem &&
                projections.providers === plan?.factorProjections?.providers),
            "mappingBound" to (mapping != null && reflection.get(mapping, "Source") === shape.problem &&
                sameView(reflection.get(mapping, "Target") as Problem, problem)),
            "runnable" to (plan?.capabilities?.admission(engine, invocation.operation)?.runnable == true),
            "requiredStagesComplete" to (stages.any { it.stage == PresolvePreparationStage.ENGINE_BAKE } &&
                stages.none { it.stage in required && it.outcome == PresolveInvocationOutcome.INTERRUPTED }),
        )
        val ready = checks.values.all { it }
        val prepared = buildJsonObject {
            base.forEach { (key, value) -> put(key, value) }
            put("preparationReady", ready)
            put("containingStopAtReturn", stopped)
            put("optionalTimeStoppedAtReturn", optionalTimeStopped)
            put("optionalWorkAllowance", allowance.second?.allowance)
            put("optionalWorkSpent", allowance.second?.spent())
            put("optionalWorkExhausted", allowance.second?.remaining() == 0L)
            put("readinessChecks", buildJsonObject { checks.forEach { (key, value) -> put(key, value) } })
            put("compiledReals", problem.numRealVars)
            put("preparedPlanDecline", plan?.decline?.name)
            put("shapeInspectionComplete", false)
            put("preparationStages", buildJsonArray {
                stages.forEach { stage -> add(buildJsonObject {
                    put("stage", stage.stage.name)
                    put("outcome", stage.outcome.name)
                    put("work", stage.work)
                }) }
            })
        }
        write(output, "preparation.json", prepared)
        if (!ready) return
        val shapeStop = ExecutionControl.after(20.seconds)
        val inspected = buildJsonObject {
            prepared.forEach { (key, value) -> put(key, value) }
            put("shapeInspectionDeadlineSeconds", 20)
            put("shapeInspectionScope", "separate-after-readiness-diagnostic")
            put("defaultAdmission", MpsLpAdmission.admission(problem, com.eignex.klause.lp.bounding.LpConfig.AUTO))
            put("full", MpsLpAdmission.shape(problem, preparation.objective, false, shapeStop))
            put("shapeInspectionComplete", !shapeStop())
        }
        write(output, "admission.json", inspected)
    }

    internal fun sameView(first: Problem, second: Problem): Boolean =
        first.numBoolVars == second.numBoolVars && first.numIntVars == second.numIntVars &&
            first.numRealVars == second.numRealVars && first.numFactors == second.numFactors &&
            first.factors.indices.all { first.factors[it] === second.factors[it] }

    private fun libraryGet(owner: Any, name: String): Any? =
        owner.javaClass.getMethod("get$name\$com_eignex_klause").invoke(owner)

    private fun write(output: File, name: String, value: JsonObject) {
        output.resolve(name).writeText("$value\n")
    }
}
