package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.formats.smtlib.SmtLibProblem
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.linearRows
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.PlannedSearch
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.result.OpenTheoryWorkSink
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchNodeDisposition
import com.eignex.klause.solver.search.SearchNodePolicy
import com.eignex.klause.util.Cancellation
import java.io.File
import java.lang.reflect.Modifier
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object LinearPrefixInspection {
    fun inspect(
        compiled: SmtLibProblem, out: File, sourceSha256: String, probe: Boolean = false,
        integrated: Boolean = false, cached: Boolean = false, reuse: Boolean = false, batching: Boolean = false,
        incremental: Boolean = false, incrementalControl: Boolean = false,
    ) {
        val emergencySeconds = if (incremental) 30 else 10
        val captures = (0..1).map { replay ->
            val incrementalRows = incremental && !incrementalControl && replay == 1
            val stop = Cancellation.after(emergencySeconds.seconds)
            val started = System.nanoTime()
            val invocation = SourceSetup.prepare(SourceSetupRequest(
                compiled.source, ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK, cancellation = stop,
            ))
            val request = (invocation.route as? SourceProblemRoute.OpenTheory)?.request
                ?: error("prefix capture requires an open theory route")
            val factory = OpenTheoryPipeline.javaClass.declaredMethods.single {
                it.name.startsWith("engineFor") && it.parameterCount == 1
            }
            val engine = requireNotNull(factory.invoke(OpenTheoryPipeline, request))
            val params = TheoryParams(timeout = stop, randomSeed = 1L,
                openWorkLimit = if (incremental) 100_000_000L else Long.MAX_VALUE)
            val stateType = Class.forName("com.eignex.klause.solver.pipeline.OpenTheorySolveState")
            val state = stateType.getDeclaredConstructor(TheoryParams::class.java).newInstance(params)
            val boundary = PrefixBoundary()
            val begin = engine.javaClass.declaredMethods.single {
                it.name.startsWith("begin") && it.parameterCount == 3
            }
            val opening = requireNotNull(begin.invoke(engine, params, state, boundary))
            val run = member(opening, "getRun")
            try {
                val automatic = integrated && (replay == 1 || cached || batching || incremental)
                val cachedProducer = automatic && (((cached || reuse) && replay == 1) || batching || incremental)
                if (run != null) {
                    val planned = field(run, "planned") as PlannedSearch
                    call(requireNotNull(planned.theory), "observePartialLinearCost")
                    if (batching && replay == 0) call(requireNotNull(planned.theory), "useIndividualSourceBounds")
                }
                if (automatic && run != null) {
                    val planned = field(run, "planned") as PlannedSearch
                    call(requireNotNull(planned.theory), "usePartialLinearPropagation")
                    if (cachedProducer) {
                        call(requireNotNull(planned.theory), "reusePartialLinearDeclines")
                    }
                    if (incrementalRows) {
                        call(requireNotNull(planned.theory), "useIncrementalLinearPropagation")
                    }
                }
                val result = run?.let { call(it, "advance") } ?: member(opening, "getResult")
                val elapsed = System.nanoTime() - started
                val ready = run != null && boundary.paused && result == null && !stop()
                val status = buildJsonObject {
                    put("revision", Reports.readGitSha())
                    put("sourceSha256", sourceSha256)
                    put("seed", 1)
                    put("targetNonRootBoundaries", 8)
                    put("visitedNonRootBoundaries", boundary.visits)
                    put("sourceVerdictClaim", false)
                    put("integrated", automatic)
                    put("cached", cachedProducer)
                    put("incrementalRows", incrementalRows)
                    put("openWorkLimit", params.openWorkLimit)
                    put("emergencySeconds", emergencySeconds)
                    put("sourceBoundBatching", !batching || replay == 1)
                    put("cliEquivalence", "unverified-direct-library-diagnostic")
                    put("ready", ready)
                    put("stopped", stop())
                    put("result", result?.javaClass?.simpleName)
                    put("openingAndTraversalNs", elapsed)
                    if (run != null) {
                        val theory = (field(run, "planned") as PlannedSearch).theory
                        put("partialPropagation", LinearPrefixSnapshot(Cancellation.after(10.seconds))
                            .record(theory?.let { member(it, "getPartialLinearPropagationStats") }))
                    }
                }
                out.resolve("prefix-$replay-status.json").writeText("$status\n")
                check(ready) { "prefix did not reach its boundary: $status" }
                val planned = field(requireNotNull(run), "planned") as PlannedSearch
                val capture = LinearPrefixSnapshot(Cancellation.after(10.seconds))
                    .capture(planned, run, compiled, out.resolve("prefix-$replay.exact"))
                val bytes = capture.toString().toByteArray()
                require(bytes.size <= MAX_BYTES) { "prefix snapshot exceeds byte allowance" }
                out.resolve("prefix-$replay.json").writeBytes(bytes)
                if ((probe || integrated || batching || incremental) && !stop()) {
                    val probeStarted = System.nanoTime()
                    val work = member(state, "getWork") as OpenTheoryWorkSink
                    val before = work.snapshot()
                    val deductions = if (probe && replay == 1) call(requireNotNull(planned.theory),
                        "probePartialLinearImplications") else null
                    val propagation = if (probe) planned.session.propagate() else ComponentResult.Consistent
                    val probeElapsed = System.nanoTime() - probeStarted
                    boundary.resume()
                    val continuationStarted = System.nanoTime()
                    val continuation = if (propagation is ComponentResult.Consistent && !stop()) {
                        call(run, "advance")
                    } else null
                    val continuationElapsed = System.nanoTime() - continuationStarted
                    val after = work.snapshot()
                    val observation = buildJsonObject {
                        put("treated", replay == 1)
                        put("integrated", automatic)
                        put("cached", cachedProducer)
                        put("incrementalRows", incrementalRows)
                        put("openWorkLimit", params.openWorkLimit)
                        put("sourceBoundBatching", !batching || replay == 1)
                        put("probeNs", probeElapsed)
                        put("continuationNs", continuationElapsed)
                        put("probe", LinearPrefixSnapshot(Cancellation.after(10.seconds)).record(deductions))
                        put("propagation", propagation.javaClass.simpleName)
                        put("continuation", continuation?.javaClass?.simpleName)
                        put("stopped", stop())
                        put("decisionLevel", planned.session.decisionLevel)
                        put("checksBefore", before.openTheoryChecks)
                        put("checksAfter", after.openTheoryChecks)
                        put("workBefore", before.openWork)
                        put("workAfter", after.openWork)
                        put("booleanDecisionsBefore", before.openBoolDecisions)
                        put("booleanDecisionsAfter", after.openBoolDecisions)
                        put("assignedBooleans", (0 until (field(requireNotNull(planned.theory), "model") as Problem)
                            .numBoolVars).count { planned.session.boolValue(it) != null })
                        put("stopCause", planned.session.executionControls.stopCause?.name)
                        put("partialPropagation", LinearPrefixSnapshot(Cancellation.after(10.seconds))
                            .record(member(requireNotNull(planned.theory), "getPartialLinearPropagationStats")))
                    }
                    out.resolve("probe-$replay.json").writeText("$observation\n")
                    println(observation)
                    if (boundary.paused && continuation == null && !stop() &&
                        planned.session.executionControls.stopCause == null
                    ) {
                        val afterCapture = LinearPrefixSnapshot(Cancellation.after(10.seconds)).capture(
                            planned, run, compiled, out.resolve("after-$replay.exact"),
                        )
                        out.resolve("after-$replay.json").writeText("$afterCapture\n")
                        if ((cached || batching || incremental) && replay == 1) {
                            check(out.resolve("after-0.json").readText() == "$afterCapture\n") {
                                "intervention changed the continued source state"
                            }
                        }
                    }
                }
                bytes
            } finally {
                (run as? AutoCloseable)?.close()
            }
        }
        val report = buildJsonObject {
            put("schema", "partial-linear-replay-v1")
            put("revision", Reports.readGitSha())
            put("sourceSha256", sourceSha256)
            put("snapshotSha256", sha256(captures[0]))
            put("replaySha256", sha256(captures[1]))
            put("experiment", when {
                incrementalControl -> "cached-producer-self-replay"
                incremental -> "cached-producer-versus-incremental-source-rows"
                batching -> "individual-versus-batched-source-bounds"
                cached -> "integrated-versus-cached"
                reuse -> "plain-versus-integrated-cache"
                integrated -> "plain-versus-integrated"
                else -> "exact-prefix-replay"
            })
            put("exactReplay", if (integrated && !cached && !batching && !incremental) JsonNull
                else JsonPrimitive(captures[0].contentEquals(captures[1])))
            put("sourceVerdictClaim", false)
        }
        out.resolve("replay.json").writeText("$report\n")
        println(report)
        if (!integrated || cached || batching || incremental) {
            check(captures[0].contentEquals(captures[1])) { "fresh prefix replay differs" }
        }
    }

    private class PrefixBoundary : SearchNodePolicy {
        var visits = 0
        var paused = false
        private var target = 8
        fun resume() {
            target = visits + 8
            paused = false
        }
        override fun beforeBranch(context: SearchContext): SearchNodeDisposition {
            if (context.decisionLevel > 0) visits++
            if (visits < target) return SearchNodeDisposition.Expand
            paused = true
            return SearchNodeDisposition.Pause
        }
    }

    private const val MAX_BYTES = 64 * 1024 * 1024
}

