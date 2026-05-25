package com.eignex.klause.bench.parity

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable

/**
 * MiniZinc parity harness. For a given `.mzn` (+ optional `.dzn`) instance, the runner:
 *
 *  1. Compiles the instance to FlatZinc against klause's redefinitions library, so the
 *     native-predicate set in [com.eignex.klause.solver.factor] gets first crack and the
 *     rest fall back to MiniZinc's standard decomposition.
 *  2. Parses the resulting `.fzn` for the multiset of constraint predicates that survived,
 *     buckets them by `native | decomposed`, and emits a per-instance coverage metric —
 *     the headline number we want to push toward 100%.
 *  3. Solves the instance once with klause and once with a reference solver (Gecode by
 *     default). For satisfaction problems the verdict is "both SAT" or "klause matches
 *     gecode's UNSAT"; for optimization, the verdict additionally compares optimal
 *     objective values.
 *
 * The harness shells out to the system `minizinc` CLI for compilation and reference
 * solving — the same CLI MiniZinc users invoke. The klause solve is done by piping the
 * `.fzn` through `klause-fzn-cli` (via the `.msc` config in `klause-mzn-lib`), which
 * exercises the same code path real users hit.
 */
object MznParity {

    /** Output of one parity check. Serialised into the per-sweep JSON report. */
    @Serializable
    data class Result(
        val name: String,
        val verdict: Verdict,
        /** Wall-clock for klause's solve (including JVM-side parse). */
        val klauseMs: Long,
        /** Wall-clock for the reference solver. */
        val referenceMs: Long,
        /** Multiset of FlatZinc predicate names appearing as constraint heads. */
        val predicateUsage: Map<String, Int>,
        /** Predicates klause handles natively (per `redefinitions.mzn`). */
        val nativeUsed: List<String>,
        /** Predicates that fell back to MiniZinc's standard decomposition. */
        val decomposedUsed: List<String>,
        /** `nativeUsage / totalUsage`, in [0, 1]. */
        val nativeCoverage: Double,
        /** Human-readable diagnostic. Empty on OK. */
        val detail: String,
    )

    @Serializable
    enum class Verdict {
        OK,                          // klause + reference agree
        SAT_DISAGREEMENT,            // klause SAT but reference UNSAT (or vice-versa)
        KLAUSE_INFEASIBLE,           // reference found a solution; klause's LS didn't
        OPT_VALUE_MISMATCH,          // both found solutions but optimums differ
        KLAUSE_TIMEOUT,
        REFERENCE_TIMEOUT,
        COMPILE_ERROR,               // minizinc failed to lower the .mzn against klause
        REFERENCE_UNAVAILABLE,       // no Gecode/etc. on PATH
        UNKNOWN_ERROR,
    }

    /** Configuration for a [run] call. */
    data class Config(
        val mznPath: File,
        val dznPath: File? = null,
        /** Display name; defaults to file basename. */
        val name: String = mznPath.nameWithoutExtension,
        /** Path to `klause-mzn-lib/share/minizinc/solvers/klause.msc`. */
        val klauseMsc: File,
        /** Path to `klause-mzn-lib/share/minizinc/klause/redefinitions.mzn`'s directory.
         *  Passed to `minizinc -G <dir>` so MiniZinc resolves klause's predicates first. */
        val klauseMznLibDir: File,
        /** Reference solver id (e.g. `org.gecode.gecode`). */
        val referenceSolver: String = "org.gecode.gecode",
        /** Per-solver timeout for both klause and reference; the reference also has the
         *  CLI-side flag-limit applied. */
        val timeoutSec: Int = 30,
        /** Working directory for intermediate `.fzn` files. */
        val workDir: File,
    )

