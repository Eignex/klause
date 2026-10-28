package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.MZN_RANDOM_SEED
import com.eignex.klause.bench.runner.MiniZincCompatibility
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.factor.codec.FactorCodecs
import com.eignex.klause.factor.codec.MissingFactorCodecException
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.formats.FormatException
import com.eignex.klause.formats.flatzinc.FlatZincArray
import com.eignex.klause.formats.flatzinc.FlatZincProgram
import com.eignex.klause.formats.flatzinc.OznApplier
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.flatzinc.writeFlatZincSolution
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal object OptionalUnaryProfile {
    private const val WORKFORCE_REVISION = "a8448864fc56162583f24aaf9c25653d93f83765"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty() || args.singleOrNull() in setOf("mode=control", "mode=semantics"))
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("build.json").path, InstalledBuild.reference)
        Reports.writeJson(out.resolve("compiler.json").path, MiniZincCompatibility.compiler)
        out.resolve("runtime.txt").writeText(listOf(
            System.getProperty("os.name"), System.getProperty("os.arch"), System.getProperty("java.version"),
            ManagementFactory.getRuntimeMXBean().inputArguments.toString(),
        ).joinToString("\n"))
        if (args.singleOrNull() == "mode=semantics") {
            for (mode in 1..10) {
                val directory = out.resolve("mode-$mode").apply { mkdirs() }
                File(CorpusFetcher.workspaceRoot(), "klause-mzn-lib/test-models/optional-unary-semantics.mzn")
                    .copyTo(directory.resolve("source.mzn"))
                directory.resolve("data.dzn").writeText("mode = $mode;\n")
                inspectPair(directory, out, listOf("off"))
            }
            archive(out)
            return
        }
        val control = out.resolve("control").apply { mkdirs() }
        File(CorpusFetcher.workspaceRoot(), "klause-mzn-lib/test-models/optional-unary.mzn")
            .copyTo(control.resolve("source.mzn"))
        inspectPair(control, out)
        if (args.isNotEmpty()) {
            archive(out)
            return
        }
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
        inspectPair(workforce, out)
        archive(out)
    }

    private fun archive(out: File) {
        val files = out.walkTopDown().filter { it.isFile && it.name != "profile.jfr" }.toList()
        ZipOutputStream(out.resolve("capture.zip").outputStream()).use { zip ->
            for (file in files) {
                zip.putNextEntry(ZipEntry(file.relativeTo(out).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        for (file in files) check(file.delete())
        out.walkBottomUp().filter { it.isDirectory && it != out }.forEach { check(it.delete()) }
    }

    private fun inspectPair(source: File, out: File, prepares: List<String> = listOf("off", "default", "aggressive")) {
        val root = CorpusFetcher.workspaceRoot()
        val nativeLibrary = File(root, "klause-mzn-lib/share/minizinc/klause")
        val nativeConfig = File(root, "klause-mzn-lib/share/minizinc/solvers/klause.msc")
        val baselineLibrary = out.resolve("baseline-library")
        if (!baselineLibrary.exists()) {
            check(nativeLibrary.copyRecursively(baselineLibrary))
            check(baselineLibrary.resolve("fzn_disjunctive_opt.mzn").delete())
            check(baselineLibrary.resolve("fzn_disjunctive_strict_opt.mzn").delete())
        }
        val baselineConfig = out.resolve("baseline.msc")
        baselineConfig.writeText(JsonObject(Reports.json.parseToJsonElement(nativeConfig.readText()).jsonObject +
            mapOf("mznlib" to JsonPrimitive(baselineLibrary.absolutePath),
                "executable" to JsonPrimitive(File(root, "klause-mzn-lib/bin/klause-fzn").absolutePath))).toString())
        Reports.writeJson(out.resolve("baseline-compiler.json").path, MiniZincCompatibility.compiler.copy(
            solverLibrarySha256 = MiniZincCompatibility.treeHash(baselineLibrary),
            configurationSha256 = MiniZincCompatibility.hash(baselineConfig),
        ))
        for ((name, library, config) in listOf(
            Triple("decomposition", baselineLibrary, baselineConfig),
            Triple("native", nativeLibrary, nativeConfig),
        )) {
            val directory = source.resolve(name).apply { mkdirs() }
            source.resolve("source.mzn").copyTo(directory.resolve("source.mzn"))
            source.resolve("data.dzn").takeIf { it.isFile }?.copyTo(directory.resolve("data.dzn"))
            inspect(directory, library, config, prepares)
        }
    }

    private fun inspect(out: File, library: File, config: File, prepares: List<String>) {
        val command = mutableListOf(
            MiniZincCompatibility.compiler.executable, "--solver",
            config.absolutePath,
            "-c", "--output-mode", "item", "--output-objective", "--random-seed", MZN_RANDOM_SEED.toString(),
            "-G", library.absolutePath,
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
        } catch (failure: FormatException) {
            out.resolve("load-error.txt").writeText(failure.stackTraceToString())
            return
        }
        out.resolve("parse-ns.txt").writeText((System.nanoTime() - parseStart).toString())
        out.resolve("names.txt").writeText("${parsed.boolVarsByName}\n${parsed.intVarsByName}\n")
        dump(parsed.problem, out.resolve("factors.txt"))
        for (prepare in prepares) {
            capture(parsed, parsed.problem, prepare, out.resolve(prepare))
        }
    }

    private fun capture(program: FlatZincProgram, model: Problem, prepare: String, out: File) {
        out.mkdirs()
        val stop = Cancellation.after(5.seconds)
        val budget = PresolveBudget(1_650_000_000L)
        val started = System.nanoTime()
        val prepared = PresolvePipeline.run(model, null, PresolveConfig.parse(prepare),
            solutionSetSensitive = false, cancellation = stop, presolveBudget = budget, randomSeed = 3)
        val preparationNs = System.nanoTime() - started
        out.resolve("preparation.txt").writeText(prepared.stats.toString())
        dump(prepared.problem, out.resolve("factors.txt"))
        val rootStart = System.nanoTime()
        val session = PropagationSession(prepared.problem, cancellation = stop)
        val rootNs = System.nanoTime() - rootStart
        out.resolve("root.txt").writeText(
            (0 until prepared.problem.numBoolVars).joinToString("\n") { "bool[$it]=${session.boolValue(it)}" } +
                "\n" + (0 until prepared.problem.numIntVars).joinToString("\n") { "int[$it]=${session.intDomain(it)}" },
        )
        val allocations = program.arraysByName["Alloc"] as? FlatZincArray.Vars
        if (allocations != null && allocations.varIds.size == 3) {
            val ids = allocations.varIds
            val live = prepared.problem.factors.flatMap { it.intVars.toList() }.toSet()
            val first = ids.take(2).firstOrNull { it in live }
            if (first != null && ids[2] in live) {
                val prefix = PropagationSession(prepared.problem, cancellation = stop)
                val before = prefix.intDomain(ids[2]).toString()
                val pin = prefix.pinInt(first, 1)
                out.resolve("allocation-prefix.json").writeText(buildJsonObject {
                    put("pinnedSourceCoordinate", first)
                    put("pinnedValue", 1)
                    put("targetSourceCoordinate", ids[2])
                    put("before", before)
                    put("after", prefix.intDomain(ids[2]).toString())
                    put("result", pin.javaClass.simpleName)
                    put("note", "live source slots; first two allocations equal in the original source")
                }.toString())
            }
        }
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
            out.resolve("flat.dzn").writeText(writeFlatZincSolution(program, it) + "\n----------\n")
            val renderer = ProcessBuilder(MiniZincCompatibility.compiler.executable, "--ozn-file",
                out.parentFile.resolve("source.ozn").absolutePath)
                .redirectInput(out.resolve("flat.dzn"))
                .redirectOutput(out.resolve("compiler-output.txt"))
                .redirectError(out.resolve("compiler-output.log")).start()
            val rendered = renderer.waitFor(5, TimeUnit.SECONDS)
            if (!rendered) renderer.destroyForcibly().waitFor()
            out.resolve("compiler-output-exit.txt").writeText("completed=$rendered exit=${renderer.exitValue()}\n")
            try {
                out.resolve("output.txt").writeText(OznApplier(out.parentFile.resolve("source.ozn").readText())
                    .render(program, it))
            } catch (failure: FormatException) {
                out.resolve("output-error.txt").writeText(failure.stackTraceToString())
            }
        }
        out.resolve("summary.json").writeText(buildJsonObject {
            put("preparationNs", preparationNs)
            put("preparationWork", budget.spent())
            put("probeCalls", budget.probeCalls)
            put("preparationAllowance", budget.allowance)
            put("rootNs", rootNs)
            put("rootPropagations", session.propagationCount)
            put("preparedUnaryFactors", prepared.problem.factors.filterIsInstance<Cumulative>().count { it.unary })
            program.boolVarsByName["p"]?.takeIf { it < prepared.problem.numBoolVars }?.let {
                put("originalPAtRoot", session.boolValue(it))
            }
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
            problem.factors.joinToString("\n") { factor ->
                try {
                    FactorCodecs.builtin.encodeToJsonElement(factor).toString()
                } catch (_: MissingFactorCodecException) {
                    buildJsonObject {
                        put("derivedClass", factor.javaClass.name)
                        put("boolVars", factor.boolVars.contentToString())
                        put("intVars", factor.intVars.contentToString())
                        for (name in listOf("getEdges", "getPrivateVertices", "getIndexGuards")) {
                            factor.javaClass.methods.singleOrNull { it.name == name }?.let {
                                put(name, it.invoke(factor).toString())
                            }
                        }
                    }.toString()
                }
            } + "\n")
    }
}
