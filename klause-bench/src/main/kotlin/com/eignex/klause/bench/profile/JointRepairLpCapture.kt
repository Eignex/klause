package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toLinearSourceProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.ExecutionOptions
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceExecutionPolicy
import com.eignex.klause.solver.pipeline.SourceExecutionRequest
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.IdentityHashMap
import kotlin.time.Duration.Companion.milliseconds

internal object JointRepairLpCapture {
    private const val PIPELINE = "com.eignex.klause.solver.pipeline."

    fun inspect(options: Map<String, String>, bytes: ByteArray, out: File) {
        val timeout = options["timeout-ms"]?.toLong() ?: 30_000L
        val limit = options["captures"]?.toInt() ?: 4
        require(timeout in 1L..30_000L && limit in 1..4)
        require(bytes.size <= 1_048_576 && sha256(bytes) == options.getValue("sha256"))
        out.mkdirs()
        out.resolve("source.mps").writeBytes(bytes)
        val stop = ExecutionControl.after(timeout.milliseconds)
        val source = requireNotNull(Mps.parse(bytes.decodeToString()).toLinearSourceProblem(cancellation = stop))
        val seed = options.getValue("seed").toLong()
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED,
            maximize = source.objective?.sense == ObjectiveSense.MAXIMIZE, cancellation = stop))
        val regionalPins = options["regional-pins"]
        val regionalCenter = options["regional-binary-center"]
        val presolve = options["presolve"]?.let(PresolveConfig::parse)
        val executionPolicy = SourceExecutionPolicy(explicitPresolve = presolve != null)
        val prepared = SourceSetup.execute(SourceExecutionRequest(invocation, executionPolicy,
            ExecutionOptions.parse(FinitePortfolioLpCapture.parameters(options)),
            finiteRequest = { shape ->
                executionPolicy.finiteRequest(shape, FiniteEngine.MIXED,
                    presolveConfig = presolve ?: PresolveConfig.DEFAULT,
                    cancellation = stop, randomSeed = seed, prepareOnly = true)
            },
            randomSeed = seed, timeout = stop, cancellation = stop,
            presolveConfig = presolve, presolveCancellation = stop))
        val preparation = (prepared as? SourceExecutionResult.Finite)?.result?.preparation
            ?: error("joint capture requires finite preparation")
        val model = preparation.problem
        val objective = requireNotNull(preparation.objective)
        require(model.numBoolVars == 0 && model.numIntVars > 0 && model.numRealVars > 0)
        require(model.numIntVars + model.numRealVars <= 4096 && model.numFactors <= 16384 && !stop())
        val companionType = Class.forName(PIPELINE + "FiniteNumericalCompanions")
        val companion = companionType.getField("Companion").get(null)
        val original = requireNotNull(call(invocation, "getLinearSource"))
        val mapping = call(preparation, "getSourceMapping")
        val authorityKind = options["capture-authority"] ?: "nominal"
        val nominalPinCompletion = regionalPins != null && authorityKind == "nominal"
        val compatibleAuthority = if (authorityKind == "accepted" || nominalPinCompletion) {
            requireNotNull(call(original, "getToleranceAuthority")).also {
                require(call(it, "compatible", mapping, model, objective, stop) == true)
            }
        } else null
        val toleranceAuthority = compatibleAuthority.takeIf { authorityKind == "accepted" }
        val captureContext = companion.javaClass.declaredMethods.single { it.name == "captureContext" }
            .apply { isAccessible = true }
        val context = requireNotNull(captureContext.invoke(companion, model, objective, stop, toleranceAuthority, null))
            as SourceProofContext
        val nativeChecker = Class.forName(PIPELINE + "FiniteIncumbentCompletionKt").methods.single {
            it.name == "incumbentCompletionCheck" && it.parameterTypes.first().simpleName == "PreparedLinearSource"
        }.invoke(null, original, mapping)
        val rejection = if (options["regional-explain-rejection"] == "true") {
            RegionalPublicationRejection(original, mapping, source.objective?.sense == ObjectiveSense.MAXIMIZE)
        } else null
        val checker = rejection?.observe(nativeChecker) ?: nativeChecker
        val activeLimitMs = if (nominalPinCompletion) 2_000L else 4_000L
        val active = stop or ExecutionControl.after(activeLimitMs.milliseconds)
        val projection = regionalCenter?.let {
            require(model.numIntVars in 1..256 && (0 until model.numIntVars).all { id ->
                val bounds = model.intBounds
                bounds.hasLower(id) && bounds.hasUpper(id) && bounds.lower(id) == 0L && bounds.upper(id) == 1L
            })
            BinaryDistanceRegion.project(context, it, options.getValue("regional-existing-free"),
                active, sha256(bytes), out)
        }
        val pins = projection?.pins ?: regionalPins
        options["regional-expected-pins-sha256"]?.let {
            require(pins != null && sha256(pins.encodeToByteArray()) == it)
        }
        if (projection != null && pins == null) return
        if (nominalPinCompletion) require(requireNotNull(pins).count { it == ',' } == model.numIntVars - 1)
        val cutPolicy = options["capture-cut-policy"] ?: "ordinary"
        val workLimit = if (nominalPinCompletion) 20_000_000L else 500_000_000L
        val allocationLimit = if (nominalPinCompletion) 64L * 1024 * 1024 else 256L * 1024 * 1024
        val params = TheoryParams(timeout = active, randomSeed = seed + 9,
            sourceLpWorkLimit = workLimit - (projection?.work ?: 0L),
            sourceLpAllocationLimit = allocationLimit - (projection?.allocation ?: 0L),
            sourceIncumbentCompletion = pins == null,
            sourcePointCuts = cutPolicy == "selected", sourceAggregateCuts = cutPolicy == "selected")
        val policy = Class.forName("com.eignex.klause.lp.SparseSourceLpPolicy").getConstructor().newInstance()
        val owner = Class.forName(PIPELINE + "JointLinearRepair").constructors.single { it.parameterCount == 17 }
            .newInstance(model, context, objective, params, policy, true, { null }, active, null,
                null, null, true, null, checker, null, null, null)
        if (pins != null) {
            val consultations = options["regional-consultations"]?.toInt() ?: 64
            replayRegion(owner as AutoCloseable, pins, model.numIntVars, context, active, consultations,
                sha256(bytes), out, objective, activeLimitMs, params)
            rejection?.inspect(sha256(bytes), sha256(pins.encodeToByteArray()), stop, out)
            return
        }
        val window = ConditionWindow(limit)
        val authority = requireNotNull(field(owner, "authority"))
        val budget = requireNotNull(field(owner, "budget"))
        val defaults = requireNotNull(call(budget, "getContext"))
        val scopes = HashMap<SourceScope, Int>()
        val states = HashMap<Int, String>()
        val owners = IdentityHashMap<Any, Int>()
        var observerNs = 0L
        val observe: (Any) -> Unit = { query ->
            val lp = field(authority, "lp")
            val session = lp?.let { field(it, "session") }
            val currentScope = session?.let { field(it, "query") }?.let { field(it, "scope") } as SourceScope?
            val selected = currentScope.takeIf { field(owner, "pending") != null }
            val state = session?.let { stateIdentity(it, owners) }
            window.observe(selected, state?.toString().orEmpty()) { id ->
                val started = System.nanoTime()
                val invocationContext = field(requireNotNull(field(owner, "query")), "context") as SourceProofContext
                scopes[requireNotNull(currentScope)] = id
                states[id] = requireNotNull(state).toString()
                val payload = FinitePortfolioLpCapture.capture(query, stop)
                payload?.let { out.resolve("conditional-$id.klexact").writeBytes(it) }
                val basis = FinitePortfolioLpCapture.writeBasis(query,
                    out.resolve("conditional-$id-basis.json"))
                out.resolve("conditional-$id.json").writeText(buildJsonObject {
                    put("condition", NumericalResidualProfile.json(field(owner, "pending")))
                    put("scope", NumericalFrontierSnapshot.scope(requireNotNull(currentScope)))
                    put("conditionScope", NumericalFrontierSnapshot.scope(invocationContext.scope))
                    put("stateIdentity", state)
                    put("route", call(query, "getRoute").toString())
                    put("sourceSha256", sha256(bytes))
                    put("captureSha256", payload?.let(::sha256))
                    put("basisSha256", basis)
                    put("captureDeclined", payload == null)
                    put("sourceVerdictClaim", false)
                }.toString())
                observerNs += System.nanoTime() - started
            }
        }
        val observing = observingContext(defaults, observe)
        budget.javaClass.getDeclaredField("solveContext").apply { isAccessible = true }.set(budget, { observing })
        val snapshots = NumericalSelectionSnapshot(stop)
        val journal = snapshots.journal()
        authority.javaClass.getDeclaredMethod("setCapture", journal.javaClass).invoke(authority, journal)
        val search = requireNotNull(call(owner, "search", false, seed + 9, false))
        val seen = IdentityHashMap<Any, Boolean>()
        var missingSide = false
        var unresolvedMissingSide = false
        var rejectionCondition: Int? = null
        var rejectionRayCapture: String? = null
        try {
            while (!active() && call(owner, "getExhausted") != true && !unresolvedMissingSide) {
                val offer: (Sample, Any?) -> Unit = { _, _ -> }
                call(search, "advance", active, 1L, offer)
                val lp = field(authority, "lp")
                val session = lp?.let { field(it, "session") }
                val guide = session?.let { field(it, "completed") }
                if (guide != null && seen.put(guide, true) == null) {
                    val metrics = call(guide, "getCertificateMetrics")
                    val rejection = metrics?.let { call(it, "getDualRejection") }
                    val phase = call(guide, "getPhase").toString()
                    val termination = call(guide, "getTermination").toString()
                    val guidanceClaim = if (termination == "INFEASIBLE_CANDIDATE") "CONTRADICTION" else "BOUND"
                    val raySuccesses = (metrics?.let { call(it, "getRaySuccesses") } as? Number)?.toLong() ?: 0L
                    val checkedRay = phase == "COMPLETE" && termination == "INFEASIBLE_CANDIDATE" && raySuccesses > 0
                    val scope = field(session, "query")?.let { field(it, "scope") } as SourceScope?
                    val id = scopes[scope]
                    if (id != null) {
                        val state = stateIdentity(session, owners)
                        val matched = states[id] == state.toString()
                        val started = System.nanoTime()
                        val rayCapture = if (matched && !active() &&
                            call(guide, "getTermination").toString() == "INFEASIBLE_CANDIDATE") {
                            captureRay(session, id, requireNotNull(scope), state, out)
                        } else null
                        observerNs += System.nanoTime() - started
                        out.resolve("guidance.jsonl").appendText(buildJsonObject {
                            put("phase", phase)
                            put("termination", termination)
                            put("guidanceClaim", guidanceClaim)
                            put("primalSize", (call(guide, "getPrimal") as DoubleArray?)?.size)
                            put("certificateMetrics", NumericalResidualProfile.json(metrics))
                            put("scope", NumericalFrontierSnapshot.scope(requireNotNull(scope)))
                            put("conditionId", id)
                            put("stateIdentity", state)
                            put("captureStateMatched", matched)
                            put("rayCaptureSha256", rayCapture)
                            rejection?.let { missing ->
                                put("dualRejection", buildJsonObject {
                                    put("column", call(missing, "getColumn") as Int)
                                    put("upper", call(missing, "getUpper") as Boolean)
                                    put("reduced", call(missing, "getReduced").toString())
                                })
                            }
                            put("missingBoundSide", rejection != null)
                            put("checkedKernelRayAvailable", checkedRay)
                        }.toString() + "\n")
                        val matchedRejection = rejection != null && matched
                        unresolvedMissingSide = matchedRejection && !checkedRay
                        if (matchedRejection) {
                            missingSide = true
                            rejectionCondition = id
                            rejectionRayCapture = rayCapture
                        }
                    }
                }
            }
            out.resolve("source-queries.json").writeText(snapshots.capture(journal).toString())
            out.resolve("result.json").writeText(buildJsonObject {
                put("captureMode", "standalone-joint-before")
                put("captureAuthority", authorityKind)
                put("captureCutPolicy", cutPolicy)
                put("sourceSha256", sha256(bytes))
                put("sourceSeed", seed)
                put("workerSeed", seed + 9)
                put("workerSeedBasis", "ordinary eight finite workers plus leading numerical companion")
                put("preparedIntegers", model.numIntVars)
                put("preparedReals", model.numRealVars)
                put("preparedFactors", model.numFactors)
                put("presolve", NumericalResidualProfile.json(preparation.presolve))
                put("activeLimitMs", 4000)
                put("sourceLpWorkLimit", params.sourceLpWorkLimit)
                put("sourceLpAllocationLimit", params.sourceLpAllocationLimit)
                put("capturedSnapshots", window.size)
                put("captureObserverNs", observerNs)
                put("observerTimeInsideActiveLimit", true)
                put("missingBoundSideObserved", missingSide)
                put("stoppedOnUnresolvedMissingSide", unresolvedMissingSide)
                put("resultRequiresMatchingCaptureState", true)
                put("rejectionCondition", rejectionCondition)
                put("rejectionCaptureAvailable", rejectionCondition?.let {
                    out.resolve("conditional-$it.klexact").isFile
                } == true)
                put("rejectionRayCaptureAvailable", rejectionRayCapture != null)
                put("stats", NumericalResidualProfile.json(call(owner, "getStats")))
                put("sourceVerdictClaim", false)
                put("fullPortfolioReplayQualified", false)
                put("performanceQualified", false)
            }.toString())
        } finally {
            (search as AutoCloseable).close()
            (owner as AutoCloseable).close()
        }
        if (options["inspect-rays"] == "true") JointRayInspection.inspect(out, stop)
    }

    private fun replayRegion(
        owner: AutoCloseable, specification: String, integers: Int, context: SourceProofContext, stop: ExecutionControl,
        consultations: Int, sourceHash: String, out: File, objective: LinearObjective, activeLimitMs: Long,
        params: TheoryParams,
    ) {
        val entries = specification.split(',')
        val pins = entries.associate { entry ->
            val id = entry.substringBefore('=').toInt()
            require(id in 0 until integers)
            id to BigFraction.ofLong(entry.substringAfter('=').toLong())
        }
        require(pins.size == entries.size)
        val condition = Class.forName(PIPELINE + "ExactNeighborhoodCondition")
            .constructors.single { it.parameterCount == 4 }
            .newInstance(emptyMap<Int, Boolean>(), pins, pins, null)
        var attempts = 0
        var disposition = "Paused"
        var reason: String? = null
        var sample: Sample? = null
        val started = System.nanoTime()
        owner.use {
            while (attempts < consultations && disposition == "Paused" && !stop()) {
                val attempt = requireNotNull(call(owner, "attempt", condition, stop))
                val result = requireNotNull(call(attempt, "getResult"))
                disposition = result.javaClass.simpleName
                if (disposition == "Witness") sample = call(result, "getSample") as Sample
                reason = call(attempt, "getReason")?.toString()
                attempts++
            }
        }
        val elapsed = System.nanoTime() - started
        val stats = requireNotNull(call(owner, "getStats")) as SolveStats
        out.resolve("regional-replay.json").writeText(buildJsonObject {
            put("sourceSha256", sourceHash)
            put("pinsSha256", sha256(specification.encodeToByteArray()))
            put("rootScope", NumericalFrontierSnapshot.scope(context.scope))
            put("integerCount", integers)
            put("pinCount", pins.size)
            put("attempts", attempts)
            put("consultationLimit", consultations)
            put("activeLimitMs", activeLimitMs)
            put("sourceLpWorkLimit", params.sourceLpWorkLimit)
            put("sourceLpAllocationLimit", params.sourceLpAllocationLimit)
            put("attemptAndRetirementNs", elapsed)
            put("disposition", disposition)
            put("reason", reason)
            put("witnessWorkingObjective", sample?.let { objective.evaluateExact(it).toString() })
            put("chargedWork", call(owner, "getWork") as Long)
            put("cancelled", stop())
            put("stageCounters", buildJsonObject {
                stats.smt.completeSearchStages.orEmpty().forEach { (name, value) -> put(name, value) }
            })
            put("sourceVerdictClaim", false)
        }.toString())
    }

    private fun captureRay(session: Any, id: Int, scope: SourceScope, state: JsonObject, out: File): String? {
        val target = out.resolve("conditional-$id-ray.json")
        if (target.exists()) return null
        val snapshot = out.resolve("conditional-$id.klexact").takeIf { it.isFile } ?: return null
        val owner = requireNotNull(field(session, "owner"))
        val current = requireNotNull(call(owner, "getState"))
        val solver = field(owner, "solver") ?: return null
        if (call(solver, "getSolvedExactState") !== current) return null
        val model = requireNotNull(call(current, "getModel"))
        val rows = call(model, "getM") as Int
        val columns = call(model, "getN") as Int
        if (columns.toLong() + rows > 8192) return null
        val bits = rayBits(call(solver, "getInfeasibleRay") as DoubleArray?, rows) ?: return null
        val basis = call(solver, "getInfeasibleBasis")?.takeIf {
            (call(it, "getBasicVars") as IntArray).size == rows &&
                (call(it, "getStatus") as Array<*>).size == columns + rows
        }
        val payload = buildJsonObject {
            put("conditionId", id)
            put("scope", NumericalFrontierSnapshot.scope(scope))
            put("stateIdentity", state)
            put("solvedStateMatched", true)
            put("snapshotSha256", sha256(snapshot.readBytes()))
            put("candidateOrientation", "engine-original")
            put("rayEncoding", "ieee754-binary64-hex")
            put("rayBits", JsonArray(bits.map(::JsonPrimitive)))
            put("infeasibleBasis", FinitePortfolioLpCapture.basisMetadata(basis) ?: JsonNull)
            put("infeasibleRow", call(solver, "getInfeasibleRow") as Int)
            put("sourceVerdictClaim", false)
            put("certificateClaim", false)
        }.toString().encodeToByteArray()
        if (payload.size > 1_048_576) return null
        target.writeBytes(payload)
        return sha256(payload)
    }

    internal fun rayBits(ray: DoubleArray?, rows: Int): List<String>? {
        if (rows !in 1..4096 || ray == null || ray.size != rows || ray.any { !it.isFinite() }) return null
        return ray.map { java.lang.Long.toHexString(it.toRawBits()).padStart(16, '0') }
    }

    internal class ConditionWindow(private val limit: Int) {
        init {
            require(limit in 1..4)
        }
        private val conditions = IdentityHashMap<Any, String>()
        var size: Int = 0
            private set

        fun observe(condition: Any?, version: String = "", capture: (Int) -> Unit) {
            if (condition == null || conditions[condition] == version || size >= limit) return
            val id = size++
            conditions[condition] = version
            capture(id)
        }
    }

    private fun stateIdentity(session: Any, owners: IdentityHashMap<Any, Int>) = buildJsonObject {
        val owner = requireNotNull(field(session, "owner"))
        val state = requireNotNull(call(owner, "getState"))
        val id = owners[owner] ?: owners.size.also { owners[owner] = it }
        put("owner", id)
        for (part in listOf("Matrix", "Bound", "Objective", "Pop", "Row")) {
            put(part.lowercase() + "Revision", call(state, "get${part}Revision") as Long)
        }
        val model = requireNotNull(call(state, "getModel"))
        put("rows", call(model, "getM") as Int)
        put("columns", call(model, "getN") as Int)
    }

    @Suppress("SpreadOperator") // Java reflection requires varargs at the copy boundary.
    internal fun observingContext(context: Any, observe: (Any) -> Unit): Any {
        val copy = context.javaClass.methods.single { it.name == "copy" }
        val observerComponent = 7
        val arguments = Array(copy.parameterCount) { index ->
            if (index + 1 == observerComponent) observe else call(context, "component${index + 1}")
        }
        return requireNotNull(copy.invoke(context, *arguments))
    }

    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun call(owner: Any, name: String, vararg args: Any?): Any? =
        NumericalResidualProfile.call(owner, name, *args)
}
