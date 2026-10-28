package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.MiniZincCompatibility
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.formats.flatzinc.FlatZincProgram
import com.eignex.klause.formats.flatzinc.OznApplier
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolvePipeline
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.Cancellation
import java.io.File
import java.lang.management.ManagementFactory
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object OptionalUnaryProfile {
    private const val WORKFORCE_REVISION = "a8448864fc56162583f24aaf9c25653d93f83765"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty())
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("build.json").path, InstalledBuild.reference)
        Reports.writeJson(out.resolve("compiler.json").path, MiniZincCompatibility.compiler)
        out.resolve("runtime.txt").writeText(listOf(
            System.getProperty("os.name"), System.getProperty("os.arch"), System.getProperty("java.version"),
            ManagementFactory.getRuntimeMXBean().inputArguments.toString(),
        ).joinToString("\n"))
        val control = out.resolve("control").apply { mkdirs() }
        File(CorpusFetcher.workspaceRoot(), "klause-mzn-lib/test-models/optional-unary.mzn")
            .copyTo(control.resolve("source.mzn"))
        inspect(control, control = true)
        val workforce = out.resolve("workforce").apply { mkdirs() }
        val url = "https://raw.githubusercontent.com/MiniZinc/mzn-challenge/" +
            "$WORKFORCE_REVISION/2026/workforce-alloc/scheduling_model.mzn"
        val bytes = URI(url).toURL().openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 30_000
        }.getInputStream().use { it.readNBytes(1024 * 1024 + 1) }
        require(bytes.size <= 1024 * 1024)
        workforce.resolve("source.mzn").writeBytes(bytes)
        workforce.resolve("origin.txt").writeText("$url\n${sha256(bytes)}\n")
        workforce.resolve("data.dzn").writeText("""
            Team = {A, B}; Task = {T1, T2, T3}; horizon = 4;
            duration = [2, 2, 2]; released = [0, 0, 0]; due = [4, 4, 4];
            compatible = array2d(Task, Team, [1, 1, 1, 1, 1, 1]);
            same_allocation = [{T1, T2}]; calendars = []; successors = [];
            original_start = [0, 0, 0];
        """.trimIndent() + "\n")
        inspect(workforce, control = false)
    }

    private fun inspect(out: File, control: Boolean) {
        val root = CorpusFetcher.workspaceRoot()
        val command = mutableListOf(
            MiniZincCompatibility.compiler.executable, "--solver",
            File(root, "klause-mzn-lib/share/minizinc/solvers/klause.msc").path,
            "-c", "--output-mode", "dzn", "--output-objective", "--random-seed", "42",
            "-G", File(root, "klause-mzn-lib/share/minizinc/klause").path,
            "--output-ozn-to-file", out.resolve("source.ozn").absolutePath,
            "--output-fzn-to-file", out.resolve("source.fzn").absolutePath,
            out.resolve("source.mzn").absolutePath,
        )
        out.resolve("data.dzn").takeIf { it.isFile }?.let { command += it.absolutePath }
        out.resolve("command.json").writeText(JsonArray(command.map(::JsonPrimitive)).toString())
        val started = System.nanoTime()
        val process = ProcessBuilder(command).redirectErrorStream(true)
            .redirectOutput(out.resolve("compiler.log")).start()
        val completed = process.waitFor(30, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly().waitFor()
        out.resolve("compile.json").writeText(buildJsonObject {
            put("completed", completed)
            put("exit", process.exitValue())
            put("elapsedNs", System.nanoTime() - started)
            put("modelSha256", sha256(out.resolve("source.mzn").readBytes()))
            out.resolve("data.dzn").takeIf { it.isFile }?.let { put("dataSha256", sha256(it.readBytes())) }
        }.toString())
        if (!completed || process.exitValue() != 0) return
        val parseStart = System.nanoTime()
        val parsed = try {
            parseFlatZinc(out.resolve("source.fzn").readText())
        } catch (failure: IllegalArgumentException) {
            out.resolve("load-error.txt").writeText(failure.stackTraceToString())
            return
        }
        out.resolve("parse-ns.txt").writeText((System.nanoTime() - parseStart).toString())
        out.resolve("names.txt").writeText("${parsed.boolVarsByName}\n${parsed.intVarsByName}\n")
        dump(parsed.problem, out.resolve("factors.txt"))
        for (prepare in listOf("off", "default", "aggressive")) {
            capture(parsed, parsed.problem, prepare, out.resolve("decomposition-$prepare"))
            if (control) capture(parsed, retained(parsed), prepare, out.resolve("retained-$prepare"))
        }
    }

    private fun retained(program: FlatZincProgram): Problem {
        val source = program.problem
        val always = source.numBoolVars
        val present = Lit.make(always, true)
        val factors = source.factors + arrayOf(
            Clause(intArrayOf(present)),
            Cumulative.unary(
                listOf("a", "b", "c").map { program.intVarsByName.getValue(it) }.toIntArray(),
                longArrayOf(2, 2, 2),
                intArrayOf(present, present, Lit.make(program.boolVarsByName.getValue("p"), true)),
            ),
        )
        return Problem(source.numBoolVars + 1, source.numIntVars, source.declaredIntDomains, factors,
            settings = source.settings, semantics = source.semantics)
    }

    private fun capture(program: FlatZincProgram, model: Problem, prepare: String, out: File) {
        out.mkdirs()
        val stop = Cancellation.after(5.seconds)
        val budget = PresolveBudget(1_000_000L)
        val started = System.nanoTime()
        val prepared = PresolvePipeline.run(model, null, PresolveConfig.parse(prepare),
            solutionSetSensitive = true, cancellation = stop, presolveBudget = budget, randomSeed = 3)
        val preparationNs = System.nanoTime() - started
        dump(prepared.problem, out.resolve("factors.txt"))
        val rootStart = System.nanoTime()
        val session = PropagationSession(prepared.problem, cancellation = stop)
        val rootNs = System.nanoTime() - rootStart
        out.resolve("root.txt").writeText(
            (0 until prepared.problem.numBoolVars).joinToString("\n") { "bool[$it]=${session.boolValue(it)}" } +
                "\n" + (0 until prepared.problem.numIntVars).joinToString("\n") { "int[$it]=${session.intDomain(it)}" },
        )
        val solveStart = System.nanoTime()
        val result = BacktrackSolver(prepared.problem.bake(cancellation = stop)).solve(BacktrackParams(
            maxDecisions = 128, randomSeed = 3, cancellation = stop,
        ))
        val solveNs = System.nanoTime() - solveStart
        val sample = (result as? SolveResult.Sat)?.assignment?.let(prepared.reconstruct)?.let {
            Sample(it.bools.copyOf(program.problem.numBoolVars), it.ints)
        }
        val checked = sample?.let { FiniteConflictSelection.checkSource(program.problem.bake(), it, stop) }
        sample?.let {
            out.resolve("assignment.txt").writeText("${it.bools.contentToString()}\n${it.ints.contentToString()}\n")
            try {
                out.resolve("output.txt").writeText(OznApplier(out.parentFile.resolve("source.ozn").readText())
                    .render(program, it))
            } catch (failure: IllegalArgumentException) {
                out.resolve("output-error.txt").writeText(failure.stackTraceToString())
            }
        }
        out.resolve("summary.json").writeText(buildJsonObject {
            put("preparationNs", preparationNs)
            put("preparationWork", budget.spent())
            put("preparationAllowance", budget.allowance)
            put("rootNs", rootNs)
            put("rootPropagations", session.propagationCount)
            put("solveNs", solveNs)
            put("result", result.javaClass.simpleName)
            put("nodes", result.stats.search.nodes.sum)
            put("fails", result.stats.search.fails.sum)
            put("propagationWork", result.stats.search.propagationWork.sum)
            put("flatSourceWitnessAccepted", checked)
            put("independentMiniZincSourceCheck", false)
            put("satisfactionProjection", true)
            put("deadlineReached", stop())
        }.toString())
    }

    private fun dump(problem: Problem, file: File) {
        file.writeText("bools=${problem.numBoolVars} ints=${problem.numIntVars}\n" +
            problem.factors.joinToString("\n") { it.toString() } + "\n")
    }
}
