package com.eignex.klause.bench.lab

import com.eignex.klause.bench.lab.PairedAudit.KEY_SEPARATOR
import com.eignex.klause.bench.lab.PairedAudit.text
import com.eignex.lab.bench.BenchCase
import com.eignex.lab.bench.BenchProtocol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * What one arm was asked to run: [ref] as requested, [revision] when an immutable commit binds it, the [parameters]
 * its cases must carry (null when the arm leaves a key unset and so inherits the solver default of its revision),
 * and its resolved [plan] by case key when retained.
 */
internal data class ArmExpectation(
    val ref: String?,
    val revision: String?,
    val parameters: Map<String, String?>,
    val plan: Map<String, BenchCase>? = null,
)

/**
 * The expected case matrix from [source]: paired row keys mapped to their case keys and declared input hashes.
 * A [filtered] cohort may hold rows the lab's reference filter dropped, so its absent rows are unverified.
 */
internal data class Cohort(
    val source: String,
    val rows: Map<String, Map<String, Map<String, String?>>>,
    val filtered: Boolean,
)

/** Binds retained cases to the treatments, revisions and cohort a lab request and its resolved plans declare. */
internal object DeclaredExperiment {
    /**
     * Expectations for every arm of [experiment]. Each arm is its config over `base`; its request parameters are
     * `bench.request.parameters` overridden by its `param.` values. [plans] maps a retained `bench-plan-N.jsonl`
     * index to its cases, N counting the repository arms in config order; [revisions] pins arms whose ref is not a
     * commit.
     */
    fun declaration(
        experiment: JsonObject,
        plans: Map<Int, List<BenchCase>> = emptyMap(),
        revisions: Map<String, String> = emptyMap(),
    ): PairedAudit.Declaration {
        require((experiment["grid"] as? JsonObject).isNullOrEmpty() && experiment["grid"] !is JsonArray) {
            "grid requests need explicit --arms and --treatment"
        }
        val base = strings(experiment["base"])
        val configs = experiment.getValue("configs").jsonArray.map { strings(it) }
        val arms = configs.map { base + it }
        val labels = arms.map { requireNotNull(it["label"]) { "request config without label" } }
        require(labels.distinct().size == labels.size) { "request arms share a label" }
        revisions.keys.filter { it !in labels }.forEach { error("--revision names undeclared arm $it") }
        val treatments = configs.flatMap { it.keys }.filter { it.startsWith(PARAMETER) }
            .map { it.removePrefix(PARAMETER) }.toSet()
        val bench = experiment["bench"] as? JsonObject
        val requested = strings((bench?.get("request") as? JsonObject)?.get("parameters"))
        val repository = arms.indices.filter { arms[it]["backend"] != REFERENCE_BACKEND }
        plans.keys.filter { it !in repository.indices }.forEach { error("bench-plan-$it.jsonl has no repository arm") }
        val planned = plans.mapKeys { (index, _) -> labels[repository[index]] }
            .mapValues { (_, cases) -> cases.associateBy(::caseKey) }
        val expected = labels.zip(arms).associate { (label, values) ->
            val ref = values["ref"]
            val pinned = revisions[label]
            require(pinned == null || pinned.matches(COMMIT)) { "--revision $label needs a full commit id" }
            require(pinned == null || ref == null || !ref.matches(COMMIT) || ref == pinned) {
                "--revision $label=$pinned contradicts its requested commit $ref"
            }
            val parameters = requested + values.filterKeys { it.startsWith(PARAMETER) }
                .mapKeys { it.key.removePrefix(PARAMETER) }
            label to ArmExpectation(
                ref = ref,
                revision = ref?.takeIf { it.matches(COMMIT) } ?: pinned,
                parameters = (treatments + requested.keys).filter(LabSettings::passedThrough)
                    .associateWith { parameters[it] }.toSortedMap(),
                plan = planned[label],
            )
        }
        val cohort = cohort(experiment, bench, plans)
        val gaps = buildList {
            expected.filterValues { it.revision == null }.forEach { (label, arm) ->
                add("$label ref ${arm.ref ?: "(lab default)"} is not a commit; pin it with --revision $label=SHA")
            }
            val translated = treatments.filterNot(LabSettings::passedThrough).sorted()
            if (translated.isNotEmpty()) {
                expected.filterValues { it.plan == null }.keys
                    .forEach { add("$it treatments $translated need the arm's resolved plan") }
            }
            if (cohort == null) add("expected cohort unavailable: pass the resolved plans or bench.frozenCases")
            if (cohort?.filtered == true) add("the reference filter may drop planned rows; absent rows are unverified")
        }
        return PairedAudit.Declaration(
            arms = labels,
            treatments = treatments + resolved(planned.values),
            allowed = setOf(PairedAudit.REVISION).takeIf { expected.values.distinctBy(ArmExpectation::ref).size > 1 }
                .orEmpty(),
            expected = expected,
            cohort = cohort,
            gaps = gaps,
        )
    }

