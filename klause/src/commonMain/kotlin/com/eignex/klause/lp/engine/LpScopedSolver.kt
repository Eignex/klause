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
) {
    val editDeclines: Long get() = editAttempts - editSuccesses
    val preparationDeclines: Long get() = preparationAttempts - preparationSuccesses
    val currentOwners: Long get() = createdOwners - closedOwners
}

internal class LpScopedSolver(
    initial: LpExactState,
    private val cancellation: Cancellation = Cancellation.Never,
    private val context: LpSolveContext = LpSolveContext.Production,
    private val refactorUpdateLimit: Int = DEFAULT_REFACTOR_UPDATE_LIMIT,
    private val iterationLimit: Int = 0,
    private val workLimit: Long = 0L,
    private val trackDegeneracy: Boolean = false,
    private val maxRetainedRows: Int = Int.MAX_VALUE,
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

    val state: LpExactState get() = trail.state
    var lastResult: CertifiedLpResult? = null
        private set
    var lastMetrics: LpSolveMetrics = LpSolveMetrics()
        private set
    val metrics: LpScopedMetrics get() = LpScopedMetrics(
        editAttempts, editSuccesses, preparationAttempts, preparationSuccesses,
        preparationWork, preparationRefactorizations, createdOwners, closedOwners, peakOwners,
        state.rows.size, state.rows.activeCount,
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

    fun append(row: LpScopedRow, scoped: Boolean, token: Cancellation = cancellation): Boolean = edit(token) {
        state.model.m < maxRetainedRows && it.append(row, scoped, token)
    }

    fun deactivate(id: Long, token: Cancellation = cancellation): Boolean = edit(token) { it.deactivate(id, token) }

    fun compact(token: Cancellation = cancellation): Boolean = edit(token) { it.compact(token) }

    fun solve(
        warm: Basis? = null,
        token: Cancellation = cancellation,
        counterResults: LpCounterResults? = null,
    ): CertifiedLpResult? {
        lastResult = null
        lastMetrics = LpSolveMetrics()
        if (!prepare(token)) return null
        val current = requireNotNull(solver)
        if (!current.adopt(state, token)) return null
        val result = try {
            if (warm == null) current.resolveBounds() else current.solve(warm)
        } finally {
            lastMetrics = current.lastMetrics
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

    private inline fun edit(token: Cancellation, change: (LpBoundTrail) -> Boolean): Boolean {
        editAttempts++
        if (closed || token()) return false
        val next = LpBoundTrail(state)
        if (!change(next)) return false
        if (next.state === state) {
            editSuccesses++
            return true
        }
        if (next.state.matrixRevision != state.matrixRevision) {
            if (!replace(next, token)) return false
        } else {
            val current = solver
            if (current != null && !current.adopt(next.state, token)) return false
            if (current == null && token()) return false
            trail = next
        }
        lastResult = null
        lastMetrics = LpSolveMetrics()
        editSuccesses++
        return true
    }

    private fun replace(next: LpBoundTrail, token: Cancellation): Boolean {
        val expected = if (next.state.model.m < state.model.m) {
            // Seat the disappearing logicals in an isolated old-size owner before deleting their slots.
            val staging = prepared(state, token) ?: return false
            try {
                val remap = LpRowRemap(state.model.n, state.rows)
                staging.second.basicVars.map { remap.column(it) }.filter { it >= 0 }.toIntArray()
            } finally {
                closeOwner(staging.first)
            }
        } else {
            IntArray(next.state.model.m) { next.state.model.n + it }
        }
        val replacement = prepared(next.state, token) ?: return false
        var published = false
        try {
            if (!replacement.second.basicVars.contentEquals(expected) || token()) return false
            val old = solver
            trail = next
            solver = replacement.first
            lastResult = null
            published = true
            if (old != null) closeOwner(old)
            return true
        } finally {
            if (!published) closeOwner(replacement.first)
        }
    }

    private fun prepared(next: LpExactState, token: Cancellation): Pair<PersistentLpSolver, Basis>? {
        preparationAttempts++
        var candidate: PersistentLpSolver? = null
        var accepted = false
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
        } finally {
            if (!accepted && candidate != null) closeOwner(candidate)
        }
    }

    private fun closeOwner(owner: PersistentLpSolver) {
        try {
            owner.close()
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