internal class LinearPrefixSnapshot(private val stop: Cancellation) {
    private var remaining = 1_000_000
    private val premiseIds = LinkedHashMap<SearchAtomPremise, Int>()
    private val premises = ArrayList<SearchAtomPremise>()

    fun record(value: Any?): JsonElement = scalar(value)

    fun capture(planned: PlannedSearch, run: Any, compiled: SmtLibProblem, exactFile: File): JsonObject {
        val session = planned.session
        val theory = requireNotNull(planned.theory)
        require(theory.javaClass.simpleName == "ExactLiraSearchComponent")
        require(session.decisionLevel > 0 && session.executionControls.stopCause == null)
        require(planned.cp?.session?.fixpointCancelled != true)
        val model = field(theory, "model") as Problem
        require(model.numRealVars == 0 && model.numBoolVars + model.numIntVars <= 100_000)
        val lp = requireNotNull((field(theory, "lpDelegate") as Lazy<*>).also { require(it.isInitialized()) }.value)
        val exactState = requireNotNull(member(lp, "getState"))
        val exactBytes = exactCapture(exactState)
        require(exactBytes.size <= 64 * 1024 * 1024)
        exactFile.writeBytes(exactBytes)
        val node = requireNotNull(field(theory, "node"))
        val prepared = requireNotNull(field(run, "prepared"))
        val mapping = requireNotNull(member(prepared, "getMapping"))
        val pins = (field(theory, "bools") as IntArray).copyOf()
        val trail = requireNotNull(field(session, "trail"))
        val atoms = field(session, "atoms")
        return buildJsonObject {
            put("schema", "partial-linear-state-v2")
            put("decisionLevel", session.decisionLevel)
            put("exactStateSha256", sha256(exactBytes))
            put("sourceSlotContract", "presolve retains slots; ordered rebuild stages reconstruct eliminated columns")
            put("sourceNames", buildJsonObject {
                put("integers", scalar(compiled.intVarNames))
                put("booleans", scalar(compiled.boolVarNames))
                put("reals", scalar(compiled.realVarNames))
            })
            put("sourceModel", dimensions(member(mapping, "getSource") as Problem))
            put("preparedModel", dimensions(member(mapping, "getTarget") as Problem))
            put("arithmeticModel", dimensions(model))
            put("privateIntegers", scalar((0 until model.numIntVars).filter { it !in compiled.intVarNames.values }))
            put("privateBooleans", scalar((0 until model.numBoolVars).filter { it !in compiled.boolVarNames.values }))
            put("reconstruction", reconstruction(mapping))
            put("decisions", scalar(field(trail, "decisions")))
            put("booleanPins", buildJsonArray {
                for (variable in pins.indices) {
                    tick()
                    val shared = session.boolValue(variable)
                    val received = when (pins[variable]) {
                        -1 -> null
                        0 -> false
                        1 -> true
                        else -> error("invalid arithmetic Boolean pin")
                    }
                    require(shared == received) { "undelivered Boolean pin" }
                    add(buildJsonObject {
                        put("value", scalar(shared))
                        put("reason", scalar(session.reasonFor(variable)?.literals))
                        put("level", scalar(shared?.let { session.levelOf(variable shl 1) }))
                    })
                }
            })
            put("registeredAtoms", scalar(atoms?.let { field(it, "assertions") }))
            put("registeredPins", buildJsonArray {
                val assertions = (atoms?.let { field(it, "assertions") } as? List<*>).orEmpty()
                for (assertion in assertions) {
                    val literal = member(requireNotNull(assertion), "getLiteral") as Int
                    if (literal and 1 != 0) continue
                    add(buildJsonObject {
                        put("literal", literal)
                        val value = session.boolValue(literal ushr 1)
                        put("value", scalar(value))
                        put("level", scalar(value?.let { session.levelOf(literal) }))
                        put("reason", scalar(session.reasonFor(literal ushr 1)?.literals))
                    })
                }
            })
            put("integerBounds", buildJsonArray {
                for (variable in 0 until model.numIntVars) {
                    tick()
                    add(buildJsonObject {
                        put("variable", variable)
                        put("declaredLower", model.intBounds.lowerAsBigInteger(variable)?.toString())
                        put("declaredUpper", model.intBounds.upperAsBigInteger(variable)?.toString())
                        put("finite", model.intDomainOrNull(variable) != null)
                        put("publishedLower", scalar(session.intLowerBound(variable)))
                        put("publishedUpper", scalar(session.intUpperBound(variable)))
                        put("lowerPremise", scalar(session.intLowerBoundPremise(variable)))
                        put("upperPremise", scalar(session.intUpperBoundPremise(variable)))
                    })
                }
            })
            put("rows", buildJsonArray {
                for ((factorIndex, factor) in model.factors.withIndex()) {
                    for ((rowIndex, row) in factor.linearRows.withIndex()) {
                        tick()
                        add(buildJsonObject {
                            put("factor", factorIndex)
                            put("row", rowIndex)
                            put("form", factor.linearForm?.javaClass?.simpleName)
                            put("data", LinearDisjunctionInspection.rowRecord(row))
                        })
                    }
                }
            })
            put("assertionLevels", scalar(field(theory, "sourceAssertionLevels")))
            put("lpWitnessPremises", scalar(field(lp, "witnesses")))
            put("rowAssertions", scalar(field(node, "rowAssertions")))
            put("sourceBranches", scalar(field(node, "sourceBranches")))
            put("integerBranches", scalar(field(node, "branches")))
            put("reducedBranches", scalar(field(node, "reducedBranches")))
            put("transformedBranches", scalar(field(node, "transformedBranches")))
            put("disequalityDirections", scalar(field(node, "disequalityDirections")))
            put("directionPremises", scalar(field(node, "directionPremises")))
            put("partialPoint", scalar(field(theory, "candidate")))
            put("premiseGraph", premiseGraph())
            tick()
        }
    }

