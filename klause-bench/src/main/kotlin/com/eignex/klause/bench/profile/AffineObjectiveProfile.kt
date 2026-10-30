package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toLinearSourceProblem
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.linearRows
import com.eignex.klause.presolve.AffinePivotOrder
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.Cancellation
import java.io.File
import java.util.Base64
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object AffineObjectiveProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size == 1 && args[0].startsWith("scan=")) {
            scan(args[0].substringAfter('='))
            return
        }
        require(args.size == 1 && args[0].startsWith("batch64="))
        val requests = Json.decodeFromString<List<List<String>>>(
            Base64.getDecoder().decode(args[0].substringAfter('=')).decodeToString(),
        )
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        for ((index, request) in requests.withIndex()) {
            val options = request.associate { it.substringBefore('=') to it.substringAfter('=') }
            val required = setOf("suite", "problem", "sha256")
            require(options.keys.containsAll(required) &&
                (options.keys - required - setOf("presolve", "pivotOrder")).isEmpty())
            val order = options["pivotOrder"]?.let { AffinePivotOrder.valueOf(it.uppercase()) }
                ?: AffinePivotOrder.MARKOWITZ
            val presolve = (options["presolve"]?.let { PresolveConfig.parse(it) } ?: PresolveConfig.DEFAULT)
                .withAffinePivotOrder(order)
            val source = Catalog.uncapped(options.getValue("suite")).problems
                .single { it.name == options.getValue("problem") }
            val bytes = CorpusFiles.open(CorpusFetcher.resolve(source.source)).use { it.readBytes() }
            require(sha256(bytes) == options.getValue("sha256"))
            val directory = out.resolve("$index").apply { mkdirs() }
            directory.resolve("source.mps").writeBytes(bytes)
            for (exact in listOf(false, true)) {
                val original = requireNotNull(Mps.parse(bytes.decodeToString()).toLinearSourceProblem(
                    if (exact) SemanticOverrides(feasibilityTolerance = SourceTolerance()) else SemanticOverrides(),
                ))
                val invocation = SourceSetup.prepare(SourceSetupRequest(
                    SourceAttachment(original, ProblemSettings()), ExecutionOperation.OPTIMIZE, FiniteEngine.BACKTRACK,
                    maximize = original.objective?.sense == ObjectiveSense.MAXIMIZE,
                ))
                val plan = requireNotNull(invocation.preparedPlan)
                val model = requireNotNull(plan.model)
                val objective = requireNotNull(MpsLpAdmission.preparedObjective(plan))
                directory.resolve("canonical-$exact.json").writeText(snapshot(model, objective).toString())
                val candidates = model.factors.flatMapIndexed { factor, value ->
                    value.linearRows.filter { row ->
                        row.isLongUnconditional && row.isIntegerOnly && row.relation == LinearOp.EQ &&
                            row.size == 2 && row.coeff(0) in -1L..1L && row.coeff(0) != 0L &&
                            row.coeff(1) == -row.coeff(0)
                    }.map { row -> buildJsonObject {
                        put("factor", factor)
                        put("row", row(row))
                        put("objective", objective.toString())
                        put("bothObjectiveColumns", (0 until row.size).all {
                            objective.intCoefficients.getOrElse(Term.intVar(row.ref(it))) { 0L } != 0L
                        })
                    } }
                }
                directory.resolve("translation-rows-$exact.json").writeText(JsonArray(candidates).toString())
                for (rewrite in listOf(false, true)) {
                    val stop = Cancellation.after(8.seconds)
                    val budget = PresolveBudget(550_000_000L)
                    val started = System.nanoTime()
                    val prepared = FinitePipeline.prepare(FinitePipelineRequest(
                        model, FiniteEngine.BACKTRACK, objective,
                        presolveConfig = presolve,
                        explicitPresolveConfig = options.keys.any { it == "presolve" || it == "pivotOrder" },
                        cancellation = stop, solveCancellation = stop, presolveBudget = budget,
                        preparedPlan = plan, transformObjective = rewrite,
                    ))
                    val elapsed = System.nanoTime() - started
                    directory.resolve("residual-$exact-$rewrite.json")
                        .writeText(snapshot(prepared.problem, prepared.objective ?: objective).toString())
                    val report = buildJsonObject {
                        put("revision", Reports.readGitSha())
                        put("sourceSha256", sha256(bytes))
                        put("problem", source.name)
                        put("exact", exact)
                        put("rewrite", rewrite)
                        put("presolve", options["presolve"] ?: "default")
                        put("affinePivotOrder", order.name)
                        put("preparationNs", elapsed)
                        put("preparationWork", budget.spent())
                        put("cancelled", stop())
                        put("infeasible", prepared.presolve?.infeasible)
                        put("objectiveChanged", prepared.objective != objective)
                        put("searchExecuted", false)
                        put("sourceVerdictClaim", false)
                    }
                    directory.resolve("report-$exact-$rewrite.json").writeText(report.toString())
                    println(report)
                }
            }
        }
    }

    private fun scan(suite: String) {
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        val stop = Cancellation.after(120.seconds)
        out.resolve("scan.jsonl").bufferedWriter().use { output ->
            for ((index, reference) in Catalog.uncapped(suite).problems.withIndex()) {
                if (stop()) break
                val bytes = CorpusFiles.open(CorpusFetcher.resolve(reference.source)).use { it.readBytes() }
                val source = Mps.parse(bytes.decodeToString()).toLinearSourceProblem(
                    SemanticOverrides(feasibilityTolerance = SourceTolerance()), cancellation = stop,
                ) ?: break
                val variables = source.variables
                val objective = source.objective?.terms.orEmpty().associate { it.column to it.coefficient }
                val translations = source.rows.mapIndexedNotNull { index, row ->
                    val terms = row.terms
                    if (row.indicator != null || row.lower == null || row.lower != row.upper || terms.size != 2 ||
                        terms.any { !variables[it.column].integer }
                    ) return@mapIndexedNotNull null
                    val a = BigFraction.of(terms[0].coefficient.numerator, terms[0].coefficient.denominator)
                    val b = BigFraction.of(terms[1].coefficient.numerator, terms[1].coefficient.denominator)
                    if (a == BigFraction.ZERO || a != b.negated()) return@mapIndexedNotNull null
                    val rhs = requireNotNull(row.lower)
                    val offset = BigFraction.of(rhs.numerator, rhs.denominator) * a.reciprocal()
                    if (offset.den != BIG_ONE) return@mapIndexedNotNull null
                    buildJsonObject {
                        put("row", index)
                        put("offset", offset.toString())
                        put("columns", JsonArray(terms.map { JsonPrimitive(it.column) }))
                        put("names", JsonArray(terms.map { JsonPrimitive(variables[it.column].name) }))
                        put("weights", JsonArray(terms.map {
                            val weight = objective[it.column]
                            JsonPrimitive(weight?.let { value ->
                                BigFraction.of(value.numerator, value.denominator).toString()
                            } ?: "0")
                        }))
                        put("bothObjectiveColumns", terms.all {
                            objective[it.column]?.numerator?.let { value -> value != BIG_ZERO } == true
                        })
                    }
                }
                if (translations.isNotEmpty()) out.resolve("$index.mps").writeBytes(bytes)
                val report = buildJsonObject {
                    put("revision", Reports.readGitSha())
                    put("suite", suite)
                    put("index", index)
                    put("problem", reference.name)
                    put("sourceSha256", sha256(bytes))
                    put("translationRows", JsonArray(translations))
                    put("searchExecuted", false)
                    put("sourceVerdictClaim", false)
                }
                output.appendLine(report.toString())
                output.flush()
                println(report)
            }
        }
        out.resolve("interrupted.txt").writeText(stop().toString())
    }

    private fun snapshot(model: Problem, objective: LinearObjective) = buildJsonObject {
        put("objective", objective.toString())
        put("booleans", model.numBoolVars)
        put("integers", model.numIntVars)
        put("reals", model.numRealVars)
        put("realLower", JsonArray(model.realLower.map { JsonPrimitive(it.toString()) }))
        put("realUpper", JsonArray(model.realUpper.map { JsonPrimitive(it.toString()) }))
        put("semantics", buildJsonObject {
            put("declared", model.semantics.declared.toString())
            put("effective", model.semantics.effective.toString())
        })
        put("integerBounds", buildJsonArray {
            for (variable in 0 until model.numIntVars) add(buildJsonObject {
                put("lower", model.intBounds.lowerAsBigInteger(variable)?.toString())
                put("upper", model.intBounds.upperAsBigInteger(variable)?.toString())
            })
        })
        put("domains", buildJsonArray {
            for (variable in 0 until model.numIntVars) add(JsonPrimitive(model.intDomainOrNull(variable).toString()))
        })
        if (model is BakedProblem) put("rootDomains", buildJsonArray {
            for (domain in model.rootIntDomains()) add(JsonPrimitive(domain.toString()))
        })
        put("factors", buildJsonArray {
            for (factor in model.factors) add(buildJsonObject {
                put("kind", factor::class.simpleName)
                put("intVars", JsonArray(factor.intVars.map { JsonPrimitive(it) }))
                put("boolVars", JsonArray(factor.boolVars.map { JsonPrimitive(it) }))
                put("key", factor.structuralKey().toString())
                put("form", factor.linearForm?.let { it::class.simpleName })
                put("rows", JsonArray(factor.linearRows.map(::row)))
            })
        })
    }

    private fun row(row: LinearRow) = buildJsonObject {
        put("refs", JsonArray((0 until row.size).map { JsonPrimitive(row.ref(it)) }))
        put("relation", row.relation.toString())
        put("strict", row.strict)
        put("activator", row.activator)
        val constants = row.constants
        put("coefficients", buildJsonArray {
            for (term in 0 until row.size) add(JsonPrimitive(when (constants) {
                is IntegralConstants -> constants.exactCoeff(term).toString()
                is RealConstants -> if (term < constants.intCoefficients.size) {
                    constants.intCoefficients.at(term).toString()
                } else constants.realCoefficients.at(term - constants.intCoefficients.size).toString()
            }))
        })
        put("bound", when (constants) {
            is IntegralConstants -> constants.exactBound.toString()
            is RealConstants -> constants.bound.toString()
        })
    }
}
