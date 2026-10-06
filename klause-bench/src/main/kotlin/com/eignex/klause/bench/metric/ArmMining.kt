package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.Reports
import kotlinx.serialization.Serializable
import java.io.File
import java.util.Locale

/**
 * Ranks portfolio arms by mining historic solves: the per-case records a lab experiment keeps
 * (`deploy/lab cases <id>`), each carrying the `arm.<label>` telemetry a `-s` portfolio run prints.
 *
 * Two lenses over the same cases. **Wins** feed [ArmCalibration.scoreWinnerSets]: on an optimisation
 * case the best-holder from the attribution stream wins; on a satisfaction case the arm credited with
 * the first solution does. **Contribution** sums what the scheduler itself credited each arm — work spent,
 * the work-weighted mean reward, and each signal's credit (clauses, cuts, bounds, fixings, …) — so an arm
 * that never holds the incumbent but feeds the pool still shows. The report is for hand-editing the arm
 * catalogs; every quarantine fault is listed by case so a broken arm cannot hide in an average.
 */
internal object ArmMining {

    /** One mined case: where it came from, the record's outcome, and each arm's telemetry.
     *  [quarantined] is the run's own account of every arm it quarantined, when it kept one. */
    data class MinedCase(
        val name: String,
        val config: String,
        val slice: Map<String, String>,
        val arms: List<ArmTelemetry>,
        val winners: Set<String>,
        val quarantined: String? = null,
    )

    /** One arm's `arm.<label>` line: scheduler accounting plus the per-signal credit. */
    data class ArmTelemetry(
        val label: String,
        val work: Long,
        val reward: Double,
        val failures: Int,
        val faults: Int,
        val credit: Map<String, Double>,
    )

    /** Per-arm contribution summed over the mined cases. [reward] is weighted by work. */
    data class Contribution(
        val arm: String,
        val cases: Int,
        val work: Long,
        val reward: Double,
        val failures: Int,
        val faults: Int,
        val credit: Map<String, Double>,
    )

    private const val ARM_PREFIX = "arm."
    private const val FIRST_SOLUTION = "FirstSolution"

    // The portfolio numbers the second and later replicas of one arm; they are the same configuration.
    private val REPLICA = Regex("#\\d+$")
    private val ACCOUNTING = setOf("segments", "work", "reward", "failures", "faults")

    /** The slicing columns `by=` accepts. */
    val SLICES: List<String> = listOf("config", "suite", "family", "format", "category", "kind")

    @Serializable
    private data class LabProblem(
        val suite: String,
        val problem: String,
        val family: String? = null,
        val format: String? = null,
        val category: String? = null,
    )

    @Serializable
    private data class LabCase(val problem: LabProblem, val arm: String, val record: SolveRecord? = null)

    /** Every case in the lab case files [files] whose record carries arm telemetry. */
    fun load(files: List<File>): List<MinedCase> = files.flatMap { file ->
        Reports.json.decodeFromString<List<LabCase>>(file.readText()).mapNotNull { case ->
            val record = case.record ?: return@mapNotNull null
            val p = case.problem
            mined(
                name = "${p.suite}/${p.problem}",
                config = case.arm,
                slice = mapOf(
                    "config" to case.arm,
                    "suite" to p.suite,
                    "family" to (p.family ?: "?"),
                    "format" to (p.format ?: "?"),
                    "category" to (p.category ?: "?"),
                    "kind" to record.kind,
                ),
                record = record,
            )
        }
    }

    /** [record] as a mined case, or null when it ran no portfolio. */
    fun mined(name: String, config: String, slice: Map<String, String>, record: SolveRecord): MinedCase? {
        val arms = record.stats.mapNotNull { (key, value) ->
            if (key.startsWith(ARM_PREFIX)) parseArm(armOf(key.removePrefix(ARM_PREFIX)), value) else null
        }
        if (arms.isEmpty()) return null
        return MinedCase(name, config, slice, arms, winners(record, arms), record.stats[SolverInvocation.QUARANTINED])
    }

