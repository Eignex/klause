package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisArithmeticException
import com.eignex.klause.simplex.basis.BasisOperationWork
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.util.Cancellation

internal data class LpScopedMetrics(
    val editAttempts: Long,
    val editSuccesses: Long,
    val preparationAttempts: Long,
    val preparationSuccesses: Long,
    val preparationWork: Long,
    val preparationRefactorizations: Long,
    val createdOwners: Long,
    val closedOwners: Long,
    val peakOwners: Long,
    val retainedRows: Int,
    val activeRows: Int,
    val appendBasisWork: Long,
    val appendUnknownWork: Long,
    // [preparationWork] charges each owner's construction at its reserved bound, the allowance preparation spends;
    // the ledger swaps that bound for the work the owners reported building their scaling views.
    val reservedConstructionWork: Long = 0L,
    val constructionWork: Long = 0L,
) {
    val editDeclines: Long get() = editAttempts - editSuccesses
    val preparationDeclines: Long get() = preparationAttempts - preparationSuccesses
    val currentOwners: Long get() = createdOwners - closedOwners
}

@Suppress("TooGenericExceptionCaught") // Ownership boundaries preserve arbitrary primary and cleanup failures.
internal class LpScopedSolver(
    initial: LpExactState,
    private val cancellation: Cancellation = Cancellation.Never,
    private val context: LpSolveContext = LpSolveContext.Production,
    private val refactorUpdateLimit: Int = DEFAULT_REFACTOR_UPDATE_LIMIT,
    private var iterationLimit: Int = 0,
    private var workLimit: Long = 0L,
    private val trackDegeneracy: Boolean = false,
    private val maxRetainedRows: Int = Int.MAX_VALUE,
    private val pricing: LpPricingOptions = LpPricingOptions(),
    private val componentSplit: Boolean = false,
) : AutoCloseable {
    private val continuationCache = LpExactContinuationCache()
    private val checkedResults = LpCounterResults()
    internal val refinementCache = LpRefinementCache()
    internal val pointRecoveryCache = LpRefinementCache()
    private var trail = LpBoundTrail(initial)
    private var solver: RetainedLpSolver? = null
    private class PendingFloat(val cursor: LpSolveCursor<FloatLpResult?>, var allowance: LpFloatAllowance) {
        var reported = LpSolveMetrics()
    }
    private var pendingFloat: PendingFloat? = null
    val floatPaused: Boolean get() = pendingFloat != null
    val floatFundingStopped: Boolean get() = floatPaused &&
        (lastFloatTermination == LpFloatTermination.WORK || lastFloatTermination == LpFloatTermination.PIVOTS)
    private var closed = false
    private var workingActive = false
    var lastWorkingMetrics: LpWorkingMetrics? = null
        private set
    private var editAttempts = 0L
    private var editSuccesses = 0L
    private var preparationAttempts = 0L
    private var preparationSuccesses = 0L
    private var preparationWork = 0L
    private var preparationRefactorizations = 0L
    private var createdOwners = 0L
    private var closedOwners = 0L
    private var peakOwners = 0L
    private var appendBasisWork = 0L
    private var appendUnknownWork = 0L
    private var reservedConstructionWork = 0L
    private var constructionWork = 0L
    private var pendingAppendSolveWork: Long? = null
    private var pendingAppendSolveWorkComplete = false
    private var pendingAppendSolve = false
    private var pendingAppendSolveOwner: RetainedLpSolver? = null

    val state: LpExactState get() = trail.state
    internal val proofPolicy: LpCertificationPolicy get() = context.certificationPolicy
    internal val cancellationToken: Cancellation get() = cancellation
    internal val checkedResultStorageDeclined: Boolean get() = checkedResults.storageDeclined
    var lastResult: CertifiedLpResult? = null
        private set
    var lastMetrics: LpSolveMetrics = LpSolveMetrics()
        private set
    var lastFloatTermination: LpFloatTermination? = null
        private set
    val metrics: LpScopedMetrics get() = LpScopedMetrics(
        editAttempts, editSuccesses, preparationAttempts, preparationSuccesses,
        preparationWork, preparationRefactorizations, createdOwners, closedOwners, peakOwners,
        state.rows.size, state.rows.activeCount,
        appendBasisWork, appendUnknownWork, reservedConstructionWork, constructionWork,
    )

    init {
        require(maxRetainedRows >= 0 && refactorUpdateLimit > 0 && iterationLimit >= 0 && workLimit >= 0L)
    }

    fun prepare(token: Cancellation = cancellation): Boolean {
        requireAvailable()
        if (closed || token() || state.model.m > maxRetainedRows) return false
        if (solver != null) return true
        solver = prepared(state, token)?.first ?: return false
        return true
    }

    fun push(token: Cancellation = cancellation): Boolean = edit(token) { it.push(token) }

    fun assertBound(
        column: Int,
        upper: Boolean,
        side: ExactLpSide,
        witness: Long,
        token: Cancellation = cancellation,
    ): Boolean = edit(token) { it.assertBound(column, upper, side, witness, token) }

    @Suppress("TooGenericExceptionCaught") // Numerical refresh can retire factors before a cleanup failure.
    fun assertBounds(assertions: List<LpBoundAssertion>, token: Cancellation = cancellation): LpBoundBatchResult {
        requireAvailable()
        if (closed || token()) return LpBoundBatchResult.Declined(0)
        val next = LpBoundTrail(state)
        val result = next.assertBounds(assertions, token)
        editAttempts += result.count
        if (result is LpBoundBatchResult.Declined || next.state === state) return result
        discardFloatProgress()
        try {
            val current = solver
            if (current != null && !current.adopt(next.state, token)) return LpBoundBatchResult.Declined(result.count)
            if (current == null && token()) return LpBoundBatchResult.Declined(result.count)
        } catch (primary: Throwable) {
            try {
                close()
            } catch (cleanup: Throwable) {
                primary.addSuppressed(cleanup)
            }
            throw primary
        }
        publish(next)
        editSuccesses += result.count - 1L
        return result
    }

    fun pop(targetDepth: Int, token: Cancellation = cancellation): Boolean = edit(token) { it.pop(targetDepth, token) }

    fun resetRoot(token: Cancellation = cancellation): Boolean = edit(token) { it.resetRoot(token) }

    fun resetRoot(initial: LpExactState, token: Cancellation = cancellation): Boolean {
        requireAvailable()
        if (initial.depth != 0) return false
        return adopt(initial, token)
    }

    fun adopt(initial: LpExactState, token: Cancellation = cancellation): Boolean {
        requireAvailable()
        editAttempts++
        if (closed || token() || !state.sameMatrix(initial)) return false
        if (initial === state && pendingFloat != null) {
            editSuccesses++
            return true
        }
        if (initial !== state) discardFloatProgress()
        val current = solver
        if (current != null && !current.adopt(initial, token)) return false
        if (current == null && token()) return false
        publish(LpBoundTrail(initial))
        return true
    }

    fun replaceObjective(objective: ExactLpObjective, token: Cancellation = cancellation): Boolean =
        edit(token) { it.replaceObjective(objective, token) }

    fun recenter(origins: List<ExactLpNumber>, token: Cancellation = cancellation): Boolean =
        edit(token) { it.recenter(origins, token) }

    fun append(row: LpScopedRow, scoped: Boolean, token: Cancellation = cancellation): Boolean = edit(token, true) {
        state.model.m < maxRetainedRows && it.append(row, scoped, token)
    }

    fun append(
        columns: List<LpStructuralColumn>,
        rows: List<LpScopedRow>,
        scoped: Boolean,
        token: Cancellation = cancellation,
        permanentRows: Set<Long> = emptySet(),
    ): Boolean = edit(token, true) {
        rows.size <= maxRetainedRows - state.model.m && it.append(columns, rows, scoped, token, permanentRows)
    }

    fun deactivate(id: Long, token: Cancellation = cancellation): Boolean = edit(token) { it.deactivate(id, token) }

    fun suspend(ids: Set<Long>, token: Cancellation = cancellation): Boolean = edit(token) { it.suspend(ids, token) }

    fun replaceRows(
        retired: Set<Long>,
        columns: List<LpStructuralColumn>,
        rows: List<LpScopedRow>,
        scoped: Boolean,
        token: Cancellation = cancellation,
        permanentRows: Set<Long> = emptySet(),
        objective: ExactLpObjective? = null,
        assertions: List<LpBoundAssertion> = emptyList(),
    ): Boolean = edit(token, rows.isNotEmpty() || columns.isNotEmpty()) {
        if (rows.size > maxRetainedRows - state.model.m) return@edit false
        it.replaceRows(retired, columns, rows, scoped, token, permanentRows, objective, assertions)
    }

    fun compact(token: Cancellation = cancellation): Boolean = compact(LpLayoutRemap(state.model.n, state.rows), token)

    fun compact(remap: LpLayoutRemap, token: Cancellation = cancellation): Boolean =
        edit(token, compaction = remap) { it.compact(remap, token) }

    val continuationLimits get() = ExactContinuationLimits(
        startArbitraryPrecision = context.startArbitraryPrecision,
        preserveOverflowCheckpoint = context.preserveOverflowCheckpoint,
    )

    val basisLifecycleWork: BasisOperationWork? get() = (solver as? PersistentLpSolver)?.basisLifecycleWork

    @Suppress("TooGenericExceptionCaught")
    fun solve(
        warm: Basis? = null,
        token: Cancellation = cancellation,
        counterResults: LpCounterResults? = null,
        continuationLimits: ExactContinuationLimits = this.continuationLimits,
        fullContinuation: Boolean = true,
        observer: LpCertificationObserver? = null,
        refinementLimits: LpRefinementLimits = LpRefinementLimits(),
        sparsePointRecovery: Boolean = false,
        claim: LpClaim = LpClaim.ATTAINED_OPTIMUM,
    ): CertifiedLpResult? {
        val attempt = solveFloat(warm, token) ?: return null
        val certified = certifyLpResult(
            requireNotNull(state.ownerWorkingModel()),
            attempt.first,
            attempt.second,
            token,
            policy = context.certificationPolicy,
            counterResults = counterResults ?: checkedResults,
            continuationCache = continuationCache,
            continuationLimits = continuationLimits,
            fullContinuation = fullContinuation,
            observer = observer,
            sparsePointRecovery = sparsePointRecovery,
            claim = claim,
            refinement = LpRefinementRequest(
                this,
                refinementCache,
                refinementLimits,
                workLimit,
                iterationLimit,
                preparationWork,
                lastMetrics,
                onBasisVerification = context.onRefinementBasisVerification,
            ),
        )
        if (token()) return null
        lastResult = certified
        return certified
    }

    fun solveFloat(
        warm: Basis? = null,
        token: Cancellation = cancellation,
        allowance: LpFloatAllowance? = null,
    ): Pair<LpSolver, FloatLpResult?>? {
        require(warm == null || allowance == null) { "an explicit allowance requires retained factors" }
        requireAvailable()
        discardFloatProgress()
        lastResult = null
        lastMetrics = LpSolveMetrics()
        lastFloatTermination = null
        if (!prepare(token)) return null
        val current = requireNotNull(solver)
        var failure: Throwable? = null
        var solveStarted = false
        val result = try {
            try {
                if (!current.adopt(state, token)) return null
                solveStarted = true
                val queryAllowance = allowance ?: if (current is ComponentLpSolver) {
                    LpFloatAllowance(workLimit, iterationLimit)
                } else null
                (if (warm == null) current.resolveBounds(
                    queryAllowance,
                ) else current.solve(warm)).also {
                    lastFloatTermination = current.lastTermination
                }
            } catch (primary: Throwable) {
                failure = primary
                throw primary
            } finally {
                completeSolveCompletion(current, failure, solveStarted)
            }
        } catch (primary: Throwable) {
            lastFloatTermination = null
            throw primary
        }
        if (token()) {
            lastFloatTermination = LpFloatTermination.CANCELLED
            return null
        }
        return current to result
    }

    fun advanceFloat(
        quantum: Long,
        allowance: LpFloatAllowance,
        token: Cancellation = cancellation,
        retainFundingStop: Boolean = false,
    ): Pair<LpSolver, FloatLpResult?>? {
        requireAvailable()
        require(quantum > 0L && allowance.work > 0L)
        lastResult = null
        lastMetrics = LpSolveMetrics()
        lastFloatTermination = null
        if (closed || token() || cancellation()) {
            discardFloatProgress()
            return null
        }
        if (retainFundingStop) {
            if (workLimit > 0L) workLimit = maxOf(workLimit, allowance.work)
            if (iterationLimit > 0) iterationLimit = maxOf(iterationLimit, allowance.iterations)
        }
        if (!prepare(token)) {
            discardFloatProgress()
            return null
        }
        val current = requireNotNull(solver)
        if (current !is ResumableLpSolver) {
            return solveFloat(token = token, allowance = allowance.copy(work = minOf(quantum, allowance.work)))
        }
        var pending = pendingFloat
        if (retainFundingStop && pending != null && pending.allowance != allowance) {
            if (!current.promoteSolve(allowance)) {
                discardFloatProgress()
                return advanceFloat(quantum, allowance, token, retainFundingStop)
            }
            pending.allowance = allowance
        }
        if (pending == null) {
            if (!current.adopt(state, token)) {
                solver = null
                closeOwner(current)
                return null
            }
            pending = PendingFloat(current.openSolve(allowance, retainFundingStop), allowance)
            pendingFloat = pending
        }
        try {
            val step = pending.cursor.advance(quantum)
            val actual = current.lastMetrics
            lastMetrics = actual - pending.reported
            pending.reported = actual
            if (step is LpSolveStep.Paused) {
                lastFloatTermination = current.lastTermination
                if (token() || cancellation()) discardFloatProgress()
                return null
            }
            pendingFloat = null
            pending.cursor.close()
            lastFloatTermination = current.lastTermination
            recordPendingAppendSolve(current)
            if (token() || cancellation()) {
                lastFloatTermination = LpFloatTermination.CANCELLED
                return null
            }
            return current to (step as LpSolveStep.Completed<FloatLpResult?>).value
        } catch (primary: Throwable) {
            try {
                discardFloatProgress()
            } catch (cleanup: Throwable) {
                if (cleanup !== primary) primary.addSuppressed(cleanup)
            }
            throw primary
        }
    }

    private fun discardFloatProgress() {
        val pending = pendingFloat ?: return
        pendingFloat = null
        val current = solver
        solver = null
        var failure: Throwable? = null
        try {
            pending.cursor.close()
            if (current != null) recordPendingAppendSolve(current)
        } catch (primary: Throwable) {
            failure = primary
            throw primary
        } finally {
            if (current != null) closeOwner(current, failure)
        }
    }

    private inline fun edit(
        token: Cancellation,
        append: Boolean = false,
        compaction: LpLayoutRemap? = null,
        change: (LpBoundTrail) -> Boolean,
    ): Boolean {
        requireAvailable()
        editAttempts++
        if (closed || token()) return false
        val next = LpBoundTrail(state)
        if (!change(next)) return false
        if (next.state === state) {
            editSuccesses++
            return true
        }
        discardFloatProgress()
        if (next.state.matrixRevision != state.matrixRevision) {
            return replace(next, token, append, compaction)
        }
        val current = solver
        if (current != null && !current.adopt(next.state, token)) return false
        if (current == null && token()) return false
        publish(next)
        return true
    }

    internal fun requireAvailable() {
        check(!workingActive) { "source owner is suspended by a working scope" }
    }

    fun <T> withWorkingModel(
        working: LpWorkingModel,
        token: Cancellation = cancellation,
        allowance: LpFloatAllowance? = null,
        block: (LpWorkingScope) -> T,
    ): T {
        requireAvailable()
        check(!closed) { "scoped solver is closed" }
        discardFloatProgress()
        require(working.source === state) { "working model belongs to another source state" }
        require(allowance == null || (allowance.work > 0L && allowance.iterations > 0))
        val childWork = allowance?.work?.let { if (workLimit > 0L) minOf(it, workLimit) else it } ?: workLimit
        val childIterations = allowance?.iterations?.let {
            if (iterationLimit > 0) minOf(it, iterationLimit) else it
        } ?: iterationLimit
        val scopeToken = cancellation or token
        val child = LpScopedSolver(
            working.state, scopeToken, context, refactorUpdateLimit, childIterations, childWork,
            trackDegeneracy, maxRetainedRows, pricing, componentSplit,
        )
        val scope = LpWorkingScope(working, child, scopeToken)
        workingActive = true
        lastResult = null
        lastWorkingMetrics = null
        var failure: Throwable? = null
        try {
            return block(scope)
        } catch (primary: Throwable) {
            failure = primary
            throw primary
        } finally {
            try {
                scope.close(failure)
            } finally {
                lastWorkingMetrics = scope.metrics
                lastResult = null
                workingActive = false
            }
        }
    }

    // The caller supplies the objective-independent region; root depth alone is not provenance.
    fun replaceObjective(
        objective: ExactLpObjective,
        baseRegion: LpExactState,
        token: Cancellation = cancellation,
    ): Boolean {
        requireAvailable()
        if (closed || token() || baseRegion.depth != 0) return false
        val next = LpBoundTrail(baseRegion)
        if (!next.replaceObjective(objective, token)) return false
        if (state.sameMatrix(next.state)) return resetRoot(next.state, token)
        discardFloatProgress()
        editAttempts++
        val replacement = prepared(next.state, token, allowComponents = false) ?: return false
        var published = false
        var failure: Throwable? = null
        try {
            if (token()) return false
            val old = solver
            if (old != null) recordPendingAppendSolve(old)
            solver = replacement.first
            clearPendingAppendSolve()
            publish(next)
            published = true
            if (old != null) closeOwner(old)
            return true
        } catch (primary: Throwable) {
            failure = primary
            throw primary
        } finally {
            if (!published) closeOwner(replacement.first, failure)
        }
    }

    private fun publish(next: LpBoundTrail) {
        trail = next
        lastResult = null
        lastMetrics = LpSolveMetrics()
        editSuccesses++
    }

    private fun replace(
        next: LpBoundTrail,
        token: Cancellation,
        append: Boolean,
        compaction: LpLayoutRemap? = null,
    ): Boolean {
        var replacementWorkLimit = workLimit
        val components = solver as? ComponentLpSolver
        if (append && compaction == null && components != null) {
            preparationAttempts++
            val adopted = appendComponentRows(next.state, components, token)
            if (adopted) {
                preparationSuccesses++
                publish(next)
                return true
            }
            solver = null
            closeOwner(components)
            if (workLimit > 0L) {
                if (components.lastMetrics.workOps >= workLimit) return false
                replacementWorkLimit -= components.lastMetrics.workOps
            }
        }
        if (append) solver?.let { recordPendingAppendSolve(it) }
        val warm = if (append) {
            (solver as? PersistentLpSolver)?.retainedBasis()?.extended(state.model, next.state.model)
        } else null
        val expected = if (compaction != null) {
            // Seat the disappearing logicals in an isolated old-size owner before deleting their slots.
            val staging = prepared(state, token) ?: return false
            var failure: Throwable? = null
            try {
                staging.second.basicVars.map { compaction.column(it) }.filter { it >= 0 }.toIntArray()
            } catch (primary: Throwable) {
                failure = primary
                throw primary
            } finally {
                closeOwner(staging.first, failure)
            }
        } else {
            IntArray(next.state.model.m) { next.state.model.n + it }
        }
        val replacement = prepared(next.state, token, append, warm, replacementWorkLimit,
            allowComponents = false) ?: return false
        var published = false
        var failure: Throwable? = null
        try {
            val replacementWork = if (append) appendBasisLifecycleWork(replacement.first) else null
            if (append) recordAppendWork(replacementWork)
            val pendingWork = basisWorkUnits(replacementWork)
            if ((warm == null && !replacement.second.basicVars.contentEquals(expected)) || token()) return false
            val old = solver
            if (!append && old != null) recordPendingAppendSolve(old)
            solver = replacement.first
            if (append) {
                pendingAppendSolveWork = pendingWork
                pendingAppendSolveWorkComplete = basisWorkComplete(replacementWork)
                pendingAppendSolve = true
                pendingAppendSolveOwner = replacement.first
            } else {
                clearPendingAppendSolve()
            }
            publish(next)
            published = true
            if (old != null) closeOwner(old)
            return true
        } catch (primary: Throwable) {
            failure = primary
            throw primary
        } finally {
            if (!published) closeOwner(replacement.first, failure)
        }
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun appendComponentRows(
        next: LpExactState,
        components: ComponentLpSolver,
        token: Cancellation,
    ): Boolean = try {
        components.appendLocalRows(next, token, workLimit) { part, childToken, childWork ->
            newPersistentLpSolver(part, childToken, refactorUpdateLimit, iterationLimit,
                childWork, trackDegeneracy, context.engineFactory, componentPricing(part, next.model.n))
        }
    } catch (primary: Throwable) {
        solver = null
        closeOwner(components, primary)
        throw primary
    } finally {
        preparationWork = saturated(preparationWork, components.lastMetrics.workOps)
        reservedConstructionWork = saturated(reservedConstructionWork, components.lastReservedConstructionWork)
        constructionWork = saturated(constructionWork, components.lastMetrics.preparationOps)
        preparationRefactorizations += components.lastRefactorizations
    }

    private fun recordAppendWork(work: Long?, complete: Boolean = work != null) {
        var aggregateComplete = true
        if (work != null) {
            appendBasisWork = saturatingAdd(appendBasisWork, work)
            aggregateComplete = appendBasisWork != Long.MAX_VALUE
        }
        if (!complete || work == null || !aggregateComplete) {
            appendUnknownWork = saturatingAdd(appendUnknownWork, 1)
        }
    }

    private fun appendBasisLifecycleWork(owner: RetainedLpSolver) = try {
        (owner as? PersistentLpSolver)?.basisLifecycleWork
    } catch (telemetry: Throwable) {
        appendUnknownWork = saturatingAdd(appendUnknownWork, 1)
        throw telemetry
    }

    private fun recordAppendWork(work: com.eignex.klause.simplex.basis.BasisOperationWork?) {
        recordAppendWork(basisWorkUnits(work), basisWorkComplete(work))
    }

    private fun basisWorkUnits(work: com.eignex.klause.simplex.basis.BasisOperationWork?): Long? =
        work?.takeUnless { it.saturated }?.units

    private fun basisWorkComplete(work: com.eignex.klause.simplex.basis.BasisOperationWork?): Boolean =
        work != null && work.complete && !work.saturated

    @Suppress("TooGenericExceptionCaught")
    private fun recordPendingAppendSolve(current: RetainedLpSolver) {
        if (!pendingAppendSolve) return
        if (current !== pendingAppendSolveOwner) {
            appendUnknownWork = saturatingAdd(appendUnknownWork, 1)
            clearPendingAppendSolve()
            return
        }
        val before = pendingAppendSolveWork
        val beforeComplete = pendingAppendSolveWorkComplete
        try {
            val afterWork = (current as? PersistentLpSolver)?.basisLifecycleWork
            val after = basisWorkUnits(afterWork)
            val delta = if (before != null && after != null && after >= before) after - before else null
            recordAppendWork(delta, beforeComplete && basisWorkComplete(afterWork))
        } catch (failure: Throwable) {
            appendUnknownWork = saturatingAdd(appendUnknownWork, 1)
            throw failure
        } finally {
            clearPendingAppendSolve()
        }
    }

    private fun clearPendingAppendSolve() {
        pendingAppendSolve = false
        pendingAppendSolveOwner = null
        pendingAppendSolveWork = null
        pendingAppendSolveWorkComplete = false
    }

    @Suppress("TooGenericExceptionCaught")
    private fun completeSolveCompletion(current: RetainedLpSolver, primary: Throwable?, solveStarted: Boolean) {
        try {
            recordSolveCompletion(current, primary, solveStarted)
        } catch (telemetry: Throwable) {
            if (primary == null) throw telemetry
            if (telemetry !== primary) primary.addSuppressed(telemetry)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun recordSolveCompletion(current: RetainedLpSolver, primary: Throwable?, solveStarted: Boolean) {
        var failure = primary
        if (solveStarted) {
            try {
                lastMetrics = current.lastMetrics
            } catch (telemetry: Throwable) {
                if (failure == null) {
                    failure = telemetry
                } else if (failure !== telemetry) {
                    failure.addSuppressed(telemetry)
                }
            }
        }
        try {
            recordPendingAppendSolve(current)
        } catch (telemetry: Throwable) {
            if (failure == null) {
                failure = telemetry
            } else if (failure !== telemetry) {
                failure.addSuppressed(telemetry)
            }
        }
        if (primary == null && failure != null) throw failure
    }

    private fun prepared(
        next: LpExactState,
        token: Cancellation,
        recordRejectedAppendWork: Boolean = false,
        warm: Basis? = null,
        workBudget: Long = workLimit,
        allowComponents: Boolean = componentSplit,
    ): Pair<RetainedLpSolver, Basis>? {
        preparationAttempts++
        var candidate: RetainedLpSolver? = null
        var accepted = false
        var failure: Throwable? = null
        try {
            if (token() || next.model.m > maxRetainedRows) return null
            val projection = LpProjectionMeter(
                workLimit = if (workBudget > 0L) workBudget else Long.MAX_VALUE,
                cancellation = token,
            )
            var constructionBound = 0L
            val working = try {
                next.ownerWorkingModel(projection)?.also {
                    // The numerical owner refreshes its scaled vectors while adopting this state.
                    projection.reserveVectors(next.model)
                    // Building it scales the matrix. Preparation caps take precedence, so the bound is reserved from
                    // this preparation's allowance and charged as spent; the ledger records the reported work.
                    constructionBound = LpScalingView.constructionWorkBound(it)
                    projection.reserve(constructionBound, 0L, matrix = true)
                }
            } finally {
                preparationWork = saturated(preparationWork, projection.work)
                reservedConstructionWork = saturated(reservedConstructionWork, constructionBound)
            } ?: return null
            val remainingWork = if (workBudget > 0L) workBudget - projection.work else 0L
            if (workBudget > 0L && remainingWork <= 0L) return null
            candidate = if (allowComponents && warm == null) {
                componentLpSolverOrNull(
                    working, token,
                    { part, childToken ->
                        newPersistentLpSolver(part, childToken, refactorUpdateLimit, iterationLimit,
                            remainingWork, trackDegeneracy, context.engineFactory, componentPricing(part, working.n))
                    },
                    ::ComponentLpSolver,
                )
            } else null
            if (candidate == null) candidate = newPersistentLpSolver(
                working,
                token,
                refactorUpdateLimit,
                iterationLimit,
                remainingWork,
                trackDegeneracy,
                context.engineFactory,
                pricing,
            )
            createdOwners++
            peakOwners = maxOf(peakOwners, createdOwners - closedOwners)
            if (!candidate.adopt(next, token)) return null
            val basis = try {
                when (val current = candidate) {
                    is PersistentLpSolver -> if (warm == null) current.prepareLogicals(token) else
                        current.prepareBasis(warm, token)
                    is ComponentLpSolver -> current.prepareLogicals(token, remainingWork)
                    else -> null
                }
            } finally {
                preparationWork = saturated(preparationWork, candidate.lastMetrics.workOps)
                constructionWork = saturated(constructionWork, candidate.lastMetrics.preparationOps)
                preparationRefactorizations += candidate.lastRefactorizations
            } ?: return null
            if (!basis.validFor(next.model) || (warm == null &&
                    (basis.basicVars.indices.any { basis.basicVars[it] != next.model.n + it } ||
                        basis.status.indices.any { (basis.status[it] == VarStatus.BASIC) != (it >= next.model.n) })) ||
                token()
            ) {
                return null
            }
            preparationSuccesses++
            accepted = true
            return candidate to basis
        } catch (_: LpProjectionStop) {
            return null
        } catch (_: BasisArithmeticException) {
            return null
        } catch (primary: Throwable) {
            failure = primary
            throw primary
        } finally {
            if (!accepted && candidate != null) {
                closeRejectedCandidate(candidate, recordRejectedAppendWork, failure)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun closeRejectedCandidate(candidate: RetainedLpSolver, recordWork: Boolean, primary: Throwable?) {
        var failure = primary
        if (recordWork) {
            try {
                recordAppendWork((candidate as? PersistentLpSolver)?.basisLifecycleWork)
            } catch (telemetry: Throwable) {
                appendUnknownWork = saturatingAdd(appendUnknownWork, 1)
                if (failure == null) failure = telemetry else failure.addSuppressed(telemetry)
            }
        }
        closeOwner(candidate, failure)
        if (primary == null && failure != null) throw failure
    }

    private fun closeOwner(owner: RetainedLpSolver, primary: Throwable? = null) {
        try {
            owner.close()
        } catch (failure: Throwable) {
            if (primary == null) throw failure
            primary.addSuppressed(failure)
        } finally {
            closedOwners++
        }
    }

    private fun componentPricing(part: LpModel, columns: Int): LpPricingOptions {
        return pricing.componentShare(part.n, columns)
    }

    override fun close() {
        requireAvailable()
        if (closed) return
        discardFloatProgress()
        closed = true
        continuationCache.clear()
        checkedResults.declineStorage()
        lastResult = null
        val current = solver
        solver = null
        if (current != null) closeOwner(current)
    }
}

private fun saturated(total: Long, add: Long): Long = if (total > Long.MAX_VALUE - add) Long.MAX_VALUE else total + add