    /** Run the full parity check for [cfg]. Synchronous; can take up to ~2× [Config.timeoutSec]
     *  wall-clock. */
    fun run(cfg: Config): Result {
        cfg.workDir.mkdirs()
        val fznFile = File(cfg.workDir, "${cfg.name}.fzn")
        val compileErr = compileToFzn(cfg, fznFile)
        if (compileErr != null) {
            return Result(
                name = cfg.name,
                verdict = Verdict.COMPILE_ERROR,
                klauseMs = 0L,
                referenceMs = 0L,
                predicateUsage = emptyMap(),
                nativeUsed = emptyList(),
                decomposedUsed = emptyList(),
                nativeCoverage = 0.0,
                detail = compileErr,
            )
        }
        val usage = parseFznPredicates(fznFile)
        val nativeSet = loadNativePredicateSet(cfg.klauseMznLibDir)
        val nativeUsed = usage.keys.filter { it in nativeSet }.sorted()
        val decomposedUsed = (usage.keys - nativeSet).sorted()
        val totalCount = usage.values.sum()
        val nativeCount = nativeUsed.sumOf { usage.getValue(it) }
        val coverage = if (totalCount == 0) 1.0 else nativeCount.toDouble() / totalCount

        val klauseRun = solveWithMinizinc(cfg, useKlause = true)
        val refRun = solveWithMinizinc(cfg, useKlause = false)

        val verdict = decideVerdict(klauseRun, refRun)
        return Result(
            name = cfg.name,
            verdict = verdict,
            klauseMs = klauseRun.elapsedMs,
            referenceMs = refRun.elapsedMs,
            predicateUsage = usage,
            nativeUsed = nativeUsed,
            decomposedUsed = decomposedUsed,
            nativeCoverage = coverage,
            detail = buildDetail(klauseRun, refRun, verdict),
        )
    }

    /** Compile the model to FlatZinc using klause's solver definition. Returns `null` on
     *  success or an error message on failure (the file is not written). */
    private fun compileToFzn(cfg: Config, out: File): String? {
        val cmd = buildList {
            add("minizinc")
            add("--solver"); add(cfg.klauseMsc.absolutePath)
            add("-c")
            add("-G"); add(cfg.klauseMznLibDir.absolutePath)
            add("--output-fzn-to-file"); add(out.absolutePath)
            add(cfg.mznPath.absolutePath)
            if (cfg.dznPath != null) add(cfg.dznPath.absolutePath)
        }
        val exec = runProcess(cmd, cfg.timeoutSec)
        if (exec.timedOut) return "minizinc compile timed out after ${cfg.timeoutSec}s"
        if (exec.exitCode != 0) return "minizinc compile failed (exit ${exec.exitCode}): ${exec.stderr.take(500)}"
        if (!out.exists()) return "minizinc compile reported success but produced no .fzn"
        return null
    }

    private data class SolveOutcome(
        val timedOut: Boolean,
        val sat: Boolean,
        /** Pretty-printed solution text (everything before `----------`). Empty for UNSAT. */
        val solution: String,
        /** For optimization problems, the last objective value printed by `--output-objective`. */
        val objective: Double?,
        val unsat: Boolean,
        val elapsedMs: Long,
        val stderr: String,
        val exitCode: Int,
    )

    private fun solveWithMinizinc(cfg: Config, useKlause: Boolean): SolveOutcome {
        val cmd = buildList {
            add("minizinc")
            if (useKlause) {
                add("--solver"); add(cfg.klauseMsc.absolutePath)
                add("-G"); add(cfg.klauseMznLibDir.absolutePath)
            } else {
                add("--solver"); add(cfg.referenceSolver)
            }
            add("--time-limit"); add((cfg.timeoutSec * 1000).toString())
            add("--output-objective")
            add(cfg.mznPath.absolutePath)
            if (cfg.dznPath != null) add(cfg.dznPath.absolutePath)
        }
        val started = System.currentTimeMillis()
        val exec = runProcess(cmd, cfg.timeoutSec * 2)
        val elapsed = System.currentTimeMillis() - started
        val out = exec.stdout
        val unsat = "=====UNSATISFIABLE=====" in out
        val unknown = "=====UNKNOWN=====" in out
        val terminatedSat = "----------" in out
        // Extract last `_objective = <number>;` line if present.
        val objLine = Regex("""_objective\s*=\s*(-?\d+(?:\.\d+)?)""").findAll(out).lastOrNull()
        val objective = objLine?.groupValues?.get(1)?.toDoubleOrNull()
        return SolveOutcome(
            timedOut = exec.timedOut || unknown,
            sat = terminatedSat && !unsat,
            solution = out.substringBefore("----------").trim(),
            objective = objective,
            unsat = unsat,
            elapsedMs = elapsed,
            stderr = exec.stderr,
            exitCode = exec.exitCode,
        )
    }

