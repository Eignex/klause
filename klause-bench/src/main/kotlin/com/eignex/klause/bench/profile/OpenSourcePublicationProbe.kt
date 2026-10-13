package com.eignex.klause.bench.profile

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.evidence.sourceProofContext
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.OpenTheoryRequest
import com.eignex.klause.solver.pipeline.SourceIncumbentPublication
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.parseBigInt
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
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

internal object OpenSourcePublicationProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        val text = requireNotNull(options["source"]) { "source=<exact affine box JSON> is required" }
        val source = Json.parseToJsonElement(text).jsonObject
        require(source.keys == setOf("semantics", "reals", "objective"))
        require(source.getValue("semantics").jsonPrimitive.content == "klause-ir-exact-v1")
        val bounds = source.getValue("reals").jsonArray.map { item ->
            val row = item.jsonObject
            require(row.keys == setOf("lower", "upper"))
            val lower = fraction(row.getValue("lower").jsonPrimitive.content)
            val upper = fraction(row.getValue("upper").jsonPrimitive.content)
            require(lower <= upper)
            exactDouble(lower) to exactDouble(upper)
        }
        val model = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = bounds.size,
            realLower = bounds.map { it.first }.toDoubleArray(), realUpper = bounds.map { it.second }.toDoubleArray())
        val specification = source.getValue("objective").jsonObject
        require(specification.keys == setOf("sense", "constant", "terms"))
        val sense = ObjectiveSense.valueOf(specification.getValue("sense").jsonPrimitive.content)
        val coefficients = MutableList(bounds.size) { BigFraction.ZERO }
        for (item in specification.getValue("terms").jsonArray) {
            val term = item.jsonObject
            require(term.keys == setOf("coefficient", "first")) { "only affine terms are supported" }
            val id = term.getValue("first").jsonPrimitive.int
            require(id in coefficients.indices)
            coefficients[id] += fraction(term.getValue("coefficient").jsonPrimitive.content)
        }
        val constant = fraction(specification.getValue("constant").jsonPrimitive.content)
        val objective = LinearObjective(realCoefficients = coefficients.map { it.toDouble() }.toDoubleArray())
        val sourceHash = hash(text.toByteArray())
        val scope = SourceScope(SourceRevision(sourceHash, "klause-ir-exact-v1"),
            objective = SourceObjective(hash(specification.toString().toByteArray()), sense))
        val declarations = requireNotNull(model.sourceProofContext(scope, objective))
        val context = SourceProofContext(scope, declarations.coordinates, declarations.premises,
            coefficients.mapIndexed { id, value -> SourceCoordinate(SourceCoordinate.Kind.Real, id) to value }.toMap(),
            constant)
        val request = OpenTheoryRequest(model, objective, maximize = sense == ObjectiveSense.MAXIMIZE)
            .withLinearAuthority(context)
        val out = File(options["out"] ?: requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        val sourceFile = out.resolve("source.json").also { it.writeText(text) }
        val results = buildJsonArray {
            for (control in (options["controls"] ?: "BACKTRACK:1,FIXED:1,MIXED:1,MIXED:2").split(',')) {
                val engine = FiniteEngine.valueOf(control.substringBefore(':'))
                val cores = control.substringAfter(':').toInt()
                val publications = ArrayList<SourceIncumbentPublication>()
                val execution = OpenTheoryPipeline.execute(request,
                    TheoryParams(openWorkLimit = 100_000, maxFlips = 20, alnsIterations = 2), engine, cores,
                    onAccepted = { publications += it }) as OpenTheoryExecution.Optimize
                val result = execution.result
                val receipt = execution.acceptedIncumbent
                val bound = when (val claim = result.linearResult?.bound?.claim) {
                    is SourceClaim.Bound -> claim.value
                    else -> null
                }
                add(buildJsonObject {
                    put("control", control)
                    put("kind", result::class.simpleName)
                    put("sense", sense.name)
                    put("sourceHash", sourceHash)
                    put("publicationCount", publications.size)
                    put("acceptedVersion", receipt?.version)
                    put("acceptedIdentityRetained", publications.lastOrNull()?.let { it === receipt } ?: false)
                    put("portfolioArms", result.stats.portfolio.arms.size)
                    put("witness", receipt?.sample?.exactReals?.let { values -> buildJsonArray {
                        values.forEach { add(JsonPrimitive(it.toString())) }
                    } } ?: JsonNull)
                    put("value", (receipt?.value as? SourceValue.Rational)?.value?.toString())
                    put("bound", (bound as? SourceValue.Rational)?.value?.toString())
                    put("strict", (result.linearResult?.bound?.claim as? SourceClaim.Bound)?.strict ?: false)
                    put("publications", buildJsonArray {
                        publications.forEach { publication -> add(buildJsonObject {
                            put("version", publication.version)
                            put("witness", publication.sample.exactReals?.let { values -> buildJsonArray {
                                values.forEach { add(JsonPrimitive(it.toString())) }
                            } } ?: JsonNull)
                            put("value", (publication.value as? SourceValue.Rational)?.value?.toString())
                        }) }
                    })
                })
            }
        }
        val payload = buildJsonObject { put("sourceHash", sourceHash); put("results", results) }
        val resultFile = out.resolve("results.json").also { it.writeText(payload.toString()) }
        out.resolve("payloads.sha256").writeText("${hash(sourceFile.readBytes())}  source.json\n" +
            "${hash(resultFile.readBytes())}  results.json\n")
        val checker = File(System.getProperty("klause.workspace.root", "."))
            .resolve("klause-bench/tools/verify_quadratic_box_results.py")
        val process = ProcessBuilder(options["python"] ?: "python3", checker.absolutePath,
            sourceFile.absolutePath, resultFile.absolutePath).redirectErrorStream(true)
            .redirectOutput(out.resolve("independent-check.json")).start()
        check(process.waitFor() == 0) { "independent affine source check failed; see independent-check.json" }
        println(payload)
    }

    private fun fraction(text: String): BigFraction {
        val parts = text.split('/')
        require(parts.size in 1..2)
        return BigFraction.of(parseBigInt(parts[0]), parts.getOrNull(1)?.let(::parseBigInt) ?: BIG_ONE)
    }

    private fun exactDouble(value: BigFraction): Double = value.toDouble().also {
        require(BigFraction.ofDouble(it) == value) { "declaration is not exact in this IR projection" }
    }

    private fun hash(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
