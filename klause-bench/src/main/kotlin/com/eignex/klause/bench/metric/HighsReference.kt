package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.MpsModel
import java.io.File
import java.nio.file.Files
import com.eignex.klause.ir.ObjectiveSense as ObjectiveDirection

/**
 * The second MPS reference, beside [ScipReference]: HiGHS as a native binary on the model file, its parallelism off and
 * its wall-clock capped by `--time_limit`, its tolerances set explicitly ([OPTIONS]) so the ones a row records are
 * the ones it ran with. Its solution is written to a file and checked against the model ([MpsWitness]): a finite
 * primal bound alone is not a solution. A run whose claim does not hold up (an infeasibility proof, a solution the
 * model rejects or that cannot be checked) is retried once without presolve, on what is left of the budget, and that
 * retry is checked the same way ([MpsReference]). Rows are keyed by solver `highs`.
 */
internal object HighsReference {
    /** The highs executable, from `PATH` by default; override with `-Dklause.bench.highs=/path/to/highs`. */
    private val BINARY = System.getProperty("klause.bench.highs", "highs")

    /** HiGHS's defaults, passed explicitly: part of the cache identity and of every row's record. */
    internal val OPTIONS = linkedMapOf(
        "mip_feasibility_tolerance" to "1e-06",
        "primal_feasibility_tolerance" to "1e-07",
        "dual_feasibility_tolerance" to "1e-07",
        "mip_rel_gap" to "0.0001",
        "mip_abs_gap" to "1e-06",
    )

    fun available(): Boolean = NativeReference.available(BINARY)

    /** The build, as `highs --version` prints it. */
    private val version: String by lazy {
        runCatching {
            NativeReference.exec(
                listOf(BINARY, "--version"),
                VERSION_WAIT_MS,
            ).first.lineSequence().first().trim()
        }
            .getOrDefault("").ifEmpty { "unknown" }
    }

    /** What a cached result depends on: the build, the options, the retry and the validation rules. */
    fun identity(build: String = version): String = "$build|${options()}|retry=presolve-off|${MpsWitness.VERSION}"

    private fun options() = OPTIONS.entries.joinToString(",") { "${it.key}=${it.value}" }

    fun run(ref: ProblemRef, budget: Budget): SolverInvocation.Result =
        CorpusFiles.withPlainFile(CorpusFetcher.resolve(ref.source)) { file ->
            val model = runCatching { Mps.parse(file.readText()) }.getOrNull()
            val maximize = model?.sense == ObjectiveDirection.MAXIMIZE
            val attempts = mutableListOf(attempt(file, model, budget.timeoutMillis, presolve = null))
            val left = budget.timeoutMillis - attempts.first().elapsedMs
            if (doubtful(
                    attempts.first(),
                ) && left >= MIN_RETRY_MS
            ) {
                attempts += attempt(file, model, left, presolve = "off")
            }
            MpsReference.result(attempts, model, maximize, identity(), options())
        }

    /** Whether a run's claim did not hold up, which a retry without presolve may settle. */
    private fun doubtful(attempt: MpsAttempt): Boolean {
        val outcome = attempt.verdict.outcome
        return attempt.claim.status == MpsWitness.Status.INFEASIBLE ||
            outcome is MpsWitness.Outcome.Invalid || outcome is MpsWitness.Outcome.Unresolved ||
            attempt.verdict.stats["proof"]?.startsWith("rejected") == true
    }

