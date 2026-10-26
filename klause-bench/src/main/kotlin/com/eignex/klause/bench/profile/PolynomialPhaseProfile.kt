package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRealProblem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.PolynomialStrategyParams
import com.eignex.klause.solver.pipeline.PolynomialTheoryParams
import com.eignex.klause.solver.pipeline.SourceExecutionRequest
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.magnitudeBitLength
import com.eignex.klause.util.parseBigInt
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object PolynomialPhaseProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 1..9)
        val options = args.drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.size == args.size - 1 && options.keys.all {
            it in setOf("pipeline", "timeoutMs", "repeats", "prepared", "capture",
                "maximumWork", "refinement", "feedback")
        })
        require(options["pipeline"] == null || options["pipeline"] in setOf("true", "both"))
        require(options["prepared"] == null || options["prepared"] == "true")
        require(options["prepared"] == null || options["pipeline"] == null)
        require(options["capture"] == null || options["capture"] in setOf("true", "both"))
        require(options["refinement"] == null || options["refinement"] in setOf("true", "both"))
        require(options["feedback"] == null || options["feedback"] in setOf("true", "both"))
        val feedback = options["feedback"] != null
        require(!feedback || options["refinement"] == "true")
        val refine = options["refinement"] != null
        val capture = options["capture"] != null
        val maximumWork = options["maximumWork"]?.toLong()?.also { require(it >= 0) }
        val preparedMode = options["prepared"] == "true"
        val pipelineMode = options["pipeline"] != null
        val directMode = options["pipeline"] != "true"
        require((!capture && !refine) || directMode)
        val repeats = options["repeats"]?.toInt() ?: 1
        require(repeats in 1..9 && (repeats == 1 || !directMode))
        val timeoutMs = options["timeoutMs"]?.toLong() ?: 60_000L
        require(timeoutMs > 0)
        val request = requestBytes(args[0])
        val cases = Json.decodeFromString<List<PhaseSource>>(request.decodeToString())
        require(cases.size in 1..18 &&
            cases.map { Triple(it.suite, it.problem, it.fixedIntegers) }.distinct().size == cases.size)
        require(!preparedMode || cases.all { it.fixedIntegers.isEmpty() })
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        if (System.getenv("KLAUSE_POLYNOMIAL_PHASE_CHILD") != "1") {
            launch(args, out)
            return
        }
        out.resolve("request.json").writeBytes(request)
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        out.resolve("settings.json").writeText(Json.encodeToString(mapOf(
            "revision" to Reports.readGitSha(),
            "javaVersion" to System.getProperty("java.runtime.version"),
            "javaCores" to Runtime.getRuntime().availableProcessors().toString(),
            "jvmOptions" to ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" "),
            "containingTimeoutMs" to timeoutMs.toString(),
            "repeats" to repeats.toString(),
            "maximumWork" to maximumWork.toString(),
            "diagnosticMaximumWork" to maximumWork.toString(),
            "failureCapture" to options["capture"].toString(),
            "cellRefinement" to options["refinement"].toString(),
            "cellFeedback" to options["feedback"].toString(),
            "requestEncoding" to args[0].substringBefore('='),
            "sourceBytes" to if (cases.any { it.gzip64 != null }) "hash-pinned request or catalog" else "catalog",
            "representationLimits" to "default",
            "route" to if (preparedMode) "unconditioned prepared polynomial service; no CP root pins or publication"
                else if (pipelineMode && directMode) "paired direct query and default MIXED pipeline/P1/seed3"
                else if (pipelineMode) "source admission and default MIXED pipeline/P1/seed3"
                else "direct normalized QfNraQuery; separate gate inspection",
        )))
        val access = PhaseAccess()
        for ((index, source) in cases.withIndex()) {
            require(source.sha256.matches(Regex("[0-9a-f]{64}")))
            val bytes = source.gzip64?.let(::capturedSource) ?: run {
                val reference = Catalog.uncapped(source.suite).problems.single { it.name == source.problem }
                CorpusFiles.open(CorpusFetcher.resolve(reference.source)).use { it.readNBytes(1024 * 1024 + 1) }
            }
            require(bytes.size <= 1024 * 1024 && sha256(bytes) == source.sha256)
            val directory = out.resolve(index.toString()).apply { mkdirs() }
            directory.resolve("source.smt2").writeBytes(bytes)
            val text = bytes.decodeToString()
            if (pipelineMode) {
                repeat(repeats) { index ->
                    val name = if (repeats == 1) "pipeline.json" else "pipeline-$index.json"
                    directory.resolve(name).writeText(Json.encodeToString(pipeline(text, timeoutMs)))
                }
            }
            if (preparedMode) {
                directory.resolve("prepared-trace.json").writeText(
                    Json.encodeToString(access.measure(text, maximumWork, timeoutMs,
                        prepared = true, capture = capture, refinement = refine, feedback = feedback)),
                )
            } else if (directMode) {
                directory.resolve("gate.json").writeText(Json.encodeToString(access.gate(text, timeoutMs)))
                directory.resolve("trace.json").writeText(
                    Json.encodeToString(access.measure(text, maximumWork, timeoutMs,
                        capture = capture, fixedIntegers = source.fixedIntegers,
                        refinement = refine, feedback = feedback)),
                )
            }
            if (options["refinement"] == "both") {
                directory.resolve("control-trace.json").writeText(Json.encodeToString(
                    access.measure(text, maximumWork, timeoutMs, prepared = preparedMode, capture = capture,
                        fixedIntegers = source.fixedIntegers)))
            }
            if (options["feedback"] == "both") {
                directory.resolve("feedback-control-trace.json").writeText(Json.encodeToString(
                    access.measure(text, maximumWork, timeoutMs, prepared = preparedMode, capture = capture,
                        fixedIntegers = source.fixedIntegers, refinement = true)))
            }
            if (options["capture"] == "both") {
                directory.resolve("plain-trace.json").writeText(Json.encodeToString(
                    access.measure(text, maximumWork, timeoutMs, prepared = preparedMode,
                        fixedIntegers = source.fixedIntegers, refinement = refine, feedback = feedback)))
            }
        }
    }

    internal fun capturedSource(gzip64: String): ByteArray {
        require(gzip64.length <= 48 * 1024)
        return gunzip(Base64.getDecoder().decode(gzip64))
    }

    internal fun requestBytes(argument: String): ByteArray {
        require(argument.startsWith("request64=") || argument.startsWith("requestz64="))
        val encoded = argument.substringAfter('=')
        require(encoded.length <= 96 * 1024)
        val bytes = Base64.getDecoder().decode(encoded)
        return if (argument.startsWith("requestz64=")) gunzip(bytes) else bytes
    }

    private fun gunzip(compressed: ByteArray): ByteArray {
        val bytes = GZIPInputStream(compressed.inputStream()).use { it.readNBytes(1024 * 1024 + 1) }
        require(bytes.size <= 1024 * 1024)
        return bytes
    }

    internal fun pipeline(text: String, timeoutMs: Long = 60_000L): PipelinePhaseTrace {
        val observation = DeadlineObservation(timeoutMs)
        val result = pipeline(text, observation)
        return result.copy(
            deadlineOverrunAtFirstPollNs = observation.deadlineOverrunAtFirstPollNs,
            deadlineOverrunAtReturnNs = maxOf(0L, observation.deadline().elapsedNow().inWholeNanoseconds),
        )
    }

    private fun pipeline(text: String, observation: DeadlineObservation): PipelinePhaseTrace {
        val source = try {
            SmtLib.parse(text, cancellation = observation)
        } catch (_: CancellationException) {
            return PipelinePhaseTrace("Unknown", "Timeout", "source-admission", null,
                "not started", "not started", observation.charges, 0, observation.stack)
        }
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            source.source, ExecutionOperation.SATISFY, FiniteEngine.MIXED, cancellation = observation,
        ))
        val plan = invocation.preparedPlan
        val model = plan?.model
        val polynomial = (invocation.route as? SourceProblemRoute.PolynomialTheory)?.request?.problem
            ?: source.source.originalSource as? PolynomialRealProblem
        val shape = PipelineSourceShape(
            plan?.kind?.name, source.source.originalSource.javaClass.simpleName,
            source.boolVarNames.size, source.intVarNames.size, source.realVarNames.size,
            model?.numBoolVars, model?.numIntVars, model?.numRealVars, model?.numFactors,
            polynomial?.let { polynomialCount(it, "totalBoolCount") },
            polynomial?.let { polynomialCount(it, "totalRealCount") },
        )
        val preparedCharges = observation.charges
        val completed = SourceSetup.execute(SourceExecutionRequest(
            invocation, theory = TheoryParams(timeout = observation, randomSeed = 3),
            polynomial = PolynomialTheoryParams(
                timeout = observation,
                strategy = PolynomialStrategyParams(
                    backtrack = BacktrackParams(randomSeed = 3),
                    localSearch = LocalSearchParams(randomSeed = 3),
                ),
            ),
            cores = 1,
        ))
        if (completed is SourceExecutionResult.Declined || completed is SourceExecutionResult.Refuted) {
            return PipelinePhaseTrace(
                if (completed is SourceExecutionResult.Refuted) "SourceRefuted" else "Unknown",
                (completed as? SourceExecutionResult.Declined)?.reason?.toString(),
                "source-preparation", null, "not started", "not started",
                preparedCharges, observation.charges - preparedCharges, observation.stack, shape,
            )
        }
        if (completed is SourceExecutionResult.Linear) {
            val execution = checkNotNull(completed.result as? OpenTheoryExecution.Satisfy).result
            return PipelinePhaseTrace(execution.javaClass.simpleName,
                (execution as? OpenTheoryResult.Unknown)?.reason?.toString(), null, null,
                "not a polynomial execution", execution.stats.toString(),
                preparedCharges, observation.charges - preparedCharges, observation.stack, shape)
        }
        val execution = (completed as? SourceExecutionResult.Polynomial)?.result
            ?: error("source setup did not install polynomial satisfaction")
        return PipelinePhaseTrace(execution.result.javaClass.simpleName,
            (execution.result as? OpenTheoryResult.Unknown)?.reason?.toString(),
            execution.resource?.toString(), execution.nativeResource,
            execution.stats.toString(), execution.result.stats.toString(),
            preparedCharges, observation.charges - preparedCharges, observation.stack, shape)
    }

    private fun polynomialCount(source: PolynomialRealProblem, field: String): Int =
        source.javaClass.getDeclaredField(field).apply { isAccessible = true }.getInt(source)

    private class DeadlineObservation(timeoutMs: Long) : Cancellation, WorkMeter {
        private val clock = Cancellation.after(timeoutMs.milliseconds)
        var charges = 0L
            private set
        var stack: List<String>? = null
            private set
        var deadlineOverrunAtFirstPollNs: Long? = null
            private set
        override fun deadline() = checkNotNull(clock.deadline())
        override fun workMeter(): WorkMeter = this
        override fun charge(units: Long) { charges += units }
        override fun isCancelled(): Boolean {
            val stopped = clock()
            if (stopped && stack == null) {
                deadlineOverrunAtFirstPollNs = maxOf(0L, deadline().elapsedNow().inWholeNanoseconds)
                stack = Thread.currentThread().stackTrace.map { it.toString() }
            }
            return stopped
        }
    }

    private fun launch(args: Array<String>, out: File) {
        val command = listOf(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-Xmx3g", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1",
            "-Dklause.workspace.root=${CorpusFetcher.workspaceRoot()}",
            "-Dklause.bench.corpusCache=${CorpusFetcher.cacheRoot}",
            "-cp", System.getProperty("java.class.path"), PolynomialPhaseProfile::class.java.name,
        ) + args
        out.resolve("command.json").writeText(Json.encodeToString(command))
        val process = ProcessBuilder(command).apply {
            environment()["KLAUSE_POLYNOMIAL_PHASE_CHILD"] = "1"
            redirectOutput(out.resolve("measured.out"))
            redirectError(out.resolve("measured.err"))
        }.start()
        val exit = process.waitFor()
        out.resolve("measured.exit").writeText("$exit\n")
        check(exit == 0) { "Measured polynomial diagnostic exited $exit" }
    }

    internal class PhaseAccess {
        private val prefix = "com.eignex.klause.theory.qfnra."
        private val parser = Class.forName("com.eignex.klause.formats.smtlib.SmtLibPolynomial")
        private val parse = parser.getMethod(
            "parse", String::class.java, Cancellation::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
        )
        private val sourceType = Class.forName("com.eignex.klause.formats.smtlib.SmtLibPolynomialProblem")
        private val formula = sourceType.getMethod("getFormula")
        private val realCount = sourceType.getMethod("getRealCount")
        private val boolCount = sourceType.getMethod("getBoolCount")
        private val limitsType = Class.forName("com.eignex.klause.arithmetic.polynomial.PolynomialLimits")
        private val paramsType = Class.forName(prefix + "QfNraParams")
        private val conditionType = Class.forName(prefix + "PolynomialCondition")
        private val queryType = Class.forName(prefix + "QfNraQuery")
        private val query = queryType.getConstructor(
            PolynomialRealFormula::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, paramsType, conditionType,
        )
        private val params = paramsType.getConstructor(Cancellation::class.java, Long::class.javaObjectType, limitsType,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        private val condition = conditionType.getConstructor(Map::class.java, Map::class.java, Map::class.java)
        private val next = queryType.getMethod("next")
        private val stats = queryType.getMethod("getStats")
        private val progress = queryType.methods.singleOrNull { it.name == "getProgress" }
        private val feedbackStats = queryType.getMethod("getCellFeedbackStats")
        private val feedbackUse = queryType.getMethod("getFirstCellFeedbackUse")
        private val feedbackReuse = queryType.getMethod("getFirstCellFeedbackReuse")
        private val partialConflicts = progress?.returnType?.methods?.singleOrNull { it.name == "getPartialConflicts" }
        private val conflictObserver = queryType.methods.singleOrNull { it.name == "setPartialConflictObserver" }
        private val captureType = Class.forName(prefix + "PolynomialFailureCapture")
        private val setCapture = queryType.getMethod("setFailureCapture", captureType)
        private val atomType = Class.forName(prefix + "PolynomialAtom")
        private val atomPolynomial = atomType.getMethod("getPolynomial")
        private val atomRelation = atomType.getMethod("getRelation")
        private val coefficients = Class.forName("com.eignex.klause.arithmetic.polynomial.Polynomial")
            .getDeclaredField("coefficients").apply { isAccessible = true }
        private val powers = Class.forName("com.eignex.klause.arithmetic.polynomial.Monomial")
            .getDeclaredField("powers").apply { isAccessible = true }
        private val fixings = Class.forName(prefix + "PolynomialPointFixingsKt").methods.single {
            it.name == "polynomialPointFixings"
        }

        private fun parsed(text: String, token: Cancellation): Any =
            parse.invoke(parser.getField("INSTANCE").get(null), text, token, true, false)

        fun gate(text: String, timeoutMs: Long = 60_000L): PhaseGate {
            var charges = 0L
            val meter = WorkMeter { charges += it }
            val token = object : Cancellation {
                private val timeout = Cancellation.after(timeoutMs.milliseconds)
                override fun isCancelled(): Boolean = timeout()
                override fun deadline() = timeout.deadline()
                override fun workMeter(): WorkMeter = meter
            }
            val source = parsed(text, token)
            val original = formula.invoke(source)
            val fixed = fixings.invoke(null, original, { check(!token()) })
            val arithmeticType = Class.forName("com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic")
            val arithmetic = arithmeticType.getConstructor(Cancellation::class.java, limitsType)
                .newInstance(token, limitsType.getConstructor().newInstance())
            val formulaType = Class.forName(prefix + "PolynomialFormula")
            val companion = formulaType.getField("Companion").get(null)
            val normalized = companion.javaClass.getMethod(
                "normalize", PolynomialRealFormula::class.java, arithmeticType, Map::class.java,
            ).invoke(companion, original, arithmetic, fixed)
            val atoms = formulaType.getMethod("getAtoms").invoke(normalized) as List<*>
            val guides = formulaType.getMethod(
                "conjunctiveAtoms", arithmeticType, Int::class.javaPrimitiveType, MutableList::class.java,
            ).invoke(normalized, arithmetic, 128, ArrayList<Int>()) as? List<*>
            val nodes = formulaType.getDeclaredField("nodes").apply { isAccessible = true }.get(normalized) as List<*>
            val kinds = nodes.groupingBy { requireNotNull(it).javaClass.simpleName }.eachCount()
            return PhaseGate(realCount.invoke(source) as Int, boolCount.invoke(source) as Int,
                atoms.size, guides?.size, kinds, charges)
        }

        fun measure(
            text: String,
            maximumWork: Long? = null,
            timeoutMs: Long = 60_000L,
            prepared: Boolean = false,
            capture: Boolean = false,
            fixedIntegers: Map<String, String> = emptyMap(),
            refinement: Boolean = false,
            feedback: Boolean = false,
        ): PhaseTrace {
            require(!feedback || refinement)
            val clock = Cancellation.after(timeoutMs.milliseconds)
            var charges = 0L
            var stop: List<String>? = null
            val meter = WorkMeter { charges += it }
            val token = object : Cancellation {
                override fun isCancelled(): Boolean {
                    val stopped = clock()
                    if (stopped && stop == null) stop = Thread.currentThread().stackTrace.map { it.toString() }
                    return stopped
                }
                override fun deadline() = clock.deadline()
                override fun workMeter(): WorkMeter = meter
            }
            var names: Map<String, String> = emptyMap()
            var originalNames: Map<String, String>? = null
            var integerFixings: Map<Int, BigFraction> = emptyMap()
            val source = if (prepared) {
                val parsed = SmtLib.parse(text, cancellation = token)
                originalNames = (parsed.source.originalSource as? PolynomialRealProblem)?.let { original ->
                    original.realVarNames.mapKeys { "real.${it.key}" }.mapValues { it.value.toString() } +
                        original.boolVarNames.mapKeys { "bool.${it.key}" }.mapValues { it.value.toString() }
                }
                val invocation = SourceSetup.prepare(SourceSetupRequest(
                    parsed.source, ExecutionOperation.SATISFY, FiniteEngine.MIXED, cancellation = token,
                ))
                val problem = (invocation.route as? SourceProblemRoute.PolynomialTheory)?.request?.problem
                    ?: return PhaseTrace(emptyList(), "NotPolynomial", invocation.route.javaClass.simpleName,
                        null, 0, 0, charges, stop)
                names = problem.realVarNames.mapKeys { "real.${it.key}" }.mapValues { it.value.toString() } +
                    problem.boolVarNames.mapKeys { "bool.${it.key}" }.mapValues { it.value.toString() }
                Triple(
                    problem.javaClass.getDeclaredField("formula").apply { isAccessible = true }.get(problem),
                    problem.javaClass.getDeclaredField("totalBoolCount").apply { isAccessible = true }.getInt(problem),
                    problem.javaClass.getDeclaredField("totalRealCount").apply { isAccessible = true }.getInt(problem),
                )
            } else {
                val parsed = parsed(text, token)
                @Suppress("UNCHECKED_CAST")
                val integers = sourceType.getMethod("getIntVarNames").invoke(parsed) as Map<String, Int>
                require(integers.keys == fixedIntegers.keys) { "Every integer declaration needs an exact fixing" }
                integerFixings = fixedIntegers.map { (name, value) ->
                    require(value.matches(Regex("-?[0-9]{1,256}")))
                    integers.getValue(name) to BigFraction.of(parseBigInt(value), parseBigInt("1"))
                }.toMap()
                names = listOf("real" to "getRealVarNames", "bool" to "getBoolVarNames", "integer" to "getIntVarNames")
                    .flatMap { (kind, getter) ->
                        @Suppress("UNCHECKED_CAST")
                        val mapping = sourceType.getMethod(getter).invoke(parsed) as Map<String, Int>
                        mapping.map { (name, id) -> "$kind.$name" to id.toString() }
                    }.toMap()
                originalNames = names
                Triple(formula.invoke(parsed), boolCount.invoke(parsed) as Int, realCount.invoke(parsed) as Int)
            }
            val original = source.first
            @Suppress("UNCHECKED_CAST")
            val fixed = (fixings.invoke(null, original, { check(!token()) }) as Map<Int, BigFraction>) + integerFixings
            val preparationCharges = charges
            val owner = query.newInstance(original, source.second, source.third,
                params.newInstance(token, maximumWork, limitsType.getConstructor().newInstance(), refinement, feedback),
                condition.newInstance(emptyMap<Int, Boolean>(), fixed, fixed))
            val captured = if (capture) captureType.getConstructor().newInstance().also { setCapture.invoke(owner, it) }
                else null
            var conflictPremises: Int? = null
            var conflictPreview: List<String>? = null
            var conflictPreviewComplete: Boolean? = null
            val observeConflict: (List<*>) -> Unit = { premises ->
                if (conflictPremises == null) {
                    conflictPremises = premises.size
                    var complete = premises.size <= 16
                    conflictPreview = premises.take(16).map {
                        val (rendered, exact) = previewConflictAtom(requireNotNull(it))
                        complete = complete && exact
                        rendered
                    }
                    conflictPreviewComplete = complete
                }
            }
            conflictObserver?.invoke(owner, observeConflict)
            val checkpoints = ArrayList<PhaseCheckpoint>()
            val start = System.nanoTime()
            var calls = 0L
            var lastProjected = 0L
            var lastStage: Triple<Long, Long, Long>? = null
            var firstFeedback: Map<String, String>? = null
            var firstUse: Map<String, String>? = null
            var result: Any?
            do {
                result = next.invoke(owner)
                calls++
                val snapshot = stats.invoke(owner)
                val projected = snapshot.javaClass.getMethod("getProjectedPolynomials").invoke(snapshot) as Long
                val observed = progress?.invoke(owner)
                if (refinement && firstUse == null) {
                    firstUse = feedbackUse.invoke(owner)?.let(::feedbackTrace)
                }
                if (refinement && firstFeedback == null) {
                    firstFeedback = feedbackReuse.invoke(owner)?.let(::feedbackTrace)
                }
                val stage = observed?.let {
                    Triple(it.javaClass.getMethod("getSnapshots").invoke(it) as Long,
                        it.javaClass.getMethod("getRegions").invoke(it) as Long,
                        partialConflicts?.invoke(it) as? Long ?: 0L)
                }
                if (result != null || (checkpoints.size < 128 &&
                        (calls <= 2 || projected != lastProjected || stage != lastStage))) {
                    checkpoints += PhaseCheckpoint(calls, (System.nanoTime() - start) / 1_000_000,
                        result?.javaClass?.simpleName ?: "Pending", snapshot.toString(), observed?.toString())
                }
                lastProjected = projected
                lastStage = stage
            } while (result == null)
            val snapshot = stats.invoke(owner)
            val nativeWork = snapshot.javaClass.getMethod("getWorkSpent").invoke(snapshot) as Long
            check(charges - preparationCharges == nativeWork) { "Native charge sum differs from snapshot" }
            return PhaseTrace(checkpoints, result.javaClass.simpleName,
                result.javaClass.methods.singleOrNull { it.name == "getReason" }?.invoke(result)?.toString(),
                result.javaClass.methods.singleOrNull { it.name == "getResource" }?.invoke(result)?.toString(),
                nativeWork, charges - preparationCharges, preparationCharges, stop, source.second, source.third,
                partialConflictPremises = conflictPremises, partialConflictPreview = conflictPreview,
                partialConflictPreviewComplete = conflictPreviewComplete,
                checkedCells = if (refinement) {
                    (queryType.getMethod("getCheckedCells").invoke(owner) as List<*>).map {
                        cellTrace(requireNotNull(it))
                    }
                } else null,
                cellRefinement = queryType.getMethod("getCellStats").invoke(owner)?.let { value ->
                    listOf("Candidates", "CheckedCells", "RepeatedCandidates",
                        "RepairedCoordinates", "Declined", "Work")
                        .associateWith { value.javaClass.getMethod("get$it").invoke(value).toString() }
                },
                cellFeedback = feedbackStats.invoke(owner)?.let { value ->
                    listOf("Admitted", "Lookups", "Uses", "Reuses", "CrossRegionUses", "Evicted",
                        "SnapshotReplacements", "ScopeDeclines", "MetadataVisits")
                        .associateWith { value.javaClass.getMethod("get$it").invoke(value).toString() }
                },
                firstCellFeedbackReuse = firstFeedback,
                firstCellFeedbackUse = firstUse,
                sourceCoordinates = names,
                originalSourceCoordinates = originalNames,
                coordinateNamespace = if (prepared) "prepared-polynomial-model" else "raw-polynomial-source",
                coordinateMapping = if (prepared) "original-to-prepared correspondence unavailable"
                    else "named original declarations; lowering-only slots unnamed",
                failureCapture = captured?.let { value ->
                    listOf("Scope", "Context", "Operation", "FirstFailure", "FirstCadFailure",
                        "FirstRefusal", "FirstSignConflict")
                        .associateWith { field ->
                        @Suppress("UNCHECKED_CAST")
                        (captureType.getMethod("get$field").invoke(value) as? Map<String, String>).orEmpty()
                    }
                },
                failureObservations = captured?.let { value ->
                    listOf("Failures", "RepeatedFirstFailure", "RepetitionComplete",
                        "InterruptedCapture", "CompletedCopySteps")
                        .associateWith { captureType.getMethod("get$it").invoke(value).toString() }
                })
        }

        private fun cellTrace(cell: Any): Map<String, String> {
            fun read(owner: Any, field: String): Any? = owner.javaClass.getMethod("get$field").invoke(owner)
            val result = linkedMapOf<String, String>()
            val scope = requireNotNull(read(cell, "Scope"))
            result["scope"] = scope.javaClass.simpleName
            result["variable"] = read(cell, "Variable").toString()
            result["lowerClosed"] = read(cell, "LowerClosed").toString()
            result["upperClosed"] = read(cell, "UpperClosed").toString()
            for (end in listOf("Lower", "Upper")) {
                read(cell, end)?.let { value ->
                    result["$end.coefficients"] = read(value, "DefiningCoefficients").toString()
                    result["$end.root"] = read(value, "RootIndex").toString()
                    result["$end.lower"] = read(value, "LowerBound").toString()
                    result["$end.upper"] = read(value, "UpperBound").toString()
                }
            }
            val atom = requireNotNull(read(cell, "Predicate"))
            result["relation"] = atomRelation.invoke(atom).toString()
            val terms = coefficients.get(atomPolynomial.invoke(atom)) as Map<*, *>
            for ((index, entry) in terms.entries.withIndex()) {
                result["term.$index.coefficient"] = entry.value.toString()
                result["term.$index.powers"] = powers.get(entry.key).toString()
            }
            if (scope.javaClass.simpleName != "Source") {
                val condition = requireNotNull(read(scope, "Condition"))
                for (field in listOf("Bools", "Lower", "Upper")) {
                    result["condition.$field"] = read(condition, field).toString()
                }
                result["snapshotIdentity"] = System.identityHashCode(read(scope, "Snapshot")).toString()
            }
            if (scope.javaClass.simpleName == "Region") {
                val premises = requireNotNull(read(scope, "Premises"))
                result["signedPremises"] = read(premises, "Atoms").toString()
                result["booleans"] = read(premises, "Bools").toString()
                result["reductionIdentity"] = System.identityHashCode(read(scope, "Reduction")).toString()
            }
            return result
        }

        private fun feedbackTrace(use: Any): Map<String, String> {
            fun read(owner: Any, field: String): Any? = owner.javaClass.getMethod("get$field").invoke(owner)
            val cell = requireNotNull(read(use, "Cell"))
            val result = cellTrace(cell).toMutableMap()
            var complete = true
            for (field in listOf("Origin", "Current")) {
                val scope = requireNotNull(read(use, field))
                result["$field.sourceIdentity"] = System.identityHashCode(read(scope, "Source")).toString()
                result["$field.snapshotIdentity"] = System.identityHashCode(read(scope, "Snapshot")).toString()
                result["$field.reductionIdentity"] = System.identityHashCode(read(scope, "Reduction")).toString()
                val premises = requireNotNull(read(scope, "Premises"))
                result["$field.signedPremises"] = read(premises, "Atoms").toString()
                result["$field.booleans"] = read(premises, "Bools").toString()
                val condition = requireNotNull(read(scope, "Condition"))
                result["$field.conditionIdentity"] = System.identityHashCode(condition).toString()
                for (kind in listOf("Bools", "Lower", "Upper")) {
                    val values = read(condition, kind) as Map<*, *>
                    if (values.size > 64) complete = false
                    result["$field.condition.$kind"] = values.entries.take(64).associate { (id, value) ->
                        val wide = value is BigFraction && (value.num.magnitudeBitLength() > 4096 ||
                            value.den.magnitudeBitLength() > 4096)
                        if (wide) complete = false
                        id.toString() to if (wide) "[wide coefficient]" else value.toString()
                    }.toString()
                }
            }
            result["scopePreviewComplete"] = complete.toString()
            val candidate = requireNotNull(read(use, "Value"))
            val coefficients = read(candidate, "DefiningCoefficients") as List<*>
            var candidateComplete = coefficients.size <= 17
            fun fraction(value: BigFraction): String = if (value.num.magnitudeBitLength() > 4096 ||
                value.den.magnitudeBitLength() > 4096) {
                candidateComplete = false
                "[wide coefficient]"
            } else value.toString()
            result["candidate.coefficients"] = coefficients.take(17).map { fraction(it as BigFraction) }.toString()
            result["candidate.root"] = read(candidate, "RootIndex").toString()
            result["candidate.lower"] = fraction(read(candidate, "LowerBound") as BigFraction)
            result["candidate.upper"] = fraction(read(candidate, "UpperBound") as BigFraction)
            result["candidatePreviewComplete"] = candidateComplete.toString()
            return result
        }

        private fun previewConflictAtom(atom: Any): Pair<String, Boolean> {
            val values = coefficients.get(atomPolynomial.invoke(atom)) as Map<*, *>
            var complete = values.size <= 8
            val terms = values.entries.take(8).map { (monomial, value) ->
                val fraction = value as BigFraction
                val factors = powers.get(monomial) as Map<*, *>
                if (factors.size > 8) complete = false
                val number = if (fraction.num.magnitudeBitLength() > 96 || fraction.den.magnitudeBitLength() > 96) {
                    complete = false
                    "[wide coefficient]"
                } else fraction.toString()
                val product = factors.entries.take(8).joinToString("*") { (variable, degree) -> "x$variable^$degree" }
                if (product.isEmpty()) number else "$number*$product"
            }
            val polynomial = if (terms.isEmpty()) "0" else terms.joinToString(" + ")
            if (polynomial.length > 480) complete = false
            return "${polynomial.take(480)} ${atomRelation.invoke(atom)} 0" to complete
        }
    }
}

