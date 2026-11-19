package com.eignex.klause.bench.lab

import com.eignex.klause.bench.lab.PairedAudit.Declaration
import com.eignex.klause.bench.lab.PairedAudit.KEY_SEPARATOR
import com.eignex.klause.bench.lab.PairedAudit.REVISION
import com.eignex.klause.bench.lab.PairedAudit.int
import com.eignex.klause.bench.lab.PairedAudit.text
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Validity of one paired row: declared arms from one accepted worker attempt under matched controls. */
internal object PairedRows {
    fun audit(
        key: String,
        row: List<AuditedCase>,
        declaration: Declaration,
        duplicates: Set<Int>,
        sourceDrift: Set<Map<String, String?>>,
        commands: List<JsonObject>,
    ): AuditedRow {
        val done = row.filter { it.status == DONE }
        val invalid = buildList {
            addAll(arms(row, declaration))
            row.filter { it.index in duplicates }.forEach { add("case ${it.index} retained more than once") }
            row.filter { it.status !in setOf(DONE, CANCELLED) }.forEach { add("${it.arm} ended ${it.status}") }
            done.forEach { case -> case.recordError?.let { add("${case.arm}: $it") } }
            addAll(attempt(done, declaration))
            addAll(identity(done.filter { it.record != null }, declaration))
            done.forEach { addAll(DeclaredExperiment.reasons(it, declaration)) }
            if (done.any { it.inputs in sourceDrift }) {
                add("plain source identity differs between rows declaring the same inputs")
            }
            addAll(commands(done, commands))
        }
        val absent = declaration.arms.filter { arm -> !ran(row, arm) }.map { arm ->
            if (row.any { it.arm == arm && it.status == CANCELLED }) "$arm cancelled" else "$arm not retained"
        }
        val censored = done.filter { case -> case.record?.let { terminal(it.proven, it.kind, it.feasible) } == false }
            .mapNotNull { it.arm }
        val state = when {
            invalid.isNotEmpty() -> RowState.INVALID
            absent.isNotEmpty() -> RowState.MISSING
            censored.isNotEmpty() -> RowState.CENSORED
            else -> RowState.COMPLETE
        }
        val parts = key.split(KEY_SEPARATOR)
        return AuditedRow(
            source = parts[0], seed = parts[1].nullable(), innerRepeat = parts[2].nullable(),
            outerRepeat = parts[3].nullable(), host = done.map { it.host }.distinct().singleOrNull(),
            receipt = done.map { it.receipt }.distinct().singleOrNull(), arms = row.map { it.arm },
            indices = row.map { it.index }, state = state, reasons = invalid + absent, censoredArms = censored,
            cases = row,
        )
    }

    /** Whether [arm] ran in [row] to an end the worker committed, successful or not. */
    fun ran(row: List<AuditedCase>, arm: String): Boolean = row.any { it.arm == arm && it.status != CANCELLED }

    /** A verdict that ends the run: a proof, or a solution of a satisfaction problem. */
    fun terminal(proven: Boolean, kind: String, feasible: Boolean?): Boolean =
        proven || (kind == "satisfy" && feasible == true)

    private fun arms(row: List<AuditedCase>, declaration: Declaration): List<String> = buildList {
        row.mapNotNull { it.arm }.filter { it !in declaration.arms }.distinct().forEach { add("undeclared arm $it") }
        if (row.any { it.arm == null }) add("case without an arm")
        row.groupingBy { it.arm }.eachCount().filter { it.value > 1 && it.key != null }
            .forEach { (arm, count) -> add("arm $arm retained $count times") }
    }

    private fun attempt(done: List<AuditedCase>, declaration: Declaration): List<String> = buildList {
        done.filter { it.receipt?.matches(SHA256) != true }.forEach { add("${it.arm} has no accepted worker receipt") }
        val receipts = done.mapNotNull { it.receipt }.distinct()
        if (receipts.size > 1) add("arms come from ${receipts.size} worker attempts")
        val hosts = done.map { it.host }.distinct()
        if (hosts.size > 1) add("arms ran on ${hosts.size} machines")
        if (done.any { it.frame == null }) add("execution frame unavailable")
        val frames = done.mapNotNull { it.frame }.distinct()
        if (frames.size > 1) add("arms ran under ${frames.size} execution frames")
        val iteration = frames.singleOrNull()?.let { text(it as? JsonObject, "iterationCases") }
        if (iteration != null && iteration != declaration.arms.size.toString()) {
            add("worker iteration ran $iteration cases for ${declaration.arms.size} declared arms")
        }
    }