    private fun attempt(file: File, model: MpsModel?, timeoutMs: Long, presolve: String?): MpsAttempt {
        val dir = Files.createTempDirectory("klause-highs").toFile()
        try {
            val solution = File(dir, "solution.sol")
            val options = File(dir, "options.txt")
            val settings = OPTIONS + listOfNotNull(
                "log_file" to File(dir, "highs.log").absolutePath,
                presolve?.let { "presolve" to it },
            )
            options.writeText(settings.entries.joinToString("\n", postfix = "\n") { "${it.key} = ${it.value}" })
            val cmd = listOf(
                BINARY,
                "--model_file", file.absolutePath,
                "--options_file", options.absolutePath,
                "--solution_file", solution.absolutePath,
                "--time_limit", "${timeoutMs / MS_PER_SEC}",
                "--parallel", "off",
            )
            val (stdout, elapsedMs) = NativeReference.exec(cmd, timeoutMs)
            val claim = parseClaim(stdout, solution.takeIf { it.isFile }?.readText())
            return MpsAttempt(
                label = presolve?.let { "presolve $it" } ?: "default",
                stdout = stdout,
                elapsedMs = elapsedMs,
                command = cmd.joinToString(" "),
                claim = claim,
                verdict = MpsReference.judge(model, claim, repair),
                reported = reported(stdout),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * The continuous repair [MpsWitness] asks for: HiGHS on a fixed-integer LP, its solution read back. It solves at a
     * tolerance tighter than the check, so a completion it returns passes the check on its own numbers.
     */
    val repair = MpsWitness.Repair { lp ->
        if (!available()) return@Repair MpsWitness.RepairResult.Failed("highs is not available")
        solveFixed(lp, REPAIR_TOLERANCE)
    }

    private fun solveFixed(lp: String, tolerance: Double): MpsWitness.RepairResult {
        val dir = Files.createTempDirectory("klause-highs-repair").toFile()
        try {
            val model = File(dir, "fixed.mps").apply { writeText(lp) }
            val solution = File(dir, "solution.sol")
            val options = File(dir, "options.txt")
            options.writeText(
                "primal_feasibility_tolerance = $tolerance\ndual_feasibility_tolerance = $tolerance\n" +
                    "log_file = ${File(dir, "highs.log").absolutePath}\n",
            )
            val cmd = listOf(
                BINARY,
                "--model_file", model.absolutePath,
                "--options_file", options.absolutePath,
                "--solution_file", solution.absolutePath,
                "--time_limit", "${REPAIR_TIMEOUT_MS / MS_PER_SEC}", "--parallel", "off",
            )
            val (stdout, _) = NativeReference.exec(cmd, REPAIR_TIMEOUT_MS)
            val claim = parseClaim(stdout, solution.takeIf { it.isFile }?.readText())
            val values = claim.assignment
            return when {
                claim.status == MpsWitness.Status.INFEASIBLE -> MpsWitness.RepairResult.Infeasible
                claim.status == MpsWitness.Status.OPTIMAL && values != null -> MpsWitness.RepairResult.Solved(values)
                else -> MpsWitness.RepairResult.Failed("highs ended ${status(stdout) ?: "without a status"}")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * A MIP's summary has `Status <status>`, `Primal bound`, `Dual bound` and `Gap`; an LP's has `Model status :
     * <status>` and `Objective value :`, an optimal LP's objective being its own dual bound. The solution comes from
     * the solution file's `# Columns` section, absent when HiGHS wrote none.
     */
    internal fun parseClaim(stdout: String, solution: String?): MpsWitness.Claim {
        val lines = stdout.lineSequence().map { it.trim() }.toList()
        fun field(name: String) = lines.lastOrNull { Regex("""^$name\s*:?\s+\S""").containsMatchIn(it) }
            ?.replace(Regex("""^$name\s*:?\s+"""), "")?.trim()
        fun number(name: String) = field(name)?.substringBefore(' ')?.toDoubleOrNull()?.takeIf { it.isFinite() }
        val text = status(stdout).orEmpty()
        val status = when {
            text.startsWith("optimal") -> MpsWitness.Status.OPTIMAL
            text.startsWith("infeasible") -> MpsWitness.Status.INFEASIBLE
            "limit" in text || "interrupt" in text -> MpsWitness.Status.LIMIT
            else -> MpsWitness.Status.UNKNOWN
        }
        val primal = number("Primal bound") ?: number("Objective value")
        val lp = field("Model status") != null && field("Primal bound") == null
        val dual = number("Dual bound") ?: primal.takeIf { lp && status == MpsWitness.Status.OPTIMAL }
        val gap = field("Gap")?.substringBefore('%')?.trim()?.toDoubleOrNull()?.div(PERCENT)
        return MpsWitness.Claim(status, primal, dual, gap, solution?.let(::solutionValues))
    }

    private fun status(stdout: String): String? = stdout.lineSequence().map { it.trim() }
        .lastOrNull { Regex("""^(Model status|Status)\s*:?\s+\S""").containsMatchIn(it) }
        ?.replace(Regex("""^(Model status|Status)\s*:?\s+"""), "")?.trim()?.lowercase()

    private fun reported(stdout: String): Map<String, String> = buildMap {
        status(stdout)?.let { put("solverStatus", it) }
        stdout.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("Solution status") }
            ?.removePrefix("Solution status")?.trim()?.let { put("solutionStatus", it) }
    }

    /** The primal values a raw-style solution file lists under `# Columns`, by name; null when it holds none. */
    internal fun solutionValues(solution: String): Map<String, Double>? {
        val lines = solution.lines()
        val primal = lines.indexOfFirst { it.startsWith("# Primal solution values") }
        if (primal < 0 || lines.getOrNull(primal + 1)?.trim().equals("None", ignoreCase = true)) return null
        val columns = (primal until lines.size).firstOrNull { lines[it].startsWith("# Columns") } ?: return null
        val count = lines[columns].removePrefix("# Columns").trim().toIntOrNull() ?: return null
        return (columns + 1..columns + count).mapNotNull { lines.getOrNull(it) }.associate { line ->
            line.substringBeforeLast(' ') to line.substringAfterLast(' ').toDouble()
        }
    }

    private const val MS_PER_SEC = 1000.0
    private const val PERCENT = 100.0
    private const val VERSION_WAIT_MS = 10_000L

    /** A retry with less than this left would only time out. */
    private const val MIN_RETRY_MS = 5_000L
    private const val REPAIR_TIMEOUT_MS = 30_000L

    private const val REPAIR_TOLERANCE = 1e-9
}
