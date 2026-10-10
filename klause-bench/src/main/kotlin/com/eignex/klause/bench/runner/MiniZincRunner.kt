package com.eignex.klause.bench.runner

import com.eignex.klause.backtrack.toBacktrackParams
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.formats.flatzinc.SolveDirective
import com.eignex.klause.solver.pipeline.linearObjective
import com.eignex.klause.solver.pipeline.parseFlatZincExecution
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Resolves [Format.MINIZINC] problems. The system `minizinc` CLI is used **only to compile**
 * `.mzn`(+`.dzn`) → `.fzn` against klause's redefinition library; the resulting FlatZinc is
 * then parsed in-process into a klause [com.eignex.klause.ir.Problem].
 * No external solver is invoked — solving is uniform across runners via the solver axis.
 */
internal class MiniZincRunner(
    private val timeoutSec: Int = System.getProperty("klause.bench.mzn.timeoutSec")?.toIntOrNull() ?: 60,
    private val exactFloats: Boolean = false,
) : Runner {
    override val id = "minizinc"

    override fun supports(ref: ProblemRef): Boolean = ref.format == Format.MINIZINC

    override fun resolve(ref: ProblemRef): ResolvedProblem {
        val executionProgram = parseFlatZincExecution(compileFzn(ref).readText(), exactFloats = exactFloats)
        val program = executionProgram.program
        val objective = program.linearObjective()
        return ResolvedProblem(
            ref,
            lazyOf(program.problem),
            objective,
            maximize = program.solve is SolveDirective.Maximize,
            lsObjective = executionProgram.localSearchObjective,
            definitionalSweep = executionProgram.definitionalSweep,
            hasFloats = program.floatVarsByName.isNotEmpty(),
            floatApproximation = program.floatVarsByName.values.any { !it.lpOnly },
            searchParams = program.searchHints?.toBacktrackParams(
                program.problem.numBoolVars,
                program.problem.numIntVars,
            ),
        )
    }

    /** Compile [ref]'s `.mzn`(+`.dzn`) to FlatZinc and return the `.fzn` file (used by the
     *  resolve path and by the coverage / compile-audit metrics that inspect the FZN).
     *
     *  The cache binds model/data and solver-library contents to paired FlatZinc and DZN output
     *  mappings. Each compile publishes the output mapping before the FlatZinc commit marker;
     *  concurrent compiles use unique temporary files and atomic replacement. */
    fun compileFzn(ref: ProblemRef): File {
        require(supports(ref)) { "${ref.name}: MiniZincRunner only resolves MINIZINC problems" }
        val root = CorpusFetcher.workspaceRoot()
        val mzn = CorpusFetcher.resolve(ref.source)
        val dzn = ref.data?.let { CorpusFetcher.resolve(it) }
        val workDir = File(root, "klause-bench/build/mzn-fzn-output-v2-seed$MZN_RANDOM_SEED").apply { mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256")
        for (
        source in listOfNotNull(mzn, dzn) +
            File(root, "klause-mzn-lib/share/minizinc").walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
        ) {
            digest.update(source.readBytes())
            digest.update(0.toByte())
        }
        val hash = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
        val fzn = File(workDir, "${ref.name.replace('/', '_')}-$hash.fzn")
        val upToDate = fzn.exists() && outputFile(fzn).exists() &&
            fzn.lastModified() >= mzn.lastModified() &&
            (dzn == null || fzn.lastModified() >= dzn.lastModified())
        if (!upToDate) compile(root, mzn, dzn, fzn)
        return fzn
    }

    fun compileOutput(ref: ProblemRef): File = outputFile(compileFzn(ref))

    private fun outputFile(fzn: File): File = File(fzn.parentFile, "${fzn.nameWithoutExtension}.ozn")

    private fun compile(root: File, mzn: File, dzn: File?, out: File) {
        val msc = File(root, "klause-mzn-lib/share/minizinc/solvers/klause.msc")
        val libDir = File(root, "klause-mzn-lib/share/minizinc/klause")
        // Compile to a unique temp, then atomically publish — concurrent compiles of the same
        // instance each write their own temp and the rename is all-or-nothing (no truncated reads).
        val tmp = File.createTempFile("${out.nameWithoutExtension}-", ".fzn.tmp", out.parentFile)
        val tmpOzn = File.createTempFile("${out.nameWithoutExtension}-", ".ozn.tmp", out.parentFile)
        val cmd = buildList {
            add("minizinc")
            add("--solver")
            add(msc.absolutePath)
            add("-c")
            add("--output-mode")
            add("dzn")
            add("--output-objective")
            add("--output-ozn-to-file")
            add(tmpOzn.absolutePath)
            add("--random-seed")
            add(MZN_RANDOM_SEED.toString())
            add("-G")
            add(libDir.absolutePath)
            add("--output-fzn-to-file")
            add(tmp.absolutePath)
            add(mzn.absolutePath)
            if (dzn != null) add(dzn.absolutePath)
        }
        val log = File.createTempFile("${out.nameWithoutExtension}-", ".log", out.parentFile)
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log).start()
        try {
            val finished = proc.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                tmp.delete()
                error("minizinc compile timed out after ${timeoutSec}s for ${mzn.name}")
            }
            val output = log.readText()
            require(proc.exitValue() == 0) {
                tmp.delete()
                "minizinc compile failed (exit ${proc.exitValue()}) for ${mzn.name}: ${output.take(500)}"
            }
            require(tmp.exists()) { "minizinc compile produced no .fzn for ${mzn.name}" }
            require(tmpOzn.length() > 0) { "minizinc compile produced no output mapping" }
            Files.move(
                tmpOzn.toPath(),
                outputFile(out).toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (proc.isAlive) proc.destroyForcibly().waitFor()
            tmp.delete()
            tmpOzn.delete()
            log.delete()
        }
    }
}

/**
 * The seed every MiniZinc compile and reference solve runs with. Some models draw their data at compile time
 * (`uniform`, `bernoulli`, ...), and an unseeded compile draws afresh on every run, so klause and the reference
 * would otherwise solve different instances.
 */
internal const val MZN_RANDOM_SEED = 1