@Serializable
private data class PhaseSource(
    val suite: String,
    val problem: String,
    val sha256: String,
    val fixedIntegers: Map<String, String> = emptyMap(),
    val gzip64: String? = null,
)

@Serializable
internal data class PhaseGate(
    val realCount: Int,
    val boolCount: Int,
    val atoms: Int,
    val conjunctiveAtoms: Int?,
    val normalizedNodeKinds: Map<String, Int>,
    val separateInspectionWork: Long,
)

@Serializable
internal data class PhaseCheckpoint(
    val nextCall: Long,
    val elapsedMs: Long,
    val result: String,
    val stats: String,
    val progress: String? = null,
)

@Serializable
internal data class PhaseTrace(
    val checkpoints: List<PhaseCheckpoint>,
    val result: String,
    val reason: String?,
    val resource: String?,
    val nativeWork: Long,
    val chargeSum: Long,
    val preparationCharges: Long,
    val containingDeadlineStack: List<String>?,
    val queryBooleans: Int? = null,
    val queryReals: Int? = null,
    val partialConflictPremises: Int? = null,
    val partialConflictPreview: List<String>? = null,
    val partialConflictPreviewComplete: Boolean? = null,
    val checkedCells: List<Map<String, String>>? = null,
    val cellRefinement: Map<String, String>? = null,
    val cellFeedback: Map<String, String>? = null,
    val firstCellFeedbackReuse: Map<String, String>? = null,
    val firstCellFeedbackUse: Map<String, String>? = null,
    val sourceCoordinates: Map<String, String>? = null,
    val originalSourceCoordinates: Map<String, String>? = null,
    val coordinateNamespace: String? = null,
    val coordinateMapping: String? = null,
    val failureCapture: Map<String, Map<String, String>>? = null,
    val failureObservations: Map<String, String>? = null,
)

@Serializable
internal data class PipelinePhaseTrace(
    val result: String,
    val reason: String?,
    val resource: String?,
    val nativeResource: String?,
    val nativeStats: String,
    val searchStats: String,
    val preparationCharges: Long,
    val executionCharges: Long,
    val containingDeadlineStack: List<String>?,
    val sourceShape: PipelineSourceShape? = null,
    val deadlineOverrunAtFirstPollNs: Long? = null,
    val deadlineOverrunAtReturnNs: Long? = null,
)

@Serializable
internal data class PipelineSourceShape(
    val preparedKind: String?,
    val sourceRepresentation: String,
    val declaredBooleans: Int,
    val declaredIntegers: Int,
    val declaredReals: Int,
    val modelBooleans: Int?,
    val modelIntegers: Int?,
    val modelReals: Int?,
    val modelFactors: Int?,
    val theoryBooleans: Int?,
    val theoryReals: Int?,
)
