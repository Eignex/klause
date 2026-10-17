package com.eignex.klause.bench.profile

import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.compile.CompiledSchema
import com.eignex.klause.compile.compile
import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.formats.json.JsonSchema
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.formats.smtlib.SmtLibProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.model.FloatSpec
import com.eignex.klause.model.IntSpec
import com.eignex.klause.model.PresenceSpec
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.schema.BoolHandle
import com.eignex.klause.schema.FloatHandle
import com.eignex.klause.schema.IntHandle
import com.eignex.klause.schema.OptFloatHandle
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.quadraticSourceResultCapture
import com.eignex.klause.solver.evidence.sourceCondition
import com.eignex.klause.solver.evidence.sourceResultCapture
import com.eignex.klause.solver.evidence.verifySourceEvidence
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toQuadraticObjectiveSpec
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.PolynomialStrategyParams
import com.eignex.klause.solver.pipeline.PolynomialTheoryParams
import com.eignex.klause.solver.pipeline.PolynomialTheoryRequest
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceExecutionRequest
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.pipeline.minimize
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

internal object ObjectiveRepresentationProbe {
    private data class SourceNames(
        val reals: Map<String, Int>, val bools: Map<String, Int>, val ints: Map<String, Int> = emptyMap(),
    )

    private data class CompiledInput(
        val model: CompiledSchema, val request: PolynomialTheoryRequest,
        val reals: Map<String, Int>, val handles: Map<String, FloatHandle>,
        val originalSource: Problem, val integers: Map<String, IntHandle>,
        val optionals: Map<String, OptFloatHandle>,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { argument ->
            val parts = argument.split('=', limit = 2)
            require(parts.size == 2) { "Use source=<file or directory> [engines=BACKTRACK,...] [out=<directory>]" }
            parts[0] to parts[1]
        }
        require(options.keys.all {
            it in setOf(
                "source", "engines", "out", "work", "seed", "moves", "timeout",
                "checker", "processors", "schema", "conditions", "capture", "entry", "pinAbsent", "requireIncumbent",
            )
        })
        val capture = when (options["capture"] ?: "none") {
            "none" -> false
            "required" -> true
            else -> error("capture must be none or required")
        }
        val sourceEntry = when (options["entry"] ?: "setup") {
            "setup" -> false
            "source" -> true
            else -> error("entry must be setup or source")
        }
        val absentPinning = options["pinAbsent"]?.toBooleanStrict()
        val requireIncumbent = options["requireIncumbent"]?.toBooleanStrict() ?: true
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
        val conditionBytes = options["conditions"]?.let { File(it).readBytes() }
        val conditions = conditionBytes?.let { Json.parseToJsonElement(it.toString(Charsets.UTF_8)).jsonObject }
        Reports.writeJson(File(output, "provenance.json").path, InstalledBuild.reference)
        val schema = schemaBytes?.toString(Charsets.UTF_8)?.let(JsonSchema::parse)
        val compiled = schema?.compile(KlauseConfig.DEFAULT,
            SemanticOverrides(pinAbsentOptionalValues = absentPinning))
        require(absentPinning == null || compiled != null) { "pinAbsent requires a schema input" }
        val originalSource = compiled?.let { BacktrackSolver.source(it).source }
        File(output, "settings.json").writeText(buildJsonObject {
            put("revision", Reports.readGitSha())
            put("capture", if (capture) "required" else "none")
            put("entry", if (sourceEntry) "source" else "setup")
            put("requireIncumbent", requireIncumbent)
            put("pinAbsentOptionalValues", originalSource?.semantics?.effective?.pinAbsentOptionalValues)
            put("seed", seed)
            put("nativeWork", work)
            put("maxFlips", moves)
            put("alnsIterations", moves)
            put("timeoutMs", timeout)
            put("javaCores", Runtime.getRuntime().availableProcessors())
            put("jvmOptions", ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" "))
            put("schemaSha256", schemaBytes?.let(::sha256))
            put("conditionsSha256", conditionBytes?.let(::sha256))
        }.toString() + "\n")
        val handles = schema?.entries?.mapNotNull { (name, entry) ->
            if (entry is FloatSpec) name to FloatHandle(name, entry.min, entry.max) else null
        }?.toMap().orEmpty()
        val realIds = handles.mapValues { (name, handle) ->
            requireNotNull(compiled?.sourceRealId(handle)) {
                "Schema float lacks an original real coordinate: $name"
            }.index
        }
        val integerHandles = schema?.entries?.mapNotNull { (name, entry) ->
            if (entry is IntSpec) name to IntHandle(name, entry.min, entry.max) else null
        }?.toMap().orEmpty()
        val optionals = schema?.entries?.mapNotNull { (name, entry) ->
            if (entry !is PresenceSpec) return@mapNotNull null
            val value = handles[entry.valueName] ?: return@mapNotNull null
            entry.valueName to OptFloatHandle(entry.valueName, BoolHandle(name), value)
        }?.toMap().orEmpty()
        schemaBytes?.let {
            File(output, "schema.json").writeBytes(it)
            File(output, "schema-sha256.txt").writeText(sha256(it) + "\n")
        }
        conditionBytes?.let { File(output, "conditions.json").writeBytes(it) }
        val prepared = compiled?.let {
            val original = requireNotNull(originalSource)
            CompiledInput(it, original.pipelineRequest(), realIds, handles, original, integerHandles, optionals)
        }
        require(!capture || prepared != null) { "Original IR capture requires a compiled schema" }
        require(!sourceEntry || engines.all {
            it == FiniteEngine.BACKTRACK || it == FiniteEngine.LOCAL_SEARCH
        }) { "Source strategy entry requires BACKTRACK or LOCAL_SEARCH engines" }
        for (source in sources.sortedBy { it.path }) {
            measure(source, output, engines, work, seed, moves, timeout, prepared, conditions, capture, sourceEntry)
        }
        when (options["checker"] ?: "none") {
            "none" -> Unit
            "existing" -> checkOutput(output, engines, "python3", requireIncumbent)
            "provision" -> {
                val log = File(output, "checker-install.log")
                val environment = File(output, "checker-venv")
                require(!environment.exists()) { "Checker environment already exists: $environment" }
                if (runProcess(log, "python3", "-m", "venv", environment.absolutePath, required = false) != 0) {
                    runProcess(log, "sudo", "apt-get", "update")
                    runProcess(log, "sudo", "apt-get", "install", "-y", "python3-venv")
                    runProcess(log, "python3", "-m", "venv", environment.absolutePath)
                }
                val python = File(environment, "bin/python").absolutePath
                runProcess(log, python, "-m", "pip", "install", "-r",
                    "klause-bench/tools/requirements-source-checks.txt")
                runProcess(File(output, "checker-dependencies.txt"), python, "-m", "pip", "freeze")
                checkOutput(output, engines, python, requireIncumbent)
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

    private fun checkOutput(output: File, engines: List<FiniteEngine>, python: String, requireIncumbent: Boolean) {
        runProcess(File(output, "checker-runtime.txt"), python, "--version")
        val command = listOf(python, "klause-bench/tools/verify_objective_probe.py",
            output.absolutePath, "--engines", engines.joinToString(",") { it.name },
        ) + (if (requireIncumbent) listOf("--require-incumbent") else emptyList()) +
            listOf("--output", File(output, "source-checks.json").absolutePath)
        runProcess(File(output, "checker.log"), command)
        val tools = listOf("verify_objective_probe.py", "verify_nra_source_results.py", "verify_source_results.py",
            "requirements-source-checks.txt")
        File(output, "checker-tool-hashes.json").writeText(buildJsonObject {
            for (tool in tools) put(tool, sha256(File("klause-bench/tools", tool).readBytes()))
        }.toString() + "\n")
    }

    private fun runProcess(log: File, vararg command: String, required: Boolean = true): Int =
        runProcess(log, command.toList(), required)

    private fun runProcess(log: File, command: List<String>, required: Boolean = true): Int {
        val process = ProcessBuilder(command).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start()
        val exit = process.waitFor()
        check(!required || exit == 0) { "${command.first()} failed; see ${log.path}" }
        return exit
    }

    private fun measure(
        input: File, output: File, engines: List<FiniteEngine>, work: Long, seed: Long, moves: Int, timeout: Long,
        compiled: CompiledInput?, conditions: JsonObject?, capture: Boolean, sourceEntry: Boolean,
    ) {
        val bytes = input.readBytes()
        val identity = sha256(bytes)
        File(output, "$identity.smt2").writeBytes(bytes)
        val captured = if (sourceEntry && compiled == null) SmtLib.parse(bytes.toString(Charsets.UTF_8))
            else SmtLib.parse(bytes.toString(Charsets.UTF_8), Long.MIN_VALUE, Long.MAX_VALUE)
        val capturedObjective = requireNotNull(
            captured.quadraticObjective ?: captured.objective?.toQuadraticObjectiveSpec(),
        ) {
            "Probe requires a captured exact source objective: $input"
        }
        val source = sourceNames(captured)
        require(compiled != null || source.ints.isEmpty() || sourceEntry) {
            "Integer sources require a compiled schema or source strategy entry"
        }
        val factorSource = if (sourceEntry && compiled == null) {
            requireNotNull(captured.source.originalSource as? Problem) {
                "Source strategy entry requires a factor source"
            }
        } else null
        val original = affineObjective(source, capturedObjective)
        val affine = if (compiled == null) original else compiledObjective(compiled, source, original)
        val quadratic = if (compiled == null) capturedObjective else affine.toQuadraticObjectiveSpec()
        val attachment = compiled?.let { SourceAttachment(it.originalSource) } ?: captured.source
        val condition = conditions?.getValue(identity)?.jsonObject ?: buildJsonObject { }
        val pins = assumptions(condition, source, compiled)
        for ((index, engine) in engines.withIndex()) {
            val encodings = if (index % 2 == 0) listOf("linear", "quadratic") else listOf("quadratic", "linear")
            for (encoding in encodings) {
                val params = PolynomialTheoryParams(
                    maximumWork = work, timeout = Cancellation.after(timeout.milliseconds),
                    strategy = PolynomialStrategyParams(
                        backtrack = BacktrackParams(randomSeed = seed),
                        localSearch = LocalSearchParams(maxFlips = moves.toLong(), randomSeed = seed),
                        alnsIterations = moves, assumptions = pins,
                    ),
                )
                val started = System.nanoTime()
                val completed = if (sourceEntry) null else {
                    val setup = if (encoding == "linear") {
                        SourceSetupRequest(attachment, ExecutionOperation.OPTIMIZE, engine, affine,
                            captured.sense == ObjectiveSense.MAXIMIZE, cancellation = params.timeout)
                    } else SourceSetupRequest(attachment, engine, quadratic, captured.sense,
                        cancellation = params.timeout)
                    SourceSetup.execute(SourceExecutionRequest(SourceSetup.prepare(setup), polynomial = params))
                }
                if (completed is SourceExecutionResult.Declined || completed is SourceExecutionResult.Refuted) {
                    val record = buildJsonObject {
                        put("sourceSha256", identity)
                        put("engine", engine.name)
                        put("encoding", encoding)
                        put("entry", if (sourceEntry) "compiled-source-strategy"
                            else if (compiled == null) "polynomial-source" else "compiled-schema")
                        put("condition", condition)
                        put("elapsedNanos", System.nanoTime() - started)
                        put("nativeWork", 0)
                        put("javaCores", Runtime.getRuntime().availableProcessors())
                        put("result", buildJsonObject {
                            put("kind", if (completed is SourceExecutionResult.Refuted) "SourceRefuted" else "Unknown")
                            put("reason", (completed as? SourceExecutionResult.Declined)?.reason?.toString())
                            put("phase", "source-preparation")
                            put("nativeOptimizationStarted", false)
                        })
                        if (capture) put("sourceCapture", buildJsonObject {
                            put("captured", false)
                            put("reason", "native optimization did not start")
                        })
                    }
                    File(output, "$identity.${engine.name}.$encoding.json").writeText(record.toString() + "\n")
                    continue
                }
                var sourceQuadratic = quadratic
                val execution = if (sourceEntry) {
                    val strategy = if (compiled != null) {
                        if (engine == FiniteEngine.BACKTRACK) BacktrackSolver.source(compiled.model)
                        else LocalSearchSolver.source(compiled.model)
                    } else {
                        if (engine == FiniteEngine.BACKTRACK) BacktrackSolver.source(requireNotNull(factorSource))
                        else LocalSearchSolver.source(requireNotNull(factorSource))
                    }
                    if (encoding == "linear") {
                        val paired = if (compiled != null) pairedObjective(compiled, affine) else affine
                        sourceQuadratic = compiled?.model?.sourceObjective(paired) ?: quadratic
                        strategy.optimize(paired, params, captured.sense)
                    } else strategy.optimize(quadratic, params, captured.sense)
                } else (completed as? SourceExecutionResult.Quadratic)?.result
                    ?: error("source setup did not install quadratic optimization: $completed")
                val elapsed = System.nanoTime() - started
                val record = buildJsonObject {
                    put("sourceSha256", identity)
                    put("engine", engine.name)
                    put("encoding", encoding)
                    put("entry", if (sourceEntry && compiled != null) "compiled-source-strategy"
                            else if (sourceEntry) "source-strategy"
                            else if (compiled == null) "polynomial-source" else "compiled-schema")
                    put("condition", condition)
                    put("elapsedNanos", elapsed)
                    put("nativeWork", execution.nativeWork)
                    put("javaCores", Runtime.getRuntime().availableProcessors())
                    put("result", resultRecord(execution.result, source, compiled, sourceEntry))
                }
                val recordFile = File(output, "$identity.${engine.name}.$encoding.json")
                recordFile.writeText(record.toString() + "\n")
                if (capture) {
                    val scopeRecord = captureRecord(requireNotNull(compiled), affine, sourceQuadratic, pins,
                        identity, execution.result, if (sourceEntry) "quadratic" else encoding)
                    val capturedRecord = buildJsonObject {
                        for ((key, value) in record) put(key, value)
                        put("sourceCapture", scopeRecord)
                    }
                    recordFile.writeText(capturedRecord.toString() + "\n")
                }
                println("${input.name} $engine $encoding ${execution.result::class.simpleName} $elapsed ns")
            }
        }
    }

    private fun captureRecord(
        compiled: CompiledInput, affine: LinearObjective, quadratic: QuadraticObjectiveSpec,
        pins: Assumptions, identity: String, result: QuadraticOptimizationResult, encoding: String,
    ): JsonObject {
        val revision = SourceRevision(identity, "klause-ir-exact-v1")
        val plan = compiled.request.preparedPlan.withObjective(affine, result.sense == ObjectiveSense.MAXIMIZE)
        val capture = requireNotNull(if (encoding == "linear") plan.sourceResultCapture(revision, pins, "cost")
            else compiled.originalSource.quadraticSourceResultCapture(
                SourceScope(revision, pins.sourceCondition(), SourceObjective("cost", result.sense)), quadratic,
            ))
        val retained = capture.quadratic(result, "objective-representation-probe")
        check(requireNotNull(retained.evidence).scope == capture.context.scope)
        var replay = "not applicable to witness-only completion"
        var weaker = "not applicable"
        if (result is QuadraticOptimizationResult.Optimal) {
            check(retained is QuadraticOptimizationResult.Optimal)
            checkNotNull(result.certificate.derivation)
            check(retained.certificate.derivation === result.certificate.derivation)
            check(retained.bound.certificate.derivation === result.bound.certificate.derivation)
            if (encoding == "linear") {
                val replacement = requireNotNull(compiled.request.preparedPlan.withObjective(
                    affine.copy(), result.sense == ObjectiveSense.MAXIMIZE,
                ).sourceResultCapture(revision, pins, "cost"))
                check(replacement.quadratic(result, "replacement-objective") === result)
                replay = "foreign original objective rejected"
                if (pins.sourceCondition() != Assumptions.None.sourceCondition()) {
                    val unrestricted = requireNotNull(plan.sourceResultCapture(revision, objectiveIdentity = "cost"))
                    check(unrestricted.quadratic(result, "weaker-condition") === result)
                    weaker = "weaker original condition rejected"
                }
            } else {
                val replacement = requireNotNull(compiled.originalSource.quadraticSourceResultCapture(
                    capture.context.scope, QuadraticObjectiveSpec(quadratic.terms, quadratic.constant),
                ))
                check(replacement.quadratic(result, "replacement-objective") === result)
                replay = "foreign original objective rejected"
                if (pins.sourceCondition() != Assumptions.None.sourceCondition()) {
                    val unrestricted = requireNotNull(compiled.originalSource.quadraticSourceResultCapture(
                        SourceScope(revision, Assumptions.None.sourceCondition(),
                            SourceObjective("cost", result.sense)),
                        quadratic,
                    ))
                    check(unrestricted.quadratic(result, "weaker-condition") === result)
                    weaker = "weaker original condition rejected"
                }
            }
        }
        val checked = retained.verifySourceEvidence(capture.context)
        check(checked !is SourceEvidenceCheck.Rejected) { "Captured original result rejected: $checked" }
        return buildJsonObject {
            put("captured", true)
            put("nativeCompletion", result is QuadraticOptimizationResult.Optimal)
            put("copiedObjectiveReplay", replay)
            put("weakerConditionReplay", weaker)
            put("derivationCheck", if (checked == SourceEvidenceCheck.Checked) "checked" else "unavailable")
            put("unavailableReason", (checked as? SourceEvidenceCheck.Unavailable)?.reason)
            put("exportedProof", "not captured")
        }
    }

    private fun sourceNames(source: SmtLibProblem): SourceNames =
        SourceNames(source.realVarNames.mapKeys { it.key.removeSurrounding("|") },
            source.boolVarNames.mapKeys { it.key.removeSurrounding("|") },
            source.intVarNames.mapKeys { it.key.removeSurrounding("|") })

    private fun assumptions(condition: JsonObject, source: SourceNames, compiled: CompiledInput?): Assumptions {
        require(condition.keys.all { it in setOf("bools", "ints") }) { "Unsupported source condition" }
        var pins = Assumptions.None
        for ((name, value) in condition["bools"]?.jsonObject.orEmpty()) {
            val sourceId = source.bools.getValue(name)
            val id = compiled?.model?.boolVarIdByName?.getValue(name) ?: sourceId
            pins = pins.withBool(id, value.jsonPrimitive.boolean)
        }
        for ((name, value) in condition["ints"]?.jsonObject.orEmpty()) {
            val sourceId = source.ints.getValue(name)
            val id = compiled?.let { it.model.sourceId(it.integers.getValue(name)).index } ?: sourceId
            pins = pins.withInt(id, value.jsonPrimitive.long)
        }
        return pins
    }

    private fun affineObjective(source: SourceNames, objective: QuadraticObjectiveSpec): LinearObjective {
        val bools = Array(source.bools.size) { BigFraction.ZERO }
        val ints = Array(source.ints.size) { BigFraction.ZERO }
        val reals = Array(source.reals.size) { BigFraction.ZERO }
        for (term in objective.terms) {
            val coefficient = BigFraction.of(term.coefficient.numerator, term.coefficient.denominator)
            if (coefficient.isZero) continue
            require(term.second == null) { "Objective contains a non-affine term" }
            when (val variable = term.first) {
                is QuadraticVariable.Bool -> bools[variable.id.index] = bools[variable.id.index] + coefficient
                is QuadraticVariable.Real -> reals[variable.id.index] = reals[variable.id.index] + coefficient
                is QuadraticVariable.Int -> ints[variable.id.index] = ints[variable.id.index] + coefficient
            }
        }
        return LinearObjective(
            boolWeights = LongArray(bools.size) { exactLong(bools[it]) },
            intCoefficients = LongArray(ints.size) { exactLong(ints[it]) },
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
        compiled: CompiledInput, source: SourceNames, original: LinearObjective,
    ): LinearObjective {
        val model = compiled.model
        require(compiled.reals.keys == source.reals.keys && model.boolVarIdByName.keys == source.bools.keys &&
            compiled.integers.keys == source.ints.keys) { "Source and schema declarations differ" }
        require(compiled.reals.values.distinct().size == compiled.reals.size) {
            "Schema real coordinates alias source declarations"
        }
        val bools = LongArray(compiled.originalSource.numBoolVars)
        val ints = LongArray(compiled.originalSource.numIntVars)
        val reals = DoubleArray(compiled.originalSource.numRealVars)
        for ((name, id) in source.bools) bools[model.sourceId(BoolHandle(name)).index] = original.boolWeights[id]
        for ((name, id) in source.ints) {
            ints[model.sourceId(compiled.integers.getValue(name)).index] = original.intCoefficients[id]
        }
        for ((name, id) in source.reals) {
            val column = compiled.reals.getValue(name)
            reals[column] = original.realCoefficients[id]
        }
        return LinearObjective(bools, ints, original.constant, reals)
    }

    private fun pairedObjective(compiled: CompiledInput, original: LinearObjective): LinearObjective {
        val model = compiled.model
        val native = model.problem
        val bools = LongArray(native.numBoolVars)
        val ints = LongArray(native.numIntVars)
        val reals = DoubleArray(native.numRealVars)
        val basis = compiled.handles.values.firstOrNull()?.let(model::minimize)
            ?: model.boolVarIdByName.keys.firstOrNull()?.let { model.minimize(BoolHandle(it)) }
            ?: compiled.integers.values.firstOrNull()?.let(model::minimize)
            ?: error("A source helper probe requires a named variable")
        fun add(helper: LinearObjective, coefficient: BigFraction) {
            if (coefficient.isZero) return
            var terms = 0
            require(helper.constant == 0L)
            for ((target, weights) in listOf(bools to helper.boolWeights, ints to helper.intCoefficients)) {
                for ((id, unit) in weights.withIndex()) if (unit != 0L) {
                    require(unit == 1L && target[id] == 0L) { "Compiled helper is not a distinct unit coordinate" }
                    target[id] = exactLong(coefficient)
                    terms++
                }
            }
            for ((id, unit) in helper.realCoefficients.withIndex()) if (unit != 0.0) {
                require(unit == 1.0 && reals[id] == 0.0) { "Compiled helper is not a distinct real unit coordinate" }
                val value = coefficient.toDouble()
                require(BigFraction.ofDouble(value) == coefficient) { "Helper real weight is not losslessly binary64" }
                reals[id] = value
                terms++
            }
            require(terms == 1) { "Compiled helper does not identify one native coordinate" }
        }
        for (name in model.boolVarIdByName.keys) {
            val handle = BoolHandle(name)
            add(model.minimize(handle), BigFraction.ofLong(original.boolWeights[model.sourceId(handle).index]))
        }
        for (handle in compiled.integers.values) {
            add(model.minimize(handle), BigFraction.ofLong(original.intCoefficients[model.sourceId(handle).index]))
        }
        for ((name, handle) in compiled.handles) {
            add(model.minimize(handle), requireNotNull(BigFraction.ofDouble(original.realCoefficients[
                compiled.reals.getValue(name)])))
        }
        return basis.copy(bools, ints, original.constant, reals)
    }

    private fun sourceDecoders(compiled: CompiledInput, sample: Sample): JsonObject = buildJsonObject {
        check(sample.coordinateFrame === compiled.originalSource) { "Source publication lost its original frame" }
        put("frame", "original-source")
        put("floats", buildJsonObject {
            for ((name, handle) in compiled.handles) {
                val decoded = compiled.model.decode(handle, sample)
                check(decoded == sample.approximateRealValue(compiled.reals.getValue(name)))
                put(name, decoded)
            }
        })
        put("optionals", buildJsonObject {
            for ((name, handle) in compiled.optionals) {
                val decoded = compiled.model.decode(handle, sample)
                val expected = if (sample.boolValue(compiled.model.sourceId(handle.present).index)) {
                    compiled.model.decode(handle.value, sample)
                } else null
                check(decoded == expected)
                put(name, decoded)
            }
        })
    }

    private fun resultRecord(
        result: QuadraticOptimizationResult, source: SourceNames, compiled: CompiledInput?, sourceEntry: Boolean,
    ) = buildJsonObject {
        put("status", result::class.simpleName)
        put("sense", result.sense.name)
        put("lsMoves", result.stats.ls.moves.sum)
        if (result is QuadraticOptimizationResult.WithIncumbent) {
            val sample = result.incumbent.sample
            if (sourceEntry && compiled != null) put("decoders", sourceDecoders(compiled, sample))
            put("objective", scalar(result.incumbent.value))
            put("witness", buildJsonObject {
                for ((name, id) in source.reals) {
                    val mapped = compiled?.reals?.getValue(name) ?: id
                    put(name, scalar(requireNotNull(sample.algebraicReals)[mapped]))
                }
                for ((name, id) in source.ints) {
                    val mapped = compiled?.let { it.model.sourceId(it.integers.getValue(name)).index } ?: id
                    put(name, sample.exactIntValue(mapped).toString())
                }
                for ((name, id) in source.bools) {
                    val mapped = compiled?.model?.sourceId(BoolHandle(name))?.index ?: id
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