    private fun identity(done: List<AuditedCase>, declaration: Declaration): List<String> = buildList {
        if (done.map { it.inputs }.distinct().size > 1) add("declared inputs differ between arms")
        done.filter { it.record?.sourceHashes.isNullOrEmpty() }
            .forEach { add("${it.arm} has no plain source identity") }
        if (done.map { it.record?.sourceHashes }.distinct().size > 1) add("plain source identity differs between arms")
        done.filter { it.seeds.distinct().size != 1 || it.seeds.first() == null }
            .forEach { add("${it.arm} seed identities disagree: ${it.seeds}") }
        done.forEach { addAll(controls(it, declaration)) }
        val facets = done.map(::facets)
        done.zip(facets).groupBy({ declaration.expected[it.first.arm]?.revision }, { it.second[REVISION] })
            .filter { (revision, builds) -> revision != null && builds.distinct().size > 1 }
            .forEach { (revision, _) -> add("arms declaring revision $revision differ in build identity") }
        for (facet in facets.flatMap { it.keys }.distinct().sorted()) {
            val allowed = facet in declaration.allowed || facet.removePrefix(CONTROLS) in declaration.treatments
            if (!allowed && facets.map { it[facet] }.distinct().size > 1) add("undeclared $facet difference")
        }
        val parameters = done.map { it.parameters }
        for (parameter in parameters.flatMap { it.keys }.distinct().sorted()) {
            if (parameter !in declaration.treatments && parameters.map { it[parameter] }.distinct().size > 1) {
                add("undeclared parameter difference $parameter")
            }
        }
    }

    private fun controls(case: AuditedCase, declaration: Declaration): List<String> = buildList {
        val record = requireNotNull(case.record)
        val controls = record.comparisonControls
        if (controls["policy"] != MATCHED_SOURCE) add("${case.arm} comparison policy ${controls["policy"]}")
        REQUIRED.filter { controls[it].isNullOrBlank() }.takeIf { it.isNotEmpty() }
            ?.let { add("${case.arm} lacks controls $it") }
        if (controls["solverBudgetMs"] != record.budgetMs.toString() ||
            controls["cpus"] != record.processors.toString()
        ) {
            add("${case.arm} effective budget or processors differ from its controls")
        }
        declaration.controls.filter { (name, value) -> controls[name] != value }
            .forEach { (name, value) -> add("${case.arm} control $name is ${controls[name]}, declared $value") }
    }

    private fun facets(case: AuditedCase): Map<String, String?> {
        val record = requireNotNull(case.record)
        val solverJars = record.buildProvenance?.distribution.orEmpty().toSortedMap()
        return record.comparisonControls.mapKeys { (name, _) -> FINGERPRINTS[name] ?: "$CONTROLS$name" } +
            mapOf(REVISION to "${record.gitSha}:$solverJars", "checker" to case.checkPolicy)
    }

    private fun commands(done: List<AuditedCase>, commands: List<JsonObject>): List<String> = buildList {
        val byIndex = commands.associateBy { int(it, "index") }
        for (case in done) {
            val command = byIndex[case.index] ?: continue
            val exit = (command["exitCode"] as? JsonPrimitive)?.content
            if (text(command, "status") != DONE || exit != "0") {
                add("case ${case.index} command ${text(command, "status")} exit $exit")
            }
            if (text(command, "host") != case.host) add("case ${case.index} command ran on another machine")
        }
    }

    private fun String.nullable() = takeUnless { it == "null" }

    const val DONE = "DONE"
    private const val CANCELLED = "CANCELLED"
    private const val CONTROLS = "controls."
    private const val MATCHED_SOURCE = "matched-source-v1"
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val FINGERPRINTS = mapOf(
        "runtimeFingerprint" to "runtime",
        "harnessFingerprint" to "harness",
        "libraryFingerprint" to "libraries",
    )
    private val REQUIRED = listOf(
        "series", "solverSeed", "compilerSeed", "solverBudgetMs", "compilerTimeoutSeconds", "caseTimeoutSeconds",
        "cpus", "memoryMiB", "heap-mib", "gc", "debug", "measurement", "exact", "complete-assignment",
        "deadlinePolicy", "runtimeFingerprint", "harnessFingerprint", "libraryFingerprint", "inputDigest",
    )
}
