package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRealProblem
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.reflect.InvocationTargetException
import java.util.Base64
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal object PolynomialWitnessProbeProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 1..4 && args[0].substringBefore('=') in setOf("request64", "catalog64"))
        val options = args.drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.size == args.size - 1 && options.keys.all { it in setOf("source", "charges", "required") })
        val mode = options["source"] ?: "direct"
        val chargeScope = options["charges"] ?: "all"
        require(mode in setOf("direct", "shared") && chargeScope in setOf("all", "coupled"))
        val required = options["required"] ?: "none"
        require(required in setOf("none", "reduce"))
        val request = Base64.getDecoder().decode(args[0].substringAfter('='))
        require(request.size <= 48 * 1024)
        val catalog = args[0].startsWith("catalog64=")
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        if (System.getenv("KLAUSE_POLYNOMIAL_PROBE_CHILD") != "1") {
            launch(args, out)
            return
        }
        File(out, "request.json").writeBytes(request)
        Reports.writeJson(File(out, "provenance.json").path, InstalledBuild.reference)
        File(out, "settings.json").writeText(
            buildJsonObject {
            put("revision", Reports.readGitSha())
            put("javaCores", Runtime.getRuntime().availableProcessors())
            put("jvmOptions", ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" "))
            put("containingTimeoutMs", 60000)
            put("preparationCheckpoints", 2)
            put("sourceMode", mode)
            put("chargeScope", chargeScope)
            put("sourceInput", if (catalog) "catalog" else "embedded")
            put("requiredInspection", required)
            put("requiredPhaseTimeoutMs", 5000)
        }.toString() + "\n"
        )
        val cases = if (catalog) {
            val sources = Json.decodeFromString<List<ProbeCatalogSource>>(request.toString(Charsets.UTF_8))
            require(sources.size in 1..18 && sources.map { it.suite to it.problem }.distinct().size == sources.size)
            sources.map { source ->
                require(source.sha256.matches(Regex("[0-9a-f]{64}")))
                val reference = Catalog.uncapped(source.suite).problems.single { it.name == source.problem }
                val bytes = CorpusFiles.open(CorpusFetcher.resolve(reference.source)).use {
                    it.readNBytes(1024 * 1024 + 1)
                }
                require(bytes.size <= 1024 * 1024 && sha256(bytes) == source.sha256)
                ProbeSource(source.suite + "/" + source.problem, Base64.getEncoder().encodeToString(bytes),
                    source.sha256)
            }
        } else Json.decodeFromString<List<ProbeSource>>(request.toString(Charsets.UTF_8)).also { sources ->
            require(sources.size in 1..8 && sources.map { it.id }.distinct().size == sources.size)
        }
        val access = ProbeAccess()
        for ((index, source) in cases.withIndex()) {
            val bytes = Base64.getDecoder().decode(source.base64)
            require(bytes.size <= (if (catalog) 1024 * 1024 else 32 * 1024) && sha256(bytes) == source.sha256)
            val directory = File(out, index.toString()).apply { mkdirs() }
            File(directory, "source.smt2").writeBytes(bytes)
            val records = (0..1).flatMap { round ->
                (if (round == 0) listOf(false, true) else listOf(true, false)).map { observed ->
                    access.measure(bytes.toString(Charsets.UTF_8), observed, chargeStacks = observed,
                        sourceMode = mode, coupledChargesOnly = chargeScope == "coupled")
                        .copy(round = round)
                }
            }
            File(directory, "probe.json").writeText(Json.encodeToString(records))
            require(records.map { it.result to it.stats }.distinct().size == 1) {
                "Observation changed native work or producer outcome for ${source.id}"
            }
            require(records.map { it.sourceShape }.distinct().size == 1) {
                "Observation changed the selected polynomial input for ${source.id}"
            }
            if (required == "reduce") {
                val inspection = access.inspectRequired(bytes.toString(Charsets.UTF_8), sourceMode = mode)
                File(directory, "required.json").writeText(Json { encodeDefaults = true }.encodeToString(inspection))
            }
        }
    }

    private fun launch(args: Array<String>, out: File) {
        val command = listOf(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-Xmx3g", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1",
            "--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector",
            "-Dklause.workspace.root=${CorpusFetcher.workspaceRoot()}",
            "-Dklause.bench.corpusCache=${CorpusFetcher.cacheRoot}",
            "-cp", System.getProperty("java.class.path"), PolynomialWitnessProbeProfile::class.java.name,
        ) + args
        out.resolve("command.json").writeText(Json.encodeToString(command))
        val process = ProcessBuilder(command).apply {
            environment()["KLAUSE_POLYNOMIAL_PROBE_CHILD"] = "1"
            redirectOutput(out.resolve("measured.out"))
            redirectError(out.resolve("measured.err"))
        }.start()
        val exit = process.waitFor()
        out.resolve("measured.exit").writeText("$exit\n")
        check(exit == 0) { "Measured polynomial probe diagnostic exited $exit" }
    }

    internal class ProbeAccess {
        private val prefix = "com.eignex.klause.theory.qfnra."
        private val parser = Class.forName("com.eignex.klause.formats.smtlib.SmtLibPolynomial")
        private val parse = parser.getMethod(
            "parse",
            String::class.java,
            Cancellation::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        private val sourceType = Class.forName("com.eignex.klause.formats.smtlib.SmtLibPolynomialProblem")
        private val formula = sourceType.getMethod("getFormula")
        private val realCount = sourceType.getMethod("getRealCount")
        private val boolCount = sourceType.getMethod("getBoolCount")
        private val limitsType = Class.forName("com.eignex.klause.arithmetic.polynomial.PolynomialLimits")
        private val paramsType = Class.forName(prefix + "QfNraParams")
        private val conditionType = Class.forName(prefix + "PolynomialCondition")
        private val queryType = Class.forName(prefix + "QfNraQuery")
        private val params = paramsType.getConstructor(Cancellation::class.java, Long::class.javaObjectType, limitsType)
        private val condition = conditionType.getConstructor(Map::class.java, Map::class.java, Map::class.java)
        private val query = queryType.getConstructor(
            PolynomialRealFormula::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            paramsType,
            conditionType,
        )
        private val next = queryType.getMethod("next")
        private val stats = queryType.getMethod("getStats")
        private val work = queryType.getDeclaredField("work").apply { isAccessible = true }
        private val workType = Class.forName(prefix + "QfNraWork")
        private val spent = workType.getDeclaredField("spent").apply { isAccessible = true }
        private val coupledEnd = workType.getDeclaredField("coupledEnd").apply { isAccessible = true }
        private val probeEnd = workType.getDeclaredField("probeEnd").apply { isAccessible = true }
        private val fixings = Class.forName(prefix + "PolynomialPointFixingsKt").methods.single {
            it.name == "polynomialPointFixings"
        }

        @Suppress("TooGenericExceptionCaught", "LongMethod")
        fun inspectRequired(
            text: String,
            sourceMode: String = "shared",
            cancellation: Cancellation = Cancellation.Never,
            timeoutMs: Long = 5000,
        ): RequiredInspection {
            require(timeoutMs in 1..5000)
            var stage = "source"
            var charged = 0L
            var shape: ProbeShape? = null
            var mandatory: Int? = null
            var equalities: Int? = null
            var graphVariable: Int? = null
            var graphTerms: Int? = null
            var graphs = 0
            fun record(status: String, eliminated: Int? = null, remaining: Int? = null,
                variables: Int? = null, inconsistent: Boolean? = null, resource: String? = null) = RequiredInspection(
                status, stage, charged, shape, mandatory, equalities, graphVariable, graphTerms,
                graphs, eliminated, remaining, variables, inconsistent, resource,
            )
            if (cancellation()) return record("Stopped")
            val selected = input(text, sourceMode)
            shape = selected.shape
            val sourceFormula = selected.formula ?: return record("NotPolynomialSource")
            val meter = WorkMeter { units -> charged += units; cancellation.charge(units) }
            val stop = object : Cancellation {
                override fun isCancelled(): Boolean = cancellation()
                override fun workMeter(): WorkMeter = meter
            } or Cancellation.after(timeoutMs.milliseconds)
            try {
                stage = "normalization"
                val arithmeticType = Class.forName("com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic")
                val arithmetic = arithmeticType.getConstructor(Cancellation::class.java, limitsType)
                    .newInstance(stop, limitsType.getConstructor().newInstance())
                val step = arithmeticType.getMethod("step")
                val normalizedType = Class.forName(prefix + "PolynomialFormula")
                val companion = normalizedType.getField("Companion").get(null)
                val normalized = companion.javaClass.getMethod("normalize", PolynomialRealFormula::class.java,
                    arithmeticType, Map::class.java).invoke(companion, sourceFormula, arithmetic, emptyMap<Int, Any>())
                stage = "mandatory-facts"
                val atoms = normalizedType.getMethod("witnessAtoms", arithmeticType, Int::class.javaPrimitiveType)
                    .invoke(normalized, arithmetic, Int.MAX_VALUE) as? List<*> ?: return record("BooleanConflict")
                mandatory = atoms.size
                val polynomials = ArrayList<Any>()
                val equations = ArrayList<Any>()
                for (atom in atoms) {
                    step.invoke(arithmetic)
                    val retained = checkNotNull(atom)
                    val polynomial = retained.javaClass.getMethod("getPolynomial").invoke(retained)
                    polynomials.add(polynomial)
                    if (retained.javaClass.getMethod("getRelation").invoke(retained).toString() == "EQ") {
                        equations.add(polynomial)
                    }
                }
                equalities = equations.size
                stage = "initial-explicit-section"
                val graph = Class.forName(prefix + "PolynomialEquationalProjectionKt")
                    .getMethod("equationalSection", List::class.java, List::class.java, arithmeticType)
                    .invoke(null, equations, polynomials, arithmetic)
                if (graph != null) {
                    graphVariable = graph.javaClass.getMethod("getVariable").invoke(graph) as Int
                    val replacement = graph.javaClass.getMethod("getReplacement").invoke(graph)
                    graphTerms = (replacement.javaClass.getMethod("getTerms").invoke(replacement) as List<*>).size
                }
                stage = "mandatory-reduction"
                val formula = companion.javaClass.getMethod("conjunction", List::class.java, arithmeticType)
                    .invoke(companion, atoms, arithmetic)
                val regionType = Class.forName(prefix + "PolynomialBooleanRegion")
                val region = regionType.getConstructor(Map::class.java, Map::class.java).newInstance(
                    atoms.indices.associateWith { step.invoke(arithmetic); true }, emptyMap<Int, Boolean>(),
                )
                val reductionType = Class.forName(prefix + "PolynomialAffineReduction")
                val reducer = reductionType.getField("Companion").get(null)
                val onGraph = { graphs++; Unit }
                val cursor = reducer.javaClass.getMethod("prepare", normalizedType, regionType,
                    Int::class.javaPrimitiveType, arithmeticType, Function0::class.java)
                    .invoke(reducer, formula, region, checkNotNull(selected.shape.realCount), arithmetic, onGraph)
                    as Iterator<*>
                var reduction: Any? = null
                while (reduction == null) reduction = cursor.next()
                val rows = reductionType.getMethod("getAtoms").invoke(reduction) as List<*>
                val referenced = HashSet<Any>()
                for (atom in rows) {
                    step.invoke(arithmetic)
                    val polynomial = checkNotNull(atom).javaClass.getMethod("getPolynomial").invoke(atom)
                    for (variable in polynomial.javaClass.getMethod("getVariables").invoke(polynomial) as Set<*>) {
                        step.invoke(arithmetic)
                        referenced.add(checkNotNull(variable))
                    }
                }
                val inconsistent = reductionType.getMethod("getInconsistent").invoke(reduction) as Boolean
                stage = "complete"
                return record(if (inconsistent) "Inconsistent" else "Reduced",
                    reductionType.getMethod("getEliminatedCount").invoke(reduction) as Int,
                    rows.size, referenced.size, inconsistent)
            } catch (error: Exception) {
                var cause: Throwable = error
                while (cause is InvocationTargetException) cause = checkNotNull(cause.cause)
                return when (cause.javaClass.simpleName) {
                    "PolynomialInterrupted" -> record("Stopped")
                    "PolynomialResourceLimit" -> record("ResourceLimit",
                        resource = cause.javaClass.getMethod("getResource").invoke(cause).toString())
                    else -> throw error
                }
            }
        }

        fun workCheckpoint(owner: Any): ProbeStop? {
            val boundary = coupledEnd.get(owner) as? Long ?: return null
            val charged = spent.getLong(owner)
            if (charged < boundary) return null
            return ProbeStop(
                charged,
                boundary,
                probeEnd.get(owner) as? Long,
                Thread.currentThread().stackTrace.map { it.toString() },
            )
        }

        fun measure(
            text: String,
            observed: Boolean,
            chargeStacks: Boolean = false,
            sourceMode: String = "direct",
            coupledChargesOnly: Boolean = false,
        ): ProbeRecord {
            val input = input(text, sourceMode)
            val sourceFormula = input.formula ?: return ProbeRecord(
                0, observed, "NotPolynomialSource", "selected route: " + input.shape.route,
                0, emptyList(), emptyMap(), input.shape,
            )
            val fixed = fixings.invoke(null, sourceFormula, {})
            val stops = ArrayList<ProbeStop>()
            var owner: Any? = null
            var captured = false
            val charges = LinkedHashMap<String, Long>()
            val meter = if (observed && chargeStacks) {
                WorkMeter { units ->
                val current = owner
                val outside = coupledChargesOnly && (current == null || coupledEnd.get(current) == null)
                val frames = if (outside) "outside coupled scope" else Thread.currentThread().stackTrace.filter {
                    it.className.startsWith("com.eignex.klause.") &&
                        !it.className.startsWith("com.eignex.klause.bench.") &&
                        !it.className.startsWith("com.eignex.klause.util.") &&
                        it.className != prefix + "QfNraWork"
                }.joinToString("\n") { it.toString() }
                charges[frames] = (charges[frames] ?: 0L) + units
            }
            } else {
                null
            }
            val observer = object : Cancellation {
                override fun workMeter(): WorkMeter? = meter
                override fun isCancelled(): Boolean {
                    val current = owner
                    if (observed && current != null && !captured) {
                        workCheckpoint(current)?.let { stop ->
                            captured = true
                            stops += stop
                        }
                    }
                    return false
                }
            }
            val settings = params.newInstance(
                observer or Cancellation.after(60.seconds),
                null,
                limitsType.getConstructor().newInstance(),
            )
            val preparedQuery = query.newInstance(
                sourceFormula,
                checkNotNull(input.shape.boolCount),
                checkNotNull(input.shape.realCount),
                settings,
                condition.newInstance(emptyMap<Int, Boolean>(), fixed, fixed),
            )
            owner = work.get(preparedQuery)
            val normalized = next.invoke(preparedQuery)
            val result = normalized ?: next.invoke(preparedQuery)
            val snapshot = stats.invoke(preparedQuery)
            val nativeWork = snapshot.javaClass.getMethod("getWorkSpent").invoke(snapshot) as Long
            if (meter != null) check(charges.values.sum() == nativeWork) { "Native charge attribution is incomplete" }
            return ProbeRecord(
                0,
                observed,
                result?.javaClass?.simpleName ?: "PendingAfterProbe",
                snapshot.toString(),
                nativeWork,
                stops.toList(),
                if (coupledChargesOnly) charges.toList().sortedByDescending { it.second }.toMap()
                else charges.toMap(),
                input.shape,
            )
        }

        private fun input(text: String, mode: String): ProbeInput {
            require(mode in setOf("direct", "shared"))
            if (mode == "direct") {
                val source = parse.invoke(parser.getField("INSTANCE").get(null), text,
                    Cancellation.Never, false, false)
                return ProbeInput(formula.invoke(source) as PolynomialRealFormula,
                    ProbeShape(mode, source.javaClass.simpleName, "direct-query",
                        realCount.invoke(source) as Int, boolCount.invoke(source) as Int))
            }
            val source = SmtLib.parse(text)
            val invocation = SourceSetup.prepare(SourceSetupRequest(source.source,
                ExecutionOperation.SATISFY, FiniteEngine.MIXED))
            val polynomial = (invocation.route as? SourceProblemRoute.PolynomialTheory)?.request?.problem
            val type = PolynomialRealProblem::class.java
            fun field(name: String): Any? = polynomial?.let {
                type.getDeclaredField(name).apply { isAccessible = true }.get(it)
            }
            return ProbeInput(field("formula") as? PolynomialRealFormula,
                ProbeShape(mode, source.source.originalSource.javaClass.simpleName,
                    invocation.route.javaClass.simpleName, field("totalRealCount") as? Int,
                    field("totalBoolCount") as? Int))
        }
    }
}

