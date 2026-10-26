package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.localsearch.CandidateCompletion
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.FinitePipelinePreparation
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.ceil
import kotlin.time.Duration.Companion.milliseconds

internal object RepairOwnerReplay {
    private const val PIPELINE = "com.eignex.klause.solver.pipeline."

    private data class Scope(
        val id: Int, val center: Sample, val freedBools: IntArray, val freedInts: IntArray,
        val pins: Assumptions, val cutoff: BigFraction, val nativeCutoff: Double, val selector: String,
        val sourceCutoff: BigFraction?, val capturedObjective: String, val unavailable: String?,
    )

    data class PointCheck(val accepted: Boolean, val objective: BigFraction? = null)

    private data class Outcome(
        val sample: Sample?, val reason: TerminationReason?, val stats: SolveStats,
        val preparationNs: Long = 0L, val bound: Any? = null, val pauses: Long = 0L,
    )

    private data class ClauseUsage(
        val used: BooleanArray, val permanent: BooleanArray, val total: Int, val complete: Boolean,
    )

    fun replay(
        controls: File, problem: BakedProblem, objective: LinearObjective, prepared: FinitePipelinePreparation,
        source: BakedProblem, inputs: IntArray, file: File, seed: Long, allowance: Long = 100_000L,
        fragmentAllowance: Long = 2_000L,
        sourceCheck: ((Sample, Cancellation) -> PointCheck)? = null, mixedContext: SourceProofContext? = null,
        sourceMaximize: Boolean = false,
    ) {
        require(allowance > 0 && fragmentAllowance > 0)
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val thread = Thread.currentThread().threadId()
        val setupStarted = System.nanoTime()
        val setupAllocation = bean.getThreadAllocatedBytes(thread)
        fun accepted(sample: Sample, stop: Cancellation) =
            sourceCheck?.invoke(sample, stop) ?: PointCheck(checkSource(source, prepared.reconstruct(sample), stop))
        val scopeOwner = Class.forName("com.eignex.klause.meta.alns.RepairScope").getField("INSTANCE").get(null)
        val freedType = Class.forName("com.eignex.klause.meta.alns.FreedVars")
        require(controls.length() in 0..4L * 1024 * 1024)
        val controlBytes = controls.readBytes()
        val controlHash = sha256(controlBytes)
        val entries = controlBytes.decodeToString().lines().filter(String::isNotBlank)
        if (problem.numRealVars != 0 && entries.any {
            (Json.parseToJsonElement(it).jsonObject.getValue("center").jsonObject["exactReals"] as? JsonArray)
                ?.size != problem.numRealVars
        }) {
            file.bufferedWriter().use { writer ->
                for (id in entries.indices) for (owner in listOf("ls", "cp-cold", "cp-retained",
                    "mixed-cold", "mixed-retained")) {
                    writer.appendLine(buildJsonObject {
                        put("controlIndex", id)
                        put("owner", owner)
                        put("status", "unavailable")
                        put("reason", "finite control has no authoritative exact real snapshot")
                        put("globalProofClaim", false)
                    }.toString())
                }
            }
            return
        }
        val scopes = entries.mapIndexed { index, line ->
            val entry = Json.parseToJsonElement(line).jsonObject
            val saved = entry.getValue("center").jsonObject
            val reals = saved["exactReals"]?.jsonArray?.map { MpsResidualObjective.rational(it.jsonPrimitive.content) }
                ?: emptyList()
            require(reals.size == problem.numRealVars)
            val center = Sample(saved.getValue("bools").jsonArray.map { it.jsonPrimitive.content.toBooleanStrict() }
                .toBooleanArray(), saved.getValue("ints").jsonArray.map { it.jsonPrimitive.content.toLong() }
                .toLongArray(), reals.map { it.toDouble() }.toDoubleArray(), exactReals = reals)
            require(center.numBoolVars == problem.numBoolVars && center.numIntVars == problem.numIntVars)
            val cutoff = objective.evaluateExact(center)
            val bools = entry["effectiveBools"]?.jsonArray?.map { it.jsonPrimitive.content.toInt() }?.toIntArray()
                ?: intArrayOf()
            val ints = entry.getValue("effectiveInts").jsonArray.map { it.jsonPrimitive.content.toInt() }.toIntArray()
            require(bools.distinct().size == bools.size && bools.all { it in 0 until problem.numBoolVars })
            require(ints.distinct().size == ints.size && ints.all { it in 0 until problem.numIntVars })
            val freed = freedType.getDeclaredConstructor(IntArray::class.java, IntArray::class.java)
                .newInstance(bools, ints)
            val pins = call(scopeOwner, "pins", problem, center, freed) as Assumptions
            val checked = accepted(center, Cancellation.after(2000.milliseconds))
            check(checked.accepted)
            val decline = entry["decline"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
            Scope(index, center, bools, ints, pins, cutoff,
                objective.evaluate(center), entry.getValue("selector").jsonPrimitive.content, checked.objective,
                entry.getValue("centerObjective").jsonPrimitive.content,
                if (bools.isEmpty() && ints.isEmpty() && decline != null) "selector declined: $decline" else null)
        }
        file.resolveSibling("${file.nameWithoutExtension}-preparation.json").writeText(buildJsonObject {
            put("controlFileSha256", controlHash)
            put("controlBytes", controlBytes.size)
            put("sourceCheckedCenters", scopes.size)
            put("elapsedNs", System.nanoTime() - setupStarted)
            put("threadBytes", bean.getThreadAllocatedBytes(thread) - setupAllocation)
            put("containingOwnerAllowanceStarted", false)
            put("independentWitnessCheck", false)
        }.toString())
        file.bufferedWriter().use { writer ->
            for (kind in listOf("ls", "cp-cold", "cp-retained", "mixed-cold", "mixed-retained")) {
                val budget = NodeBudget(allowance)
                val lifetime = Cancellation.after(10_000.milliseconds)
                var previousCutoff: BigFraction? = null
                var retained: Any? = null
                var transient: AutoCloseable? = null
                var epoch = 0
                var mixedWork = 0L
                var mixedAllocation = 0L
                var mixedOperations = 0
                fun accountMixed(owner: Any) {
                    val stages = (call(owner, "getStats") as SolveStats).smt.completeSearchStages.orEmpty()
                    mixedWork += stages["jointRepairFundedWork"] ?: 0L
                    mixedAllocation += stages["jointRepairFundedAllocation"] ?: 0L
                    mixedOperations += call(checkNotNull(NumericalFrontierSnapshot.field(owner, "budget")),
                        "getOperations") as Int
                }
                var activeStop: Cancellation = Cancellation.Never
                var activeCenter: Sample? = null
                val dynamic = object : Cancellation {
                    override fun isCancelled(): Boolean = activeStop()
                    override fun deadline() = activeStop.deadline()
                }
                try {
                    for (scope in scopes) {
                        val fragmentStart = budget.spent
                        val funded = Cancellation { budget.spent - fragmentStart >= fragmentAllowance }
                        val stop = lifetime or Cancellation.after(2000.milliseconds) or funded
                        activeStop = stop
                        activeCenter = scope.center
                        val allocationStart = bean.getThreadAllocatedBytes(thread)
                        val start = System.nanoTime()
                        val unavailable = scope.unavailable ?: when {
                            kind.startsWith("cp") && BigFraction.ofDouble(scope.cutoff.toDouble()) != scope.cutoff ->
                                "exact cutoff is not representable by the retained CP double contract"
                            else -> null
                        }
                        val looser = unavailable == null && previousCutoff?.let { scope.cutoff > it } == true
                        if (looser && retained != null) {
                            if (kind == "mixed-retained") accountMixed(retained)
                            (retained as AutoCloseable).close()
                            retained = null
                            epoch++
                        }
                        val before = retained?.let { call(it, "getStats") as SolveStats } ?: SolveStats.EMPTY
                        var native: Any? = null
                        var usageBefore: ClauseUsage? = null
                        var usageAfter: ClauseUsage? = null
                        var observationNs = 0L
                        val nativeOutcome = when {
                            stop() -> Outcome(null, stopped(stop), before)
                            budget.exhausted() -> Outcome(null, TerminationReason.BudgetExhausted, before)
                            unavailable != null -> Outcome(null, TerminationReason.Unsupported, before)
                            kind == "ls" -> local(problem, objective, scope, stop, budget, seed, fragmentAllowance)
                            kind.startsWith("cp") -> {
                                val opened = System.nanoTime()
                                native = retained ?: call(BacktrackSolver(problem), "openRepair", objective,
                                    BacktrackParams(nodeBudget = budget, randomSeed = seed, cancellation = dynamic))
                                val preparation = System.nanoTime() - opened
                                if (kind == "cp-retained") retained = native
                                else transient = native as AutoCloseable
                                val observationStart = System.nanoTime()
                                usageBefore = clauseUsage(checkNotNull(native), stop)
                                observationNs += System.nanoTime() - observationStart
                                val completed = complete(checkNotNull(native), scope, stop, preparation,
                                    fragmentAllowance)
                                val afterStart = System.nanoTime()
                                usageAfter = clauseUsage(checkNotNull(native), stop)
                                observationNs += System.nanoTime() - afterStart
                                call(budget, "spendMoves", (usageBefore.used.size + usageAfter.used.size).toLong())
                                completed
                            }
                            else -> {
                                val opened = System.nanoTime()
                                native = retained ?: mixed(problem, objective, dynamic, budget,
                                    { activeCenter }, 1_000_000_000L - mixedWork,
                                    512L * 1024 * 1024 - mixedAllocation, 256 - mixedOperations, mixedContext)
                                val preparation = System.nanoTime() - opened
                                if (kind == "mixed-retained") retained = native
                                else transient = native as? AutoCloseable
                                native?.let { numerical(it, scope, stop, budget, preparation) }
                                    ?: Outcome(null, TerminationReason.Unsupported, SolveStats.EMPTY, preparation)
                            }
                        }
                        if (native != null) previousCutoff = scope.cutoff
                        val outcome = if (funded()) nativeOutcome.copy(reason = TerminationReason.BudgetExhausted)
                            else nativeOutcome
                        if (native != null && kind.endsWith("cold")) {
                            if (kind == "mixed-cold") {
                                accountMixed(native)
                            }
                            (native as AutoCloseable).close()
                            transient = null
                        }
                        val elapsed = System.nanoTime() - start
                        val allocation = bean.getThreadAllocatedBytes(thread) - allocationStart
                        val sample = outcome.sample
                        if (sample != null) {
                            check(sample.numBoolVars == scope.center.numBoolVars &&
                                sample.numIntVars == scope.center.numIntVars)
                            check((0 until sample.numBoolVars).all {
                                it in scope.freedBools || sample.boolValue(it) == scope.center.boolValue(it)
                            })
                            check((0 until sample.numIntVars).all {
                                it in scope.freedInts || sample.exactIntValue(it) == scope.center.exactIntValue(it)
                            })
                        }
                        val verificationStarted = System.nanoTime()
                        val verificationAllocation = bean.getThreadAllocatedBytes(thread)
                        val realAuthority = sample == null || problem.numRealVars == 0 ||
                            sample.exactReals?.size == problem.numRealVars
                        val verification = sample?.takeIf { realAuthority }?.let { accepted(it, lifetime) }
                        val checked = verification?.accepted
                        val verificationNs = System.nanoTime() - verificationStarted
                        val verificationBytes = bean.getThreadAllocatedBytes(thread) - verificationAllocation
                        val sourceImproving = scope.sourceCutoff?.let { cutoff -> verification?.objective?.let {
                            if (sourceMaximize) it > cutoff else it < cutoff
                        } }
                        val improving = sample != null && checked == true &&
                            objective.evaluateExact(sample) < scope.cutoff &&
                            (scope.sourceCutoff == null || sourceImproving == true)
                        val status = when {
                            improving -> "improvement"
                            checked == true -> "feasible-nonimprovement"
                            !realAuthority -> "unavailable"
                            sample != null && checked == false && !lifetime() -> "source-rejected"
                            outcome.reason == TerminationReason.SearchExhausted -> "conditioned-closure"
                            outcome.reason == TerminationReason.BudgetExhausted -> "budget-exhaustion"
                            outcome.reason in setOf(TerminationReason.Cancelled, TerminationReason.Timeout) ->
                                "cancelled"
                            outcome.reason == null -> "pause"
                            else -> "unavailable"
                        }
                        writer.appendLine(buildJsonObject {
                            put("centerId", scope.id)
                            put("owner", kind)
                            put("ownerSeed", seed)
                            put("selector", scope.selector)
                            put("epoch", epoch)
                            put("status", status)
                            put("reason", outcome.reason?.toString())
                            put("nativeReason", nativeOutcome.reason?.toString())
                            put("completion", outcome.reason?.toString() ?: if (sample != null) "point" else "paused")
                            put("unavailable", unavailable)
                            put("strictWorkingCutoff", scope.cutoff.toString())
                            put("cutoffRule", "strict improvement on the checked control center")
                            put("controlFileSha256", controlHash)
                            put("capturedCenterObjective", scope.capturedObjective)
                            put("nativeCenterObjective", scope.nativeCutoff)
                            put("selectorUnavailable", scope.unavailable != null)
                            put("effectiveBools", JsonArray(scope.freedBools.map(::JsonPrimitive)))
                            put("effectiveInts", JsonArray(scope.freedInts.map(::JsonPrimitive)))
                            put("scopeNamespace", "captured prepared finite slots")
                            put("sourceInputChanges", sample?.let { candidate ->
                                val from = prepared.reconstruct(scope.center)
                                val to = prepared.reconstruct(candidate)
                                inputs.count { from.exactIntValue(it) != to.exactIntValue(it) }
                            })
                            put("complementBoolPins", buildJsonObject {
                                for (id in 0 until scope.center.numBoolVars) if (id !in scope.freedBools)
                                    put(id.toString(), scope.center.boolValue(id))
                            })
                            put("complementIntPins", buildJsonObject {
                                for (id in 0 until scope.center.numIntVars) if (id !in scope.freedInts)
                                    put(id.toString(), scope.center.exactIntValue(id).toString())
                            })
                            put("center", sample(scope.center))
                            put("nativeSourceAccepted", checked)
                            put("sourceCenterObjective", scope.sourceCutoff?.toString())
                            put("sourceCandidateObjective", verification?.objective?.toString())
                            put("sourceStrictImprovement", sourceImproving)
                            put("objective", sample?.let { objective.evaluateExact(it).toString() })
                            put("candidate", sample?.let { sample(prepared.reconstruct(it)) } ?: JsonNull)
                            put("conditionedClosure", outcome.reason == TerminationReason.SearchExhausted)
                            put("conditionedBound", NumericalResidualProfile.json(outcome.bound))
                            put("globalProofClaim", false)
                            put("independentWitnessCheck", false)
                            put("containingAllowance", allowance)
                            put("containingSpent", budget.spent)
                            put("fragmentAllowance", fragmentAllowance)
                            put("fragmentSpent", budget.spent - fragmentStart)
                            put("fragmentCooperativeOverspend", (budget.spent - fragmentStart - fragmentAllowance)
                                .coerceAtLeast(0L))
                            put("workCurrency", "NodeBudget decision equivalents; native counters are separate")
                            put("elapsedNs", elapsed)
                            put("threadBytes", allocation)
                            put("sourceVerificationNs", verificationNs)
                            put("sourceVerificationThreadBytes", verificationBytes)
                            put("preparationNs", outcome.preparationNs)
                            put("slicePauses", outcome.pauses)
                            put("carriedSourceLpWork", mixedWork)
                            put("carriedSourceLpAllocation", mixedAllocation)
                            put("carriedSourceLpOperations", mixedOperations)
                            put("reuseObservationNs", observationNs)
                            put("retainedClauseUses", reuse(usageBefore, usageAfter))
                            put("statsBefore", NumericalResidualProfile.json(before))
                            put("statsAfter", NumericalResidualProfile.json(outcome.stats))
                        }.toString())
                        writer.flush()
                    }
                } finally {
                    try {
                        transient?.close()
                    } finally {
                        (retained as? AutoCloseable)?.close()
                    }
                }
            }
        }
    }

    private fun local(problem: BakedProblem, objective: LinearObjective, scope: Scope, stop: Cancellation,
        budget: NodeBudget, seed: Long, allowance: Long,
    ): Outcome {
        val start = System.nanoTime()
        val completion = if (problem.numRealVars == 0) null else
            Class.forName("com.eignex.klause.portfolio.LeafRealCompletion").constructors
                .single { it.parameterCount == 2 }.newInstance(problem, objective) as CandidateCompletion
        val solver = LocalSearchSolver(problem, completion = completion)
        val preparation = System.nanoTime() - start
        val available = minOf(allowance, (budget.limit - budget.spent).coerceAtLeast(0L))
        val instructions = available / 5 * 4 + available % 5 * 4 / 5
        val result = solver.minimize(objective, LocalSearchParams(assumptions = scope.pins,
            initialAssignment = scope.center, cancellation = stop, nodeBudget = budget, randomSeed = seed,
            maxInstructions = instructions))
        return Outcome(result.assignment, when (result) {
            is MinimizeResult.BestFound -> result.reason
            is MinimizeResult.Unknown -> result.reason
            is MinimizeResult.Unbounded -> TerminationReason.Unsupported
            else -> TerminationReason.SearchExhausted
        }, result.stats, preparation)
    }

    private fun complete(owner: Any, scope: Scope, stop: Cancellation, preparation: Long, allowance: Long): Outcome {
        val start = System.nanoTime()
        call(owner, "begin", scope.pins, allowance, scope.cutoff.toDouble(), stop)
        val prepared = preparation + System.nanoTime() - start
        var result: Any
        var pauses = 0L
        do {
            result = checkNotNull(call(owner, "runSlice", Cancellation.Never, 10L, 64L))
            if (call(result, "getReason") == null) pauses++
        } while (call(result, "getReason") == null && !stop())
        val reason = call(result, "getReason") as TerminationReason?
            ?: if (stop()) stopped(stop) else null
        return Outcome(call(result, "getSample") as Sample?, reason,
            call(owner, "getStats") as SolveStats, prepared, pauses = pauses)
    }

    private fun mixed(problem: BakedProblem, objective: LinearObjective, stop: Cancellation, budget: NodeBudget,
        center: () -> Sample?, work: Long, allocation: Long, operations: Int,
        originalContext: SourceProofContext?,
    ): Any? {
        val limits = TheoryParams(cancellation = stop, nodeBudget = budget, sourceLpWorkLimit = work.coerceAtLeast(0),
            sourceLpAllocationLimit = allocation.coerceAtLeast(0), sourceLpOperations = operations.coerceAtLeast(0))
        val context = originalContext ?: run {
            val type = Class.forName(PIPELINE + "FiniteNumericalCompanions").getField("Companion").get(null)
            val companion = call(type, "prepare", problem, objective, limits, stop) ?: return null
            NumericalFrontierSnapshot.field(companion, "context") as SourceProofContext
        }
        val policy = Class.forName("com.eignex.klause.lp.SparseSourceLpPolicy").getConstructor().newInstance()
        return Class.forName(PIPELINE + "JointLinearRepair").constructors.single { it.parameterCount == 8 }
            .newInstance(problem, context, objective, limits, policy, true, center, stop)
    }

    private fun numerical(owner: Any, scope: Scope, stop: Cancellation, budget: NodeBudget,
        preparation: Long,
    ): Outcome {
        val bools = (0 until scope.center.numBoolVars).filter { it !in scope.freedBools }
            .associateWith(scope.center::boolValue)
        val pins = (0 until scope.center.numIntVars).filter { it !in scope.freedInts }
            .associateWith { BigFraction.of(scope.center.exactIntValue(it), BIG_ONE) }
        val condition = Class.forName(PIPELINE + "ExactNeighborhoodCondition").constructors
            .single { it.parameterCount == 4 }.newInstance(bools, pins, pins, scope.cutoff)
        var attempt: Any
        var result: Any
        var pauses = 0L
        do {
            val before = call(owner, "getWork") as Long
            attempt = checkNotNull(call(owner, "attempt", condition, Cancellation.Never))
            call(budget, "spendMoves", 1L + ceil(((call(owner, "getWork") as Long) - before) * 0.8).toLong())
            result = checkNotNull(call(attempt, "getResult"))
            if (result.javaClass.simpleName == "Paused") pauses++
        } while (result.javaClass.simpleName == "Paused" && !stop() && !budget.exhausted())
        val reason = when {
            result.javaClass.simpleName == "Refuted" -> TerminationReason.SearchExhausted
            stop() -> stopped(stop)
            budget.exhausted() -> TerminationReason.BudgetExhausted
            else -> call(attempt, "getReason") as TerminationReason?
        }
        return Outcome(call(attempt, "getFeasiblePoint") as Sample?, reason,
            call(owner, "getStats") as SolveStats, preparation, call(attempt, "getBound"), pauses)
    }

    @Suppress("UNCHECKED_CAST")
    private fun checkSource(problem: BakedProblem, sample: Sample, stop: Cancellation): Boolean {
        val verifier = Class.forName("com.eignex.klause.solver.validation.FiniteWitnessVerificationKt")
            .methods.single { it.name == "finiteWitnessVerifier" && it.parameterCount == 5 }
            .invoke(null, problem, null, Assumptions.None, stop, null)
            as CandidateVerifier<Sample, Double?>
        val checked = verifier.verify(Candidate(sample, null))
        check(checked !is Verification.Rejected)
        return checked is Verification.Accepted
    }

    fun sample(value: Sample) = buildJsonObject {
        put("bools", JsonArray(value.bools.map(::JsonPrimitive)))
        put("ints", JsonArray((0 until value.numIntVars).map { JsonPrimitive(value.exactIntValue(it).toString()) }))
        put("exactReals", value.exactReals?.let {
            JsonArray(it.map { coordinate -> JsonPrimitive(coordinate.toString()) })
        }
            ?: JsonNull)
        put("approximateReals", JsonArray(value.reals.map(::JsonPrimitive)))
    }

    private fun clauseUsage(owner: Any, stop: Cancellation): ClauseUsage {
        val field = owner.javaClass.declaredFields.single {
            it.type.name == "com.eignex.klause.backtrack.ResumableMinimize"
        }.apply { isAccessible = true }
        val handle = field.get(owner)
        val session = handle.javaClass.getDeclaredMethod("getSession").apply { isAccessible = true }.invoke(handle)
        val total = call(session, "getLearnedClauseCount") as Int
        val used = ArrayList<Boolean>()
        val permanent = ArrayList<Boolean>()
        for (index in 0 until minOf(total, 4096)) {
            if (stop()) break
            used += call(session, "learnedClauseUsedSinceReduction", index) as Boolean
            permanent += call(session, "learnedClausePermanent", index) as Boolean
        }
        return ClauseUsage(used.toBooleanArray(), permanent.toBooleanArray(), total, used.size == total)
    }

    private fun reuse(before: ClauseUsage?, after: ClauseUsage?) = buildJsonObject {
        val compatible = before != null && after != null && after.total >= before.total &&
            after.used.size >= before.used.size && before.permanent.indices.all {
                before.permanent[it] == after.permanent[it]
            }
        put("available", compatible)
        put("completePrefix", before?.complete == true && after?.complete == true)
        put("maximumClauses", 4096)
        put("flagsCleared", false)
        put("databaseReductionEnabled", false)
        put("alreadyUsedClausesHaveUnknownRepeatUse", true)
        if (compatible) {
            val earlier = checkNotNull(before)
            val later = checkNotNull(after)
            put("newlyUsedRetainedNogoods", earlier.used.indices.count {
                !earlier.used[it] && later.used[it] && !earlier.permanent[it]
            })
            put("newlyUsedRetainedPermanentClauses", earlier.used.indices.count {
                !earlier.used[it] && later.used[it] && earlier.permanent[it]
            })
        }
    }

    private fun stopped(stop: Cancellation): TerminationReason =
        if (stop.deadline()?.hasPassedNow() == true) TerminationReason.Timeout else TerminationReason.Cancelled

    private fun call(owner: Any, name: String, vararg args: Any?): Any? =
        NumericalResidualProfile.call(owner, name, *args)
}