    private fun reconstruction(mapping: Any): JsonArray = buildJsonArray {
        for (stage in call(mapping, "getStages") as List<*>) {
            tick()
            val current = requireNotNull(stage)
            require(field(current, "opaqueReconstruction") == false) { "opaque reconstruction cannot be replayed" }
            val rebuild = requireNotNull(member(current, "getRebuild"))
            add(buildJsonObject {
                put("source", dimensions(member(current, "getSource") as Problem))
                put("target", dimensions(member(current, "getTarget") as Problem))
                put("sourceBooleanCount", scalar(member(rebuild, "getSourceBoolVars")))
                put("steps", scalar(field(rebuild, "steps")))
            })
        }
    }

    private fun dimensions(model: Problem): JsonObject = buildJsonObject {
        put("booleans", model.numBoolVars)
        put("integers", model.numIntVars)
        put("reals", model.numRealVars)
        put("factors", model.factors.size)
    }

    private fun exactCapture(state: Any): ByteArray {
        tick()
        val settingsType = Class.forName("com.eignex.klause.lp.engine.LpReplaySettings")
        val kindType = Class.forName("com.eignex.klause.lp.engine.LpReplaySolverKind")
        val settings = settingsType.declaredConstructors.single { it.parameterCount == 9 }.newInstance(
            "partial-linear-prefix", 1L, kindType.enumConstants.first(), true, 0, 0, 0L, 50, false,
        )
        val captureType = Class.forName("com.eignex.klause.lp.engine.LpExactCapture")
        val companion = captureType.getField("Companion").get(null)
        val capture = companion.javaClass.methods.single { it.name == "capture" && it.parameterCount == 4 }
            .invoke(companion, state, settings, emptyList<Any>(), Int.MAX_VALUE)
        val bytes = call(requireNotNull(capture), "encode") as ByteArray
        val decoded = companion.javaClass.methods.single { it.name == "decode" && it.parameterCount == 1 }
            .invoke(companion, bytes)
        require(bytes.contentEquals(call(requireNotNull(decoded), "encode") as ByteArray))
        tick()
        return bytes
    }

