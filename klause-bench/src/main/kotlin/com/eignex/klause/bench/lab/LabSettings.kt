package com.eignex.klause.bench.lab

import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.presolve.PresolveEmphasis
import com.eignex.lab.bench.BenchResources

internal data class LabSettings(val parameters: Map<String, String>) {
    val cpus = (parameters["cpus"] ?: "1").toInt().also { require(it in 1..1024) }
    val timeout = (parameters["timeout-ms"] ?: "10000").toLong().also { require(it in 1..300_000_000) }
    val memory = (parameters["memory-mib"] ?: "4096").toLong().also { require(it in 2048..Int.MAX_VALUE.toLong()) }
    val compileTimeout = (parameters["compile-timeout-sec"] ?: "60").toInt().also { require(it in 1..3600) }
    val repeats = (parameters["repeats"] ?: "1").toInt().also { require(it in 1..10000) }
    val seeds = (parameters["solver-seeds"] ?: "1").split(',').map { it.trim().toLong() }.distinct()
    val engines = (parameters["engines"] ?: "default").split(',').map { it.trim().lowercase() }.distinct()
    val debug = parameters["debug"]?.toBooleanStrict() ?: true
    val measurement = parameters["measurement"] ?: "none"
    val heap = (parameters["heap-mib"] ?: (memory - 1536).toString()).toLong()
    val gc = parameters["gc"] ?: "default"
    val wallTimeout = (timeout * 2 + 20_999) / 1000 + 2 * compileTimeout + 120

    init {
        val unknown = parameters.keys.filterNot { it in SELECTORS || it in OPTIONS || it.startsWith("param.") }
        require(unknown.isEmpty()) { "Unknown Klause bench parameters: $unknown" }
        require(engines.all { it in ENGINES }) { "Unknown engine; supported: $ENGINES" }
        require(measurement in setOf("none", "resources", "jfr", "compilation", "compilation+log"))
        require(heap in 512..memory - 1536) { "heap-mib must reserve 1536 MiB for harness and native memory" }
        require(gc in setOf("default", "serial", "parallel", "g1", "z"))
        require(wallTimeout <= 604800) { "Combined solve and compiler ceilings exceed the lab's seven-day case limit" }
        parameters["exact"]?.toBooleanStrict()
        parameters["lp"]?.let { require(runCatching { LpConfig.parse(it) }.isSuccess) { "Invalid LP configuration" } }
        parameters["presolve"]?.let { requireNotNull(PresolveEmphasis.fromId(it)) { "Unknown presolve emphasis" } }
        for (key in listOf("max", "per-family")) parameters[key]?.let { require(it.toInt() > 0) }
        parameters["seed"]?.toLong()
        parameters.filterKeys { it.startsWith("param.") }.forEach { (key, value) ->
            require(key.removePrefix("param.").matches(Regex("[A-Za-z][A-Za-z0-9_.-]*")) && value.isNotBlank())
            require(value.none(Char::isISOControl))
        }
    }

    fun resources() = BenchResources(
        cpus,
        memory,
        wallTimeout,
        requiredTags = setOf("platform:linux", "arch:x86_64"),
    )

    fun caseParameters(engine: String, seed: Long): Map<String, String> =
        parameters.filterKeys { it.startsWith("param.") || it in setOf("lp", "presolve", "exact") } + mapOf(
            "engine" to engine,
            "seed" to seed.toString(),
            "timeout-ms" to timeout.toString(),
            "compile-timeout-sec" to compileTimeout.toString(),
            "debug" to debug.toString(),
            "measurement" to measurement,
            "heap-mib" to heap.toString(),
            "gc" to gc,
        )

    companion object {
        val SELECTORS = setOf("suite", "set", "name", "category", "tag", "kind", "per-family", "seed", "max", "balance")
        private val OPTIONS = setOf(
            "engines", "solver-seeds", "repeats", "cpus", "memory-mib", "timeout-ms", "lp",
            "presolve", "exact", "debug", "measurement", "heap-mib", "gc", "compile-timeout-sec",
        )
        private val ENGINES = setOf(
            "default", "fixed", "backtrack", "bt", "cp", "localsearch", "ls", "local-search",
            "mixed", "portfolio", "pf", "alns", "lns", "hybrid-lns",
        )
    }
}
