package com.eignex.klause.bench.runner

import com.eignex.klause.backtrack.toBacktrackParams
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.metric.MINIZINC_SOLUTION_SEPARATOR
import com.eignex.klause.bench.metric.SolveEvidence
import com.eignex.klause.bench.metric.SolverOutput
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.FlatZincObjective
import com.eignex.klause.formats.flatzinc.linearObjective
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.flatzinc.toSearchHints
import com.eignex.klause.ir.ObjectiveSense
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

// The system `minizinc` CLI only compiles to FlatZinc against klause's redefinition library; in-process consumers
// request parsing lazily through ResolvedProblem.
internal class MiniZincRunner(
    private val timeoutSec: Int = System.getProperty("klause.bench.mzn.timeoutSec")?.toIntOrNull() ?: 60,
    private val exactFloats: Boolean = false,
) : Runner {
    override val id = "minizinc"

    override fun supports(ref: ProblemRef): Boolean = ref.format == Format.MINIZINC

    override fun resolve(ref: ProblemRef): ResolvedProblem = resolveCompiled(ref, compileFzn(ref))

    internal fun resolveCompiled(ref: ProblemRef, fzn: File): ResolvedProblem {
        val sense = FlatZincObjective.read(fzn)
        val program by lazy { parseFlatZinc(fzn.readText(), exactFloats = exactFloats) }
        return ResolvedProblem(
            ref,
            lazy { program.problem },
            deferredObjective = lazy { program.linearObjective() },
            optimizes = sense != null,
            maximize = sense == ObjectiveSense.MAXIMIZE,
            deferredSearchParams = lazy {
                program.searchHints?.toSearchHints()?.toBacktrackParams(
                    program.problem.numBoolVars,
                    program.problem.numIntVars,
                )
            },
        )
    }

    // The cache binds model/data, compiler identity and solver-library contents to paired FlatZinc and `.ozn` output
    // mappings. The output mapping is published before the `.fzn`, which acts as the commit marker.
    fun compileFzn(ref: ProblemRef, fresh: Boolean = false): File {
        require(supports(ref)) { "${ref.name}: MiniZincRunner only resolves MINIZINC problems" }
        val root = CorpusFetcher.workspaceRoot()
        val mzn = CorpusFetcher.resolve(ref.source)
        val dzn = ref.data?.let { CorpusFetcher.resolve(it) }
        val includes = CorpusFetcher.includeDirs(ref.source)
        val includedFiles = includes.flatMap { it.walkTopDown().filter(File::isFile).toList() }
            .distinctBy { it.canonicalPath }.sortedBy { it.path }
        val workDir = File(root, "klause-bench/build/mzn-fzn-output-v3-seed$MZN_RANDOM_SEED").apply { mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(MiniZincCompatibility.compiler.toString().toByteArray())
        for (
        source in listOfNotNull(mzn, dzn) + includedFiles +
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
        if (fresh || !upToDate) compile(root, mzn, dzn, fzn, includes)
        return fzn
    }

    fun compileOutput(ref: ProblemRef): File = outputFile(compileFzn(ref))

    // solns2out evaluates the compiled output mapping, so the result assigns the model's output variables, enum values
    // by name and `_objective`, never FlatZinc-introduced variables. Throws, saying why, when the mapping fails.
    fun modelOutput(ref: ProblemRef, flatSolution: Sequence<String>, maxChars: Int): String? {
        val ozn = compileOutput(ref)
        val dir = Files.createTempDirectory("klause-ozn-").toFile()
        try {
            val input = File(dir, "flat.dzn")
            input.bufferedWriter().use { writer ->
                for (line in flatSolution) writer.appendLine(line)
                writer.appendLine(MINIZINC_SOLUTION_SEPARATOR)
            }
            val output = File(dir, "model.dzn")
            val log = File(dir, "solns2out.log")
            val proc = ProcessBuilder(MiniZincCompatibility.compiler.executable, "--ozn-file", ozn.absolutePath)
                .redirectInput(input).redirectOutput(output).redirectError(log).start()
            try {
                check(proc.waitFor(OUTPUT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "minizinc --ozn-file timed out after ${OUTPUT_TIMEOUT_SECONDS}s"
                }
                check(proc.exitValue() == 0) {
                    val diagnostic = CharArray(500)
                    val size = log.bufferedReader().use { it.read(diagnostic).coerceAtLeast(0) }
                    "minizinc --ozn-file failed (exit ${proc.exitValue()}): ${diagnostic.concatToString(0, size).trim()}"
                }
                return SolveEvidence.mappedMiniZincSolution(SolverOutput.Spool(output), maxChars)
            } finally {
                if (proc.isAlive) proc.destroyForcibly().waitFor()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    fun outputFile(fzn: File): File = File(fzn.parentFile, "${fzn.nameWithoutExtension}.ozn")

    private fun compile(root: File, mzn: File, dzn: File?, out: File, includes: List<File>) {
        val sourceHashes = buildMap {
            put("model", MiniZincCompatibility.hash(mzn))
            if (dzn != null) put("data", MiniZincCompatibility.hash(dzn))
        }
        val msc = File(root, "klause-mzn-lib/share/minizinc/solvers/klause.msc")
        val libDir = File(root, "klause-mzn-lib/share/minizinc/klause")
        // Each concurrent compile writes its own temp; the atomic rename means a reader never sees a truncated file.
        val tmp = File.createTempFile("${out.nameWithoutExtension}-", ".fzn.tmp", out.parentFile)
        val tmpOzn = File.createTempFile("${out.nameWithoutExtension}-", ".ozn.tmp", out.parentFile)
        val cmd = buildList {
            add(MiniZincCompatibility.compiler.executable)
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
            includes.forEach {
                add("-I")
                add(it.absolutePath)
            }
            add(mzn.absolutePath)
            if (dzn != null) add(dzn.absolutePath)
        }
        val log = File.createTempFile("${out.nameWithoutExtension}-", ".log", out.parentFile)
        val start = System.nanoTime()
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log).start()
        try {
            val finished = proc.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly().waitFor()
                throw MiniZincCompileFailure(
                    proc.exitValue(),
                    "minizinc compile timed out after ${timeoutSec}s for ${mzn.name}\n${log.readText()}".trimEnd(),
                    MiniZincCompatibility.compiler,
                    cmd,
                    System.nanoTime() - start,
                    sourceHashes,
                    timeoutSec = timeoutSec,
                )
            }
            val output = log.readText()
            check(
                sourceHashes["model"] == MiniZincCompatibility.hash(mzn) &&
                    (dzn == null || sourceHashes["data"] == MiniZincCompatibility.hash(dzn)),
            ) { "MiniZinc source changed during compilation" }
            if (proc.exitValue() != 0) {
                throw MiniZincCompileFailure(
                    proc.exitValue(),
                    output,
                    MiniZincCompatibility.compiler,
                    cmd,
                    System.nanoTime() - start,
                    sourceHashes,
                )
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

// Some models draw their data at compile time (`uniform`, `bernoulli`, ...), so an unseeded compile would make klause
// and the reference solve different instances.
internal const val MZN_RANDOM_SEED = 1

private const val OUTPUT_TIMEOUT_SECONDS = 30L
