package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.math.abs

internal object BinaryDistanceRegion {
    private const val MAX_FREED = 32
    private const val GUIDE_WORK = 20_000_000L
    private const val GUIDE_BYTES = 32L * 1024 * 1024
    private const val GUIDE_OPERATIONS = 16
    private const val MOVEMENT = 1e-7

    data class Projection(val pins: String?, val work: Long, val allocation: Long)

    fun project(
        original: SourceProofContext, center: String, freeSpec: String, stop: ExecutionControl,
        sourceHash: String, out: File,
    ): Projection {
        val started = System.nanoTime()
        val coordinates = original.coordinates
        val premises = original.premises
        val integers = coordinates.count { it.kind == SourceCoordinate.Kind.Integer }
        require(center.length == integers && center.all { it == '0' || it == '1' })
        val freeIds = freeSpec.split(',').map(String::toInt)
        val existingFree = freeIds.toSet()
        require(freeIds.size == existingFree.size && existingFree.size in 1 until MAX_FREED &&
            existingFree.all { it in center.indices })
        val centerHash = sha256(center.encodeToByteArray())
        val revision = SourceRevision("$sourceHash/$centerHash/binary-l1", "diagnostic-binary-distance-v1")
        val scope = original.scope.copy(revision = revision,
            objective = SourceObjective("${revision.identity}/objective", ObjectiveSense.MINIMIZE))
        val costs = center.indices.associate { id ->
            SourceCoordinate(SourceCoordinate.Kind.Integer, id) to
                BigFraction.ofLong(if (center[id] == '0') 1L else -1L)
        }
        val work = 1L + 4L * center.length + coordinates.size + premises.size
        val allocation = 256L + 192L * center.length + 128L * (coordinates.size + premises.size)
        val lpPackage = "com.eignex.klause.lp."
        val funding = Class.forName(lpPackage + "SourceLpFunding").constructors.single { it.parameterCount == 2 }
            .newInstance(GUIDE_WORK, GUIDE_BYTES)
        call(funding, "grant", GUIDE_WORK)
        call(funding, "charge", work, allocation)
        val auxiliary = SourceProofContext(scope, coordinates, premises, costs,
            BigFraction.ofLong(center.count { it == '1' }.toLong())).withLinearAuthority()
        val budget = budget(lpPackage, funding)
        val owner = Class.forName(lpPackage + "optimization.LinearAuthority")
            .constructors.single { it.parameterCount == 6 }
            .newInstance(budget, { _: Any -> }, true,
                Class.forName(lpPackage + "SparseSourceLpPolicy").getConstructor().newInstance(), emptyList<Any>(),
                false) as AutoCloseable
        var result: Any? = null
        var consultations = 0
        owner.use {
            while (consultations < GUIDE_OPERATIONS && !stop() && call(funding, "getPaused") != true) {
                val progress = requireNotNull(call(owner, "advance", auxiliary, stop, false, null))
                consultations++
                if (call(progress, "getPaused") != true) {
                    result = progress
                    break
                }
            }
        }
        val point = result?.let { call(it, "getRelaxedPoint") } as? Map<*, *>
        val values = center.indices.map { id -> point?.get(SourceCoordinate(SourceCoordinate.Kind.Integer, id)) as? Double }
        val complete = values.all { it != null && it.isFinite() && it in -MOVEMENT..(1 + MOVEMENT) }
        val moved = if (complete) center.indices.filter { id ->
            abs(requireNotNull(values[id]) - center[id].digitToInt()) > MOVEMENT
        } else emptyList()
        val freed = (existingFree + moved).sorted()
        val pins = if (!stop() && complete && freed.size in (existingFree.size + 1)..MAX_FREED) {
            center.indices.filterNot { it in freed }.joinToString(",") { "$it=${center[it]}" }
        } else null
        val spent = call(funding, "getSpent") as Long
        val allocated = call(funding, "getAllocated") as Long
        out.resolve("distance-projection.json").writeText(buildJsonObject {
            put("sourceSha256", sourceHash)
            put("centerSha256", centerHash)
            put("originalScope", NumericalFrontierSnapshot.scope(original.scope))
            put("auxiliaryScope", NumericalFrontierSnapshot.scope(scope))
            put("originalFree", JsonArray(freeIds.map(::JsonPrimitive)))
            put("moved", JsonArray(moved.map(::JsonPrimitive)))
            put("freed", JsonArray(freed.map(::JsonPrimitive)))
            put("coordinates", JsonArray(values.map { if (it?.isFinite() == true) JsonPrimitive(it) else JsonNull }))
            put("complete", complete)
            put("pins", pins)
            put("pinsSha256", pins?.let { sha256(it.encodeToByteArray()) })
            put("consultations", consultations)
            put("work", spent)
            put("allocation", allocated)
            put("elapsedNs", System.nanoTime() - started)
            put("cancelled", stop())
        }.toString())
        return Projection(pins, spent, allocated)
    }

    private fun budget(lpPackage: String, funding: Any): Any {
        val contextType = Class.forName(lpPackage + "engine.LpSolveContext")
        val context = requireNotNull(call(contextType.getField("Companion").get(null), "getProduction"))
        return Class.forName(lpPackage + "SourceLpBudget").constructors.single { it.parameterCount == 6 }
            .newInstance(GUIDE_OPERATIONS, null, { context }, { _: Any -> }, null, funding)
    }

    @Suppress("SpreadOperator") // Java reflection requires varargs.
    private fun call(owner: Any, name: String, vararg args: Any?): Any? =
        NumericalResidualProfile.call(owner, name, *args)
}