private class ProbeInput(val formula: PolynomialRealFormula?, val shape: ProbeShape)

@Serializable
private data class ProbeCatalogSource(val suite: String, val problem: String, val sha256: String)

@Serializable
private data class ProbeSource(val id: String, val base64: String, val sha256: String)

@Serializable
internal data class ProbeRecord(
    val round: Int,
    val observed: Boolean,
    val result: String,
    val stats: String,
    val nativeWork: Long,
    val stops: List<ProbeStop>,
    val charges: Map<String, Long>,
    val sourceShape: ProbeShape? = null,
)

@Serializable
internal data class ProbeShape(
    val mode: String,
    val representation: String,
    val route: String,
    val realCount: Int?,
    val boolCount: Int?,
)

@Serializable
internal data class ProbeStop(val workSpent: Long, val coupledEnd: Long, val probeEnd: Long?, val stack: List<String>)

@Serializable
internal data class RequiredInspection(
    val status: String,
    val stage: String,
    val analysisWork: Long,
    val sourceShape: ProbeShape?,
    val mandatoryAtoms: Int?,
    val mandatoryEqualities: Int?,
    val initialExplicitGraphVariable: Int?,
    val initialExplicitGraphReplacementTerms: Int?,
    val totalGraphSteps: Int,
    val eliminatedCount: Int?,
    val remainingAtoms: Int?,
    val remainingReferencedVariables: Int?,
    val inconsistent: Boolean?,
    val resource: String?,
    val scope: String = "unconditioned-mandatory-conjunction-only",
    val coordinateFixingsApplied: Boolean = false,
    val ordinaryRecoveryCredit: Boolean = false,
    val sourceAcceptanceCredit: Boolean = false,
)
