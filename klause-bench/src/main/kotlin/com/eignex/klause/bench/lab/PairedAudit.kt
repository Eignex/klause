package com.eignex.klause.bench.lab

import com.eignex.klause.bench.metric.SolveRecord
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.lab.bench.BenchCase
import com.eignex.lab.bench.BenchProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.Reader
import kotlin.system.exitProcess

/**
 * Audits a retained paired lab comparison without changing it; see `klause-bench/scripts/paired-audit.sh`.
 * Exits 0 when every retained or expected row is accepted, 1 when a row is invalid or missing.
 */
fun main(args: Array<String>) {
    val report = runCatching { PairedAudit.run(args.toList()) }.getOrElse { failure ->
        System.err.println("paired-audit: ${failure.message}")
        exitProcess(2)
    }
    println(Reports.json.encodeToString(PairedAuditReport.serializer(), report))
    exitProcess(if (report.accepted) 0 else 1)
}

/**
 * One retained case row, reduced to the identities, controls, outcomes and costs the audit compares.
 * Statistics and witnesses are not kept, so retained jobs stream in bounded memory.
 */
internal data class AuditedCase(
    val index: Int,
    val status: String,
    val arm: String?,
    val rowKey: String,
    val host: String?,
    val seeds: List<String?>,
    val inputs: Map<String, String?>,
    val parameters: Map<String, String>,
    val record: SolveRecord?,
    val recordError: String?,
    val receipt: String?,
    val frame: JsonElement?,
    val checkState: String?,
    val checkPolicy: String?,
    val witnessBound: Boolean,
    val cliCpuSeconds: Double?,
    val adapterCpuSeconds: Double?,
    val checkerElapsedMs: Long?,
)

internal object PairedAudit {
    /**
     * What a comparison may differ in: its [arms], benchCase parameter keys set per arm ([treatments]),
     * identity facets allowed to differ between arms ([allowed]) and pinned comparison control values ([controls]).
     */
    internal data class Declaration(
        val arms: List<String>,
        val treatments: Set<String> = emptySet(),
        val allowed: Set<String> = emptySet(),
        val controls: Map<String, String> = emptyMap(),
        val rows: Int? = null,
    )

    fun run(args: List<String>): PairedAuditReport {
        val options = options(args)
        fun file(name: String) = options[name]?.single()?.let(::File)
        val request = file("request")?.let { Json.parseToJsonElement(it.readText()).jsonObject }
        val declared = request?.let(::declaration)
        val arms = options["arms"]?.flatMap { it.split(',') }?.filter(String::isNotBlank) ?: declared?.arms
        val declaration = Declaration(
            arms = requireNotNull(arms) { "declare arms with --request or --arms" },
            treatments = declared?.treatments.orEmpty() + options["treatment"].orEmpty(),
            allowed = declared?.allowed.orEmpty() + options["allow"].orEmpty(),
            controls = options["control"].orEmpty().associate { it.substringBefore('=') to it.substringAfter('=') },
            rows = options["rows"]?.single()?.toInt(),
        )
        val execution = file("execution")?.let { Json.parseToJsonElement(it.readText()).jsonArray }
        val job = file("job")?.let { Json.parseToJsonElement(it.readText()).jsonObject }
        val cases = requireNotNull(file("cases")) { "--cases lab-cases.jsonl is required" }
        return cases.bufferedReader().use { audit(it, declaration, execution, job) }
    }

    /** Arms, treatment keys and revision freedom declared by a lab experiment request's `configs`. */
    fun declaration(request: JsonObject): Declaration {
        require("grid" !in request) { "grid requests need explicit --arms and --treatment" }
        val configs = request.getValue("configs").jsonArray.map { it.jsonObject }
        val refs = configs.mapNotNull { text(it, "ref") }.distinct()
        return Declaration(
            arms = configs.map { requireNotNull(text(it, "label")) { "request config without label" } },
            treatments = configs.flatMap { it.keys }.filter { it.startsWith(PARAMETER) }
                .map { it.removePrefix(PARAMETER) }.toSet(),
            allowed = if (refs.size > 1) setOf(REVISION) else emptySet(),
        )
    }

