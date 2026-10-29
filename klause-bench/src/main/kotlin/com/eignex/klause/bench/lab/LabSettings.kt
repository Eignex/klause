package com.eignex.klause.bench.lab

import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.lab.bench.BenchResources

internal data class LabSettings(val parameters: Map<String, String>) {
    val selections: List<Map<String, String>> = parameters["selections"]?.let {
        com.eignex.lab.bench.BenchProtocol.json.decodeFromString<List<Map<String, String>>>(it)
    } ?: listOf(parameters.filterKeys { it in SELECTORS }.ifEmpty { mapOf("suite" to "dimacs-core", "name" to "php4") })
    val cpus = (parameters["cpus"] ?: "1").toInt().also { require(it in 1..1024) }
    val timeout = (parameters["timeout-ms"] ?: "10000").toLong().also { require(it in 1..300_000_000) }
    val memory = (parameters["memory-mib"] ?: "4096").toLong().also { require(it in 2048..Int.MAX_VALUE.toLong()) }
    val compileTimeout = (parameters["compile-timeout-sec"] ?: "60").toInt().also { require(it in 1..3600) }
    val repeats = (parameters["repeats"] ?: "1").toInt().also { require(it in 1..10000) }
    val seeds = (parameters["solver-seeds"] ?: "1").split(',').map { it.trim().toLong() }.distinct()
    val engines = (parameters["engines"] ?: "default").split(',').map { it.trim().lowercase() }.distinct()
    val inputOnly = parameters["input-only"]?.toBooleanStrict() ?: false
    val debug = parameters["debug"]?.toBooleanStrict() ?: true
    val measurement = parameters["measurement"] ?: "none"
    val heap = (parameters["heap-mib"] ?: (memory - 1536).toString()).toLong()
    val gc = parameters["gc"] ?: "default"
    val wallTimeout = (timeout * 2 + 20_999) / 1000 + 2 * compileTimeout + 120

    init {
        require(selections.isNotEmpty())
        require(selections.all { it.isNotEmpty() && it.keys.all { key -> key in SELECTORS } })
        val unknown = parameters.keys.filterNot { it in SELECTORS || it in OPTIONS || it.startsWith("param.") }
        require(unknown.isEmpty()) { "Unknown Klause bench parameters: $unknown" }
        require(engines.all { it in ENGINES }) { "Unknown engine; supported: $ENGINES" }
        require(measurement in setOf("none", "resources", "jfr", "compilation", "compilation+log"))
        require(heap in 512..memory - 1536) { "heap-mib must reserve 1536 MiB for harness and native memory" }
        require(gc in setOf("default", "serial", "parallel", "g1", "z"))
        require(wallTimeout <= 604800) { "Combined solve and compiler ceilings exceed the lab's seven-day case limit" }
        parameters["exact"]?.toBooleanStrict()
        parameters["complete-assignment"]?.toBooleanStrict()
        parameters["lp"]?.let { require(runCatching { LpConfig.parse(it) }.isSuccess) { "Invalid LP configuration" } }
        parameters["presolve"]?.let {
            require(runCatching { PresolveConfig.parse(it) }.isSuccess) { "Invalid presolve configuration" }
        }
        for (key in listOf("max", "per-family")) parameters[key]?.let { require(it.toInt() > 0) }
        parameters["seed"]?.toLong()
        parameters.filterKeys { it.startsWith("param.") }.forEach { (key, value) ->
            require(key !in setOf("param.source-validation", "param.source-validation-gzip")) {
                "$key is a lab control; use bench.sourceValidation outside bench.request.parameters"
            }
            require(key.removePrefix("param.").matches(Regex("[A-Za-z][A-Za-z0-9_.-]*")) && value.isNotBlank())
            require(value.none(Char::isISOControl))
        }
    }

    fun resources() = BenchResources(
        cpus,
        memory,
        wallTimeout,
        requiredTags = if (inputOnly) emptySet() else setOf("platform:linux", "arch:x86_64"),
    )

    fun caseParameters(engine: String, seed: Long): Map<String, String> =
        parameters.filterKeys { it.startsWith("param.") || it in setOf("lp", "presolve", "exact") } + mapOf(
            "input-only" to inputOnly.toString(),
            "engine" to engine,
            "seed" to seed.toString(),
            "timeout-ms" to timeout.toString(),
            "compile-timeout-sec" to compileTimeout.toString(),
            "debug" to debug.toString(),
            "measurement" to measurement,
            "heap-mib" to heap.toString(),
            "gc" to gc,
            "complete-assignment" to (parameters["complete-assignment"] ?: "false"),
        )

    companion object {
        val SELECTORS = setOf("suite", "set", "name", "category", "tag", "kind", "per-family", "seed", "max", "balance")
        private val OPTIONS = setOf(
            "input-only", "selections", "engines", "solver-seeds", "repeats", "cpus", "memory-mib", "timeout-ms", "lp",
            "presolve", "exact", "debug", "measurement", "heap-mib", "gc", "compile-timeout-sec", "complete-assignment",
        )
        private val ENGINES = setOf(
            "default", "fixed", "backtrack", "bt", "cp", "localsearch", "ls", "local-search",
            "mixed", "portfolio", "pf", "alns", "lns", "hybrid-lns",
        )
    }
}