    /** Resolved plan cases of one retained `bench-plan-N.jsonl`. */
    fun plan(text: String): List<BenchCase> =
        BenchProtocol.json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray
            .map { BenchProtocol.json.decodeFromJsonElement(BenchCase.serializer(), it) }

    /** The executed case of one retained `cases/N/bench-case.json`. */
    fun case(text: String): BenchCase = BenchProtocol.json.decodeFromString(BenchCase.serializer(), text)

    /** The lab's comparison identity of a case, which pairs it with the same workload in every arm. */
    fun caseKey(case: BenchCase): String = case.attributes["comparisonKey"] ?: case.id

    /**
     * Why [case] departs from its arm's declared revision, treatment values, resolved plan or the cohort. Treatment
     * values and plans are compared only with an executed case: a lab-cases row holds its first arm's case for all.
     */
    fun reasons(case: AuditedCase, declaration: PairedAudit.Declaration): List<String> = buildList {
        val record = case.record ?: return@buildList
        val arm = declaration.expected[case.arm]
        val revision = record.gitSha?.takeIf { it.matches(COMMIT) }
        if (arm?.revision != null && revision != null && revision != arm.revision) {
            add("${case.arm} ran revision $revision, declared ${arm.revision}")
        }
        val executed = arm.takeIf { case.executed }
        executed?.parameters?.filter { (key, value) -> case.parameters[key] != value }?.forEach { (key, value) ->
            add("${case.arm} treatment $key is ${case.parameters[key] ?: UNSET}, declared ${value ?: UNSET}")
        }
        val planned = executed?.plan?.get(case.caseKey)
        if (planned != null) {
            val differing = (planned.parameters.keys + case.parameters.keys)
                .filter { planned.parameters[it] != case.parameters[it] }.sorted()
            if (differing.isNotEmpty()) add("${case.arm} parameters $differing differ from its resolved plan")
            if (planned.inputs.associate { it.role to it.sha256 } != case.inputs) {
                add("${case.arm} inputs differ from its resolved plan")
            }
        }
        val cohort = declaration.cohort ?: return@buildList
        val inputs = cohort.rows[case.rowKey]?.get(case.caseKey)
        when {
            inputs == null -> add("${case.arm} case ${label(case.rowKey)} is not in the declared cohort")
            inputs != case.inputs -> add("${case.arm} inputs differ from the declared cohort")
        }
    }

    /** Job-level contradictions between the request, the resolved plans and retained build evidence. */
    fun errors(declaration: PairedAudit.Declaration, prepared: Set<String>?): List<String> = buildList {
        for ((label, arm) in declaration.expected) {
            val plan = arm.plan ?: continue
            for ((key, value) in arm.parameters) {
                plan.values.map { it.parameters[key] }.distinct().filter { it != value }.forEach {
                    add("resolved plan of $label sets $key to ${it ?: UNSET}, the request declares ${value ?: UNSET}")
                }
            }
        }
        val selected = declaration.expected.values.mapNotNull { it.plan?.keys }.distinct()
        if (selected.size > 1) add("resolved plans select different workloads")
        if (prepared != null && prepared.isNotEmpty()) {
            declaration.expected.filterValues { it.revision != null && it.revision !in prepared }
                .forEach { (label, arm) -> add("no accepted preparation archive for $label revision ${arm.revision}") }
        }
    }

