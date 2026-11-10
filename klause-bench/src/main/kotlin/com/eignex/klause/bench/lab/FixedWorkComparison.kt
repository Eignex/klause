package com.eignex.klause.bench.lab

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.system.exitProcess

/**
 * Pairs fixed-work SAT rows from `lab cases --json` on stdin; see `klause-bench/scripts/compare-fixed-work.sh`.
 * Prints one JSON line per row, then a summary line.
 */
@OptIn(ExperimentalSerializationApi::class)
fun main(args: Array<String>) {
    val declaration = runCatching { FixedWorkComparison.Declaration.parse(args.toList()) }.getOrElse { failure ->
        System.err.println("compare-fixed-work: ${failure.message}")
        exitProcess(2)
    }
    val cases = Json.decodeToSequence(System.`in`, JsonElement.serializer(), DecodeSequenceMode.AUTO_DETECT)
        .map { it as? JsonObject ?: error("each case must be a JSON object") }
    FixedWorkComparison.compare(cases, declaration).forEach { println(it) }
}

/**
 * Classifies each fixed-work row by validity first and trajectory second. A row is valid only when each arm has
 * exactly one retained, completed and comparable measurement, both arms carry the same accepted worker receipt and
 * execution frame, and source, seed, work, measurement and runtime identities agree except where [Declaration.allowed]
 * permits a difference. Contradicting evidence makes a row `invalid`, an arm that did not complete comparable work
 * makes it `incomparable`, and absent evidence makes it `unverifiable`; such rows keep their trajectory diagnostics
 * but never contribute a ratio.
 */
internal object FixedWorkComparison {
    /** The two compared arms and the identity facets allowed to differ between them. */
    internal data class Declaration(val control: String, val candidate: String, val allowed: Set<String> = emptySet()) {
        init {
            require(control != candidate) { "control and candidate must be different arms" }
            val unknown = allowed - ALLOWABLE
            require(unknown.isEmpty()) { "cannot allow $unknown; allowable differences are ${ALLOWABLE.sorted()}" }
        }

        companion object {
            fun parse(args: List<String>): Declaration {
                require(args.size >= 2 && args.size % 2 == 0 && args.drop(2).chunked(2).all { it[0] == "--allow" }) {
                    "usage: CONTROL CANDIDATE [--allow FACET]..."
                }
                val allowed = args.drop(2).chunked(2).flatMap { it[1].split(',') }.filter(String::isNotBlank)
                return Declaration(args[0], args[1], allowed.toSet())
            }
        }
    }

    fun compare(cases: Sequence<JsonObject>, declaration: Declaration): List<JsonObject> {
        val retained = cases.map(::retain).toList()
        val repeated = retained.mapNotNull { it.index }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val rows = retained.groupBy { it.key to it.repeat }
            .toSortedMap(compareBy({ it.first.orEmpty() }, { it.second?.toLongOrNull() }, { it.second.orEmpty() }))
            .map { (group, row) -> FixedWorkRow(group.first, group.second, row, declaration, repeated).json() }
        return rows + summary(rows, declaration)
    }

    private fun summary(rows: List<JsonObject>, declaration: Declaration): JsonObject {
        fun count(type: String) = rows.count { text(it, "class") == type }
        val matched = rows.filter { text(it, "class") == MATCHED }
        fun median(field: String) = median(matched.mapNotNull { number(it, field) })
        return buildJsonObject {
            put("summary", true)
            put("control", declaration.control)
            put("candidate", declaration.candidate)
            putJsonArray("allowed") { declaration.allowed.sorted().forEach { add(JsonPrimitive(it)) } }
            put("rows", rows.size)
            put("matched", count(MATCHED))
            put("completedDiverged", count(COMPLETED_DIVERGED))
            put("diverged", count(DIVERGED))
            put("incomparable", count(INCOMPARABLE))
            put("invalid", count(INVALID))
            put("unverifiable", count(UNVERIFIABLE))
            put("incomplete", count(INCOMPLETE))
            put("matchedMedianElapsedRatio", median("elapsedRatio"))
            put("matchedMedianCpuRatio", median("cpuRatio"))
        }
    }

    /** Drops warmup records, metrics, artifacts and output; keeps the warmup count the identity check reads. */
    private fun retain(line: JsonObject): RetainedCase {
        val record = line["record"] as? JsonObject
        val details = record?.get("details") as? JsonObject
        val trimmed = details?.let { JsonObject(it - "warmups" + ("warmupCount" to count(it["warmups"]))) }
        val slim = record?.let { JsonObject(it - setOf("metrics", "artifacts") + ("details" to (trimmed ?: JsonNull))) }
        val case = JsonObject(line - "stdout" + ("record" to (slim ?: JsonNull)))
        return RetainedCase(
            index = text(case, "index")?.toIntOrNull(),
            arm = text(case, "arm"),
            key = text(case, "problem", "benchCase", "attributes", "comparisonKey"),
            repeat = text(case, "repeat"),
            json = case,
        )
    }

    private fun count(value: JsonElement?): JsonElement =
        (value as? JsonArray)?.let { JsonPrimitive(it.size) } ?: JsonNull

    private fun median(values: List<Double>): Double? {
        val sorted = values.sorted()
        return when {
            sorted.isEmpty() -> null
            sorted.size % 2 == 1 -> sorted[sorted.size / 2]
            else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        }
    }

    /** The primitive at [path] as text, or null when any step is absent or JSON null. */
    fun text(value: JsonElement?, vararg path: String): String? =
        (element(value, *path) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    fun number(value: JsonElement?, vararg path: String): Double? = text(value, *path)?.toDoubleOrNull()

    fun element(value: JsonElement?, vararg path: String): JsonElement? =
        path.fold(value) { node, key -> (node as? JsonObject)?.get(key) }?.takeUnless { it is JsonNull }

    const val MATCHED = "matched"
    const val COMPLETED_DIVERGED = "completed-diverged"
    const val DIVERGED = "diverged"
    const val INCOMPARABLE = "incomparable"
    const val INVALID = "invalid"
    const val UNVERIFIABLE = "unverifiable"
    const val INCOMPLETE = "incomplete"

    /** Facets a declaration may allow to differ: the build identity and the treatment parameters. */
    val ALLOWABLE = setOf("revision", "harness", "library", "recipe", "deadline-seconds", "trace-rows", "heap-mib")
}

/** One retained case, keyed by its lab case index, arm, comparison key and repeat. */
internal data class RetainedCase(
    val index: Int?,
    val arm: String?,
    val key: String?,
    val repeat: String?,
    val json: JsonObject,
)
