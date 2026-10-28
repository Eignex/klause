package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.profile.NumericalFrontierSnapshot.Companion.field
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.sourceProofContext
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.ExecutionOption
import com.eignex.klause.solver.pipeline.ExecutionOptions
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceExecutionRequest
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.util.Cancellation
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object RealBooleanCutProfile {
    private const val PREFIX = "com.eignex.klause.lp.optimization."
    private val x = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val y = SourceCoordinate(SourceCoordinate.Kind.Real, 1)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty() || args.toList() == listOf("preparationOnly=true"))
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        check(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        out.resolve("comparisons.jsonl").bufferedWriter().use { writer ->
            for (refutation in listOf(false, true)) {
                val name = if (refutation) "coupled-refutation" else "feasible"
                val source = source(refutation, out)
                if (args.isNotEmpty()) continue
                val system = buildJsonObject {
                    put("scope", NumericalFrontierSnapshot.scope(source.scope))
                    put("system", NumericalFrontierSnapshot.system(source))
                }.toString().toByteArray()
                out.resolve("$name-source.json").writeBytes(system)
                inspect(source, out.resolve("$name-consequences.json"))
                repeat(4) { round ->
                    val arms = listOf("partial", "composed", "separate-hulls", "exhaustive")
                        .let { if (round % 2 == 0) it else it.reversed() }
                    for (arm in arms) {
                        val context = if (arm == "separate-hulls") hulls(source) else source
                        val conditions = if (arm == "exhaustive") listOf(false, true).map {
                            SourceCondition(bools = mapOf(0 to it))
                        } else listOf(SourceCondition())
                        val allocated = bean.getThreadAllocatedBytes(thread)
                        val start = System.nanoTime()
                        val replays = conditions.map { condition ->
                            replay(context.atScope(context.scope.copy(condition = condition)), arm == "composed",
                                conditions.size)
                        }
                        val elapsed = System.nanoTime() - start
                        val bytes = bean.getThreadAllocatedBytes(thread) - allocated
                        val proofs = replays.map { replay ->
                            field(replay, "query")?.let { field(it, "refutation") as SourceEvidence? }
                        }
                        val evidence = if (arm != "exhaustive") proofs.single() else
                            if (proofs.any { it == null }) null else SourceEvidence(source.scope, SourceClaim.Refutation,
                                SourceDerivation.BooleanSplit(0, proofs[0]!!.derivation, proofs[1]!!.derivation))
                        val verdict = evidence?.let { SourceEvidenceVerifier(context.withLinearAuthority()).verify(it) }
                        check(evidence == null || verdict == SourceEvidenceCheck.Checked)
                        val record = buildJsonObject {
                            put("source", name)
                            put("sourceSha256", sha256(system))
                            put("revision", Reports.readGitSha())
                            put("arm", arm)
                            put("round", round)
                            put("warmup", round == 0)
                            put("os", System.getProperty("os.name"))
                            put("arch", System.getProperty("os.arch"))
                            put("java", System.getProperty("java.runtime.version"))
                            put("instrumented", true)
                            put("independentProofCheck", false)
                            put("lifecycleNs", elapsed)
                            put("threadAllocatedBytes", bytes)
                            put("sourceCheck", verdict?.toString())
                            put("consultations", replays.size)
                            for (metric in listOf("operations", "work", "allocation")) {
                                put(metric, replays.sumOf { (field(it, metric) as Number).toLong() })
                            }
                            put("stopped", replays.any { field(it, "stopped") == true })
                        }
                        writer.appendLine(record.toString())
                        writer.flush()
                        println(record)
                    }
                }
            }
        }
    }

    private fun source(refutation: Boolean, out: File): SourceProofContext {
        val model = Problem(1, 0, emptyArray(), buildList {
            for (id in 0..1) add(ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(id),
                doubleArrayOf(1.0), LinearOp.LE, 0.0))
            for (sign in listOf(-1.0, 1.0)) add(Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
                doubleArrayOf(sign, sign), LinearOp.LE, 0.25))
            if (refutation) add(Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
                doubleArrayOf(-1.0, 1.0), LinearOp.LE, -0.5))
        }, numRealVars = 2, realLower = doubleArrayOf(-1.0, -1.0), realUpper = doubleArrayOf(1.0, 1.0))
        val objective = LinearObjective(realCoefficients = doubleArrayOf(-1.0, 1.0))
        val scope = SourceScope(SourceRevision("coupled-real-$refutation", "klause-ir-exact-v1"),
            objective = SourceObjective("difference", ObjectiveSense.MINIMIZE))
        val stop = Cancellation.after(3.seconds)
        val started = System.nanoTime()
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(model), ExecutionOperation.OPTIMIZE,
            FiniteEngine.BACKTRACK, objective, cancellation = stop))
        val ordinary = SourceSetup.execute(SourceExecutionRequest(invocation,
            ExecutionOptions(ExecutionOption.dryRunPresolve(true)), timeout = stop,
            presolveBudget = PresolveBudget(100_000L)))
        val preparation = (ordinary as? SourceExecutionResult.Prepared)?.result
        val numeric = preparation?.takeUnless { it.infeasible }?.model?.sourceProofContext(scope, objective)
        val candidate = numeric?.let { prepare(it) { _, _ -> } }
        val receipt = buildJsonObject {
            put("revision", Reports.readGitSha())
            put("resultKind", ordinary::class.simpleName)
            put("defaultPresolve", true)
            put("elapsedNs", System.nanoTime() - started)
            put("stopped", stop())
            put("infeasible", preparation?.infeasible)
            put("factors", preparation?.model?.factors?.size)
            put("booleans", preparation?.model?.numBoolVars)
            put("reals", preparation?.model?.numRealVars)
            put("passes", preparation?.stats?.passes?.joinToString(","))
            put("capturedPremises", numeric?.premises?.size)
            put("guardedPremises", numeric?.premises?.values?.count { it.guards.isNotEmpty() })
            put("commonRows", candidate?.let { (field(it, "consequences") as List<*>).size })
            put("scanComplete", candidate?.let { field(it, "complete") as Boolean })
            numeric?.let { put("system", NumericalFrontierSnapshot.system(it)) }
        }
        out.resolve("ordinary-preparation-$refutation.json").writeText(receipt.toString())
        println(receipt)
        val prepared = requireNotNull(model.prepareExecutionPlan(objective).model)
        return requireNotNull(prepared.sourceProofContext(scope, objective))
    }

    private fun replay(source: SourceProofContext, composed: Boolean, alternatives: Int): Any {
        val stop = Cancellation.after(3.seconds)
        val captureType = Class.forName(PREFIX + "LinearAuthorityCapture")
        val capture = captureType.declaredConstructors.single { it.parameterCount == 4 }
            .newInstance(1, 1_000_000L, 16_777_216L, stop)
        val result = Class.forName(PREFIX + "LinearAuthorityResult").getDeclaredConstructor().newInstance()
        captureType.declaredMethods.single { it.name == "record" }.invoke(capture, source, null, null, null, result, null)
        val query = (field(capture, "retained") as List<*>).single()
        val type = Class.forName(PREFIX + "LinearAuthorityReplay")
        return type.declaredMethods.single { it.name == "activeRows" }
            .invoke(type.getField("INSTANCE").get(null), query, 20_000_000L / alternatives,
                67_108_864L / alternatives, stop, composed)
    }

    private fun inspect(source: SourceProofContext, file: File) {
        var work = 0L
        var allocation = 0L
        val result = prepare(source) { steps, bytes -> work += steps; allocation += bytes }
        @Suppress("UNCHECKED_CAST")
        val consequences = field(result, "consequences") as List<SourceEvidence>
        val rounding = Class.forName(PREFIX + "LinearAuthorityCuts").getField("Companion").get(null)
        val installed = rounding.javaClass.declaredMethods.single { it.name == "prepare" }
            .invoke(rounding, source, Cancellation.after(3.seconds), { _: Long -> })
        val quarter = BigFraction.ofLong(4).reciprocal()
        val common = consequences.single { it.claim == SourceClaim.Consequence(
            SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.MINUS_ONE), quarter)) }
        val bound = SourceEvidence(source.scope, SourceClaim.Bound(quarter.negated()),
            SourceDerivation.Combination(listOf(SourceDerivation.Combination.Term(BigFraction.ONE,
                SourceDerivation.Import(common)))))
        check(SourceEvidenceVerifier(source).verify(bound) == SourceEvidenceCheck.Checked)
        file.writeText(buildJsonObject {
            put("complete", field(result, "complete") as Boolean)
            put("reason", field(result, "reason") as String)
            put("preparationWork", work)
            put("modeledPreparationAllocation", allocation)
            put("installedRoundingCuts", (field(requireNotNull(installed), "rows") as List<*>).size)
            put("checkedLowerBound", quarter.negated().toString())
            put("independentProofCheck", false)
            put("rows", buildJsonArray { consequences.forEach { evidence ->
                val row = (evidence.claim as SourceClaim.Consequence).inequality
                add(buildJsonObject {
                    put("rhs", row.rhs.toString())
                    put("strict", row.strict)
                    put("coefficients", buildJsonObject { row.coefficients.forEach { (id, value) ->
                        put("${id.kind}/${id.id}", value.toString())
                    } })
                    put("sourceCheck", SourceEvidenceVerifier(source).verify(evidence).toString())
                    put("proof", proof(evidence.derivation))
                })
            } })
        }.toString())
    }

    private fun prepare(source: SourceProofContext, cost: (Long, Long) -> Unit): Any {
        val type = Class.forName(PREFIX + "LinearBooleanCutPreparation")
        val limits = Class.forName(PREFIX + "LinearBooleanCutLimits").getDeclaredConstructor().newInstance()
        return type.declaredMethods.single { it.name == "prepare" }
            .invoke(type.getField("INSTANCE").get(null), source, Cancellation.after(3.seconds), cost, limits)
    }

    private fun proof(value: SourceDerivation): JsonObject = buildJsonObject {
        put("kind", value::class.simpleName)
        when (value) {
            is SourceDerivation.BooleanSplit -> {
                put("boolean", value.id)
                put("whenFalse", proof(value.whenFalse))
                put("whenTrue", proof(value.whenTrue))
            }
            is SourceDerivation.Combination -> put("terms", buildJsonArray { value.terms.forEach {
                add(buildJsonObject { put("weight", it.weight.toString()); put("row", proof(it.derivation)) })
            } })
            is SourceDerivation.Premise -> put("id", value.id)
            else -> error("unexpected common-row proof")
        }
    }

    private fun hulls(source: SourceProofContext): SourceProofContext {
        val b = SourceCoordinate(SourceCoordinate.Kind.Real, 2)
        val rows = source.premises.filterValues { it.guards.isEmpty() }.toMutableMap()
        rows["hull/b/lower"] = SourcePremise(SourceInequality(mapOf(b to BigFraction.MINUS_ONE), BigFraction.ZERO))
        rows["hull/b/upper"] = SourcePremise(SourceInequality(mapOf(b to BigFraction.ONE), BigFraction.ONE))
        for (id in listOf(x, y)) {
            rows["hull/$id/upper"] = SourcePremise(SourceInequality(
                mapOf(id to BigFraction.ONE, b to BigFraction.ONE), BigFraction.ONE))
            rows["hull/$id/lower"] = SourcePremise(SourceInequality(
                mapOf(id to BigFraction.MINUS_ONE, b to BigFraction.MINUS_ONE), BigFraction.ZERO))
        }
        val context = SourceProofContext(source.scope.copy(revision = source.scope.revision.copy(
            identity = source.scope.revision.identity + "-hulls")), source.coordinates + b, rows, source.objective)
        val values = listOf(BigFraction.ofLong(2) * BigFraction.ofLong(5).reciprocal(),
            BigFraction.ofLong(-2) * BigFraction.ofLong(5).reciprocal(), BigFraction.ofLong(2).reciprocal())
        val point = Sample(booleanArrayOf(false), longArrayOf(), values.map { it.toDouble() }.toDoubleArray(),
            exactReals = values)
        check(SourceEvidenceVerifier(context.withLinearAuthority()).verify(SourceEvidence(context.scope,
            SourceClaim.Witness(point), SourceDerivation.SourceWitness)) == SourceEvidenceCheck.Checked)
        return context
    }
}
