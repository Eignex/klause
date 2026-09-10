package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisArithmeticException
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
    val appendReplacementAttempts: Long,
    val appendTransfers: Long,
    val appendIntendedFreshBuilds: Long,
    val appendFallbacks: Long,
    val appendBasisWork: Long,
    val appendUnknownWork: Long,
    val lastAppendDecline: LpAppendTransferDecline?,
) {
    val editDeclines: Long get() = editAttempts - editSuccesses
    val preparationDeclines: Long get() = preparationAttempts - preparationSuccesses
    val currentOwners: Long get() = createdOwners - closedOwners
}

internal enum class LpAppendSelection {
    PRODUCTION_FRESH,
    CANDIDATE,
    FORCE_TRANSFER,
    FRESH_INTENDED,
}

@Suppress("TooGenericExceptionCaught") // Ownership boundaries preserve arbitrary primary and cleanup failures.
internal class LpScopedSolver(
    initial: LpExactState,
    private val cancellation: Cancellation = Cancellation.Never,
    private val context: LpSolveContext = LpSolveContext.Production,
    private val refactorUpdateLimit: Int = DEFAULT_REFACTOR_UPDATE_LIMIT,
    private val iterationLimit: Int = 0,
    private val workLimit: Long = 0L,
    private val trackDegeneracy: Boolean = false,
    private val maxRetainedRows: Int = Int.MAX_VALUE,
    private val appendSelection: LpAppendSelection = LpAppendSelection.PRODUCTION_FRESH,
) : AutoCloseable {
    private var trail = LpBoundTrail(initial)
    private var solver: PersistentLpSolver? = null
    private var closed = false
    private var editAttempts = 0L
    private var editSuccesses = 0L
    private var preparationAttempts = 0L
    private var preparationSuccesses = 0L
    private var preparationWork = 0L
    private var preparationRefactorizations = 0L
    private var createdOwners = 0L
    private var closedOwners = 0L
    private var peakOwners = 0L
    private var appendReplacementAttempts = 0L
    private var appendTransfers = 0L
    private var appendIntendedFreshBuilds = 0L
    private var appendFallbacks = 0L
    private var appendBasisWork = 0L
    private var appendUnknownWork = 0L
    private var lastAppendDecline: LpAppendTransferDecline? = null
    private var pendingAppendSolveWork: Long? = null
    private var pendingAppendSolve = false

    val state: LpExactState get() = trail.state
    var lastResult: CertifiedLpResult? = null
        private set
    var lastMetrics: LpSolveMetrics = LpSolveMetrics()
        private set
    val metrics: LpScopedMetrics get() = LpScopedMetrics(
        editAttempts, editSuccesses, preparationAttempts, preparationSuccesses,
        preparationWork, preparationRefactorizations, createdOwners, closedOwners, peakOwners,
        state.rows.size, state.rows.activeCount,
        appendReplacementAttempts, appendTransfers, appendIntendedFreshBuilds, appendFallbacks,
        appendBasisWork, appendUnknownWork, lastAppendDecline,
    )

    init {
        require(maxRetainedRows >= 0 && refactorUpdateLimit > 0 && iterationLimit >= 0 && workLimit >= 0L)
    }

    fun prepare(token: Cancellation = cancellation): Boolean {
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

    fun pop(targetDepth: Int, token: Cancellation = cancellation): Boolean = edit(token) { it.pop(targetDepth, token) }

    fun replaceObjective(objective: ExactLpObjective, token: Cancellation = cancellation): Boolean =
        edit(token) { it.replaceObjective(objective, token) }

    fun recenter(origins: List<ExactLpNumber>, token: Cancellation = cancellation): Boolean =
        edit(token) { it.recenter(origins, token) }

    fun append(row: LpScopedRow, scoped: Boolean, token: Cancellation = cancellation): Boolean = edit(token, true) {
        state.model.m < maxRetainedRows && it.append(row, scoped, token)
    }

    fun deactivate(id: Long, token: Cancellation = cancellation): Boolean = edit(token) { it.deactivate(id, token) }

    fun compact(token: Cancellation = cancellation): Boolean = edit(token) { it.compact(token) }

    @Suppress("TooGenericExceptionCaught")
    fun solve(
        warm: Basis? = null,
        token: Cancellation = cancellation,
        counterResults: LpCounterResults? = null,
    ): CertifiedLpResult? {
        lastResult = null
        lastMetrics = LpSolveMetrics()
        if (!prepare(token)) return null
        val current = requireNotNull(solver)
        var failure: Throwable? = null
        val result = try {
            if (!current.adopt(state, token)) return null
            if (warm == null) current.resolveBounds() else current.solve(warm)
        } catch (primary: Throwable) {
            failure = primary
            throw primary
        } finally {
            recordSolveCompletion(current, failure)
        }
        if (token()) return null
        val certified = certifyLpResult(
            requireNotNull(state.toWorkingModel()),
            current,
            result,
            token,
            policy = context.certificationPolicy,
            counterResults = counterResults,
        )
        if (token()) return null
        lastResult = certified
        return certified
    }

    private inline fun edit(token: Cancellation, append: Boolean = false, change: (LpBoundTrail) -> Boolean): Boolean {
        editAttempts++
        if (closed || token()) return false
        val next = LpBoundTrail(state)
        if (!change(next)) return false
        if (next.state === state) {
            editSuccesses++
            return true
        }
        if (next.state.matrixRevision != state.matrixRevision) {
            return replace(next, token, append)
        }
        val current = solver
        if (current != null && !current.adopt(next.state, token)) return false
        if (current == null && token()) return false
        publish(next)
        return true
    }

    private fun publish(next: LpBoundTrail) {
        trail = next
        lastResult = null
        lastMetrics = LpSolveMetrics()
        editSuccesses++
    }

    private fun replace(next: LpBoundTrail, token: Cancellation, append: Boolean): Boolean {
        val selected = if (append) appendReplacement(next.state, token) else null
        val expected = selected?.second?.basicVars ?: if (next.state.model.m < state.model.m) {
            // Seat the disappearing logicals in an isolated old-size owner before deleting their slots.
            val staging = prepared(state, token) ?: return false
            var failure: Throwable? = null
            try {
                val remap = LpRowRemap(state.model.n, state.rows)
                staging.second.basicVars.map { remap.column(it) }.filter { it >= 0 }.toIntArray()
            } catch (primary: Throwable) {
                failure = primary
                throw primary
            } finally {
                closeOwner(staging.first, failure)
            }
        } else {
            IntArray(next.state.model.m) { next.state.model.n + it }
        }
        val replacement = selected ?: prepared(next.state, token, append) ?: return false
        var published = false
        var failure: Throwable? = null
        try {
            if (!replacement.second.basicVars.contentEquals(expected) || token()) return false
            val replacementWork = if (append) replacement.first.basisLifecycleWork else null
            if (append && selected == null) recordAppendWork(replacementWork)
            val pendingWork = knownBasisWork(replacementWork)
            val old = solver
            solver = replacement.first
            if (append) {
                pendingAppendSolveWork = pendingWork
                pendingAppendSolve = true
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

    private fun appendReplacement(next: LpExactState, token: Cancellation): Pair<PersistentLpSolver, Basis>? {
        val current = solver ?: return null
        val mode = when (appendSelection) {
            LpAppendSelection.PRODUCTION_FRESH -> return null

            LpAppendSelection.CANDIDATE -> {
                if (!appendCandidate(state, next, current)) return null
                LpAppendReplacementMode.TRANSFER
            }

            LpAppendSelection.FORCE_TRANSFER -> LpAppendReplacementMode.TRANSFER

            LpAppendSelection.FRESH_INTENDED -> LpAppendReplacementMode.FRESH_INTENDED
        }
        appendReplacementAttempts++
        lastAppendDecline = null
        val rowMap = IntArray(state.rows.size) { old -> next.rows.index(state.rows.row(old).id) }
        val columnMap = IntArray(state.model.numVars) { old ->
            if (old < state.model.n) old else next.model.n + rowMap[old - state.model.n]
        }
        val attempt = current.appendReplacement(next, rowMap, columnMap, mode, token)
        recordAppendWork(attempt.basisWork)
        val accepted = attempt.replacement
        if (accepted == null) {
            lastAppendDecline = attempt.decline ?: LpAppendTransferDecline.UNSUPPORTED
            appendFallbacks++
            return null
        }
        createdOwners++
        peakOwners = maxOf(peakOwners, createdOwners - closedOwners)
        if (accepted.transferred) appendTransfers++ else appendIntendedFreshBuilds++
        return accepted.solver to accepted.basis
    }

    private fun appendCandidate(current: LpExactState, next: LpExactState, owner: PersistentLpSolver): Boolean {
        val appendedRows = next.model.m - current.model.m
        if (
            appendedRows < 1 || current.model.m < 16 || current.model.n <= 0 || !owner.appendTransferReady ||
            knownBasisWork(owner.basisLifecycleWork) == null
        ) {
            return false
        }
        if (current.rows.entries().any { next.rows.index(it.id) < 0 }) return false
        var oldNonzeros = 0L
        var appendedNonzeros = 0L
        val oldIds = current.rows.entries().map { it.id }.toSet()
        for (column in 0 until next.model.n) {
            for (entry in next.model.entries(column)) {
                if (!projectedNonzero(entry.number)) continue
                if (next.rows.row(entry.row).id in oldIds) oldNonzeros++ else appendedNonzeros++
            }
        }
        val oldArea = current.model.m.toLong() * current.model.n
        val appendedArea = appendedRows.toLong() * current.model.n
        return oldNonzeros >= oldArea - oldArea / 4L && appendedNonzeros >= (appendedArea + 3L) / 4L
    }

    private fun projectedNonzero(number: ExactLpNumber): Boolean =
        (number.ieeeBits?.let { Double.fromBits(it) } ?: number.value.toDouble()) != 0.0

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun recordAppendWork(work: Long?) {
        if (work == null) {
            appendUnknownWork = saturatingAdd(appendUnknownWork, 1)
        } else {
            appendBasisWork = saturatingAdd(appendBasisWork, work)
        }
    }

    private fun recordAppendWork(work: com.eignex.klause.simplex.basis.BasisOperationWork?) {
        recordAppendWork(knownBasisWork(work))
    }

    private fun knownBasisWork(work: com.eignex.klause.simplex.basis.BasisOperationWork?): Long? =
        work?.takeIf { it.complete && !it.saturated }?.units

    @Suppress("TooGenericExceptionCaught")
    private fun recordPendingAppendSolve(current: PersistentLpSolver) {
        if (!pendingAppendSolve) return
        val before = pendingAppendSolveWork
        try {
            val after = knownBasisWork(current.basisLifecycleWork)
            recordAppendWork(if (before != null && after != null && after >= before) after - before else null)
        } catch (failure: Throwable) {
            appendUnknownWork = saturatingAdd(appendUnknownWork, 1)
            throw failure
        } finally {
            pendingAppendSolve = false
            pendingAppendSolveWork = null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun recordSolveCompletion(current: PersistentLpSolver, primary: Throwable?) {
        var failure = primary
        try {
            lastMetrics = current.lastMetrics
        } catch (telemetry: Throwable) {
            if (failure == null) failure = telemetry else failure.addSuppressed(telemetry)
        }
        try {
            recordPendingAppendSolve(current)
        } catch (telemetry: Throwable) {
            if (failure == null) failure = telemetry else failure.addSuppressed(telemetry)
        }
        if (primary == null && failure != null) throw failure
    }

    private fun prepared(
        next: LpExactState,
        token: Cancellation,
        recordRejectedAppendWork: Boolean = false,
    ): Pair<PersistentLpSolver, Basis>? {
        preparationAttempts++
        var candidate: PersistentLpSolver? = null
        var accepted = false
        var failure: Throwable? = null
        try {
            if (token() || next.model.m > maxRetainedRows) return null
            val working = next.toWorkingModel() ?: return null
            candidate = newPersistentLpSolver(
                working,
                token,
                refactorUpdateLimit,
                iterationLimit,
                workLimit,
                trackDegeneracy,
                context.engineFactory,
            )
            createdOwners++
            peakOwners = maxOf(peakOwners, createdOwners - closedOwners)
            if (!candidate.adopt(next, token)) return null
            val basis = try {
                candidate.prepareLogicals(token)
            } finally {
                preparationWork += candidate.lastMetrics.workOps
                preparationRefactorizations += candidate.lastRefactorizations
            } ?: return null
            if (basis.basicVars.size != next.model.m || basis.status.size != next.model.numVars ||
                basis.basicVars.indices.any { basis.basicVars[it] != next.model.n + it } ||
                basis.status.indices.any { (basis.status[it] == VarStatus.BASIC) != (it >= next.model.n) } || token()
            ) {
                return null
            }
            preparationSuccesses++
            accepted = true
            return candidate to basis
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
    private fun closeRejectedCandidate(candidate: PersistentLpSolver, recordWork: Boolean, primary: Throwable?) {
        var failure = primary
        if (recordWork) {
            try {
                recordAppendWork(candidate.basisLifecycleWork)
            } catch (telemetry: Throwable) {
                if (failure == null) failure = telemetry else failure.addSuppressed(telemetry)
            }
        }
        closeOwner(candidate, failure)
        if (primary == null && failure != null) throw failure
    }

    private fun closeOwner(owner: PersistentLpSolver, primary: Throwable? = null) {
        try {
            owner.close()
        } catch (failure: Throwable) {
            if (primary == null) throw failure
            primary.addSuppressed(failure)
        } finally {
            closedOwners++
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        lastResult = null
        val current = solver
        solver = null
        if (current != null) closeOwner(current)
    }
}
