package com.eignex.klause.bench.profile

import com.eignex.klause.bench.report.Reports
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
import com.eignex.klause.solver.pipeline.minimize
import com.eignex.klause.solver.pipeline.pipelineRequest
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

internal object SourceEvidenceProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "source JSON and engine are required" }
        val bytes = args[0].toByteArray(Charsets.UTF_8)
        val input = Json.parseToJsonElement(args[0]).jsonObject
        require(input.keys == setOf("semantics", "kind", "lower", "upper", "coefficient", "constant", "sense"))
        require(input.getValue("semantics").jsonPrimitive.content == "klause-ir-exact-v1")
        require(input.getValue("kind").jsonPrimitive.content == "real-box-square")
        val lower = input.getValue("lower").jsonPrimitive.long
        val upper = input.getValue("upper").jsonPrimitive.long
        val coefficient = input.getValue("coefficient").jsonPrimitive.long
        val constant = input.getValue("constant").jsonPrimitive.long
        val sense = ObjectiveSense.valueOf(input.getValue("sense").jsonPrimitive.content)
        require(lower <= upper && coefficient > 0)
        require(BigFraction.ofDouble(lower.toDouble()) == BigFraction.ofLong(lower))
        require(BigFraction.ofDouble(upper.toDouble()) == BigFraction.ofLong(upper))
        val source = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(lower.toDouble()), realUpper = doubleArrayOf(upper.toDouble()))
        val variable = QuadraticVariable.Real(SourceRealId(0))
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(
            QuadraticCoefficient.ofLong(coefficient), variable, variable)), QuadraticCoefficient.ofLong(constant))
        val engine = FiniteEngine.valueOf(args[1])
        val execution = OpenTheoryPipeline.minimize(source.pipelineRequest(), objective,
            PolynomialTheoryParams(maximumWork = 100_000), engine, sense)
        val result = execution.result
        val scope = SourceScope(SourceRevision(hash(bytes), "klause-ir-exact-v1"),
            objective = SourceObjective("square-real-0", sense))
        val capture = requireNotNull(source.quadraticSourceResultCapture(scope, objective))
        val captured = capture.quadratic(result, "source-evidence-probe",
            boundDerivation = SourceQuadraticBoxDerivation)
        val out = File(requireNotNull(System.getenv("OUT")))
        out.mkdirs()
        out.resolve("source.json").writeBytes(bytes)
        out.resolve("runtime-provenance.json").writeText(buildJsonObject {
            put("revision", Reports.readGitSha())
            put("javaVersion", System.getProperty("java.version"))
            put("javaVendor", System.getProperty("java.vendor"))
            put("processors", Runtime.getRuntime().availableProcessors())
            put("classpath", buildJsonArray {
                for (path in System.getProperty("java.class.path").split(File.pathSeparator)) {
                    val file = File(path)
                    if (file.isFile) add(buildJsonObject {
                        put("name", file.name)
                        put("bytes", file.length())
                        put("sha256", hash(file.readBytes()))
                    })
                }
            })
        }.toString())
        val payload = buildJsonObject {
            put("sourceHash", scope.revision.identity)
            put("engine", engine.name)
            put("sense", sense.name)
            put("nativeWork", execution.nativeWork)
            put("result", result::class.simpleName)
            put("capture", captured.verifySourceEvidence(capture.context)::class.simpleName)
            put("proofFileExport", false)
            if (captured is QuadraticOptimizationResult.Optimal) {
                val point = captured.incumbent.sample
                val coordinate = point.algebraicReals?.single()?.rational ?: point.exactReals?.single()
                put("witness", requireNotNull(coordinate).toString())
                put("value", requireNotNull(captured.incumbent.value.rational).toString())
                put("bound", requireNotNull(captured.bound.value.rational).toString())
                put("strict", captured.bound.strict)
                put("rule", "original-quadratic-box")
            }
        }
        out.resolve("source-evidence.json").writeText(payload.toString())
        println(payload)
        val checker = out.resolve("independent-checker.py")
        SourceEvidenceProbe::class.java.getResourceAsStream("/source-evidence/check_source_evidence.py")
            .use { input -> checker.writeBytes(requireNotNull(input).readBytes()) }
        val process = ProcessBuilder("python3", checker.absolutePath, out.resolve("source.json").absolutePath,
            out.resolve("source-evidence.json").absolutePath, out.resolve("independent-check.json").absolutePath)
            .redirectErrorStream(true).redirectOutput(out.resolve("independent-checker.log")).start()
        check(process.waitFor() == 0) { "independent source checker failed" }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it) }
}
