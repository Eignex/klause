package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.util.Cancellation
import java.io.File
import java.lang.Long.parseUnsignedLong
import java.lang.reflect.InvocationTargetException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

internal object JointRayInspection {
    private const val ENGINE = "com.eignex.klause.lp.engine."
    private const val WORK = 2_000_000L
    private const val BYTES = 67_108_864L
    private val rayName = Regex("conditional-[0-3]-ray\\.json")
    private val encoded = Regex("[0-9a-f]{16}")

    fun inspect(out: File, stop: Cancellation) {
        val files = out.listFiles().orEmpty().filter { rayName.matches(it.name) }.sortedBy { it.name }
        require(files.size <= 4)
        val rays = buildJsonArray {
            for (file in files) {
                if (stop()) break
                add(inspectRay(file, stop))
            }
        }
        out.resolve("ray-inspection.json").writeText(buildJsonObject {
            put("maximumCapturedRays", 4)
            put("maximumCandidates", 8)
            put("maximumProjectionWorkPerRay", WORK)
            put("maximumProjectionAllocationPerRay", BYTES)
            put("maximumVerificationWorkPerCandidate", WORK)
            put("maximumVerificationAllocationPerCandidate", BYTES)
            put("separateFromProducerGrant", true)
            put("inspectionStopped", stop())
            put("rays", rays)
            put("sourceVerdictClaim", false)
            put("independentProofClaim", false)
        }.toString())
    }

    private fun inspectRay(file: File, stop: Cancellation): JsonObject {
        require(file.length() in 1L..1_048_576L)
        val payload = file.readBytes()
        val input = Reports.json.parseToJsonElement(payload.decodeToString()).jsonObject
        val id = input.getValue("conditionId").jsonPrimitive.int
        require(id in 0..3 && file.name == "conditional-$id-ray.json")
        val snapshot = file.parentFile.resolve("conditional-$id.klexact")
        require(snapshot.length() in 1L..1_048_576L)
        val bytes = snapshot.readBytes()
        require(sha256(bytes) == input.getValue("snapshotSha256").jsonPrimitive.content)
        val companion = Class.forName(ENGINE + "LpExactCapture").getField("Companion").get(null)
        val capture = requireNotNull(call(companion, "decode", bytes))
        val state = requireNotNull(call(capture, "getInitialState"))
        val exact = requireNotNull(call(state, "getModel"))
        val rows = call(exact, "getM") as Int
        val columns = call(exact, "getN") as Int
        require(rows in 1..4096 && columns >= 0 && columns.toLong() + rows <= 8192)
        val identity = input.getValue("stateIdentity").jsonObject
        require(identity.getValue("rows").jsonPrimitive.int == rows &&
            identity.getValue("columns").jsonPrimitive.int == columns)
        for (part in listOf("Matrix", "Bound", "Objective", "Pop", "Row")) {
            require(call(state, "get${part}Revision") ==
                identity.getValue(part.lowercase() + "Revision").jsonPrimitive.long)
        }
        val ray = requireNotNull(decodeRay(input.getValue("rayBits").jsonArray.map {
            it.jsonPrimitive.content
        }, rows))
        val projection = create("LpProjectionMeter", WORK, BYTES, stop)
        val model = call(state, "ownerWorkingModel", projection)
        val candidates = buildJsonArray {
            if (model != null) {
                check(call(model, "getExactState") === state)
                for (negated in listOf(false, true)) {
                    if (stop()) break
                    add(verify(model, DoubleArray(ray.size) { if (negated) -ray[it] else ray[it] }, negated, stop))
                }
            }
        }
        return buildJsonObject {
            put("conditionId", id)
            put("rayCaptureSha256", sha256(payload))
            put("snapshotSha256", sha256(bytes))
            put("scope", input.getValue("scope"))
            put("stateIdentity", identity)
            put("decodedRevisionsMatched", true)
            put("projectionDeclined", model == null)
            put("projection", NumericalResidualProfile.json(projection))
            put("candidates", candidates)
        }
    }

    private fun verify(model: Any, ray: DoubleArray, negated: Boolean, stop: Cancellation): JsonObject {
        val started = System.nanoTime()
        val limits = create("ReconstructionLimits", 8192, 65536, 4096, WORK, BYTES, 0)
        val meter = create("ReconstructionMeter", limits, stop)
        var run: Any? = null
        var decline: Any? = null
        try {
            val authority = create("ReconstructionAuthority", model, meter)
            val method = Class.forName(ENGINE + "ReconstructedCertificateKt").declaredMethods.single {
                it.name == "reconstructIeee" && it.parameterCount == 2
            }.apply { isAccessible = true }
            val vector = method.invoke(null, ray, meter)
            val current = create("ReconstructionRun", authority, meter)
            run = current
            call(current, "dual", vector, true, false, null)
        } catch (failure: InvocationTargetException) {
            val cause = failure.targetException
            if (cause.javaClass.simpleName != "ReconstructionStop") throw cause
            decline = call(cause, "getReason")
        }
        val conflict = run?.let { call(it, "getConflict") } != null
        val metrics = requireNotNull(call(meter, "snapshot", decline))
        val rejection = call(metrics, "getDualRejection")
        return buildJsonObject {
            put("orientation", if (negated) "negated-engine-original" else "engine-original")
            put("outcome", when {
                decline != null -> "DECLINED"
                conflict -> "KERNEL_CONFLICT"
                rejection != null -> "MISSING_BOUND_SIDE"
                else -> "NON_CONTRADICTORY"
            })
            put("kernelConflictAvailable", conflict)
            put("decline", decline?.toString())
            put("metrics", NumericalResidualProfile.json(metrics))
            rejection?.let {
                put("dualRejection", buildJsonObject {
                    put("column", call(it, "getColumn") as Int)
                    put("upper", call(it, "getUpper") as Boolean)
                    put("reduced", call(it, "getReduced").toString())
                })
            }
            put("elapsedNs", System.nanoTime() - started)
        }
    }

    internal fun decodeRay(bits: List<String>, rows: Int): DoubleArray? {
        if (rows !in 1..4096 || bits.size != rows || bits.any { !encoded.matches(it) }) return null
        return DoubleArray(rows) {
            Double.fromBits(parseUnsignedLong(bits[it], 16))
        }.takeIf { values -> values.all(Double::isFinite) }
    }

    @Suppress("SpreadOperator")
    private fun create(name: String, vararg args: Any?): Any =
        Class.forName(ENGINE + name).declaredConstructors.single { it.parameterCount == args.size }
            .apply { isAccessible = true }.newInstance(*args)

    @Suppress("SpreadOperator")
    private fun call(owner: Any, name: String, vararg args: Any?): Any? =
        owner.javaClass.methods.single {
            it.name.substringBefore('$') == name && it.parameterCount == args.size
        }.apply { isAccessible = true }.invoke(owner, *args)
}
