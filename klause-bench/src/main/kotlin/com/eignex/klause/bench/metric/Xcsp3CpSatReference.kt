package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.source.CorpusFetcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.util.concurrent.TimeUnit

/**
 * The XCSP3 cp-sat reference. `minizinc --solver cp-sat` can only read FlatZinc and OR-Tools ships no
 * XCSP3 frontend, so an XCSP3 instance is solved by the [IMAGE] container: CPMpy (the OR-Tools cp-sat
 * modelling lib that won the XCSP3 2024 cp-sat track) reads the `.xml` and solves it with cp-sat
 * directly — the same engine used for MiniZinc, so the reference table stays a single cp-sat oracle.
 * Python lives only in the container (mirroring the vizier one); this returns a [SolverInvocation.Result]
 * so the reference sweep caches and scores XCSP3 exactly like the MiniZinc path.
 */
internal object Xcsp3CpSatReference {
    const val IMAGE = "klause-xcsp3-cpsat:latest"
    private const val DOCKER_INSPECT_WAIT_MS = 5_000L

    /** The container's one-line JSON verdict (see `klause-bench/xcsp3-cpsat/solve.py`). */
    @Serializable
    private data class Verdict(
        val exit: String,
        val runtime: Double? = null,
        val objective: Double? = null,
        val maximize: Boolean? = null,
        val error: String? = null,
    )

    /** Whether the converter image is built (`docker image inspect`). */
    fun imageAvailable(): Boolean = runCatching {
        val p = ProcessBuilder("docker", "image", "inspect", IMAGE)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        p.waitFor(DOCKER_INSPECT_WAIT_MS, TimeUnit.MILLISECONDS) && p.exitValue() == 0
    }.getOrElse { false }

    /** Solve [ref]'s XCSP3 `.xml` with cp-sat in the container under [budget]. The objective sense
     *  (`maximize`), unknowable without parsing the model, is carried in `stats["maximize"]`. */
    fun run(ref: ProblemRef, budget: Budget): SolverInvocation.Result {
        val xml = CorpusFetcher.resolve(ref.source)
        val timeoutSec = (budget.timeoutMillis / 1000).coerceAtLeast(1)
        val cmd = listOf(
            "docker",
            "run",
            "--rm",
            "-v",
            "${xml.parentFile.absolutePath}:/in:ro",
            IMAGE,
            "/in/${xml.name}",
            timeoutSec.toString(),
        )
        val proc = ProcessBuilder(cmd).redirectErrorStream(false).start()
        val stdout = proc.inputStream.bufferedReader().readText()
        // Generous over the in-container `-t` deadline (docker start-up + cp-sat flush), like the
        // MiniZinc reference watchdog: only ever kills a genuine runaway.
        if (!proc.waitFor(budget.timeoutMillis * 2 + 20_000, TimeUnit.MILLISECONDS)) proc.destroyForcibly()
        val json = stdout.lineSequence().lastOrNull { it.trimStart().startsWith("{") }
            ?: error("xcsp3 cp-sat: no JSON verdict (${stdout.take(200)})")
        val v = Reports.json.decodeFromString<Verdict>(json)
        if (v.exit == "ERROR") error("xcsp3 cp-sat: ${v.error ?: "unknown"}")
        val feasible = when (v.exit) {
            "OPTIMAL", "FEASIBLE" -> true
            "UNSATISFIABLE" -> false
            else -> null
        }
        val proven = v.exit == "OPTIMAL" || v.exit == "UNSATISFIABLE"
        val timeMs = ((v.runtime ?: 0.0) * 1000).toLong()
        return SolverInvocation.Result(
            feasible = feasible,
            objective = v.objective,
            timeToBestMs = timeMs.takeIf { feasible == true },
            timeToFirstFeasibleMs = timeMs.takeIf { feasible == true },
            proven = proven,
            stats = mapOf("solveTime" to (v.runtime?.toString() ?: "0"), "maximize" to v.maximize.toString()),
            rawOutput = stdout,
            command = cmd.joinToString(" "),
        )
    }
}
