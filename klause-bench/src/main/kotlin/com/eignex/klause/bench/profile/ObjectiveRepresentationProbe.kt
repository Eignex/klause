package com.eignex.klause.bench.profile

import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.compile.CompiledSchema
import com.eignex.klause.compile.compile
import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.formats.json.JsonSchema
import com.eignex.klause.formats.smtlib.SmtLibAdmission
import com.eignex.klause.formats.smtlib.SmtLibInput
import com.eignex.klause.ir.PolynomialRealProblem
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.model.FloatSpec
import com.eignex.klause.schema.BoolHandle
import com.eignex.klause.schema.FloatHandle
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toQuadraticObjectiveSpec
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.PolynomialStrategyParams
import com.eignex.klause.solver.pipeline.PolynomialTheoryParams
import com.eignex.klause.solver.pipeline.minimize
import com.eignex.klause.solver.pipeline.optimize
import com.eignex.klause.solver.pipeline.pipelineRequest
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.div
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.gcd
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object ObjectiveRepresentationProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { argument ->
            val parts = argument.split('=', limit = 2)
            require(parts.size == 2) { "Use source=<file or directory> [engines=BACKTRACK,...] [out=<directory>]" }
            parts[0] to parts[1]
        }
        require(options.keys.all {
            it in setOf(
                "source", "engines", "out", "work", "seed", "moves", "timeout", "checker", "processors", "schema",
            )
        })
        val processors = options["processors"]?.toInt() ?: 1
        require(processors > 0)
        if (Runtime.getRuntime().availableProcessors() != processors) {
            launchWithProcessors(args, options, processors)
            return
        }
        val input = File(requireNotNull(options["source"]) { "source is required" })
        val sources = if (input.isDirectory) input.walkTopDown().filter { it.extension == "smt2" }.toList()
            else listOf(input)
        require(sources.isNotEmpty() && sources.all { it.isFile }) { "No SMT sources at $input" }
        val output = File(options["out"] ?: requireNotNull(System.getenv("OUT")) { "out or OUT is required" })
        require(output.mkdirs() || output.isDirectory)
        val engines = options["engines"]?.split(',')?.map(FiniteEngine::valueOf) ?: FiniteEngine.entries
        require(engines.isNotEmpty() && engines.distinct().size == engines.size)
        val work = options["work"]?.toLong() ?: 200000L
        val seed = options["seed"]?.toLong() ?: 17L
        val moves = options["moves"]?.toInt() ?: 1
        val timeout = options["timeout"]?.toLong() ?: 25000L
        require(work > 0 && moves >= 0 && timeout > 0)
        val schemaBytes = options["schema"]?.let { File(it).readBytes() }
        Reports.writeJson(File(output, "provenance.json").path, InstalledBuild.reference)
        File(output, "settings.json").writeText(buildJsonObject {
            put("revision", Reports.readGitSha())
            put("seed", seed)
            put("nativeWork", work)
            put("maxFlips", moves)
            put("alnsIterations", moves)
            put("timeoutMs", timeout)
            put("javaCores", Runtime.getRuntime().availableProcessors())
            put("jvmOptions", ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" "))
            put("schemaSha256", schemaBytes?.let(::sha256))
        }.toString() + "\n")
        val schema = schemaBytes?.toString(Charsets.UTF_8)?.let(JsonSchema::parse)
        val compiled = schema?.compile(KlauseConfig.DEFAULT)
        val handles = schema?.entries?.mapNotNull { (name, entry) ->
            if (entry is FloatSpec) name to FloatHandle(name, entry.min, entry.max) else null
        }?.toMap().orEmpty()
        val realIds = handles.mapValues { (name, handle) ->
            requireNotNull(compiled?.sourceRealId(handle)) {
                "Schema float is represented by a finite grid: $name"
            }.index
        }
        schemaBytes?.let {
            File(output, "schema.json").writeBytes(it)
            File(output, "schema-sha256.txt").writeText(sha256(it) + "\n")
        }
        for (source in sources.sortedBy { it.path }) {
            measure(source, output, engines, work, seed, moves, timeout, compiled, realIds, handles)
        }
        when (options["checker"] ?: "none") {
            "none" -> Unit
            "existing" -> checkOutput(output, engines, "python3")
            "provision" -> {
                val log = File(output, "checker-install.log")
                runProcess(log, "sudo", "apt-get", "update")
                runProcess(log, "sudo", "apt-get", "install", "-y", "python3-venv")
                val environment = File(output, "checker-venv")
                require(!environment.exists()) { "Checker environment already exists: $environment" }
                runProcess(log, "python3", "-m", "venv", environment.absolutePath)
                val python = File(environment, "bin/python").absolutePath
                runProcess(log, python, "-m", "pip", "install", "-r",
                    "klause-bench/tools/requirements-source-checks.txt")
                runProcess(File(output, "checker-dependencies.txt"), python, "-m", "pip", "freeze")
                checkOutput(output, engines, python)
                check(environment.deleteRecursively()) { "Could not dispose the owned checker environment" }
            }
            else -> error("checker must be none, existing or provision")
        }
    }

    private fun launchWithProcessors(args: Array<String>, options: Map<String, String>, processors: Int) {
        val output = File(options["out"] ?: requireNotNull(System.getenv("OUT")))
        require(output.mkdirs() || output.isDirectory)
        val jvm = ManagementFactory.getRuntimeMXBean().inputArguments.filterNot {
            it.startsWith("-XX:ActiveProcessorCount=") || it.startsWith("-XX:StartFlightRecording=")
        }
        val command = listOf(File(System.getProperty("java.home"), "bin/java").absolutePath) + jvm + listOf(
            "-XX:ActiveProcessorCount=$processors",
            "-XX:StartFlightRecording=filename=${File(output, "objective-probe.jfr").absolutePath},settings=profile",
            "-cp", System.getProperty("java.class.path"), ObjectiveRepresentationProbe::class.java.name,
        ) + args
        val child = ProcessBuilder(command).inheritIO().start()
        check(child.waitFor() == 0) { "Objective probe JVM failed" }
    }

    private fun checkOutput(output: File, engines: List<FiniteEngine>, python: String) {
        runProcess(File(output, "checker.log"), python, "klause-bench/tools/verify_objective_probe.py",
            output.absolutePath, "--engines", engines.joinToString(",") { it.name },
            "--require-incumbent", "--output", File(output, "source-checks.json").absolutePath)
        val tools = listOf("verify_objective_probe.py", "verify_nra_source_results.py", "verify_source_results.py",
            "requirements-source-checks.txt")
        File(output, "checker-tool-hashes.json").writeText(buildJsonObject {
            for (tool in tools) put(tool, sha256(File("klause-bench/tools", tool).readBytes()))
        }.toString() + "\n")
    }

    private fun runProcess(log: File, vararg command: String) {
        val process = ProcessBuilder(command.toList()).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start()
        check(process.waitFor() == 0) { "${command.first()} failed; see ${log.path}" }
    }

    private fun measure(
        input: File, output: File, engines: List<FiniteEngine>, work: Long, seed: Long, moves: Int, timeout: Long,
        compiled: CompiledSchema?, realIds: Map<String, Int>, handles: Map<String, FloatHandle>,
    ) {
        val bytes = input.readBytes()
        val identity = sha256(bytes)
        File(output, "$identity.smt2").writeBytes(bytes)
        val captured = SmtLibAdmission.parse(bytes.toString(Charsets.UTF_8), Long.MIN_VALUE, Long.MAX_VALUE)
        require(captured is SmtLibInput.Quadratic && captured.source is SmtLibInput.Polynomial) {
            "Probe requires an admitted polynomial real source with an affine objective: $input"
        }
        val source = (captured.source as SmtLibInput.Polynomial).problem
        val original = affineObjective(source, captured.objective)
        val affine = if (compiled == null) original else compiledObjective(compiled, source, original, realIds, handles)
        val quadratic = if (compiled == null) captured.objective else affine.toQuadraticObjectiveSpec()
        val request = compiled?.problem?.pipelineRequest() ?: source.pipelineRequest()
        for ((index, engine) in engines.withIndex()) {
            val encodings = if (index % 2 == 0) listOf("linear", "quadratic") else listOf("quadratic", "linear")
            for (encoding in encodings) {
                val params = PolynomialTheoryParams(
                    maximumWork = work, timeout = Cancellation.after(timeout.milliseconds),
                    strategy = PolynomialStrategyParams(
                        backtrack = BacktrackParams(randomSeed = seed),
                        localSearch = LocalSearchParams(maxFlips = moves.toLong(), randomSeed = seed),
                        alnsIterations = moves,
                    ),
                )
                val started = System.nanoTime()
                val execution = if (encoding == "linear") {
                    OpenTheoryPipeline.optimize(request, affine, params, engine, captured.sense)
                } else OpenTheoryPipeline.minimize(request, quadratic, params, engine, captured.sense)
                val elapsed = System.nanoTime() - started
                val record = buildJsonObject {
                    put("sourceSha256", identity)
                    put("engine", engine.name)
                    put("encoding", encoding)
                    put("entry", if (compiled == null) "polynomial-source" else "compiled-schema")
                    put("elapsedNanos", elapsed)
                    put("nativeWork", execution.nativeWork)
                    put("javaCores", Runtime.getRuntime().availableProcessors())
                    put("result", resultRecord(execution.result, source, compiled, realIds))
                }
                File(output, "$identity.${engine.name}.$encoding.json").writeText(record.toString() + "\n")
                println("${input.name} $engine $encoding ${execution.result::class.simpleName} $elapsed ns")
            }
        }
    }

    private fun affineObjective(source: PolynomialRealProblem, objective: QuadraticObjectiveSpec): LinearObjective {
        val bools = Array(source.sourceBoolCount) { BigFraction.ZERO }
        val reals = Array(source.sourceRealCount) { BigFraction.ZERO }
        for (term in objective.terms) {
            val coefficient = BigFraction.of(term.coefficient.numerator, term.coefficient.denominator)
            if (coefficient.isZero) continue
            require(term.second == null) { "Objective contains a non-affine term" }
            when (val variable = term.first) {
                is QuadraticVariable.Bool -> bools[variable.id.index] = bools[variable.id.index] + coefficient
                is QuadraticVariable.Real -> reals[variable.id.index] = reals[variable.id.index] + coefficient
                is QuadraticVariable.Int ->
                    error("Pure polynomial real source contains an integer objective coordinate")
            }
        }
        return LinearObjective(
            boolWeights = LongArray(bools.size) { exactLong(bools[it]) },
            constant = exactLong(BigFraction.of(objective.constant.numerator, objective.constant.denominator)),
            realCoefficients = DoubleArray(reals.size) {
                val value = reals[it].toDouble()
                require(BigFraction.ofDouble(value) == reals[it]) { "Real coefficient is not losslessly binary64" }
                value
            },
        )
    }

    private fun exactLong(value: BigFraction): Long {
        require(value.den == BIG_ONE && value.num.fitsLong()) { "Objective integer does not fit LinearObjective" }
        return value.num.toLongExact()
    }

    private fun compiledObjective(
        compiled: CompiledSchema, source: PolynomialRealProblem, original: LinearObjective,
        realIds: Map<String, Int>, handles: Map<String, FloatHandle>,
    ): LinearObjective {
        require(realIds.keys == source.realVarNames.keys &&
            compiled.boolVarIdByName.keys == source.boolVarNames.keys) { "Source and schema declarations differ" }
        require(compiled.intVarIdByName.isEmpty()) { "Polynomial real source has no integer declarations" }
        require(realIds.values.distinct().size == realIds.size) { "Schema real coordinates alias source declarations" }
        val bools = LongArray(compiled.problem.numBoolVars)
        val reals = DoubleArray(compiled.problem.numRealVars)
        for ((name, id) in source.boolVarNames) {
            bools[compiled.sourceId(BoolHandle(name)).index] = original.boolWeights[id]
        }
        for ((name, id) in source.realVarNames) {
            val column = realIds.getValue(name)
            val objective = compiled.minimize(handles.getValue(name))
            require(column in objective.realCoefficients.indices &&
                objective.realCoefficients.withIndex().all { (index, coefficient) ->
                coefficient == if (index == column) 1.0 else 0.0
            }) { "Compiled float objective differs from its named continuous source coordinate" }
            reals[column] = original.realCoefficients[id]
        }
        return LinearObjective(boolWeights = bools, constant = original.constant, realCoefficients = reals)
    }

    private fun resultRecord(
        result: QuadraticOptimizationResult, source: PolynomialRealProblem,
        compiled: CompiledSchema?, realIds: Map<String, Int>,
    ) = buildJsonObject {
        put("status", result::class.simpleName)
        put("sense", result.sense.name)
        put("lsMoves", result.stats.ls.moves.sum)
        if (result is QuadraticOptimizationResult.WithIncumbent) {
            val sample = result.incumbent.sample
            put("objective", scalar(result.incumbent.value))
            put("witness", buildJsonObject {
                for ((name, id) in source.realVarNames) {
                    val mapped = if (compiled == null) id else realIds.getValue(name)
                    put(name, scalar(requireNotNull(sample.algebraicReals)[mapped]))
                }
                for ((name, id) in source.boolVarNames) {
                    val mapped = compiled?.sourceId(BoolHandle(name))?.index ?: id
                    put(name, sample.boolValue(mapped).toString())
                }
            })
        }
        val bound = when (result) {
            is QuadraticOptimizationResult.Optimal -> result.bound
            is QuadraticOptimizationResult.UnattainedLimit -> result.bound
            is QuadraticOptimizationResult.BestFound -> result.bound
            is QuadraticOptimizationResult.Unknown -> result.bound
            else -> null
        }
        if (bound != null) {
            put("bound", scalar(bound.value))
            put("strict", bound.strict)
        }
        when (result) {
            is QuadraticOptimizationResult.BestFound -> put("reason", result.reason.name)
            is QuadraticOptimizationResult.Unknown -> put("reason", result.reason.name)
            else -> Unit
        }
        put("exportedProof", "not captured")
    }

    private fun scalar(value: AlgebraicReal): String {
        value.rational?.let { return fraction(it) }
        var denominator = BIG_ONE
        for (coefficient in value.definingCoefficients) {
            denominator = denominator / denominator.gcd(coefficient.den) * coefficient.den
        }
        val terms = value.definingCoefficients.mapIndexedNotNull { exponent, coefficient ->
            if (coefficient.isZero) return@mapIndexedNotNull null
            val integer = coefficient.num * (denominator / coefficient.den)
            val number = signed(integer.toString())
            when (exponent) {
                0 -> number
                1 -> "(* $number x)"
                else -> "(* $number (^ x $exponent))"
            }
        }
        val polynomial = if (terms.size == 1) terms.single() else "(+ ${terms.joinToString(" ")})"
        return "(root-obj $polynomial ${value.rootIndex})"
    }

    private fun fraction(value: BigFraction): String = if (value.den == BIG_ONE) signed(value.num.toString())
        else "(/ ${signed(value.num.toString())} ${value.den})"

    private fun signed(value: String): String = if (value.startsWith('-')) "(- ${value.substring(1)})" else value
}
