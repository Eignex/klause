package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.smtlib.SmtLibAdmission
import com.eignex.klause.formats.smtlib.SmtLibInput
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.PolynomialStrategyParams
import com.eignex.klause.solver.pipeline.PolynomialTheoryParams
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.pipeline.pipelineRequest
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Base64
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object PolynomialPhaseProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 1..3 && args[0].startsWith("request64="))
        val options = args.drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.size == args.size - 1 && options.keys.all { it in setOf("pipeline", "timeoutMs") })
        require(options["pipeline"] == null || options["pipeline"] == "true")
        val pipelineMode = options["pipeline"] == "true"
        val timeoutMs = options["timeoutMs"]?.toLong() ?: 60_000L
        require(timeoutMs > 0)
        val encoded = args[0].substringAfter('=')
        require(encoded.length <= 48 * 1024)
        val request = Base64.getDecoder().decode(encoded)
        val cases = Json.decodeFromString<List<PhaseSource>>(request.decodeToString())
        require(cases.size in 1..18 && cases.map { it.suite to it.problem }.distinct().size == cases.size)
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
            "maximumWork" to "null",
            "representationLimits" to "default",
            "route" to if (pipelineMode) "source admission and default MIXED pipeline/P1/seed3"
                else "direct normalized QfNraQuery; separate gate inspection",
            "arithmeticObservations" to if (pipelineMode) "disabled"
                else "last subresultant and division requests; metadata read after query",
        )))
        val access = PhaseAccess()
        for ((index, source) in cases.withIndex()) {
            require(source.sha256.matches(Regex("[0-9a-f]{64}")))
            val reference = Catalog.uncapped(source.suite).problems.single { it.name == source.problem }
            val bytes = CorpusFiles.open(CorpusFetcher.resolve(reference.source)).use { it.readNBytes(1024 * 1024 + 1) }
            require(bytes.size <= 1024 * 1024 && sha256(bytes) == source.sha256)
            val directory = out.resolve(index.toString()).apply { mkdirs() }
            directory.resolve("source.smt2").writeBytes(bytes)
            val text = bytes.decodeToString()
            if (pipelineMode) {
                directory.resolve("pipeline.json").writeText(Json.encodeToString(pipeline(text, timeoutMs)))
            } else {
                directory.resolve("gate.json").writeText(Json.encodeToString(access.gate(text, timeoutMs)))
                directory.resolve("trace.json").writeText(
                    Json.encodeToString(access.measure(text, timeoutMs = timeoutMs)),
                )
            }
        }
    }

    internal fun pipeline(text: String, timeoutMs: Long = 60_000L): PipelinePhaseTrace {
        val observation = DeadlineObservation(timeoutMs)
        val source = SmtLibAdmission.parse(text, cancellation = observation)
        require(source is SmtLibInput.Polynomial) { "Source did not select polynomial admission: ${source.javaClass}" }
        val request = source.problem.pipelineRequest()
        val preparedCharges = observation.charges
        val execution = OpenTheoryPipeline.execute(request, PolynomialTheoryParams(
            timeout = observation,
            strategy = PolynomialStrategyParams(
                backtrack = BacktrackParams(randomSeed = 3),
                localSearch = LocalSearchParams(randomSeed = 3),
            ),
        ), FiniteEngine.MIXED, 1)
        return PipelinePhaseTrace(execution.result.javaClass.simpleName,
            (execution.result as? OpenTheoryResult.Unknown)?.reason?.toString(),
            execution.resource?.toString(), execution.nativeResource,
            execution.stats.toString(), execution.result.stats.toString(),
            preparedCharges, observation.charges - preparedCharges, observation.stack)
    }

    private class DeadlineObservation(timeoutMs: Long) : Cancellation, WorkMeter {
        private val clock = Cancellation.after(timeoutMs.milliseconds)
        var charges = 0L
            private set
        var stack: List<String>? = null
            private set
        override fun deadline() = clock.deadline()
        override fun workMeter(): WorkMeter = this
        override fun charge(units: Long) { charges += units }
        override fun isCancelled(): Boolean {
            val stopped = clock()
            if (stopped && stack == null) stack = Thread.currentThread().stackTrace.map { it.toString() }
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
        private val params = paramsType.getConstructor(Cancellation::class.java, Long::class.javaObjectType, limitsType)
        private val condition = conditionType.getConstructor(Map::class.java, Map::class.java, Map::class.java)
        private val next = queryType.getMethod("next")
        private val stats = queryType.getMethod("getStats")
        private val fixings = Class.forName(prefix + "PolynomialPointFixingsKt").methods.single {
            it.name == "polynomialPointFixings"
        }

        private fun parsed(text: String, token: Cancellation): Any =
            parse.invoke(parser.getField("INSTANCE").get(null), text, token, false, false)

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

        fun measure(text: String, maximumWork: Long? = null, timeoutMs: Long = 60_000L): PhaseTrace {
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
            val source = parsed(text, token)
            val original = formula.invoke(source)
            val fixed = fixings.invoke(null, original, { check(!token()) })
            val preparationCharges = charges
            val owner = query.newInstance(original, boolCount.invoke(source), realCount.invoke(source),
                params.newInstance(token, maximumWork, limitsType.getConstructor().newInstance()),
                condition.newInstance(emptyMap<Int, Boolean>(), fixed, fixed))
            val arithmetic = queryType.getDeclaredField("arithmetic").apply { isAccessible = true }.get(owner)
            val observation = ArithmeticObservation(arithmetic)
            val checkpoints = ArrayList<PhaseCheckpoint>()
            val start = System.nanoTime()
            var calls = 0L
            var lastProjected = 0L
            var result: Any?
            do {
                result = next.invoke(owner)
                calls++
                val snapshot = stats.invoke(owner)
                val projected = snapshot.javaClass.getMethod("getProjectedPolynomials").invoke(snapshot) as Long
                if (calls <= 2 || projected != lastProjected || result != null) {
                    checkpoints += PhaseCheckpoint(calls, (System.nanoTime() - start) / 1_000_000,
                        result?.javaClass?.simpleName ?: "Pending", snapshot.toString())
                }
                lastProjected = projected
            } while (result == null)
            val snapshot = stats.invoke(owner)
            val nativeWork = snapshot.javaClass.getMethod("getWorkSpent").invoke(snapshot) as Long
            check(charges - preparationCharges == nativeWork) { "Native charge sum differs from snapshot" }
            return PhaseTrace(checkpoints, result.javaClass.simpleName,
                result.javaClass.methods.singleOrNull { it.name == "getReason" }?.invoke(result)?.toString(),
                result.javaClass.methods.singleOrNull { it.name == "getResource" }?.invoke(result)?.toString(),
                nativeWork, charges - preparationCharges, preparationCharges, stop,
                observation.subresultants(), observation.division())
        }
    }
}