    private fun scalar(value: Any?, depth: Int = 0): JsonElement {
        tick()
        require(depth <= 128) { "premise snapshot exceeds depth allowance" }
        return when (value) {
            null -> JsonNull
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is Enum<*> -> JsonPrimitive(value.name)
            is BigFraction -> JsonPrimitive(value.toString())
            is IntArray -> JsonArray(value.map { scalar(it, depth + 1) })
            is LongArray -> JsonArray(value.map { scalar(it, depth + 1) })
            is Array<*> -> JsonArray(value.map { scalar(it, depth + 1) })
            is List<*> -> JsonArray(value.map { scalar(it, depth + 1) })
            is Map<*, *> -> JsonArray(value.entries.map {
                buildJsonObject {
                    put("key", scalar(it.key, depth + 1))
                    put("value", scalar(it.value, depth + 1))
                }
            })
            is SearchAtomPremise -> premiseReference(value)
            else -> fields(value, depth)
        }
    }

    private fun premiseReference(premise: SearchAtomPremise): JsonObject {
        val id = premiseIds.getOrPut(premise) {
            premises.size.also { premises += premise }
        }
        return buildJsonObject { put("premise", id) }
    }

    fun premiseGraph(): JsonArray = buildJsonArray {
        var index = 0
        while (index < premises.size) {
            tick()
            val premise = premises[index++]
            add(buildJsonObject {
                when (premise) {
                    is SearchAtomPremise.Asserted -> {
                        put("type", "asserted")
                        put("decision", scalar(premise.decision))
                    }
                    is SearchAtomPremise.All -> {
                        put("type", "all")
                        put("children", scalar(field(premise, "premises")))
                    }
                    SearchAtomPremise.Unavailable -> put("type", "unavailable")
                }
            })
        }
    }