    private fun decideVerdict(klause: SolveOutcome, reference: SolveOutcome): Verdict {
        if (klause.timedOut && klause.elapsedMs > 0 && !klause.sat) return Verdict.KLAUSE_TIMEOUT
        if (reference.timedOut && !reference.sat && !reference.unsat) return Verdict.REFERENCE_TIMEOUT
        if (reference.exitCode != 0 && reference.stderr.contains("Solver not found")) {
            return Verdict.REFERENCE_UNAVAILABLE
        }
        // Both have a definitive verdict.
        if (reference.unsat && klause.sat) return Verdict.SAT_DISAGREEMENT
        if (reference.sat && klause.unsat) return Verdict.SAT_DISAGREEMENT
        if (reference.sat && klause.timedOut) return Verdict.KLAUSE_INFEASIBLE
        if (reference.sat && !klause.sat && !klause.timedOut) return Verdict.KLAUSE_INFEASIBLE
        if (reference.sat && klause.sat && reference.objective != null && klause.objective != null) {
            if (kotlin.math.abs(reference.objective - klause.objective) > 1e-6) {
                return Verdict.OPT_VALUE_MISMATCH
            }
        }
        if (klause.exitCode != 0 && reference.exitCode == 0) return Verdict.UNKNOWN_ERROR
        return Verdict.OK
    }

    private fun buildDetail(klause: SolveOutcome, reference: SolveOutcome, verdict: Verdict): String =
        when (verdict) {
            Verdict.OK -> ""
            Verdict.SAT_DISAGREEMENT -> "klause sat=${klause.sat}/unsat=${klause.unsat} vs ref sat=${reference.sat}/unsat=${reference.unsat}"
            Verdict.KLAUSE_INFEASIBLE -> "ref found solution; klause did not within budget. klause stderr: ${klause.stderr.take(200)}"
            Verdict.OPT_VALUE_MISMATCH -> "klause obj=${klause.objective}, ref obj=${reference.objective}"
            Verdict.KLAUSE_TIMEOUT -> "klause timed out (${klause.elapsedMs} ms). stderr: ${klause.stderr.take(200)}"
            Verdict.REFERENCE_TIMEOUT -> "reference solver timed out"
            Verdict.COMPILE_ERROR -> "compile failed"  // populated upstream
            Verdict.REFERENCE_UNAVAILABLE -> "reference solver missing on PATH"
            Verdict.UNKNOWN_ERROR -> "klause exit=${klause.exitCode} stderr=${klause.stderr.take(300)}"
        }

    /** Multiset of predicate names appearing as the head of a `constraint <pred>(...)` row
     *  in [fznFile]. */
    private fun parseFznPredicates(fznFile: File): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        val constraintHead = Regex("""^\s*constraint\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        fznFile.useLines { lines ->
            for (line in lines) {
                val m = constraintHead.find(line) ?: continue
                val name = m.groupValues[1]
                counts.merge(name, 1) { old, _ -> old + 1 }
            }
        }
        return counts
    }

    /** Parse `predicate <name>(...)` from klause's `redefinitions.mzn`. Each declaration
     *  names a predicate the klause-fzn-cli backend handles natively. */
    private fun loadNativePredicateSet(mznLibDir: File): Set<String> {
        val redefFile = File(mznLibDir, "redefinitions.mzn")
        if (!redefFile.isFile) return emptySet()
        val predicateDecl = Regex("""^\s*predicate\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        return buildSet {
            redefFile.useLines { lines ->
                for (line in lines) {
                    val m = predicateDecl.find(line) ?: continue
                    add(m.groupValues[1])
                }
            }
        }
    }

    private data class ProcessExec(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
    )

    private fun runProcess(cmd: List<String>, timeoutSec: Int): ProcessExec {
        val pb = ProcessBuilder(cmd).redirectErrorStream(false)
        val proc = pb.start()
        proc.outputStream.close()
        val finished = proc.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return ProcessExec(exitCode = -1, stdout = "", stderr = "timed out", timedOut = true)
        }
        val out = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        return ProcessExec(proc.exitValue(), out, err, timedOut = false)
    }
}
