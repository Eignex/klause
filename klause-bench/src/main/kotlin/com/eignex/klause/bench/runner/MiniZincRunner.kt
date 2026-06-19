package com.eignex.klause.bench.runner

import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.formats.flatzinc.SolveDirective
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.maximizeInt
import com.eignex.klause.solver.objective.minimizeInt
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * Resolves [Format.MINIZINC] problems. The system `minizinc` CLI is used **only to compile**
 * `.mzn`(+`.dzn`) → `.fzn` against klause's redefinition library; the resulting FlatZinc is
 * then parsed in-process (`parseFlatZinc`) into a klause [com.eignex.klause.solver.Problem].
 * No external solver is invoked — solving is uniform across runners via the solver axis.
 */
internal class MiniZincRunner(
    private val timeoutSec: Int = System.getProperty("klause.bench.mzn.timeoutSec")?.toIntOrNull() ?: 60,
) : Runner {
    override val id = "minizinc"

    override fun supports(ref: ProblemRef): Boolean = ref.format == Format.MINIZINC

    override fun resolve(ref: ProblemRef): ResolvedProblem {
        val program = parseFlatZinc(compileFzn(ref).readText())
        val objective: LinearObjective? = when (val s = program.solve) {
            is SolveDirective.Minimize -> program.intVarsByName[s.objVar]?.let { program.problem.minimizeInt(it) }
            is SolveDirective.Maximize -> program.intVarsByName[s.objVar]?.let { program.problem.maximizeInt(it) }
            is SolveDirective.Satisfy -> null
        }
        return ResolvedProblem(
            ref,
            program.problem,
            objective,
            maximize = program.solve is SolveDirective.Maximize,
            lsObjective = program.lsObjective,
            definitionalSweep = program.definitionalSweep,
            searchParams = program.defaultBacktrackParams,
        )
    }

    /** Compile [ref]'s `.mzn`(+`.dzn`) to FlatZinc and return the `.fzn` file (used by the
     *  resolve path and by the coverage / compile-audit metrics that inspect the FZN).
     *
     *  Cached + concurrency-safe: a `.fzn` newer than its `.mzn`(+`.dzn`) sources is reused
     *  (skip recompile); a fresh compile goes to a unique temp file and is then **atomically
     *  renamed** into place, so several bench JVMs compiling the same instance in parallel can
     *  never observe a half-written `.fzn`. (Source mtimes only — bump/clean `build/mzn-fzn` if the
     *  klause redefinition library itself changes.) */
    fun compileFzn(ref: ProblemRef): File {
        require(supports(ref)) { "${ref.name}: MiniZincRunner only resolves MINIZINC problems" }
        val root = CorpusFetcher.workspaceRoot()
        val mzn = CorpusFetcher.resolve(ref.source)
        val dzn = ref.data?.let { CorpusFetcher.resolve(it) }
        val workDir = File(root, "klause-bench/build/mzn-fzn").apply { mkdirs() }
        val fzn = File(workDir, "${ref.name.replace('/', '_')}.fzn")
        val upToDate = fzn.exists() &&
            fzn.lastModified() >= mzn.lastModified() &&
            (dzn == null || fzn.lastModified() >= dzn.lastModified())
        if (!upToDate) compile(root, mzn, dzn, fzn)
        return fzn
    }

    private fun compile(root: File, mzn: File, dzn: File?, out: File) {
        val msc = File(root, "klause-mzn-lib/share/minizinc/solvers/klause.msc")
        val libDir = File(root, "klause-mzn-lib/share/minizinc/klause")
        // Compile to a unique temp, then atomically publish — concurrent compiles of the same
        // instance each write their own temp and the rename is all-or-nothing (no truncated reads).
        val tmp = File.createTempFile("${out.nameWithoutExtension}-", ".fzn.tmp", out.parentFile)
        val cmd = buildList {
            add("minizinc")
            add("--solver")
            add(msc.absolutePath)
            add("-c")
            add("-G")
            add(libDir.absolutePath)
            add("--output-fzn-to-file")
            add(tmp.absolutePath)
            add(mzn.absolutePath)
            if (dzn != null) add(dzn.absolutePath)
        }
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val finished = proc.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            tmp.delete()
            error("minizinc compile timed out after ${timeoutSec}s for ${mzn.name}")
        }
        val output = proc.inputStream.bufferedReader().readText()
        require(proc.exitValue() == 0) {
            tmp.delete()
            "minizinc compile failed (exit ${proc.exitValue()}) for ${mzn.name}: ${output.take(500)}"
        }
        require(tmp.exists()) { "minizinc compile produced no .fzn for ${mzn.name}" }
        Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