    private fun fields(value: Any, depth: Int): JsonObject {
        val type = value.javaClass
        require(type.name.startsWith("com.eignex.klause.solver.search.") ||
            type.name.startsWith("com.eignex.klause.theory.qflra.") ||
            type.name.startsWith("com.eignex.klause.presolve.RebuildStep")) {
            "unsupported capture value ${type.name}"
        }
        return buildJsonObject {
            put("type", type.simpleName)
            for (property in type.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.sortedBy { it.name }) {
                if (property.name == "owner") continue
                property.isAccessible = true
                put(property.name, scalar(property.get(value), depth + 1))
            }
        }
    }

    private fun tick() {
        require(remaining-- > 0) { "prefix capture entry allowance exhausted" }
        require(!stop()) { "prefix capture deadline exhausted" }
    }
}

private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
    .apply { isAccessible = true }.get(owner)

private fun member(owner: Any, name: String): Any? = owner.javaClass.methods.singleOrNull {
    it.parameterCount == 0 && (it.name == name || it.name.startsWith("$name\$"))
}?.apply { isAccessible = true }?.invoke(owner)

private fun call(owner: Any, name: String): Any? = owner.javaClass.methods.single {
    it.parameterCount == 0 && (it.name == name || it.name.startsWith("$name\$"))
}.apply { isAccessible = true }.invoke(owner)
