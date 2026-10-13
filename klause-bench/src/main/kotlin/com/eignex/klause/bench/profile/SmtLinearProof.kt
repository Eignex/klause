package com.eignex.klause.bench.profile

import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.optimization.LinearApproach
import com.eignex.klause.lp.optimization.LinearPinnedObjective
import com.eignex.klause.lp.optimization.LinearPinnedRefutation
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
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
import com.eignex.klause.solver.pipeline.optimizeLinear
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Capture ordinary affine SMT optimization and independently check strict limits and branch coverage. */
object SmtLinearProof {
    /** Write captures and independent checker results for the original SMT paths in [args] under `OUT`. */
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.takeWhile { it.startsWith("--") }.associate { argument ->
            val pair = argument.removePrefix("--").split('=', limit = 2)
            require(pair.size == 2) { "Use --name=value" }
            pair[0] to pair[1]
        }
        require(options.keys.all { it in setOf("engine", "cores", "checks", "work", "timeout", "condition", "route") })
        val engine = when (options["engine"] ?: "cp") {
            "cp" -> FiniteEngine.BACKTRACK
            "fixed" -> FiniteEngine.FIXED
            "mixed" -> FiniteEngine.MIXED
            "ls" -> FiniteEngine.LOCAL_SEARCH
            "alns" -> FiniteEngine.ALNS
            else -> error("Unknown engine")
        }
        val cores = (options["cores"] ?: "1").toInt().also { require(it > 0) }
        val direct = when (options["route"] ?: "request") {
            "request" -> false
            "authority" -> true
            else -> error("Unknown route")
        }
        require(!direct || (engine == FiniteEngine.BACKTRACK && cores == 1))
        val inputs = args.dropWhile { it.startsWith("--") }
        require(inputs.isNotEmpty()) { "Pass original SMT paths" }
        val root = File(System.getProperty("klause.workspace.root", "."))
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        val conditionBytes = options["condition"]?.let { path ->
            File(path).let { if (it.isAbsolute) it else root.resolve(path) }.readBytes()
        }
        conditionBytes?.let { out.resolve("condition.json").writeBytes(it) }
        inputs.forEachIndexed { index, path ->
            val params = TheoryParams(maxLeaves = (options["checks"] ?: "1000").toLong(),
                openWorkLimit = (options["work"] ?: "2000000").toLong(), maxDecisions = 1_000,
                timeout = Cancellation.after((options["timeout"] ?: "3000").toLong().milliseconds))
            val input = File(path).let { if (it.isAbsolute) it else root.resolve(path) }
            val bytes = input.readBytes()
            out.resolve("$index.smt2").writeBytes(bytes)
            val compiled = SmtLib.parse(bytes.decodeToString())
            val maximize = compiled.sense == ObjectiveSense.MAXIMIZE
            val minimized = requireNotNull(compiled.objective).toLinearObjective()
            val objective = if (maximize) minimized.negated() else minimized
            val sense = if (maximize) ObjectiveSense.MAXIMIZE else ObjectiveSense.MINIMIZE
            val coordinates = buildMap {
                compiled.boolVarNames.forEach { (name, id) ->
                    put(name, SourceCoordinate(SourceCoordinate.Kind.Boolean, id))
                }
                compiled.intVarNames.forEach { (name, id) ->
                    put(name, SourceCoordinate(SourceCoordinate.Kind.Integer, id))
                }
                compiled.realVarNames.forEach { (name, id) ->
                    put(name, SourceCoordinate(SourceCoordinate.Kind.Real, id))
                }
            }
            val condition = conditionBytes?.let { readCondition(it, coordinates) } ?: SourceCondition()
            val scope = SourceScope(SourceRevision(sha(bytes), "klause-ir-exact-v1"),
                condition = condition, objective = SourceObjective("cost", sense))
            val capture = requireNotNull(compiled.model.sourceProofContext(scope, objective))
            val request = OpenTheoryRequest(compiled.model, objective, maximize = maximize)
                .let { if (conditionBytes == null) it else it.withLinearAuthority(capture) }
            val execution = if (direct) null else {
                OpenTheoryPipeline.execute(request, params, engine, cores) as OpenTheoryExecution.Optimize
            }
            val witnessOnly = engine == FiniteEngine.LOCAL_SEARCH || engine == FiniteEngine.ALNS
            val result = if (direct) {
                OpenTheoryPipeline.optimizeLinear(capture, params)
            } else execution?.result?.linearResult
            require(witnessOnly || result != null) { "Complete route omitted typed linear authority" }
            if (witnessOnly) require(execution?.result is OpenTheoryOptimum.Bounded)
            require(result == null || result.scope.condition == condition)
            val terminal = result?.terminal
            require(!witnessOnly || terminal == null) { "Witness-only route published a terminal" }
            val claim = terminal?.claim
            val incumbent = result?.incumbent ?: (execution?.result as? OpenTheoryOptimum.Bounded)?.incumbent?.let {
                (it as? OpenTheoryAssignment.Sampled)?.sample
            }
            val value = result?.value ?: (execution?.result as? OpenTheoryOptimum.Bounded)?.value?.let {
                if (maximize) it.negated() else it
            }
            val point = when (claim) {
                is SourceClaim.Optimal -> claim.sample
                is SourceClaim.Ray -> claim.sample
                is SourceClaim.Limit -> (requireNotNull(terminal).derivation as LinearApproach).base ?: claim.sample
                else -> incumbent
            }
            val record = buildJsonObject {
                put("sourceSHA256", sha(bytes))
                put("conditionSHA256", conditionBytes?.let { JsonPrimitive(sha(it)) } ?: JsonNull)
                put("maximize", maximize)
                put("reason", (result?.reason ?: (execution?.result as? OpenTheoryOptimum.Bounded)?.reason).toString())
                put("engine", engine.id)
                put("route", if (direct) "authority" else "request")
                put("cores", cores)
                put("kind", when (claim) {
                    is SourceClaim.Optimal -> "optimal"
                    is SourceClaim.Ray -> "ray"
                    is SourceClaim.Limit -> "limit"
                    SourceClaim.Refutation -> "refutation"
                    null -> "partial"
                    else -> error("Unsupported SMT claim: $claim")
                })
                put("columns", buildJsonObject {
                    compiled.boolVarNames.forEach { (name, id) -> put(name, "Boolean/$id") }
                    compiled.intVarNames.forEach { (name, id) -> put(name, "Integer/$id") }
                    compiled.realVarNames.forEach { (name, id) -> put(name, "Real/$id") }
                })
                put("point", point?.let(::sample) ?: JsonNull)
                put("incumbent", incumbent?.let(::sample) ?: JsonNull)
                put("value", value?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                put("objective", coefficients(capture.objective))
                put("constant", capture.objectiveConstant.toString())
                put("rows", buildJsonObject {
                    capture.premises.forEach { (id, row) ->
                        put(id, buildJsonObject {
                            put("coefficients", coefficients(row.inequality.coefficients))
                            put("rhs", row.inequality.rhs.toString())
                            put("strict", row.inequality.strict)
                            put("guards", buildJsonObject {
                                row.guards.forEach { (id, value) -> put("Boolean/$id", value) }
                            })
                        })
                    }
                })
                put("bound", when (claim) {
                    is SourceClaim.Optimal -> JsonPrimitive((claim.value as SourceValue.Rational).value.toString())
                    is SourceClaim.Limit -> JsonPrimitive((claim.value as SourceValue.Rational).value.toString())
                    else -> JsonNull
                })
                put("endpoint", (terminal?.derivation as? LinearApproach)?.endpoint?.let(::sample) ?: JsonNull)
                put("proof", terminal?.let { proof(it.derivation) } ?: JsonNull)
                put("ray", (claim as? SourceClaim.Ray)?.let { coefficients(it.direction) } ?: JsonNull)
                put("globalBound", result?.bound?.let {
                    JsonPrimitive(((it.claim as SourceClaim.Bound).value as SourceValue.Rational).value.toString())
                } ?: JsonNull)
                put("globalProof", result?.bound?.let { proof(it.derivation) } ?: JsonNull)
            }
            out.resolve("$index.json").writeText("$record\n")
            val checker = root.resolve("klause-bench/tools/verify_smt_linear_proof.py")
            val command = mutableListOf("python3", checker.path, out.resolve("$index.smt2").path,
                out.resolve("$index.json").path)
            if (conditionBytes != null) command += out.resolve("condition.json").path
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
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

    private fun readCondition(bytes: ByteArray, coordinates: Map<String, SourceCoordinate>): SourceCondition {
        val data = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        require(data.keys.all { it in setOf("bools", "lower", "upper") })
        val bools = data["bools"]?.jsonObject.orEmpty().map { (name, value) ->
            val coordinate = coordinates.getValue(name)
            require(coordinate.kind == SourceCoordinate.Kind.Boolean)
            coordinate.id to value.jsonPrimitive.boolean
        }.toMap()
        fun bounds(side: String): Map<SourceCoordinate, BigFraction> =
            data[side]?.jsonObject.orEmpty().map { (name, value) ->
                val coordinate = coordinates.getValue(name)
                require(coordinate.kind != SourceCoordinate.Kind.Boolean)
                val parts = value.jsonPrimitive.content.split('/')
                require(parts.size in 1..2)
                coordinate to BigFraction.of(parseBigInt(parts[0]),
                    if (parts.size == 2) parseBigInt(parts[1]) else BIG_ONE)
            }.toMap()
        return SourceCondition(bools, bounds("lower"), bounds("upper"))
    }

    private fun coefficients(values: Map<SourceCoordinate, BigFraction>): JsonElement = buildJsonObject {
        values.forEach { (coordinate, value) -> put(key(coordinate), value.toString()) }
    }

    private fun sample(sample: Sample): JsonElement = buildJsonObject {
        for (id in 0 until sample.numBoolVars) put("Boolean/$id", sample.boolValue(id))
        for (id in 0 until sample.numIntVars) put("Integer/$id", sample.exactIntValue(id).toString())
        for (id in 0 until sample.numRealVars) put("Real/$id", requireNotNull(sample.exactReals)[id].toString())
    }

    private fun array(vararg values: JsonElement): JsonElement = JsonArray(values.toList())

    private fun proof(derivation: SourceDerivation): JsonElement = when (derivation) {
        is LinearApproach -> proof(derivation.strictBound)
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
        is SourceDerivation.BooleanSplit -> array(JsonPrimitive("bool"), JsonPrimitive(derivation.id),
            proof(derivation.whenFalse), proof(derivation.whenTrue))
        SourceDerivation.SourceRay -> JsonPrimitive("source-ray")
        else -> error("Unsupported derivation: $derivation")
    }
}
