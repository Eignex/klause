package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toLinearSourceProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.lp.optimization.LinearPinnedObjective
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.LinearTraversal
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.util.Cancellation
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.reflect.Proxy
import java.util.Base64
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object MpsSparseRoot {
    private const val LP = "com.eignex.klause.lp."
    private const val ENGINE = LP + "engine."
    private const val REFACTOR_UPDATES = 50
    private val defaultPolicy = Class.forName(LP + "SparseSourceLpPolicy").getConstructor().newInstance()
    private data class Variant(val layout: String, val phaseOne: String, val values: String, val start: String)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 1..9 && args[0].startsWith("batch64="))
        val options = args.drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys.all {
            it in setOf("layout", "phase1", "values", "start", "work", "iterations", "entry", "funding")
        })
        val entry = options["entry"] ?: "engine"
        require(entry in setOf("engine", "session", "bind"))
        require(entry == "engine" || options.keys.none { it in setOf("phase1", "values", "start") })
        val funding = options["funding"]?.toLong()
        require(funding == null || (entry == "session" && funding > 0L))
        require(("work" in options) == ("iterations" in options))
        val work = options["work"]?.toLong() ?: 150_000_000L
        val iterations = options["iterations"]?.toInt() ?: 1024
        require(work > 0L && iterations > 0)
        val floating = Class.forName(ENGINE + "LpFloatAllowance").constructors.single { it.parameterCount == 2 }
            .newInstance(work, iterations)
        val layouts = when (options["layout"]) {
            null, "identity" -> listOf("IDENTITY")
            "equalities" -> listOf("UNCONDITIONAL_EQUALITIES")
            "compare" -> listOf("IDENTITY", "UNCONDITIONAL_EQUALITIES")
            else -> error("unsupported sparse root layout")
        }
        val phaseOnePolicies = when (options["phase1"]) {
            null, "long-step" -> listOf("LONG_STEP")
            "first-bound" -> listOf("FIRST_BOUND")
            "compare" -> listOf("FIRST_BOUND", "LONG_STEP")
            else -> error("unsupported phase one policy")
        }
        val valuePolicies = when (options["values"]) {
            null, "incremental" -> listOf("INCREMENTAL")
            "recompute" -> listOf("RECOMPUTE")
            "compare" -> listOf("RECOMPUTE", "INCREMENTAL")
            else -> error("unsupported primal value policy")
        }
        val startPolicies = when (options["start"]) {
            null, "singleton" -> listOf("FREE_SINGLETON")
            "logical" -> listOf("LOGICAL")
            "compare" -> listOf("LOGICAL", "FREE_SINGLETON")
            else -> error("unsupported numerical opening")
        }
        val variants = layouts.flatMap { layout ->
            phaseOnePolicies.flatMap { phaseOne ->
                valuePolicies.flatMap { values ->
                    startPolicies.map { start -> Variant(layout, phaseOne, values, start) }
                }
            }
        }
        val requests = Json.decodeFromString<List<List<String>>>(
            Base64.getDecoder().decode(args[0].substringAfter('=')).decodeToString())
        require(requests.size in 1..16)
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("build-provenance.json").path, InstalledBuild.reference)
        val rounds = if (variants.size == 1) listOf(1) else listOf(0, 1)
        for (round in rounds) {
            requests.forEachIndexed { index, request ->
                for (variant in if (round == 0) variants else variants.reversed()) {
                    val path = "$index/$round/${variant.layout}/${variant.phaseOne}/${variant.values}/${variant.start}"
                    inspect(request, out.resolve(path).apply { mkdirs() }, round, variant, floating, entry, funding)
                }
            }
        }
    }

    private fun inspect(
        request: List<String>,
        out: File,
        round: Int,
        variant: Variant,
        floating: Any,
        entry: String,
        funding: Long?,
    ) {
        val options = request.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys == setOf("suite", "problem", "sha256"))
        val selected = Catalog.uncapped(options.getValue("suite")).problems
            .single { it.name == options.getValue("problem") }
        val bytes = CorpusFiles.open(CorpusFetcher.resolve(selected.source)).use { it.readNBytes(16 * 1024 * 1024 + 1) }
        require(bytes.size <= 16 * 1024 * 1024 && sha256(bytes) == options.getValue("sha256"))
        out.resolve("source.mps").writeBytes(bytes)
        val stop = Cancellation.after(30.seconds)
        val source = checkNotNull(Mps.parse(bytes.decodeToString()).toLinearSourceProblem(cancellation = stop))
        val plan = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.FIXED,
            maximize = source.objective?.sense == ObjectiveSense.MAXIMIZE, cancellation = stop,
            linearTraversal = LinearTraversal.NUMERICAL))
        val execution = checkNotNull(NumericalFrontierSnapshot.field(plan.route, "execution"))
        val numerical = checkNotNull(NumericalFrontierSnapshot.field(execution, "request"))
        val context = numerical.javaClass.declaredMethods.single {
            it.name == "authorityContext" && it.parameterCount == 2
        }.apply { isAccessible = true }.invoke(numerical, checkNotNull(call(numerical, "getObjective")), stop)
            as SourceProofContext
        val linear = checkNotNull(NumericalFrontierSnapshot.field(plan, "linearSource"))
        val view = checkNotNull(NumericalFrontierSnapshot.field(linear, "view"))
        val objectiveScale = NumericalFrontierSnapshot.field(view, "objectiveScale") as BigFraction
        require(context.coordinates.none { it.kind == SourceCoordinate.Kind.Boolean })
        require(context.premises.values.all { it.guards.isEmpty() && it.integerPins.isEmpty() })
        val coordinates = context.coordinates.sortedWith(compareBy({ it.kind.ordinal }, { it.id }))
        val coordinateIndex = coordinates.withIndex().associate { it.value to it.index }
        val rowType = Class.forName("com.eignex.klause.simplex.exact.ExactRationalInequality")
        val rowConstructor = rowType.constructors.single { it.parameterCount == 4 }
        val rows = context.premises.values.map { premise ->
            val row = premise.inequality
            val ids = row.coefficients.keys.map { coordinateIndex.getValue(it) }.sorted().toIntArray()
            rowConstructor.newInstance(ids, ids.map { row.coefficients.getValue(coordinates[it]) }, row.rhs, row.strict)
        }
        val sign = if (context.scope.objective?.sense == ObjectiveSense.MAXIMIZE) BigFraction.MINUS_ONE
            else BigFraction.ONE
        val costs = coordinates.map { (context.objective[it] ?: BigFraction.ZERO) * sign }
        val modelType = Class.forName(LP + "optimization.LinearAuthorityModel")
        val companion = modelType.getField("Companion").get(null)
        val prepare = companion.javaClass.methods.single { it.name == "prepare" }
        val packed = checkNotNull(when (prepare.parameterCount) {
            3 -> prepare.invoke(companion, rows, costs.size, stop)
            4 -> prepare.invoke(companion, rows, costs.size, stop, emptySet<Int>())
            else -> error("unsupported source preparation signature")
        })
        val workingRows = call(packed, "getRows") as List<*>
        val columns = call(packed, "getColumns") as List<*>
        val sparseType = Class.forName(LP + "SparseSourceLp")
        val rowPolicy = Class.forName(LP + "SparseSourceLpRowPolicy").enumConstants.single {
            it.toString() == variant.layout
        }
        val policy = defaultPolicy.javaClass.constructors.single { it.parameterCount == 5 }.newInstance(
            call(defaultPolicy, "getPreparation"), floating,
            call(defaultPolicy, "getQuantum"), call(defaultPolicy, "getCertificate"), rowPolicy,
        )
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        if (entry != "engine") {
            retained(packed, context, costs, sign, policy, out, bean, thread, entry, funding, bytes)
            return
        }
        val started = System.nanoTime()
        val allocated = bean.getThreadAllocatedBytes(thread)
        val preparation = checkNotNull(call(sparseType.getField("Companion").get(null), "prepare",
            workingRows, columns, costs, context.objectiveConstant * sign, policy, stop))
        val prepareNs = System.nanoTime() - started
        val prepareBytes = bean.getThreadAllocatedBytes(thread) - allocated
        val prepared = call(preparation, "getPrepared")
        val report = buildJsonObject {
            put("revision", Reports.readGitSha())
            put("problem", options.getValue("problem"))
            put("round", round)
            put("phaseOnePolicy", variant.phaseOne)
            put("primalValuePolicy", variant.values)
            put("startPolicy", variant.start)
            put("warmup", round == 0)
            put("sourceSha256", sha256(bytes))
            put("sourceSemantics", NumericalResidualProfile.json(source.semantics.declared))
            put("effectiveSourceSemantics", NumericalResidualProfile.json(source.semantics.effective))
            put("sourceTolerance", NumericalResidualProfile.json(source.semantics.effective.feasibilityTolerance))
            put("nativeSourceScope", NumericalFrontierSnapshot.scope(context.scope))
            put("objectiveScale", objectiveScale.toString())
            put("inputRows", rows.size)
            put("workingRows", workingRows.size)
            put("emittedRows", prepared?.let { call(it, "getEmittedRowCount") } as Int?)
            put("columns", columns.size)
            put("preparation", NumericalResidualProfile.json(call(preparation, "getMetrics")))
            put("preparationDecline", call(preparation, "getDecline")?.toString())
            put("prepareNs", prepareNs)
            put("prepareThreadBytes", prepareBytes)
            put("policy", NumericalResidualProfile.json(policy))
            put("refactorUpdates", REFACTOR_UPDATES)
            put("pricingSeed", 3)
            put("sourceVerdictClaim", false)
            put("independentWitnessCheck", false)
            put("javaVersion", System.getProperty("java.version"))
            put("os", System.getProperty("os.name"))
            put("arch", System.getProperty("os.arch"))
            put("processors", Runtime.getRuntime().availableProcessors())
            put("jvmMaxHeapBytes", Runtime.getRuntime().maxMemory())
        }
        out.resolve("preparation.json").writeText(report.toString() + "\n")
        println(report)
        if (prepared != null && !stop()) {
            run(checkNotNull(call(prepared, "getModel")), out, bean, thread, policy, variant) { guide ->
                val bound = call(guide, "getBound") ?: return@run buildJsonObject { put("available", false) }
                val startedLift = System.nanoTime()
                val beforeLift = bean.getThreadAllocatedBytes(thread)
                val check = checkBound(bound, prepared, packed, context, costs, sign)
                buildJsonObject {
                    put("available", true)
                    put("check", check)
                    val value = call(bound, "getValue") as BigFraction
                    put("originalDirectionNativeBound", (value * sign).toString())
                    put("nominalSourceUnitBound", (value * sign * objectiveScale.reciprocal()).toString())
                    put("elapsedNs", System.nanoTime() - startedLift)
                    put("threadBytes", bean.getThreadAllocatedBytes(thread) - beforeLift)
                }
            }
        }
    }

    private fun retained(
        packed: Any,
        context: SourceProofContext,
        costs: List<BigFraction>,
        sign: BigFraction,
        policy: Any,
        out: File,
        bean: ThreadMXBean,
        thread: Long,
        entry: String,
        totalFunding: Long?,
        source: ByteArray,
    ) {
        val stop = Cancellation.after(30.seconds)
        val budget = Class.forName(LP + "SourceLpBudget").getConstructor().newInstance()
        val funding = totalFunding?.let { work ->
            Class.forName(LP + "SourceLpFunding").constructors.single { it.parameterCount == 2 }
                .newInstance(work, 512L * 1024L * 1024L).also { ledger ->
                    val field = budget.javaClass.getDeclaredField("funding")
                    field.isAccessible = true
                    field.set(budget, ledger)
                }
        }
        val session = Class.forName(LP + "optimization.LinearAuthoritySession").constructors
            .single { it.parameterCount == 2 }.newInstance(budget, { _: String, _: Long -> })
        val observerType = Class.forName(ENGINE + "LpCertificationObserver")
        val observer = Proxy.newProxyInstance(observerType.classLoader, arrayOf(observerType)) { _, _, _ -> null }
        val columns = call(packed, "getColumns") as List<*>
        val rows = call(packed, "getRows") as List<*>
        val free = columns.map { column ->
            val value = checkNotNull(column)
            call(value, "copy", Class.forName(ENGINE + "ExactLpBounds").getConstructor().newInstance(),
                call(value, "getOrigin"), call(value, "getIntegral"), call(value, "getTag"))
        }
        val sparseType = Class.forName(LP + "SparseSourceLp")
        val model = if (entry == "bind") {
            val preparation = checkNotNull(call(sparseType.getField("Companion").get(null), "prepare", rows, free,
                costs, context.objectiveConstant * sign, policy, stop))
            call(checkNotNull(call(preparation, "getPrepared")), "getModel")
        } else null
        val coordinates = context.coordinates.sortedWith(compareBy({ it.kind.ordinal }, { it.id }))
        val restricted = columns.indices.firstOrNull {
            call(checkNotNull(call(checkNotNull(columns[it]), "getBounds")), "getLower") != null
        }
        val alternate = restricted?.let { index ->
            val bounds = checkNotNull(call(checkNotNull(columns[index]), "getBounds"))
            val side = checkNotNull(call(bounds, "getLower"))
            val number = checkNotNull(call(side, "getNumber"))
            context.scope.copy(condition = SourceCondition(lower = mapOf(coordinates[index] to
                (call(number, "getValue") as BigFraction))))
        }
        require(entry != "bind" || alternate != null)
        out.resolve("request.json").writeText(buildJsonObject {
            put("revision", Reports.readGitSha())
            put("sourceSha256", sha256(source))
            put("scope", NumericalFrontierSnapshot.scope(context.scope))
            put("entry", entry)
            put("funding", totalFunding)
            put("policy", NumericalResidualProfile.json(policy))
            put("columns", columns.size)
            put("rows", rows.size)
            put("sourceVerdictClaim", false)
            put("independentWitnessCheck", false)
            put("javaVersion", System.getProperty("java.version"))
            put("os", System.getProperty("os.name"))
            put("arch", System.getProperty("os.arch"))
        }.toString() + "\n")
        try {
            out.resolve("retained.jsonl").bufferedWriter().use { output ->
                var index = 0
                var paused: Boolean
                do {
                    funding?.let { call(it, "grant", 20_000_000L) }
                    val started = System.nanoTime()
                    val allocated = bean.getThreadAllocatedBytes(thread)
                    var sourcePrepared: Any? = null
                    val guide = if (entry == "bind") {
                        checkNotNull(call(session, "bind", packed, call(checkNotNull(model), "getObjective"), stop,
                            { model }, if (index % 2 == 0) context.scope else alternate, call(policy, "getFloating")))
                        null
                    } else {
                        val consume: (Any, Any, Cancellation) -> Any = { guidance, prepared, _ ->
                            sourcePrepared = prepared
                            guidance
                        }
                        call(session, "advance", packed, costs, context.objectiveConstant * sign, context.scope,
                            stop, policy, observer, consume)
                    }
                    val elapsed = System.nanoTime() - started
                    val allocation = bean.getThreadAllocatedBytes(thread) - allocated
                    val bound = guide?.let { call(it, "getBound") }
                    val sourceBound = buildJsonObject {
                        put("available", bound != null)
                        if (bound != null) {
                            val liftStarted = System.nanoTime()
                            val liftBytes = bean.getThreadAllocatedBytes(thread)
                            put("check", checkBound(bound, checkNotNull(sourcePrepared), packed, context, costs, sign))
                            val value = call(bound, "getValue") as BigFraction
                            put("originalDirectionNativeBound", (value * sign).toString())
                            put("approximateOriginalDirectionNativeBound",
                                (value * sign).toDouble().takeIf(Double::isFinite))
                            put("elapsedNs", System.nanoTime() - liftStarted)
                            put("threadBytes", bean.getThreadAllocatedBytes(thread) - liftBytes)
                        }
                    }
                    paused = guide?.let { call(it, "getPaused") == true } ?:
                        (funding?.let { call(it, "getPaused") == true && call(it, "getExhausted") != true } ?: false)
                    val owner = NumericalFrontierSnapshot.field(session, "owner")
                    val state = owner?.let { call(it, "getState") }
                    val assertions = state?.let { call(it, "getAssertions") } as List<*>?
                    if (entry == "bind") {
                        val expected = columns.flatMapIndexed { column, description ->
                            val bounds = checkNotNull(call(checkNotNull(description), "getBounds"))
                            listOf(false to "getLower", true to "getUpper").mapNotNull { (upper, getter) ->
                                call(bounds, getter)?.let { Triple(column, upper, it) }
                            }
                        }
                        check(assertions?.size == expected.size)
                        expected.forEachIndexed { witness, (column, upper, side) ->
                            val actual = checkNotNull(assertions[witness])
                            check(call(actual, "getColumn") == column && call(actual, "getUpper") == upper &&
                                call(actual, "getSide") == side && call(actual, "getWitness") == witness.toLong() &&
                                call(actual, "getDepth") == 1)
                        }
                    }
                    val report = buildJsonObject {
                        put("index", index++)
                        put("elapsedNs", elapsed)
                        put("threadBytes", allocation)
                        put("paused", paused)
                        put("termination", guide?.let { call(it, "getTermination") }?.toString())
                        put("phase", guide?.let { call(it, "getPhase") }?.toString())
                        put("heuristicCoordinates", (guide?.let { call(it, "getPrimal") } as DoubleArray?)?.size ?: 0)
                        put("checkedNativeBound", bound?.let { call(it, "getValue").toString() })
                        put("nativeSourceBound", sourceBound)
                        put("certificate", NumericalResidualProfile.json(guide?.let {
                            call(it, "getCertificateMetrics")
                        }))
                        put("budget", NumericalResidualProfile.json(budget))
                        put("funding", NumericalResidualProfile.json(funding))
                        put("preparations", call(session, "getPreparations") as Long)
                        put("assertionSha256", assertions?.let {
                            sha256(NumericalResidualProfile.json(it).toString().encodeToByteArray())
                        })
                        put("floatMetrics", NumericalResidualProfile.json(owner?.let { call(it, "getLastMetrics") }))
                        put("ownerMetrics", NumericalResidualProfile.json(owner?.let { call(it, "getMetrics") }))
                    }
                    output.appendLine(report.toString())
                    output.flush()
                    println(report)
                } while (!stop() && index < 256 && (paused || (entry == "bind" && index < 32)))
            }
        } finally {
            (session as AutoCloseable).close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun checkBound(
        bound: Any,
        prepared: Any,
        packed: Any,
        context: SourceProofContext,
        costs: List<BigFraction>,
        sign: BigFraction,
    ): String {
        val stop = Cancellation.after(10.seconds)
        val duals = call(bound, "getDualMultipliers") as List<BigFraction>? ?: return "NO_MULTIPLIERS"
        val weights = call(prepared, "sourceRowWeights", duals.map { it.negated() }, stop) as List<BigFraction>?
            ?: return "ROW_LIFT_DECLINED"
        val original = call(packed, "lift", weights, costs.map { it.negated() }, stop) as List<BigFraction>?
            ?: return "SOURCE_LIFT_DECLINED"
        val ids = context.premises.keys.toList()
        require(original.size == ids.size)
        val derivation = SourceDerivation.Combination(original.mapIndexedNotNull { id, weight ->
            if (weight.isZero) null else SourceDerivation.Combination.Term(weight, SourceDerivation.Premise(ids[id]))
        })
        val value = call(bound, "getValue") as BigFraction
        return SourceEvidenceVerifier(context.withLinearAuthority()).verify(SourceEvidence(context.scope,
            SourceClaim.Bound(value * sign), LinearPinnedObjective(derivation)), stop).toString()
    }

    private fun run(
        model: Any,
        out: File,
        bean: ThreadMXBean,
        thread: Long,
        policy: Any,
        variant: Variant,
        checkBound: (Any) -> JsonObject,
    ) {
        val stop = Cancellation.after(10.seconds)
        val trail = Class.forName(ENGINE + "LpBoundTrail").constructors.single {
            it.parameterCount == 1 && it.parameterTypes.single().isInstance(model)
        }
            .newInstance(model)
        val context = Class.forName(ENGINE + "LpSolveContext").getConstructor().newInstance()
        val pricing = Class.forName(ENGINE + "LpPricingOptions").constructors.single { it.parameterCount == 2 }
            .newInstance(LpZeroObjectivePricing.MIN_BOUND_SUPPORT, 3L)
        val allowance = checkNotNull(call(policy, "getFloating"))
        val owner = Class.forName(ENGINE + "LpScopedSolver").constructors.single { it.parameterCount == 9 }
            .newInstance(call(trail, "getState"), stop, context, REFACTOR_UPDATES, call(allowance, "getIterations"),
                call(allowance, "getWork"), false, Int.MAX_VALUE, pricing)
        val limits = checkNotNull(call(policy, "getCertificate"))
        val advance = Class.forName(ENGINE + "LpRootGuidanceKt").methods.single {
            it.name == "advanceRootGuidance" && it.parameterCount == 7
        }
        try {
            val preparationStarted = System.nanoTime()
            val preparationBytes = bean.getThreadAllocatedBytes(thread)
            val admitted = call(owner, "prepare", stop) == true
            out.resolve("owner-preparation.json").writeText(buildJsonObject {
                put("admitted", admitted)
                put("elapsedNs", System.nanoTime() - preparationStarted)
                put("threadBytes", bean.getThreadAllocatedBytes(thread) - preparationBytes)
                put("ownerMetrics", NumericalResidualProfile.json(call(owner, "getMetrics")))
            }.toString() + "\n")
            if (!admitted) return
            val numericalOwner = checkNotNull(NumericalFrontierSnapshot.field(owner, "solver"))
            numericalOwner.javaClass.getDeclaredField("phaseOneLongSteps").apply { isAccessible = true }
                .set(numericalOwner, variant.phaseOne == "LONG_STEP")
            numericalOwner.javaClass.getDeclaredField("phaseOneIncrementalValues").apply { isAccessible = true }
                .set(numericalOwner, variant.values == "INCREMENTAL")
            numericalOwner.javaClass.getDeclaredField("singletonStart").apply { isAccessible = true }
                .set(numericalOwner, variant.start == "FREE_SINGLETON")
            call(numericalOwner, "enableProgressTrace")
            out.resolve("guidance.jsonl").bufferedWriter().use { output ->
                var iteration = 0
                do {
                    val started = System.nanoTime()
                    val bytes = bean.getThreadAllocatedBytes(thread)
                    val guide = advance.invoke(null, owner, call(policy, "getQuantum"), allowance, limits, stop, null,
                        call(context, "getCertificationPolicy"))
                    val elapsed = System.nanoTime() - started
                    val allocation = bean.getThreadAllocatedBytes(thread) - bytes
                    val point = call(guide, "getPrimal") as DoubleArray?
                    val numerical = NumericalFrontierSnapshot.field(owner, "solver")
                    val observations = numerical?.let { NumericalFrontierSnapshot.field(it, "observations") }
                    point?.let { out.resolve("heuristic-primal.json")
                        .writeText(JsonArray(it.map(::JsonPrimitive)).toString() + "\n") }
                    val report = buildJsonObject {
                        put("iteration", iteration++)
                        put("elapsedNs", elapsed)
                        put("threadBytes", allocation)
                        put("phase", call(guide, "getPhase").toString())
                        put("paused", call(guide, "getPaused") as Boolean)
                        put("termination", call(guide, "getTermination")?.toString())
                        put("optimalCandidate", call(guide, "getOptimalCandidate") as Boolean)
                        put("heuristicCoordinates", point?.size ?: 0)
                        put("checkedNativeBound", call(guide, "getBound")?.let { call(it, "getValue").toString() })
                        put("certificate", NumericalResidualProfile.json(call(guide, "getCertificateMetrics")))
                        put("nativeSourceBound", checkBound(guide))
                        put("maxLuFill", observations?.let { call(it, "getMaxLuFill") } as Double?)
                        put("maxLuDensity", observations?.let { call(it, "getMaxLuDensity") } as Double?)
                        put("numericalProgress", NumericalResidualProfile.json(numerical?.let {
                            call(it, "getLastNumericalMetrics")
                        }))
                        put("simplexProgress", NumericalResidualProfile.json(call(numericalOwner, "getProgressTrace")))
                        put("refactorPolicy", NumericalResidualProfile.json(
                            call(numericalOwner, "getLastRefactorPolicyMetrics")))
                        put("devexWeightCorrections", call(numericalOwner, "getLastDevexWeightCorrections") as Int)
                        put("harrisMinisteps", call(numericalOwner, "getLastHarrisMinistepSelections") as Int)
                        put("floatMetrics", NumericalResidualProfile.json(call(owner, "getLastMetrics")))
                        put("ownerMetrics", NumericalResidualProfile.json(call(owner, "getMetrics")))
                    }
                    output.appendLine(report.toString())
                    output.flush()
                    println(report)
                    check(iteration <= 256)
                } while (call(guide, "getPaused") == true && !stop())
            }
        } finally {
            val started = System.nanoTime()
            val allocated = bean.getThreadAllocatedBytes(thread)
            (owner as AutoCloseable).close()
            out.resolve("close.json").writeText(buildJsonObject {
                put("elapsedNs", System.nanoTime() - started)
                put("threadBytes", bean.getThreadAllocatedBytes(thread) - allocated)
                put("ownerMetrics", NumericalResidualProfile.json(call(owner, "getMetrics")))
            }.toString() + "\n")
        }
    }

    @Suppress("SpreadOperator")
    private fun call(owner: Any, name: String, vararg args: Any?): Any? =
        NumericalResidualProfile.call(owner, name, *args)
}
