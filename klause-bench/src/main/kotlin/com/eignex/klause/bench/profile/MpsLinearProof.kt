package com.eignex.klause.bench.profile

import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toExactSourceProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.optimization.LinearPinnedObjective
import com.eignex.klause.lp.optimization.LinearPinnedRefutation
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.evidence.sourceProofContext
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.OpenTheoryOptimum
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.OpenTheoryRequest
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.util.Cancellation
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Capture ordinary exact MPS optimization and check its source proof with independent rational arithmetic. */
object MpsLinearProof {
    /** Write captures and independent checker results for the original MPS paths in [args] under `OUT`. */
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.takeWhile { it.startsWith("--") }.associate { argument ->
            val pair = argument.removePrefix("--").split('=', limit = 2)
            require(pair.size == 2) { "Use --name=value" }
            pair[0] to pair[1]
        }
        require(options.keys.all { it in setOf("engine", "cores", "checks", "work", "timeout") })
        val engine = when (options["engine"] ?: "cp") {
            "cp" -> FiniteEngine.BACKTRACK
            "fixed" -> FiniteEngine.FIXED
            "mixed" -> FiniteEngine.MIXED
            "ls" -> FiniteEngine.LOCAL_SEARCH
            "alns" -> FiniteEngine.ALNS
            else -> error("Unknown engine")
        }
        val cores = (options["cores"] ?: "1").toInt().also { require(it > 0) }
        val inputs = args.dropWhile { it.startsWith("--") }
        require(inputs.isNotEmpty()) { "Pass original MPS paths" }
        val root = File(System.getProperty("klause.workspace.root", "."))
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        inputs.forEachIndexed { index, path ->
            val params = TheoryParams(maxLeaves = (options["checks"] ?: "1000").toLong(),
                openWorkLimit = (options["work"] ?: "2000000").toLong(), maxDecisions = 1_000,
                timeout = Cancellation.after((options["timeout"] ?: "3000").toLong().milliseconds))
            val input = File(path).let { if (it.isAbsolute) it else root.resolve(path) }
            val bytes = input.readBytes()
            out.resolve("$index.mps").writeBytes(bytes)
            val compiled = Mps.parse(bytes.decodeToString()).toExactSourceProblem()
            val objective = requireNotNull(compiled.objective).toLinearObjective()
            val sense = if (compiled.maximize) ObjectiveSense.MAXIMIZE else ObjectiveSense.MINIMIZE
            val capture = requireNotNull(compiled.model.sourceProofContext(SourceScope(
                SourceRevision("probe", "klause-ir-exact-v1"), objective = SourceObjective("cost", sense)), objective))
            val execution = OpenTheoryPipeline.execute(OpenTheoryRequest(compiled.model, objective,
                maximize = compiled.maximize), params, engine, cores) as OpenTheoryExecution.Optimize
            val witnessOnly = engine == FiniteEngine.LOCAL_SEARCH || engine == FiniteEngine.ALNS
            val result = execution.result.linearResult
            require(witnessOnly || result != null) { "Complete route omitted typed linear authority" }
            if (witnessOnly) require(execution.result is OpenTheoryOptimum.Bounded)
            val terminal = result?.terminal
            require(!witnessOnly || terminal == null) { "Witness-only route published a terminal" }
            val claim = terminal?.claim
            val incumbent = result?.incumbent ?: (execution.result as? OpenTheoryOptimum.Bounded)?.incumbent?.let {
                (it as? OpenTheoryAssignment.Sampled)?.sample
            }
            val value = result?.value ?: (execution.result as? OpenTheoryOptimum.Bounded)?.value?.let {
                if (compiled.maximize) it.negated() else it
            }
            val point = when (claim) {
                is SourceClaim.Optimal -> claim.sample
                is SourceClaim.Ray -> claim.sample
                else -> incumbent
            }
            val record = buildJsonObject {
                put("sourceSHA256", sha(bytes))
                put("maximize", compiled.maximize)
                put("reason", (result?.reason ?: (execution.result as? OpenTheoryOptimum.Bounded)?.reason).toString())
                put("engine", engine.id)
                put("cores", cores)
                put("kind", when (claim) {
                    is SourceClaim.Optimal -> "optimal"
                    is SourceClaim.Ray -> "ray"
                    SourceClaim.Refutation -> "refutation"
                    null -> "partial"
                    else -> error("Unsupported MPS claim: $claim")
                })
                put("columns", buildJsonObject {
                    compiled.columns.forEach { put(it.name, "${if (it.real) "Real" else "Integer"}/${it.id}") }
                })
                put("point", point?.let(::sample) ?: JsonNull)
                put("incumbent", incumbent?.let(::sample) ?: JsonNull)
                put("value", value?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                put("objective", coefficients(capture.objective))
                put("constant", capture.objectiveConstant.toString())
                put("rows", buildJsonObject {
                    capture.premises.forEach { (id, row) ->
                        require(row.guards.isEmpty() && !row.inequality.strict)
                        put(id, buildJsonObject {
                            put("coefficients", coefficients(row.inequality.coefficients))
                            put("rhs", row.inequality.rhs.toString())
                        })
                    }
                })
                put("bound", (claim as? SourceClaim.Optimal)?.let {
                    JsonPrimitive((it.value as SourceValue.Rational).value.toString())
                } ?: JsonNull)
                put("proof", terminal?.let { proof(it.derivation) } ?: JsonNull)
                put("ray", (claim as? SourceClaim.Ray)?.let { coefficients(it.direction) } ?: JsonNull)
                put("globalBound", result?.bound?.let {
                    JsonPrimitive(((it.claim as SourceClaim.Bound).value as SourceValue.Rational).value.toString())
                } ?: JsonNull)
                put("globalProof", result?.bound?.let { proof(it.derivation) } ?: JsonNull)
            }
            out.resolve("$index.json").writeText("$record\n")
            val checker = root.resolve("klause-bench/tools/verify_mps_linear_proof.py")
            val process = ProcessBuilder("python3", checker.path, out.resolve("$index.mps").path,
                out.resolve("$index.json").path).redirectErrorStream(true).start()
            val checked = process.inputStream.bufferedReader().readText()
            out.resolve("$index-check.json").writeText(checked)
            check(process.waitFor() == 0) { checked }
            println(checked.trim())
            out.resolve("$index-settings.txt").writeText("engine=${engine.id}\ncores=$cores\n$params\n")
        }
        val jars = root.resolve("klause-bench/build/install/klause-bench/lib").listFiles().orEmpty()
            .filter { it.extension == "jar" }.sortedBy { it.name }
        out.resolve("installed-jars.txt").writeText(jars.joinToString("\n") { "${sha(it.readBytes())}  ${it.name}" })
        out.resolve("runtime.txt").writeText("${System.getProperty("java.runtime.version")}\n" +
            "${java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments}\n$options\n")
        root.resolve("build/provenance.json").takeIf { it.isFile }?.copyTo(out.resolve("build-provenance.json"),
            overwrite = true)
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it) }

    private fun key(coordinate: SourceCoordinate): String = "${coordinate.kind}/${coordinate.id}"

    private fun coefficients(values: Map<SourceCoordinate, BigFraction>): JsonElement = buildJsonObject {
        values.forEach { (coordinate, value) -> put(key(coordinate), value.toString()) }
    }

    private fun sample(sample: Sample): JsonElement = buildJsonObject {
        for (id in 0 until sample.numIntVars) put("Integer/$id", sample.exactIntValue(id).toString())
        for (id in 0 until sample.numRealVars) put("Real/$id", requireNotNull(sample.exactReals)[id].toString())
    }

    private fun array(vararg values: JsonElement): JsonElement = JsonArray(values.toList())

    private fun proof(derivation: SourceDerivation): JsonElement = when (derivation) {
        is LinearPinnedObjective -> proof(derivation.bound)
        is LinearPinnedRefutation -> proof(derivation.conflict)
        is SourceDerivation.Optimum -> proof(derivation.bound)
        is SourceDerivation.Premise -> array(JsonPrimitive("row"), JsonPrimitive(derivation.id))
        is SourceDerivation.Assumption -> array(JsonPrimitive("assume"), JsonPrimitive(key(derivation.coordinate)),
            JsonPrimitive(derivation.upper))
        is SourceDerivation.Combination -> array(JsonPrimitive("sum"), JsonArray(derivation.terms.map {
            array(JsonPrimitive(it.weight.toString()), proof(it.derivation))
        }))
        is SourceDerivation.IntegerSplit -> array(JsonPrimitive("int"), JsonPrimitive(key(derivation.coordinate)),
            JsonPrimitive(derivation.threshold.toString()), proof(derivation.below), proof(derivation.above))
        SourceDerivation.SourceRay -> JsonPrimitive("source-ray")
        else -> error("Unsupported derivation: $derivation")
    }
}
