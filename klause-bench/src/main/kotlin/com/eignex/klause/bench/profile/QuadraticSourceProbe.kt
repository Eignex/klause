package com.eignex.klause.bench.profile

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceQuadraticBoxDerivation
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.quadraticSourceResultCapture
import com.eignex.klause.solver.evidence.verifySourceEvidence
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.PolynomialTheoryParams
import com.eignex.klause.solver.pipeline.QuadraticIncumbentPublication
import com.eignex.klause.solver.pipeline.minimize
import com.eignex.klause.solver.pipeline.pipelineRequest
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.parseBigInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicLong

internal object QuadraticSourceProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        val text = requireNotNull(options["source"]) { "source=<exact box JSON> is required" }
        val source = Json.parseToJsonElement(text).jsonObject
        require(source.getValue("semantics").jsonPrimitive.content == "klause-ir-exact-v1")
        require(source.keys == setOf("semantics", "reals", "objective")) { "unsupported source fields" }
        val bounds = source.getValue("reals").jsonArray.map { item ->
            val row = item.jsonObject
            require(row.keys == setOf("lower", "upper")) { "unsupported declaration fields" }
            val lower = fraction(row.getValue("lower").jsonPrimitive.content)
            val upper = fraction(row.getValue("upper").jsonPrimitive.content)
            require(lower <= upper)
            exactDouble(lower) to exactDouble(upper)
        }
        val original = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = bounds.size,
            realLower = bounds.map { it.first }.toDoubleArray(), realUpper = bounds.map { it.second }.toDoubleArray())
        val specification = source.getValue("objective").jsonObject
        require(specification.keys == setOf("sense", "constant", "terms")) { "unsupported objective fields" }
        val sense = ObjectiveSense.valueOf(specification.getValue("sense").jsonPrimitive.content)
        fun variable(id: Int): QuadraticVariable.Real {
            require(id in bounds.indices)
            return QuadraticVariable.Real(SourceRealId(id))
        }
        val objective = QuadraticObjectiveSpec(specification.getValue("terms").jsonArray.map { item ->
            val term = item.jsonObject
            require(term.keys.all { it in setOf("coefficient", "first", "second") })
            QuadraticObjectiveTerm(coefficient(term.getValue("coefficient").jsonPrimitive.content),
                variable(term.getValue("first").jsonPrimitive.int),
                term["second"]?.jsonPrimitive?.int?.let(::variable))
        }, coefficient(specification.getValue("constant").jsonPrimitive.content))
        val out = File(options["out"] ?: System.getenv("OUT") ?: "build/quadratic-source-probe").apply { mkdirs() }
        val sourceFile = out.resolve("source.json").also { it.writeText(text) }
        val scope = SourceScope(SourceRevision(hash(sourceFile.readBytes()), "klause-ir-exact-v1"),
            objective = SourceObjective(hash(specification.toString().toByteArray()), sense))
        val capture = requireNotNull(original.quadraticSourceResultCapture(scope, objective))
        val results = buildJsonArray {
            for (control in (options["controls"] ?: "BACKTRACK:1,MIXED:1,MIXED:4").split(',')) {
                val engine = FiniteEngine.valueOf(control.substringBefore(':'))
                val cores = control.substringAfter(':').toInt()
                val forwarded = AtomicLong()
                val lifetime = object : Cancellation {
                    override fun isCancelled(): Boolean = false
                    override fun workMeter(): WorkMeter = WorkMeter { forwarded.addAndGet(it) }
                }
                val publications = ArrayList<QuadraticIncumbentPublication>()
                val execution = OpenTheoryPipeline.minimize(original.pipelineRequest(), objective,
                    PolynomialTheoryParams(maximumWork = options["maximumWork"]?.toLong() ?: 100_000,
                        cancellation = lifetime), engine, sense, cores, onAccepted = { publications += it })
                val result = execution.result
                val captured = capture.quadratic(result, control, boundDerivation = SourceQuadraticBoxDerivation)
                add(buildJsonObject {
                    put("control", control)
                    put("kind", result::class.simpleName)
                    put("sense", sense.name)
                    put("sourceHash", scope.revision.identity)
                    put("nativeWork", execution.nativeWork)
                    put("polynomialWork", execution.polynomialStats.workSpent)
                    put("numericalWork", execution.numericalWork)
                    put("forwardedWork", forwarded.get())
                    put("publicationCount", publications.size)
                    put("acceptedVersion", execution.acceptedIncumbent?.version)
                    put("acceptedIdentityRetained", publications.lastOrNull()?.let {
                        it === execution.acceptedIncumbent
                    } ?: false)
                    put("portfolioArms", result.stats.portfolio.arms.size)
                    put("sourceCapture", captured.verifySourceEvidence(capture.context)::class.simpleName)
                    put("engineOnly", capture.quadratic(result, control)
                        .verifySourceEvidence(capture.context)::class.simpleName)
                    val point = (result as? QuadraticOptimizationResult.WithIncumbent)?.incumbent
                    put("witness", point?.sample?.algebraicReals?.let { values -> buildJsonArray {
                        values.forEach { value -> add(value.rational?.let { JsonPrimitive(it.toString()) }
                            ?: JsonNull) }
                    } } ?: JsonNull)
                    put("value", point?.value?.rational?.toString())
                    val bound = when (result) {
                        is QuadraticOptimizationResult.Optimal -> result.bound
                        is QuadraticOptimizationResult.UnattainedLimit -> result.bound
                        is QuadraticOptimizationResult.BestFound -> result.bound
                        is QuadraticOptimizationResult.Unknown -> result.bound
                        is QuadraticOptimizationResult.Infeasible, is QuadraticOptimizationResult.Unbounded -> null
                    }
                    put("bound", bound?.value?.rational?.toString())
                    put("strict", bound?.strict ?: false)
                })
            }
        }
        val payload = buildJsonObject { put("sourceHash", scope.revision.identity); put("results", results) }
        val resultFile = out.resolve("results.json").also { it.writeText(payload.toString()) }
        out.resolve("payloads.sha256").writeText("${hash(sourceFile.readBytes())}  source.json\n" +
            "${hash(resultFile.readBytes())}  results.json\n")
        val root = File(System.getProperty("klause.workspace.root", "."))
        val checker = root.resolve("klause-bench/tools/verify_quadratic_box_results.py")
        val process = ProcessBuilder(options["python"] ?: "python3", checker.absolutePath,
            sourceFile.absolutePath, resultFile.absolutePath).redirectErrorStream(true)
            .redirectOutput(out.resolve("independent-check.json")).start()
        check(process.waitFor() == 0) { "independent quadratic source check failed; see independent-check.json" }
        println(payload)
    }

    private fun fraction(text: String): BigFraction {
        val parts = text.split('/')
        require(parts.size in 1..2)
        return BigFraction.of(parseBigInt(parts[0]), parts.getOrNull(1)?.let(::parseBigInt) ?: BIG_ONE)
    }

    private fun coefficient(text: String): QuadraticCoefficient = fraction(text).let {
        QuadraticCoefficient.of(it.num, it.den)
    }

    private fun exactDouble(value: BigFraction): Double = value.toDouble().also {
        require(BigFraction.ofDouble(it) == value) { "declaration cannot be represented exactly by this IR projection" }
    }

    private fun hash(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
