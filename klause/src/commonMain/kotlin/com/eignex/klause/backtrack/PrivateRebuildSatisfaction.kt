package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SearchInitializationCancelled
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.lifetimeCancellation
import com.eignex.klause.solver.pipeline.withStats
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.result.UnsoundnessException
import com.eignex.klause.solver.withSourceSemantics
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

@Suppress("TooGenericExceptionCaught") // ownership boundaries preserve primary and cleanup failures
internal class PrivateRebuildSatisfaction(
    private val solver: BacktrackSolver,
    private val params: BacktrackParams,
) : ResumableSolve {
    private val lifetime = params.cancellation.lifetimeCancellation()
    private val sharedParams = params.copy(nodeBudget = params.nodeBudget ?: NodeBudget(
        minOf(params.maxDecisions, params.maxInstructions ?: Long.MAX_VALUE),
    ))
    private var root: PropagationSession? = null
    private var rootMark = -1
    private var chargedPrivateWork = 0L
    private val canonical = ResumableSatisfaction(solver, sharedParams, ownsWorkDebt = false, atRoot = {
        val mark = it.explanationState.undoTop
        if (mark == rootMark || !it.canSimplifyRoot || trialSpent() >= params.privateRebuildWork) false
        else {
            rootMark = mark
            root = it
            true
        }
    })
    private var generation: PrivateRebuildGeneration? = null
    private var consumer: ResumableSatisfaction? = null
    private var retiredStats = SolveStats.EMPTY
    private var retiredWork = 0L
    private var debt = 0L
    private var done: SolveResult? = null
    private var closed = false
    private var candidate: Sample? = null
    private var checkedFactors = 0
    private val observations = LinkedHashMap<String, Long>()
    private var retainedStats = SolveStats.EMPTY

    override val isDone: Boolean get() = done != null
    override val initialWork: Long = canonical.initialWork
    override val work: Long get() = canonical.work + retiredWork + (consumer?.work ?: 0L)
    override val stats: SolveStats
        get() {
            if (closed) return retainedStats
            val total = canonical.stats.mergedWith(retiredStats).mergedWith(consumer?.stats ?: SolveStats.EMPTY)
            return total.copy(search = total.search.copy(learning = total.search.learning + observations))
        }

    private fun note(key: String, value: Long = 1L) {
        val name = "privateRebuild$key"
        observations[name] = saturatedWork(observations[name] ?: 0L, value)
    }

    override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long): SolveResult? {
        done?.let { return it }
        check(!closed) { "search is closed" }
        val start = work
        val repaid = if (sliceNodes < 0L) 0L else minOf(debt, sliceNodes)
        debt -= repaid
        val allowance = if (sliceNodes < 0L) -1L else sliceNodes - repaid
        if (allowance == 0L) return null
        val deadline = TimeSource.Monotonic.markNow() + sliceMillis.milliseconds
        val token = lifetime or global or Cancellation.until(deadline) or Cancellation {
            allowance >= 0L && work - start >= allowance
        }
        fun nodes(): Long = if (allowance < 0L) -1L else (allowance - (work - start)).coerceAtLeast(0L)
        fun millis(): Long = (deadline - TimeSource.Monotonic.markNow()).inWholeMilliseconds.coerceAtLeast(0L)
        try {
            if (consumer == null && root == null) {
                canonical.runSlice(global, millis(), nodes())?.let { return finish(it) }
            }
            val pending = root
            if (consumer == null && pending != null && !token()) prepare(pending, token)
            val search = consumer
            if (search != null && !token()) {
                val trialToken = token or Cancellation {
                    trialSpent() >= params.privateRebuildWork
                }
                val result = search.runSlice(global or Cancellation {
                    trialSpent() >= params.privateRebuildWork
                }, millis(), nodes())
                if (result is SolveResult.Sat) {
                    val mapped = candidate ?: checkNotNull(generation).let {
                        charge(it.reconstructionWork)
                        note("ReconstructionWork", it.reconstructionWork)
                        val startMapping = TimeSource.Monotonic.markNow()
                        it.reconstruct(result.assignment).also { lifted ->
                            candidate = lifted
                            note("ReconstructionNanos", startMapping.elapsedNow().inWholeNanoseconds)
                        }
                    }
                    if (validate(mapped, trialToken)) {
                        note("SourceAccepted")
                        return finish(SolveResult.Sat(mapped).withSourceSemantics(solver.problem.semantics))
                    }
                    // A partial source check grants no publication authority; the retained canonical
                    // traversal still owns its complete proof search and warm state.
                    if (!trialToken()) throw UnsoundnessException("private rebuild witness failed its canonical clauses")
                    note("SourceUnavailable")
                    if (trialSpent() >= params.privateRebuildWork) retire()
                } else if (result != null || trialSpent() >= params.privateRebuildWork) {
                    note(if (result is SolveResult.Unsat) "RefutationDeferred" else "TrialStopped")
                    retire()
                }
            }
            if ((lifetime or global)()) {
                return finish(SolveResult.Unknown(TerminationReason.BudgetExhausted))
            }
            return null
        } catch (failure: Throwable) {
            try {
                close()
            } catch (releaseFailure: Throwable) {
                failure.addSuppressed(releaseFailure)
            }
            throw failure
        } finally {
            if (allowance >= 0L) debt = saturatedWork(debt, (work - start - allowance).coerceAtLeast(0L))
        }
    }

    private fun trialSpent(): Long = saturatedWork(chargedPrivateWork,
        saturatedWork(retiredWork, consumer?.work ?: 0L).coerceAtMost(
            Long.MAX_VALUE / PROPAGATION_WORK_PER_NODE,
        ) * PROPAGATION_WORK_PER_NODE)

    private fun charge(work: Long) {
        checkNotNull(root).chargeInprocessingWork(work)
        chargedPrivateWork = saturatedWork(chargedPrivateWork, work)
    }

    private fun prepare(session: PropagationSession, containing: Cancellation) {
        val preparationStart = session.work
        note("Attempts")
        val meter = WorkMeter(::charge)
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = containing() || trialSpent() >= params.privateRebuildWork
            override fun deadline() = containing.deadline()
            override fun workMeter(): WorkMeter = meter
        }
        val start = TimeSource.Monotonic.markNow()
        val prepared = PrivateRebuildGeneration.prepare(session, params.learnedMemoryScratchBytes, token)
        note("PreparationNanos", start.elapsedNow().inWholeNanoseconds)
        note("PreparationWork", session.work - preparationStart)
        if (prepared == null || token()) {
            note("Declined")
            root = null
            return
        }
        generation = prepared
        note("Eliminated", prepared.eliminated.toLong())
        note("ModeledPrivateBytes", prepared.modeledBytes)
        note("WarmClausesRetained", session.learnedClauseCount.toLong())
        // The new model identity owns all imports and propagation indexes. A canonical exchange
        // cannot attach to it without a supported translation, so its cursor stays with canonical.
        val privateParams = sharedParams.copy(
            privateRebuildWork = 0L, clauseExchange = null, cutExchange = null, pooledIncumbents = null,
            cancellation = params.cancellation or Cancellation { trialSpent() >= params.privateRebuildWork },
            onEvent = null, branchObserver = null, maxDecisions = params.maxDecisions.coerceAtMost(
                (params.privateRebuildWork - trialSpent()).coerceAtLeast(0L) / PROPAGATION_WORK_PER_NODE,
            ),
            maxLearnedClauses = minOf(params.maxLearnedClauses ?: 256, 256),
        )
        try {
            consumer = ResumableSatisfaction(BacktrackSolver(prepared.problem), privateParams, ownsWorkDebt = false)
        } catch (stopped: SearchInitializationCancelled) {
            retiredWork += stopped.work
            retiredStats = retiredStats.mergedWith(stopped.stats)
            generation = null
            root = null
            note("OpeningStopped")
            return
        }
        note("Consumers")
    }

    private fun validate(sample: Sample, token: Cancellation): Boolean {
        val start = TimeSource.Monotonic.markNow()
        try {
            while (checkedFactors < solver.problem.numFactors) {
                if (token()) return false
                val factor = solver.problem.factors[checkedFactors]
                val literals = (factor as Clause).literals
                charge(literals.size + 1L)
                if (literals.none { Lit.evaluate(it, sample.boolValue(Lit.variable(it))) }) return false
                checkedFactors++
            }
            return !token()
        } finally {
            note("SourceCheckNanos", start.elapsedNow().inWholeNanoseconds)
        }
    }

    private fun retire() {
        val search = consumer ?: return
        val start = TimeSource.Monotonic.markNow()
        search.close()
        retiredWork += search.work
        retiredStats = retiredStats.mergedWith(search.stats)
        consumer = null
        generation = null
        candidate = null
        root = null
        note("Retired")
        note("RetirementNanos", start.elapsedNow().inWholeNanoseconds)
    }

    private fun finish(result: SolveResult): SolveResult {
        close()
        return result.withStats(stats).also { done = it }
    }

    override fun close() {
        if (closed) return
        try {
            retire()
        } finally {
            try {
                canonical.close()
            } finally {
                root = null
                generation = null
                retainedStats = stats
                closed = true
            }
        }
    }

    companion object {
        fun eligible(problem: BakedProblem, params: BacktrackParams): Boolean =
            params.privateRebuildWork > 0L && problem.numIntVars == 0 && problem.numRealVars == 0 &&
                params.assumptions.isEmpty && params.assumptions.deductions.isEmpty &&
                params.componentFactory == null && params.lpConfig in listOf(null, LpConfig.OFF) &&
                params.lpPlan == LpPlan() && problem.factors.all { it is Clause }
    }
}
