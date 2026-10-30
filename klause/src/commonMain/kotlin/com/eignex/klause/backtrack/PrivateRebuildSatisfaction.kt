package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SearchInitializationCancelled
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.lifetimeCancellation
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
    private var rootPins = (solver.problem.rootDeductions as PropagationResult.Implied).boolKeys.size
    private var chargedPrivateWork = 0L
    private var binaryCount = 0
    private var binaryRevision = 0L
    private var attemptedBinaryPairs = longArrayOf()
    private var attemptedBinaryCount = 0
    private var pendingBinary: PrivateRebuildBinaryLearning? = null
    private var rootCancellation: Cancellation = Cancellation.Never
    private val canonical = ResumableSatisfaction(solver, sharedParams, ownsWorkDebt = false, atRoot = ::atRoot)
    private var generation: PrivateRebuildGeneration? = null
    private var consumer: ResumableSatisfaction? = null
    private var retiredStats = SolveStats.EMPTY
    private var retiredClauseWork = 0L
    private var debt = 0L
    private var done: SolveResult? = null
    private var closed = false
    private var candidate: Sample? = null
    private var checkedFactors = 0
    private val observations = LinkedHashMap<String, Long>()
    private var retainedStats = SolveStats.EMPTY

    override val isDone: Boolean get() = done != null
    override val initialWork: Long = canonical.initialWork
    override val work: Long get() = saturatedWork(canonical.work,
        saturatedWork(retiredClauseWork, consumer?.clauseWork ?: 0L) / PROPAGATION_WORK_PER_NODE)
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

    private fun atRoot(session: PropagationSession): Boolean {
        if (!session.canSimplifyRoot) return false
        val mark = session.explanationState.undoTop
        val scanWork = solver.problem.numBoolVars.toLong()
        if (mark != rootMark) {
            if (scanWork > params.privateRebuildWork - trialSpent()) return false
            rootMark = mark
            val start = TimeSource.Monotonic.markNow()
            val pins = session.rootFixedVariableCount()
            chargeRoot(session, scanWork)
            note("RootScanWork", scanWork)
            note("RootScanNanos", start.elapsedNow().inWholeNanoseconds)
            if (pins > rootPins) {
                note("ProgressPins", (pins - rootPins).toLong())
                rootPins = pins
                root = session
                return true
            }
        }
        if (binaryCount == session.learnedClauseCount && binaryRevision == session.learnedClauseRevision ||
            attemptedBinaryCount == PrivateRebuildBinaryLearning.MAX_RELATIONS) return false
        binaryCount = session.learnedClauseCount
        binaryRevision = session.learnedClauseRevision
        val historyBytes = PrivateRebuildBinaryLearning.MAX_RELATIONS * Long.SIZE_BYTES.toLong()
        if (params.learnedMemoryScratchBytes < PrivateRebuildBinaryLearning.SCRATCH_BYTES + historyBytes) return false
        var spent = 0L
        val allowance = minOf(PrivateRebuildBinaryLearning.SCAN_WORK,
            (params.privateRebuildWork - trialSpent()).coerceAtLeast(0L))
        val start = TimeSource.Monotonic.markNow()
        val captured = PrivateRebuildBinaryLearning.capture(
            session, attemptedBinaryPairs, attemptedBinaryCount,
        ) { work ->
            if (work > allowance - spent || session.cancelRequested || lifetime() || rootCancellation()) false
            else {
                spent += work
                chargeRoot(session, work)
                true
            }
        }
        note("BinaryScanWork", spent)
        note("BinaryScanNanos", start.elapsedNow().inWholeNanoseconds)
        if (captured == null || rootCancellation()) return false
        if (attemptedBinaryPairs.isEmpty()) {
            val entries = PrivateRebuildBinaryLearning.MAX_RELATIONS.toLong()
            if (entries > params.privateRebuildWork - trialSpent()) return false
            chargeRoot(session, entries)
            note("BinaryHistoryWork", entries)
            note("BinaryHistoryBytes", historyBytes)
            attemptedBinaryPairs = LongArray(PrivateRebuildBinaryLearning.MAX_RELATIONS)
        }
        attemptedBinaryPairs[attemptedBinaryCount++] = captured.key
        pendingBinary = captured
        root = session
        note("BinaryRelations")
        return true
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
                val result = try {
                    rootCancellation = token
                    canonical.runSlice(global, millis(), nodes())
                } finally {
                    rootCancellation = Cancellation.Never
                }
                result?.let { return finish(it) }
            }
            val pending = root
            if (consumer == null && pending != null && !token()) prepare(pending, token)
            val search = consumer
            if (search != null && !token()) {
                val trialToken = token or Cancellation {
                    trialSpent() >= params.privateRebuildWork
                }
                val trialStart = TimeSource.Monotonic.markNow()
                val result = try {
                    search.runSlice(lifetime or global or Cancellation {
                        trialSpent() >= params.privateRebuildWork
                    }, millis(), nodes())
                } finally {
                    note("TrialNanos", trialStart.elapsedNow().inWholeNanoseconds)
                }
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
                    if (!trialToken()) {
                        throw UnsoundnessException("private rebuild witness failed its canonical clauses")
                    }
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
        saturatedWork(retiredClauseWork, consumer?.clauseWork ?: 0L))

    private fun charge(work: Long) {
        chargeRoot(checkNotNull(root), work)
    }

    private fun chargeRoot(session: PropagationSession, work: Long) {
        session.chargeInprocessingWork(work)
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
        val binary = pendingBinary
        pendingBinary = null
        val prepared = PrivateRebuildGeneration.prepare(session,
            params.learnedMemoryScratchBytes - attemptedBinaryPairs.size * Long.SIZE_BYTES.toLong(), token,
            onDecline = { note("Declined$it") }, learnedBinary = binary) {
            (params.privateRebuildWork - trialSpent()).coerceAtLeast(0L) / 2L
        }
        note("PreparationNanos", start.elapsedNow().inWholeNanoseconds)
        note("PreparationWork", session.work - preparationStart)
        if (prepared == null || token()) {
            note("Declined")
            root = null
            return
        }
        generation = prepared
        if (prepared.passStopped) note("PassStopped")
        note("Eliminated", prepared.eliminated.toLong())
        note("EquivalentColumns", prepared.equivalenceMerges.toLong())
        note("AdmittedColumns", prepared.admittedColumns.toLong())
        note("ModeledPrivateBytes", prepared.modeledBytes)
        note("WarmClausesRetained", session.learnedClauseCount.toLong())
        note("WarmClausesCopied", prepared.warmClauses.clauses.size.toLong())
        note("WarmClauseLiterals", prepared.warmClauses.literals)
        note("WarmClauseWork", prepared.warmClauses.work)
        note("WarmClausesTranslated", prepared.warmClauses.translated.toLong())
        note("WarmProjectionScratchBytes", prepared.warmClauses.projectionScratchBytes)
        note("WarmClauseNormalizationWork", prepared.warmClauses.normalizationWork)
        note("WarmClauseNanos", prepared.warmClauseNanos)
        note("BakeConstructionEntries", prepared.bakeWork.constructionEntries)
        note("BakePropagationWork", prepared.bakeWork.propagationWork)
        note("MappingPreparationWork", prepared.mappingWork)
        note("ReusedSourceClauses", prepared.reusedSourceClauses.toLong())
        note("BveIndexedFactors", prepared.indexedFactors.toLong())
        note("BveSourceFactors", prepared.sourceFactors.toLong())
        charge(prepared.bakeWork.constructionEntries)
        note("OpeningConstructionWork", prepared.bakeWork.constructionEntries)
        if (token()) {
            generation = null
            root = null
            note("OpeningStopped")
            return
        }
        // The new model identity owns all imports and propagation indexes. A canonical exchange
        // cannot attach to it without a supported translation, so its cursor stays with canonical.
        val privateParams = sharedParams.copy(
            privateRebuildWork = 0L, clauseExchange = null, cutExchange = null, pooledIncumbents = null,
            cancellation = containing or Cancellation { trialSpent() >= params.privateRebuildWork },
            onEvent = null, branchObserver = null, maxDecisions = params.maxDecisions.coerceAtMost(
                (params.privateRebuildWork - trialSpent()).coerceAtLeast(0L) / PROPAGATION_WORK_PER_NODE,
            ),
            maxLearnedClauses = minOf(params.maxLearnedClauses ?: 256, 256),
        )
        val openingStart = TimeSource.Monotonic.markNow()
        try {
            consumer = ResumableSatisfaction(BacktrackSolver(prepared.problem), privateParams, ownsWorkDebt = false)
        } catch (stopped: SearchInitializationCancelled) {
            val decisions = stopped.stats.search.nodes.sum.toLong()
                .coerceAtMost(Long.MAX_VALUE / PROPAGATION_WORK_PER_NODE) * PROPAGATION_WORK_PER_NODE
            retiredClauseWork = saturatedWork(retiredClauseWork,
                saturatedWork(decisions, stopped.stats.search.propagationWork.sum.toLong()))
            retiredStats = retiredStats.mergedWith(stopped.stats)
            generation = null
            root = null
            note("OpeningStopped")
            return
        } finally {
            note("OpeningNanos", openingStart.elapsedNow().inWholeNanoseconds)
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
                note("SourceCheckWork", literals.size + 1L)
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
        try {
            search.close()
        } finally {
            retiredClauseWork = saturatedWork(retiredClauseWork, search.clauseWork)
            retiredStats = retiredStats.mergedWith(search.stats)
            consumer = null
            generation = null
            candidate = null
            checkedFactors = 0
            root = null
            note("Retired")
            note("RetirementNanos", start.elapsedNow().inWholeNanoseconds)
        }
    }

    private fun finish(result: SolveResult): SolveResult {
        close()
        val merged = when (result) {
            is SolveResult.Sat -> result.copy(stats = stats)
            is SolveResult.Unsat -> result.copy(stats = stats)
            is SolveResult.Unknown -> result.copy(stats = stats)
        }
        return merged.withSourceSemantics(solver.problem.semantics).also { done = it }
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
                pendingBinary = null
                attemptedBinaryPairs = longArrayOf()
                retainedStats = stats
                closed = true
            }
        }
    }

    companion object {
        fun eligible(problem: BakedProblem, params: BacktrackParams): Boolean =
            params.privateRebuildWork > 0L && problem.numIntVars == 0 && problem.numRealVars == 0 &&
                problem.rootDeductions is PropagationResult.Implied &&
                params.assumptions.isEmpty && params.assumptions.deductions.isEmpty &&
                params.componentFactory == null && params.lpConfig in listOf(null, LpConfig.OFF) &&
                params.lpPlan == LpPlan()
    }
}