    fun audit(
        cases: Reader,
        declaration: Declaration,
        execution: JsonArray? = null,
        job: JsonObject? = null,
    ): PairedAuditReport {
        val retained = cases.buffered().lineSequence().filter(String::isNotBlank)
            .map { parse(Json.parseToJsonElement(it).jsonObject) }.toList()
        val commands = job?.get("commands")?.jsonArray?.map { it.jsonObject }.orEmpty()
        val duplicates = retained.groupingBy { it.index }.eachCount().filterValues { it > 1 }.keys
        val sourceDrift = retained.filter { it.record != null }.groupBy { it.inputs }
            .filterValues { group -> group.map { it.record?.sourceHashes }.distinct().size > 1 }.keys
        val rows = retained.groupBy { it.rowKey }.map { (key, row) ->
            PairedRows.audit(key, row, declaration, duplicates, sourceDrift, commands)
        }
        val absent = (declaration.rows ?: rows.size) - rows.size
        val unretained = commands.map { int(it, "index") }.filter { index -> retained.none { it.index == index } }
        val attempts = execution?.let(PairedCosts::attempts)
        val jobErrors = buildList {
            if (duplicates.isNotEmpty()) add("case indices retained more than once: ${duplicates.sorted()}")
            if (absent < 0) add("${-absent} rows beyond the ${declaration.rows} expected")
            if (unretained.isNotEmpty()) add("commands without a retained case: ${unretained.sorted()}")
            attempts?.errors?.let(::addAll)
            if (attempts != null) {
                val ran = rows.count { row -> declaration.arms.all { PairedRows.ran(row.cases, it) } }
                if (attempts.committedRows != ran) {
                    add("solve attempts committed ${attempts.committedRows} rows, $ran rows ran every arm")
                }
            }
        }
        val counts = rows.groupingBy { it.state }.eachCount()
        val missing = (counts[RowState.MISSING] ?: 0) + absent.coerceAtLeast(0)
        return PairedAuditReport(
            arms = declaration.arms,
            treatments = declaration.treatments.sorted(),
            allowedDifferences = declaration.allowed.sorted(),
            complete = counts[RowState.COMPLETE] ?: 0,
            censored = counts[RowState.CENSORED] ?: 0,
            invalid = counts[RowState.INVALID] ?: 0,
            missing = missing,
            accepted = (counts[RowState.INVALID] ?: 0) == 0 && missing == 0 && jobErrors.isEmpty(),
            jobErrors = jobErrors,
            outcomes = declaration.arms.associateWith { arm -> PairedCosts.outcomes(rows, arm) },
            costs = declaration.arms.associateWith { arm -> PairedCosts.costs(rows, arm, commands) },
            retainedCosts = PairedCosts.costs(retained, commands),
            attempts = attempts,
            rows = rows,
        )
    }

    private fun parse(line: JsonObject): AuditedCase {
        val problem = line.getValue("problem").jsonObject
        val case = BenchProtocol.json.decodeFromJsonElement(BenchCase.serializer(), problem.getValue("benchCase"))
        val record = line["record"] as? JsonObject
        val decoded = record?.let {
            runCatching { Reports.json.decodeFromJsonElement(SolveRecord.serializer(), it) }
        }
        val solve = decoded?.getOrNull()
        val controls = solve?.comparisonControls.orEmpty()
        val check = record?.get("labCheck") as? JsonObject
        val metrics = (record?.get("labBench") as? JsonObject)?.get("metrics") as? JsonArray
        val adapter = (record?.get("labAdapterResources") as? JsonObject)?.takeIf { text(it, "state") == "complete" }
        val adapterResources = ((adapter?.get("receipt") as? JsonObject)?.get("resources") as? JsonObject)
            ?.let { resources -> resources.keys.associateWith { text(resources, it) } }
        val cliResources = metrics?.map { it.jsonObject }?.mapNotNull { metric ->
            text(metric, "name")?.let { it to text(metric, "value") }
        }?.toMap()
        val execution = record?.get("labExecution") as? JsonObject
        val witness = solve?.finalWitness?.let { sha256(it.toByteArray()) }
        val seed = text(line, "seed")
        val repeat = text(line, "repeat")
        val pair = case.attributes["pairKey"] ?: requireNotNull(text(problem, "problem"))
        return AuditedCase(
            index = int(line, "index"),
            status = requireNotNull(text(line, "status")),
            arm = text(line, "arm"),
            rowKey = listOf(pair, seed, case.attributes["repeat"], repeat).joinToString(KEY_SEPARATOR),
            host = text(line, "host"),
            seeds = listOf(seed, solve?.seed?.toString(), case.attributes["seed"], controls["solverSeed"]),
            inputs = case.inputs.associate { it.role to it.sha256 },
            parameters = case.parameters,
            record = solve,
            recordError = decoded?.exceptionOrNull()?.let { "record is not a solver record: ${it.message}" }
                ?: if (record == null) "no record" else null,
            receipt = text(execution, "receiptSha256"),
            frame = execution?.get("frame"),
            checkState = text(check, "state"),
            checkPolicy = text(check, "policy"),
            witnessBound = witness != null && witness == text(check, "witnessSha256"),
            cliCpuSeconds = cpu(cliResources),
            adapterCpuSeconds = cpu(adapterResources),
            checkerElapsedMs = check?.let { text(it, "elapsedMs")?.toLongOrNull() },
        )
    }

    private fun cpu(values: Map<String, String?>?): Double? {
        val user = values?.get("userSeconds")?.toDoubleOrNull() ?: return null
        val system = values["systemSeconds"]?.toDoubleOrNull() ?: return null
        return user + system
    }

    private fun options(args: List<String>): Map<String, List<String>> {
        require(args.size % 2 == 0) { "options are --name value pairs" }
        return args.chunked(2).groupBy({ (name, _) ->
            require(name.removePrefix("--") in OPTIONS && name.startsWith("--")) { "unknown option $name" }
            name.removePrefix("--")
        }, { it[1] })
    }

    internal fun text(value: JsonObject?, key: String): String? =
        (value?.get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    internal fun int(value: JsonObject, key: String): Int = requireNotNull(text(value, key)?.toInt()) { "no $key" }

    internal val ACCEPTED = setOf(RowState.COMPLETE, RowState.CENSORED)
    internal const val REVISION = "revision"
    internal const val KEY_SEPARATOR = "\u0000"
    private const val PARAMETER = "param."
    private val OPTIONS = setOf("cases", "request", "execution", "job", "arms", "treatment", "allow", "control", "rows")
}