    /** Parse one `segments=… work=… reward=… failures=… faults=… <Signal>=…` telemetry value. */
    fun parseArm(label: String, value: String): ArmTelemetry {
        val fields = value.split(' ').filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        return ArmTelemetry(
            label = label,
            work = fields["work"]?.toLongOrNull() ?: 0L,
            reward = fields["reward"]?.toDoubleOrNull() ?: 0.0,
            failures = fields["failures"]?.toIntOrNull() ?: 0,
            faults = fields["faults"]?.toIntOrNull() ?: 0,
            credit = fields.filterKeys { it !in ACCOUNTING }
                .mapNotNull { (signal, amount) -> amount.toDoubleOrNull()?.let { signal to it } }
                .toMap(),
        )
    }

    // A satisfaction case has no attribution stream; the scheduler's first-solution credit names the
    // arm that produced the model. An infeasibility proof names no winner.
    private fun winners(record: SolveRecord, arms: List<ArmTelemetry>): Set<String> = when {
        record.attribution.isNotEmpty() -> setOf(armOf(SolveMetric.best(record.attribution, record.maximize).label))
        record.feasible == true -> arms.filter { (it.credit[FIRST_SOLUTION] ?: 0.0) > 0.0 }.map { it.label }.toSet()
        else -> emptySet()
    }

    private fun armOf(label: String): String = label.replace(REPLICA, "")

    /** Sum each arm's telemetry over [cases], most rewarded first. */
    fun contributions(cases: List<MinedCase>): List<Contribution> {
        // Replicas fold into one arm, so a case can hold several of its lines; key each by case position.
        val runs = cases.withIndex().flatMap { (at, case) -> case.arms.map { at to it } }
        return runs.groupBy { it.second.label }.map { (arm, armRuns) ->
            val lines = armRuns.map { it.second }
            val work = lines.sumOf { it.work }
            val weighted = lines.sumOf { it.reward * it.work }
            val credit = sortedMapOf<String, Double>()
            for (line in lines) for ((signal, amount) in line.credit) credit[signal] = (credit[signal] ?: 0.0) + amount
            Contribution(
                arm = arm,
                cases = armRuns.map { it.first }.distinct().size,
                work = work,
                reward = if (work > 0) weighted / work else 0.0,
                failures = lines.sumOf { it.failures },
                faults = lines.sumOf { it.faults },
                credit = credit,
            )
        }.sortedWith(compareByDescending<Contribution> { it.reward }.thenBy { it.arm })
    }

    /** Win-share and set-cover ranking over [cases]' winner sets. */
    fun wins(cases: List<MinedCase>): ArmCalibration.Report {
        val arms = cases.flatMap { c -> c.arms.map { it.label } }.distinct()
        val won = cases.mapNotNull { it.winners.ifEmpty { null } }
        return ArmCalibration.scoreWinnerSets(arms, won, instances = cases.size)
    }

    /** The mining report over [cases], sliced within each value of the [by] column when given. */
    fun render(cases: List<MinedCase>, by: String? = null): String = buildString {
        require(by == null || by in SLICES) { "unknown by=$by (have: ${SLICES.joinToString()})" }
        val faulty = cases.flatMap { c -> c.arms.filter { it.faults > 0 }.map { c to it } }
        if (faulty.isNotEmpty()) {
            appendLine("!!! ${faulty.size} quarantined arm run(s) — fix these before trusting any ranking below:")
            for ((case, arm) in faulty) {
                appendLine("!!!   ${arm.label} on ${case.name} [${case.config}] faults=${arm.faults}")
                case.quarantined?.let { appendLine("!!!     $it") }
            }
            appendLine()
        }
        append(section("all cases", cases))
        if (by != null) {
            for ((value, slice) in cases.groupBy { it.slice[by] ?: "?" }.toSortedMap()) {
                appendLine()
                append(section("$by=$value", slice))
            }
        }
    }

    private fun section(title: String, cases: List<MinedCase>): String = buildString {
        appendLine("### $title (${cases.size} cases)")
        append(ArmCalibration.render(wins(cases)))
        appendLine()
        appendLine("--- contribution | cases  work  reward  failures  faults  credit ---")
        for (c in contributions(cases)) {
            val credit = c.credit.entries.joinToString(" ") { (signal, amount) -> "$signal=${round(amount)}" }
            appendLine(
                "  ${c.arm.padEnd(28)} ${c.cases.toString().padStart(5)} ${c.work.toString().padStart(10)} " +
                    "${round(c.reward).padStart(7)} ${c.failures.toString().padStart(4)} " +
                    "${c.faults.toString().padStart(4)}  $credit",
            )
        }
    }

    private fun round(value: Double): String = String.format(Locale.ROOT, "%.3f", value)
}
