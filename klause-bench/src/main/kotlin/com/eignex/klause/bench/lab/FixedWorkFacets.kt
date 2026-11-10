package com.eignex.klause.bench.lab

import com.eignex.klause.bench.lab.FixedWorkComparison.element
import com.eignex.klause.bench.lab.FixedWorkComparison.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The source, seed, work, measurement, runtime and build identities of a fixed-work pair. Each facet gathers every
 * claim an arm makes about it, from the planned parameters through the retained record; claims within one arm must
 * agree and at least one must be present, and the arms must agree unless a declared difference [Facet.allowedBy] it.
 */
internal object FixedWorkFacets {
    /** One identity, its claims within an arm, and the declared differences that let it differ between arms. */
    private class Facet(val name: String, val allowedBy: Set<String>, val claims: (JsonObject) -> List<String?>)

    fun check(
        control: JsonObject,
        candidate: JsonObject,
        allowed: Set<String>,
        invalid: MutableList<String>,
        unverifiable: MutableList<String>,
    ) {
        for (facet in FACETS) {
            val agreed = listOf(CONTROL to control, CANDIDATE to candidate).map { (role, case) ->
                val claims = facet.claims(case).filterNotNull().distinct()
                if (claims.isEmpty()) unverifiable += "$role ${facet.name} unavailable"
                if (claims.size > 1) invalid += "$role ${facet.name} disagrees within the arm: ${shown(claims)}"
                claims.singleOrNull()
            }
            if (null !in agreed && agreed[0] != agreed[1] && facet.allowedBy.none { it in allowed }) {
                invalid += "${facet.name} differs: ${shown(agreed.filterNotNull())}"
            }
        }
        val parameters = listOf(control, candidate).map(::parameters)
        for (name in parameters.flatMap { it.keys }.distinct().sorted() - COVERED) {
            if (name !in allowed && parameters[0][name] != parameters[1][name]) {
                invalid += "undeclared parameter difference $name"
            }
        }
    }

    private fun shown(values: List<String>): String =
        if (values.all { it.length <= SHOWN }) values.joinToString(" vs ") else "${values.size} values"

    private fun parameters(case: JsonObject): Map<String, String?> =
        (element(case, "problem", "benchCase", "parameters") as? JsonObject).orEmpty().mapValues { text(it.value) }

    private fun parameter(case: JsonObject, name: String): String? =
        text(case, "problem", "benchCase", "parameters", name)

    private fun details(case: JsonObject, vararg path: String): String? = text(case, "record", "details", *path)

    private fun measured(case: JsonObject, vararg path: String): String? =
        text(case, "record", "details", "measured", *path)

    private fun json(value: JsonElement?): String? = value?.toString()

    private fun model(case: JsonObject): JsonObject? =
        (element(case, "problem", "benchCase", "inputs") as? JsonArray)?.filterIsInstance<JsonObject>()
            ?.singleOrNull { text(it, "role") == "model" }

    private fun seconds(millis: String?): String? = millis?.toLongOrNull()?.let {
        if (it % MILLIS == 0L) (it / MILLIS).toString() else "${it}ms"
    }

    private const val CONTROL = "control"
    private const val CANDIDATE = "candidate"
    private const val SHOWN = 64
    private const val MILLIS = 1000L

    private val FACETS = listOf(
        Facet("source hash", emptySet()) {
            listOf(
                parameter(it, "sha256"),
                text(model(it), "sha256"),
                details(it, "identity", "declaredSha256"),
                details(it, "identity", "plainSha256"),
                measured(it, "sourceSha256"),
            )
        },
        Facet("source path", emptySet()) {
            listOf(parameter(it, "source-path"), text(model(it), "path"), details(it, "identity", "sourcePath"))
        },
        Facet("seed", emptySet()) {
            listOf(
                text(it, "seed"),
                parameter(it, "seed"),
                text(it, "problem", "benchCase", "attributes", "seed"),
                measured(it, "seed"),
            )
        },
        Facet("work unit", emptySet()) { listOf(parameter(it, "unit"), details(it, "unit"), measured(it, "unit")) },
        Facet("work limit", emptySet()) { listOf(parameter(it, "limit"), details(it, "limit"), measured(it, "limit")) },
        Facet("measurement", emptySet()) {
            listOf(parameter(it, "measurement"), details(it, "measurement"), measured(it, "jvmState"))
        },
        Facet("warmups", emptySet()) {
            listOf(parameter(it, "warmups"), details(it, "warmupCount"), measured(it, "index"))
        },
        Facet("recipe", setOf("recipe")) {
            listOf(parameter(it, "recipe"), details(it, "recipe"), measured(it, "recipe"))
        },
        Facet("engine options", setOf("recipe")) {
            listOf(json(element(it, "record", "details", "measured", "engine")))
        },
        Facet("deadline", setOf("deadline-seconds")) {
            listOf(
                parameter(it, "deadline-seconds"),
                details(it, "deadlineSeconds"),
                seconds(measured(it, "deadlineMillis")),
            )
        },
        Facet("trace rows", setOf("trace-rows")) {
            listOf(parameter(it, "trace-rows"), measured(it, "trajectory", "rowLimit"))
        },
        Facet("runtime", setOf("heap-mib")) { listOf(json(element(it, "record", "details", "runtime"))) },
        Facet("case resources", setOf("heap-mib", "deadline-seconds")) {
            listOf(json(element(it, "problem", "benchCase", "resources")))
        },
        Facet("revision", setOf("revision")) { listOf(details(it, "identity", "revision")) },
        Facet("solver library", setOf("revision")) {
            val jars = element(it, "record", "details", "identity", "engine") as? JsonObject
            listOf(json(jars?.takeIf { jar -> jar.isNotEmpty() }))
        },
        Facet("harness fingerprint", setOf("harness")) { listOf(details(it, "identity", "harnessFingerprint")) },
        Facet("library fingerprint", setOf("library")) { listOf(details(it, "identity", "libraryFingerprint")) },
    )

    /** Parameters whose agreement the facets already check against the record. */
    private val COVERED = setOf(
        "sha256", "source-path", "seed", "unit", "limit", "measurement", "warmups", "recipe", "deadline-seconds",
        "trace-rows",
    )
}
