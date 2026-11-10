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

private const val PARTIAL = 3

/**
 * Audits a retained paired lab comparison without changing it; see `klause-bench/scripts/paired-audit.sh`.
 * Exits 0 when every row is accepted and the declared experiment is fully verified, 1 when a row is invalid or
 * missing, and 3 when rows are accepted but the declared treatments, revisions or cohort are only partly verifiable.
 */
fun main(args: Array<String>) {
    val report = runCatching { PairedAudit.run(args.toList()) }.getOrElse { failure ->
        System.err.println("paired-audit: ${failure.message}")
        exitProcess(2)
    }
    println(Reports.json.encodeToString(PairedAuditReport.serializer(), report))
    exitProcess(
        when {
            !report.accepted -> 1
            !report.declared.verified -> PARTIAL
            else -> 0
        },
    )
}

/**
 * One retained case row, reduced to the identities, controls, outcomes and costs the audit compares.
 * Statistics, witnesses and runtime file maps are dropped, so a large retained job stays small in memory.
 */
internal data class AuditedCase(
    val index: Int,
    val status: String,
    val arm: String?,
    val rowKey: String,
    val caseKey: String,
    val host: String?,
    val seeds: List<String?>,
    val inputs: Map<String, String?>,
    val parameters: Map<String, String>,
    val executed: Boolean,
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
     * A request adds each arm's [expected] revision and values and the [cohort]; [gaps] name what it leaves
     * unverifiable.
     */
    internal data class Declaration(
        val arms: List<String>,
        val treatments: Set<String> = emptySet(),
        val allowed: Set<String> = emptySet(),
        val controls: Map<String, String> = emptyMap(),
        val rows: Int? = null,
        val expected: Map<String, ArmExpectation> = emptyMap(),
        val cohort: Cohort? = null,
        val gaps: List<String> = emptyList(),
    )

    fun run(args: List<String>): PairedAuditReport {
        val options = options(args)
        fun file(name: String) = options[name]?.single()?.let(::File)
        val job = file("job")?.let { Json.parseToJsonElement(it.readText()).jsonObject }
        val request = file("request")?.let { Json.parseToJsonElement(it.readText()).jsonObject }
            ?.let { it["experiment"] as? JsonObject ?: it } ?: job?.get("experiment") as? JsonObject
        val plans = file("plans")?.listFiles().orEmpty().mapNotNull { plan ->
            PLAN.matchEntire(plan.name)?.let { it.groupValues[1].toInt() to DeclaredExperiment.plan(plan.readText()) }
        }.toMap()
        val revisions = options["revision"].orEmpty().associate { it.substringBefore('=') to it.substringAfter('=') }
        val declared = request?.let { DeclaredExperiment.declaration(it, plans, revisions) }
        require(declared != null || (plans.isEmpty() && revisions.isEmpty())) {
            "--plans and --revision need a request"
        }
        val arms = options["arms"]?.flatMap { it.split(',') }?.filter(String::isNotBlank)
        require(arms == null || declared == null || arms == declared.arms) { "--arms contradicts the request's arms" }
        val declaration = (declared ?: Declaration(requireNotNull(arms) { "declare arms with --request or --arms" }))
            .let { base ->
                base.copy(
                    treatments = base.treatments + options["treatment"].orEmpty(),
                    allowed = base.allowed + options["allow"].orEmpty(),
                    controls = options["control"].orEmpty()
                        .associate { it.substringBefore('=') to it.substringAfter('=') },
                    rows = options["rows"]?.single()?.toInt(),
                )
            }
        val execution = file("execution")?.let { Json.parseToJsonElement(it.readText()).jsonArray }
        val files = file("files")?.let { Json.parseToJsonElement(it.readText()).jsonArray }
        val prepared = files?.let(DeclaredExperiment::prepared)
        val cases = requireNotNull(file("cases")) { "--cases lab-cases.jsonl is required" }
        val executed = file("executed")?.listFiles().orEmpty().mapNotNull { directory ->
            val case = directory.resolve("bench-case.json").takeIf(File::isFile) ?: return@mapNotNull null
            directory.name.toIntOrNull()?.let { it to DeclaredExperiment.case(case.readText()) }
        }.toMap().takeIf { options.containsKey("executed") }
        return cases.bufferedReader().use { audit(it, declaration, execution, job, prepared, executed) }
    }

    fun audit(
        cases: Reader,
        declaration: Declaration,
        execution: JsonArray? = null,
        job: JsonObject? = null,
        prepared: Set<String>? = null,
        executed: Map<Int, BenchCase>? = null,
    ): PairedAuditReport {
        val retained = cases.buffered().lineSequence().filter(String::isNotBlank)
            .map { Json.parseToJsonElement(it).jsonObject }.map { parse(it, executed?.get(int(it, "index"))) }.toList()
        val commands = job?.get("commands")?.jsonArray?.map { it.jsonObject }.orEmpty()
        val duplicates = retained.groupingBy { it.index }.eachCount().filterValues { it > 1 }.keys
        val sourceDrift = retained.filter { it.record != null }.groupBy { it.inputs }
            .filterValues { group -> group.map { it.record?.sourceHashes }.distinct().size > 1 }.keys
        val rows = retained.groupBy { it.rowKey }.map { (key, row) ->
            PairedRows.audit(key, row, declaration, duplicates, sourceDrift, commands)
        }
        val cohort = declaration.cohort?.takeUnless { it.filtered }
        val absent = when (cohort) {
            null -> (declaration.rows ?: rows.size) - rows.size
            else -> retained.map { it.rowKey }.toSet().let { seen -> cohort.rows.keys.count { it !in seen } }
        }
        val unretained = commands.map { int(it, "index") }.filter { index -> retained.none { it.index == index } }
        val attempts = execution?.let(PairedCosts::attempts)
        val jobErrors = buildList {
            if (duplicates.isNotEmpty()) add("case indices retained more than once: ${duplicates.sorted()}")
            if (absent < 0) add("${-absent} rows beyond the ${declaration.rows} expected")
            if (cohort != null && declaration.rows != null && declaration.rows != cohort.rows.size) {
                add("--rows ${declaration.rows} contradicts the declared cohort of ${cohort.rows.size} rows")
            }
            addAll(DeclaredExperiment.errors(declaration, prepared))
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
        val accepted = (counts[RowState.INVALID] ?: 0) == 0 && missing == 0 && jobErrors.isEmpty()
        return PairedAuditReport(
            arms = declaration.arms,
            treatments = declaration.treatments.sorted(),
            allowedDifferences = declaration.allowed.sorted(),
            complete = counts[RowState.COMPLETE] ?: 0,
            censored = counts[RowState.CENSORED] ?: 0,
            invalid = counts[RowState.INVALID] ?: 0,
            missing = missing,
            accepted = accepted,
            declared = DeclaredExperiment.coverage(retained, declaration, prepared, executed != null, accepted),
            jobErrors = jobErrors,
            outcomes = declaration.arms.associateWith { arm -> PairedCosts.outcomes(rows, arm) },
            costs = declaration.arms.associateWith { arm -> PairedCosts.costs(rows, arm, commands) },
            retainedCosts = PairedCosts.costs(retained, commands),
            attempts = attempts,
            rows = rows,
        )
    }

    private fun parse(line: JsonObject, executed: BenchCase?): AuditedCase {
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
            caseKey = DeclaredExperiment.caseKey(case),
            host = text(line, "host"),
            seeds = listOf(seed, solve?.seed?.toString(), case.attributes["seed"], controls["solverSeed"]),
            inputs = (executed ?: case).inputs.associate { it.role to it.sha256 },
            parameters = (executed ?: case).parameters,
            executed = executed != null,
            record = solve?.let(::retained),
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

    private fun retained(record: SolveRecord): SolveRecord = record.copy(
        stats = emptyMap(),
        attribution = emptyList(),
        finalWitness = null,
        stderr = null,
        numericalSemantics = null,
        buildProvenance = record.buildProvenance?.let { build ->
            build.copy(
                distribution = build.distribution.filterKeys(::solverJar),
                runtime = emptyMap(),
                runtimeOptions = emptyMap(),
                harness = emptyMap(),
            )
        },
    )

    /** Solver and CLI implementation jars, whose hashes identify the revision rather than the runtime. */
    internal fun solverJar(path: String): Boolean = path.substringAfterLast('/').let {
        (it.startsWith("klause-jvm-") || it.startsWith("klause-cli-jvm-")) && it.endsWith(".jar")
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
    private val PLAN = Regex("bench-plan-(\\d+)\\.jsonl")
    private val OPTIONS = setOf(
        "cases", "request", "execution", "job", "arms", "treatment", "allow", "control", "rows", "plans", "files",
        "revision", "executed",
    )
}
