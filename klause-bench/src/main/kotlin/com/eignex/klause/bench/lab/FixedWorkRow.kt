package com.eignex.klause.bench.lab

import com.eignex.klause.bench.lab.FixedWorkComparison.COMPLETED_DIVERGED
import com.eignex.klause.bench.lab.FixedWorkComparison.DIVERGED
import com.eignex.klause.bench.lab.FixedWorkComparison.Declaration
import com.eignex.klause.bench.lab.FixedWorkComparison.INCOMPARABLE
import com.eignex.klause.bench.lab.FixedWorkComparison.INCOMPLETE
import com.eignex.klause.bench.lab.FixedWorkComparison.INVALID
import com.eignex.klause.bench.lab.FixedWorkComparison.MATCHED
import com.eignex.klause.bench.lab.FixedWorkComparison.UNVERIFIABLE
import com.eignex.klause.bench.lab.FixedWorkComparison.element
import com.eignex.klause.bench.lab.FixedWorkComparison.number
import com.eignex.klause.bench.lab.FixedWorkComparison.text
import com.eignex.klause.bench.profile.FixedWorkSatMeasurement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** The validity and trajectory classification of one fixed-work row: every retained case of a key and repeat. */
internal class FixedWorkRow(
    private val key: String?,
    private val repeat: String?,
    private val cases: List<RetainedCase>,
    private val declaration: Declaration,
    private val repeated: Set<Int>,
) {
    private val invalid = mutableListOf<String>()
    private val incomparable = mutableListOf<String>()
    private val unverifiable = mutableListOf<String>()
    private val absent = mutableListOf<String>()

    /** How the two arms' measurements relate, with the ratios a valid row of this kind reports. */
    private data class Trajectory(
        val type: String,
        val elapsedRatio: Double? = null,
        val cpuRatio: Double? = null,
        val divergence: Pair<String?, String?>? = null,
    )

    fun json(): JsonObject {
        val controls = arm(declaration.control, CONTROL)
        val candidates = arm(declaration.candidate, CANDIDATE)
        if (key == null) invalid += "case without a comparison key"
        cases.filter { it.index == null }.forEach { invalid += "${it.arm} case without an index" }
        cases.mapNotNull { it.index }.distinct().filter { it in repeated }
            .forEach { invalid += "case $it retained more than once" }
        val control = controls.singleOrNull()?.json
        val candidate = candidates.singleOrNull()?.json
        val trajectory = if (control != null && candidate != null) pair(control, candidate) else null
        val type = when {
            invalid.isNotEmpty() -> INVALID
            absent.isNotEmpty() -> INCOMPLETE
            incomparable.isNotEmpty() -> INCOMPARABLE
            unverifiable.isNotEmpty() || trajectory == null -> UNVERIFIABLE
            else -> trajectory.type
        }
        val ratios = type == MATCHED || type == COMPLETED_DIVERGED
        return buildJsonObject {
            put("key", key)
            put("repeat", repeat?.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(repeat))
            put("class", type)
            putJsonArray("reasons") {
                (invalid + absent + incomparable + unverifiable).forEach { add(JsonPrimitive(it)) }
            }
            put("trajectory", trajectory?.type)
            if (ratios) {
                put("elapsedRatio", trajectory?.elapsedRatio)
                if (type == MATCHED) put("cpuRatio", trajectory?.cpuRatio)
            }
            trajectory?.divergence?.let { (first, common) ->
                put("firstDifferentCheckpointEvents", first?.toLongOrNull())
                put("lastCommonCheckpointEvents", common?.toLongOrNull())
            }
            putJsonArray("indices") { cases.mapNotNull { it.index }.sorted().forEach { add(JsonPrimitive(it)) } }
            put("control", control?.let(::summary) ?: JsonNull)
            put("candidate", candidate?.let(::summary) ?: JsonNull)
        }
    }

    private fun arm(name: String, role: String): List<RetainedCase> {
        val found = cases.filter { it.arm == name }
        if (found.isEmpty()) absent += "$role arm $name not retained"
        if (found.size > 1) invalid += "$role arm $name retained ${found.size} times"
        return found
    }

    private fun pair(control: JsonObject, candidate: JsonObject): Trajectory? {
        val comparable = listOf(measurement(control, CONTROL), measurement(candidate, CANDIDATE)).all { it }
        if (listOf(control, candidate).all(::recorded)) {
            attempt(control, candidate)
            FixedWorkFacets.check(control, candidate, declaration.allowed, invalid, unverifiable)
        }
        return if (comparable) trajectory(control, candidate) else null
    }

    /** Whether [case] completed comparable fixed work, from the case, record, process and measured states. */
    private fun measurement(case: JsonObject, role: String): Boolean {
        val details = element(case, "record", "details")
        val measured = element(details, "measured")
        val stop = text(measured, "stop")
        val reasons = when {
            text(case, "status") != DONE -> listOf("$role case ended ${text(case, "status")}")

            text(case, "record", "status") != SUCCESS ->
                listOf("$role record ${text(case, "record", "status")}: ${text(case, "record", "message")}")

            !recorded(case) -> {
                invalid += "$role record schema ${text(details, "schema")} is not ${FixedWorkSatMeasurement.SCHEMA}"
                return false
            }

            text(measured, "role") != "measured" -> listOf("$role has no measured invocation")

            else -> buildList {
                text(details, "process", "exit").takeIf { it != "0" }?.let { add("$role child exited with $it") }
                if (text(details, "process", "emergencyKilled") != "false") add("$role reached the process ceiling")
                if (listOf(details, measured).any { text(it, "interrupted") != "false" }) add("$role interrupted")
                if (listOf(details, measured).any { text(it, "comparable") != "true" }) add("$role not comparable")
                when (stop) {
                    LIMIT -> if (reached(case) != true) add("$role stopped at the limit without reaching it")
                    VERDICT -> text(measured, "result").let { if (it !in RESULTS) add("$role verdict $it") }
                    else -> add("$role stopped at $stop")
                }
            }
        }
        incomparable += reasons
        return reasons.isEmpty()
    }

    /** One accepted worker attempt and execution frame; the host alone does not establish it. */
    private fun attempt(control: JsonObject, candidate: JsonObject) {
        val arms = listOf(CONTROL to control, CANDIDATE to candidate)
        val hosts = arms.map { (role, case) ->
            text(case, "host").also { if (it == null) unverifiable += "$role host unavailable" }
        }
        if (null !in hosts && hosts.distinct().size > 1) invalid += "arms ran on 2 machines"
        val receipts = arms.map { (role, case) ->
            receipt(case).also { if (it == null) unverifiable += "$role has no accepted worker receipt" }
        }
        if (null !in receipts && receipts.distinct().size > 1) invalid += "arms come from 2 worker attempts"
        val frames = arms.map { (role, case) ->
            (element(case, "record", "labExecution", "frame") as? JsonObject)
                .also { if (it == null) unverifiable += "$role execution frame unavailable" }
        }
        if (null !in frames && frames.distinct().size > 1) invalid += "arms ran under 2 execution frames"
        val iteration = frames.filterNotNull().distinct().singleOrNull()?.let { text(it, "iterationCases") }
        val retained = cases.mapNotNull { it.index }.distinct().size
        if (iteration != null && iteration != retained.toString()) {
            invalid += "worker iteration ran $iteration cases for $retained retained cases"
        }
    }

    private fun receipt(case: JsonObject): String? {
        val execution = element(case, "record", "labExecution")
        return text(execution, "receiptSha256")
            ?.takeIf { text(execution, "policy") == RECEIPT_POLICY && it.matches(SHA256) }
    }

    /**
     * Rows that reached the limit match on their limit-point digests and compare limit-point times; rows that both
     * completed with a verdict before it match on full digests and compare the measured interval.
     */
    private fun trajectory(control: JsonObject, candidate: JsonObject): Trajectory? {
        val reached = listOf(reached(control), reached(candidate))
        val verdicts = listOf(control, candidate).all { text(measured(it), "stop") == VERDICT }
        return when {
            reached.all { it == true } -> {
                val same = same(control, candidate, ::prefix, "limit-point digest") ?: return null
                if (!same) return Trajectory(DIVERGED, divergence = divergence(control, candidate))
                val times = times(control, candidate, "limitPoint", "limit-point")
                Trajectory(MATCHED, times?.first, times?.second).takeIf { times != null }
            }

            reached.none { it == true } && verdicts -> {
                val same = same(control, candidate, ::digest, "trajectory digest") ?: return null
                val times = times(control, candidate, "measured", "measured") ?: return null
                if (same) {
                    Trajectory(MATCHED, times.first, times.second)
                } else {
                    Trajectory(COMPLETED_DIVERGED, times.first, divergence = divergence(control, candidate))
                }
            }

            else -> Trajectory(DIVERGED, divergence = divergence(control, candidate))
        }
    }

    /** Whether both arms' [read] digests are present and equal; null, with a reason, when either is absent. */
    private fun same(
        control: JsonObject,
        candidate: JsonObject,
        read: (JsonObject) -> String?,
        name: String,
    ): Boolean? {
        val digests = listOf(CONTROL to control, CANDIDATE to candidate).map { (role, case) ->
            read(case).also { if (it == null) unverifiable += "$role $name unavailable" }
        }
        return if (null in digests) null else digests[0] == digests[1]
    }

    /** Candidate/control wall and thread CPU ratios over the interval at [field]; null when a time is absent. */
    private fun times(
        control: JsonObject,
        candidate: JsonObject,
        field: String,
        name: String,
    ): Pair<Double?, Double?>? {
        val values = listOf(CONTROL to control, CANDIDATE to candidate).map { (role, case) ->
            val wall = number(measured(case), field, "elapsedNs")
            val cpu = number(measured(case), field, "threadCpuNs")
            if (wall == null || cpu == null) unverifiable += "$role $name time unavailable"
            wall to cpu
        }
        if (values.any { it.first == null || it.second == null }) return null
        return ratio(values[0].first, values[1].first) to ratio(values[0].second, values[1].second)
    }

    private fun ratio(control: Double?, candidate: Double?): Double? =
        if (control != null && candidate != null && control > 0) candidate / control else null

    /** Events at the first checkpoint whose digests differ, and at the last one before it both arms share. */
    private fun divergence(control: JsonObject, candidate: JsonObject): Pair<String?, String?> {
        val (left, right) = listOf(control, candidate).map {
            (element(measured(it), "trajectory", "checkpoints") as? JsonArray).orEmpty()
        }
        val common = minOf(left.size, right.size)
        val first = (0 until common).firstOrNull { index ->
            val digest = text(left[index], "digest")
            digest == null || digest != text(right[index], "digest")
        }
        val shared = (first ?: common) - 1
        return first?.let { text(left[it], "events") } to left.getOrNull(shared)?.let { text(it, "events") }
    }

    private fun summary(case: JsonObject): JsonObject {
        val measured = measured(case)
        val interval = if (reached(case) == true) "limitPoint" else "measured"
        return buildJsonObject {
            put("index", text(case, "index")?.toLongOrNull())
            put("status", text(case, "status"))
            put("record", text(case, "record", "status"))
            put("host", text(case, "host"))
            put("receipt", receipt(case))
            put("revision", text(case, "record", "details", "identity", "revision"))
            put("measurement", text(case, "record", "details", "measurement"))
            put("stop", text(measured, "stop"))
            put("result", text(measured, "result"))
            put("prefixDigest", prefix(case))
            put("digest", digest(case))
            put("timeNs", number(measured, interval, "elapsedNs"))
            put("cpuNs", number(measured, interval, "threadCpuNs"))
            put("processCpuNs", number(measured, "measured", "processCpuNs"))
            put("preparationNs", number(measured, "preparation", "elapsedNs"))
            put("work", number(measured, "counters", "work"))
            put("decisions", number(measured, "counters", "decisions"))
            put("selectorConflicts", number(measured, "trajectory", "conflicts"))
        }
    }

    private fun recorded(case: JsonObject): Boolean =
        text(case, "record", "details", "schema") == FixedWorkSatMeasurement.SCHEMA

    private fun measured(case: JsonObject): JsonElement? = element(case, "record", "details", "measured")

    private fun prefix(case: JsonObject): String? = text(measured(case), "trajectory", "prefix", "digest")

    private fun digest(case: JsonObject): String? = text(measured(case), "trajectory", "digest")

    private fun reached(case: JsonObject): Boolean? = measured(case)?.let {
        text(it, "limitReached") == "true" && text(it, "limitPoint", "reached") == "true"
    }

    private companion object {
        const val CONTROL = "control"
        const val CANDIDATE = "candidate"
        const val DONE = "DONE"
        const val SUCCESS = "success"
        const val LIMIT = "limit"
        const val VERDICT = "verdict"
        const val RECEIPT_POLICY = "worker-receipt-v1"
        val RESULTS = setOf("Sat", "Unsat")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