private class ArithmeticObservation(arithmetic: Any) {
    private val polynomialType = Class.forName("com.eignex.klause.arithmetic.polynomial.Polynomial")
    private val degree = polynomialType.getMethod("degree", Int::class.javaPrimitiveType)
    private val terms = polynomialType.getMethod("getTerms")
    private val variables = polynomialType.getMethod("getVariables")
    private var left: Any? = null
    private var right: Any? = null
    private var variable = 0
    private var index: Int? = null
    private var dividend: Any? = null
    private var divisor: Any? = null

    init {
        val subresultants: (Any, Any, Int, Int?) -> Unit = { a, b, coordinate, minor ->
            left = a
            right = b
            variable = coordinate
            index = minor
            dividend = null
            divisor = null
        }
        val division: (Any, Any) -> Unit = { a, b ->
            dividend = a
            divisor = b
        }
        arithmetic.javaClass.getMethod("setSubresultantObserver", Function4::class.java)
            .invoke(arithmetic, subresultants)
        arithmetic.javaClass.getMethod("setDivisionObserver", Function2::class.java).invoke(arithmetic, division)
    }

    fun subresultants(): PhaseSubresultants? {
        val a = left ?: return null
        val b = checkNotNull(right)
        val m = degree.invoke(a, variable) as Int
        val n = degree.invoke(b, variable) as Int
        return PhaseSubresultants(variable, index, m, n, termCount(a), termCount(b),
            index?.let { m.toLong() + n - 2L * it })
    }

    fun division(): PhaseDivision? {
        val a = dividend ?: return null
        val b = checkNotNull(divisor)
        val count = termCount(b)
        return PhaseDivision(termCount(a), count,
            (variables.invoke(b) as List<*>).isEmpty(), count == 1)
    }

    private fun termCount(value: Any): Int = (terms.invoke(value) as List<*>).size
}

@Serializable
private data class PhaseSource(val suite: String, val problem: String, val sha256: String)

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
internal data class PhaseCheckpoint(val nextCall: Long, val elapsedMs: Long, val result: String, val stats: String)

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
    val lastSubresultants: PhaseSubresultants? = null,
    val lastDivision: PhaseDivision? = null,
)

@Serializable
internal data class PhaseSubresultants(
    val variable: Int,
    val index: Int?,
    val leftDegree: Int,
    val rightDegree: Int,
    val leftTerms: Int,
    val rightTerms: Int,
    val matrixSize: Long?,
)

@Serializable
internal data class PhaseDivision(
    val dividendTerms: Int,
    val divisorTerms: Int,
    val divisorIsConstant: Boolean,
    val divisorIsMonomial: Boolean,
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
)
