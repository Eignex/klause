package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.localsearch.CandidateCompletion
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceProofContext
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
        val pins: Assumptions, val cutoff: BigFraction, val nativeCutoff: Double,
    )

    private data class Outcome(
        val sample: Sample?, val reason: TerminationReason?, val stats: SolveStats,
        val preparationNs: Long = 0L, val bound: Any? = null,
    )

    fun replay(
        controls: File, problem: BakedProblem, objective: LinearObjective, prepared: FinitePipelinePreparation,
        source: BakedProblem, file: File, seed: Long, allowance: Long = 100_000L,
    ) {
        val scopeOwner = Class.forName("com.eignex.klause.meta.alns.RepairScope").getField("INSTANCE").get(null)
        val freedType = Class.forName("com.eignex.klause.meta.alns.FreedVars")
        val entries = controls.readLines().filter(String::isNotBlank)
        if (problem.numRealVars != 0) {
            file.bufferedWriter().use { writer ->
                for (id in entries.indices) for (owner in listOf("ls", "cp-cold", "cp-retained", "mixed-cold", "mixed-retained")) {
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
            val center = Sample(saved.getValue("bools").jsonArray.map { it.jsonPrimitive.content.toBooleanStrict() }
                .toBooleanArray(), saved.getValue("ints").jsonArray.map { it.jsonPrimitive.content.toLong() }.toLongArray())
            require(center.numBoolVars == problem.numBoolVars && center.numIntVars == problem.numIntVars)
            val cutoff = objective.evaluateExact(center)
            val bools = intArrayOf()
            val ints = entry.getValue("effectiveInts").jsonArray.map { it.jsonPrimitive.content.toInt() }.toIntArray()
            val freed = freedType.getDeclaredConstructor(IntArray::class.java, IntArray::class.java)
                .newInstance(bools, ints)
            val pins = call(scopeOwner, "pins", problem, center, freed) as Assumptions
            check(checkSource(source, prepared.reconstruct(center), Cancellation.Never))
            Scope(index, center, bools, ints, pins, cutoff, objective.evaluate(center))
        }
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val thread = Thread.currentThread().threadId()
        file.bufferedWriter().use { writer ->
            for (kind in listOf("ls", "cp-cold", "cp-retained", "mixed-cold", "mixed-retained")) {
                val budget = NodeBudget(allowance)
                val lifetime = Cancellation.after(10_000.milliseconds)
                var previousCutoff: BigFraction? = null
                var retained: Any? = null
                var epoch = 0
                var mixedWork = 0L
                var mixedAllocation = 0L
                var mixedOperations = 0
                var activeStop: Cancellation = Cancellation.Never
                var activeCenter: Sample? = null
                val dynamic = object : Cancellation {
                    override fun isCancelled(): Boolean = activeStop()
                    override fun deadline() = activeStop.deadline()
                }
                try {
                    for (scope in scopes) {
                        val stop = lifetime or Cancellation.after(2000.milliseconds)
                        activeStop = stop
                        activeCenter = scope.center
                        val looser = previousCutoff?.let { scope.cutoff > it } == true
                        if (looser && retained != null) {
                            (retained as AutoCloseable).close()
                            retained = null
                            epoch++
                        }
                        previousCutoff = scope.cutoff
                        val before = retained?.let { call(it, "getStats") as SolveStats } ?: SolveStats.EMPTY
                        val allocationStart = bean.getThreadAllocatedBytes(thread)
                        val start = System.nanoTime()
                        var native: Any? = null
                        val unavailable = when {
                            kind.startsWith("cp") && BigFraction.ofDouble(scope.cutoff.toDouble()) != scope.cutoff ->
                                "exact cutoff is not representable by the retained CP double contract"
                            else -> null
                        }
                        val outcome = when {
                            stop() || budget.exhausted() -> Outcome(null, TerminationReason.BudgetExhausted, before)
                            unavailable != null -> Outcome(null, TerminationReason.Unsupported, before)
                            kind == "ls" -> local(problem, objective, scope, stop, budget, seed)
                            kind.startsWith("cp") -> {
                                val opened = System.nanoTime()
                                native = retained ?: call(BacktrackSolver(problem), "openRepair", objective,
                                    BacktrackParams(nodeBudget = budget, randomSeed = seed, cancellation = dynamic))
                                val preparation = System.nanoTime() - opened
                                if (kind == "cp-retained") retained = native
                                complete(checkNotNull(native), scope, stop, preparation)
                            }
                            else -> {
                                val opened = System.nanoTime()
                                native = retained ?: mixed(problem, objective, dynamic, budget,
                                    { activeCenter }, 1_000_000_000L - mixedWork,
                                    512L * 1024 * 1024 - mixedAllocation, 256 - mixedOperations)
                                val preparation = System.nanoTime() - opened
                                if (kind == "mixed-retained") retained = native
                                native?.let { numerical(it, scope, stop, budget, preparation) }
                                    ?: Outcome(null, TerminationReason.Unsupported, SolveStats.EMPTY, preparation)
                            }
                        }
                        if (native != null && kind.endsWith("cold")) {
                            if (kind == "mixed-cold") {
                                val stages = outcome.stats.smt.completeSearchStages.orEmpty()
                                mixedWork += stages["jointRepairFundedWork"] ?: 0L
                                mixedAllocation += stages["jointRepairFundedAllocation"] ?: 0L
                                mixedOperations += call(checkNotNull(NumericalFrontierSnapshot.field(native, "budget")),
                                    "getOperations") as Int
                            }
                            (native as AutoCloseable).close()
                        }
                        val elapsed = System.nanoTime() - start
                        val allocation = bean.getThreadAllocatedBytes(thread) - allocationStart
                        val sample = outcome.sample
                        val checked = sample?.let { checkSource(source, prepared.reconstruct(it), stop) }
                        val improving = sample != null && checked == true && objective.evaluateExact(sample) < scope.cutoff
                        val status = when {
                            improving -> "improvement"
                            checked == true -> "feasible-nonimprovement"
                            outcome.reason == TerminationReason.SearchExhausted -> "conditioned-closure"
                            outcome.reason == TerminationReason.BudgetExhausted -> "budget-exhaustion"
                            outcome.reason == TerminationReason.Cancelled || outcome.reason == TerminationReason.Timeout ->
                                "cancelled"
                            outcome.reason == null -> "pause"
                            else -> "unavailable"
                        }
                        writer.appendLine(buildJsonObject {
                            put("centerId", scope.id)
                            put("owner", kind)
                            put("epoch", epoch)
                            put("status", status)
                            put("reason", outcome.reason?.toString())
                            put("unavailable", unavailable)
                            put("strictWorkingCutoff", scope.cutoff.toString())
                            put("cutoffRule", "strict improvement on the checked control center")
                            put("controlFileSha256", com.eignex.klause.bench.metric.sha256(controls.readBytes()))
                            put("capturedNativeCutoff", scope.nativeCutoff)
                            put("effectiveBools", JsonArray(scope.freedBools.map(::JsonPrimitive)))
                            put("effectiveInts", JsonArray(scope.freedInts.map(::JsonPrimitive)))
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
                            put("objective", sample?.let { objective.evaluateExact(it).toString() })
                            put("candidate", sample?.let { sample(prepared.reconstruct(it)) } ?: JsonNull)
                            put("conditionedClosure", outcome.reason == TerminationReason.SearchExhausted)
                            put("conditionedBound", NumericalResidualProfile.json(outcome.bound))
                            put("globalProofClaim", false)
                            put("independentWitnessCheck", false)
                            put("containingAllowance", allowance)
                            put("containingSpent", budget.spent)
                            put("elapsedNs", elapsed)
                            put("threadBytes", allocation)
                            put("preparationNs", outcome.preparationNs)
                            put("statsBefore", NumericalResidualProfile.json(before))
                            put("statsAfter", NumericalResidualProfile.json(outcome.stats))
                        }.toString())
                        writer.flush()
                    }
                } finally {
                    (retained as? AutoCloseable)?.close()
                }
            }
        }
    }

    private fun local(problem: BakedProblem, objective: LinearObjective, scope: Scope, stop: Cancellation,
        budget: NodeBudget, seed: Long,
    ): Outcome {
        val start = System.nanoTime()
        val completion = if (problem.numRealVars == 0) null else
            Class.forName("com.eignex.klause.portfolio.LeafRealCompletion").constructors
                .single { it.parameterCount == 2 }.newInstance(problem, objective) as CandidateCompletion
        val solver = LocalSearchSolver(problem, completion = completion)
        val preparation = System.nanoTime() - start
        val result = solver.minimize(objective, LocalSearchParams(assumptions = scope.pins,
            initialAssignment = scope.center, cancellation = stop, nodeBudget = budget, randomSeed = seed))
        return Outcome(result.assignment, when (result) {
            is MinimizeResult.BestFound -> result.reason
            is MinimizeResult.Unknown -> result.reason
            else -> TerminationReason.SearchExhausted
        }, result.stats, preparation)
    }

    private fun complete(owner: Any, scope: Scope, stop: Cancellation, preparation: Long): Outcome {
        call(owner, "begin", scope.pins, Long.MAX_VALUE, scope.cutoff.toDouble(), stop)
        var result: Any
        do {
            result = checkNotNull(call(owner, "runSlice", Cancellation.Never, 10L, 64L))
        } while (call(result, "getReason") == null && !stop())
        val reason = call(result, "getReason") as TerminationReason?
            ?: if (stop()) TerminationReason.Cancelled else null
        return Outcome(call(result, "getSample") as Sample?, reason,
            call(owner, "getStats") as SolveStats, preparation)
    }

    private fun mixed(problem: BakedProblem, objective: LinearObjective, stop: Cancellation, budget: NodeBudget,
        center: () -> Sample?, work: Long, allocation: Long, operations: Int,
    ): Any? {
        val type = Class.forName(PIPELINE + "FiniteNumericalCompanions").getField("Companion").get(null)
        val limits = TheoryParams(cancellation = stop, nodeBudget = budget, sourceLpWorkLimit = work.coerceAtLeast(0),
            sourceLpAllocationLimit = allocation.coerceAtLeast(0), sourceLpOperations = operations.coerceAtLeast(0))
        val companion = call(type, "prepare", problem, objective, limits, stop) ?: return null
        val context = NumericalFrontierSnapshot.field(companion, "context") as SourceProofContext
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
        do {
            val before = call(owner, "getWork") as Long
            attempt = checkNotNull(call(owner, "attempt", condition, Cancellation.Never))
            call(budget, "spendMoves", 1L + ceil(((call(owner, "getWork") as Long) - before) * 0.8).toLong())
            result = checkNotNull(call(attempt, "getResult"))
        } while (result.javaClass.simpleName == "Paused" && !stop() && !budget.exhausted())
        val reason = when {
            result.javaClass.simpleName == "Refuted" -> TerminationReason.SearchExhausted
            stop() -> TerminationReason.Cancelled
            budget.exhausted() -> TerminationReason.BudgetExhausted
            else -> call(attempt, "getReason") as TerminationReason?
        }
        return Outcome(call(attempt, "getFeasiblePoint") as Sample?, reason,
            call(owner, "getStats") as SolveStats, preparation, call(attempt, "getBound"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun checkSource(problem: BakedProblem, sample: Sample, stop: Cancellation): Boolean {
        val verifier = Class.forName("com.eignex.klause.solver.validation.FiniteWitnessVerificationKt")
            .methods.single { it.name == "finiteWitnessVerifier" && it.parameterCount == 5 }
            .invoke(null, problem, null, Assumptions.None, stop, null)
            as com.eignex.klause.solver.incumbent.CandidateVerifier<Sample, Double?>
        val checked = verifier.verify(com.eignex.klause.solver.incumbent.Candidate(sample, null))
        check(checked !is com.eignex.klause.solver.incumbent.Verification.Rejected)
        return checked is com.eignex.klause.solver.incumbent.Verification.Accepted
    }

    private fun sample(value: Sample) = buildJsonObject {
        put("bools", JsonArray(value.bools.map(::JsonPrimitive)))
        put("ints", JsonArray((0 until value.numIntVars).map { JsonPrimitive(value.exactIntValue(it).toString()) }))
        put("exactReals", value.exactReals?.let { JsonArray(it.map { value -> JsonPrimitive(value.toString()) }) }
            ?: JsonNull)
        put("approximateReals", JsonArray(value.reals.map(::JsonPrimitive)))
    }

    private fun call(owner: Any, name: String, vararg args: Any?): Any? =
        NumericalResidualProfile.call(owner, name, *args)
}