    /** Declared-experiment coverage; [DeclaredCoverage.verified] is withheld while any gap remains. */
    fun coverage(
        retained: List<AuditedCase>,
        declaration: PairedAudit.Declaration,
        prepared: Set<String>?,
        executed: Boolean,
        accepted: Boolean,
    ): DeclaredCoverage {
        val cohort = declaration.cohort?.let { cohort ->
            val seen = retained.map { it.rowKey }.toSet()
            CohortCoverage(
                source = cohort.source,
                expected = cohort.rows.size,
                retained = cohort.rows.keys.count { it in seen },
                absent = cohort.rows.keys.filter { it !in seen }.map(::label).sorted(),
                unexpected = retained.filter { cohort.rows[it.rowKey]?.containsKey(it.caseKey) != true }
                    .map { "${it.arm} ${label(it.rowKey)}" }.distinct().sorted(),
            )
        }
        val unidentified = retained.count { it.record != null && it.record.gitSha?.matches(COMMIT) != true }
        val gaps = declaration.gaps + buildList {
            if (declaration.expected.isEmpty()) add("no request: arm treatments, revisions and cohort are undeclared")
            if (declaration.expected.isNotEmpty() && !executed) {
                add("executed cases unavailable, so per-arm treatment values are unverified")
            }
            val unexecuted = retained.count { !it.executed }
            if (executed && unexecuted > 0) add("$unexecuted retained cases have no executed bench-case.json")
            if (unidentified > 0) add("$unidentified retained records have no commit identity")
            if (prepared != null && prepared.isEmpty()) add("the file listing holds no preparation archive")
        }
        return DeclaredCoverage(
            verified = accepted && gaps.isEmpty(),
            arms = declaration.expected.mapValues { (_, arm) ->
                DeclaredArm(arm.ref, arm.revision, arm.parameters, arm.plan != null)
            },
            cohort = cohort,
            gaps = gaps,
        )
    }

    /** Commits whose accepted preparation archives a `lab files --json` listing retains. */
    fun prepared(files: JsonArray): Set<String> = files.mapNotNull { text(it.jsonObject, "path") }
        .mapNotNull { ARCHIVE.matchEntire(it)?.groupValues?.get(1) }.toSet()

    private fun cohort(experiment: JsonObject, bench: JsonObject?, plans: Map<Int, List<BenchCase>>): Cohort? {
        val frozen = (bench?.get("frozenCases") as? JsonArray).orEmpty()
            .map { BenchProtocol.json.decodeFromJsonElement(BenchCase.serializer(), it) }
        val canonical = frozen.ifEmpty { plans.minByOrNull { it.key }?.value ?: return null }
        val seeds = (experiment["seeds"] as? JsonArray).orEmpty().map { (it as JsonPrimitive).content }
            .ifEmpty { listOf(null) }
        val repeats = text(experiment, "repeats")?.toInt() ?: 1
        val rows = canonical.flatMap { case ->
            val pair = case.attributes["pairKey"] ?: caseKey(case)
            val inputs = case.inputs.associate { it.role to it.sha256 }
            seeds.flatMap { seed ->
                (0 until repeats).map { repeat ->
                    val seeded = case.attributes["seed"] ?: seed
                    val row = listOf(pair, seeded, case.attributes["repeat"], repeat.toString())
                    row.joinToString(KEY_SEPARATOR) to (caseKey(case) to inputs)
                }
            }
        }.groupBy({ it.first }, { it.second }).mapValues { (_, cases) -> cases.toMap() }
        val filter = text(bench, "referenceFilter")
        val filters = bench?.get("referenceFilters") as? JsonObject
        return Cohort(
            source = if (frozen.isNotEmpty()) "frozen-cases" else "resolved-plan",
            rows = rows,
            filtered = filter !in setOf(null, "any") || !filters.isNullOrEmpty(),
        )
    }

    /**
     * Planner-owned case parameters that differ between resolved plans of the same case: a request treatment the
     * planner translates, such as `engines` into `engine`. Pass-through keys keep their request expectations.
     */
    private fun resolved(plans: Collection<Map<String, BenchCase>>): Set<String> =
        plans.flatMap { it.keys }.distinct().flatMap { key ->
            val cases = plans.mapNotNull { it[key] }
            cases.flatMap { it.parameters.keys }.distinct()
                .filter { parameter -> cases.map { it.parameters[parameter] }.distinct().size > 1 }
        }.filterNot(LabSettings::passedThrough).toSet()

    private fun strings(value: JsonElement?): Map<String, String> = (value as? JsonObject).orEmpty()
        .filterValues { it is JsonPrimitive && it !is JsonNull }.mapValues { (it.value as JsonPrimitive).content }

    private fun label(rowKey: String): String {
        val parts = rowKey.split(KEY_SEPARATOR)
        return "${parts[0]} seed ${parts[1]} repeat ${parts[2]}/${parts[3]}"
    }

    private const val PARAMETER = "param."
    private const val UNSET = "unset"
    private const val REFERENCE_BACKEND = "reference"
    private val COMMIT = Regex("[0-9a-f]{40}")
    private val ARCHIVE = Regex("preparation/([0-9a-f]{40})\\.zip")
}
