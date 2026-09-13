package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation

internal sealed interface LpBoundBatchResult {
    val count: Int

    data class Applied(override val count: Int) : LpBoundBatchResult
    data class Conflict(override val count: Int, val reason: LpBoundConflict) : LpBoundBatchResult
    data class Declined(override val count: Int) : LpBoundBatchResult
}

internal class LpBoundTrail(initial: LpExactState) {
    constructor(initial: ExactLpModel) : this(LpExactState(initial))

    var state: LpExactState = initial
        private set

    fun push(token: Cancellation = Cancellation.Never): Boolean {
        if (token() || state.depth == Int.MAX_VALUE) return false
        val next = snapshot(scopes = state.scopes + state.assertions.size)
        return commit(next, token)
    }

    fun assertBound(
        column: Int,
        upper: Boolean,
        side: ExactLpSide,
        witness: Long,
        token: Cancellation = Cancellation.Never,
    ): Boolean {
        if (token() || column !in 0 until state.model.numVars ||
            (column >= state.model.n && !state.rows.row(column - state.model.n).active) || witness < 0L ||
            state.assertions.any { it.witness == witness } || state.boundRevision == Long.MAX_VALUE
        ) {
            return false
        }
        val next = snapshot(
            assertions = state.assertions + LpBoundAssertion(column, upper, side, witness, state.depth),
            boundRevision = state.boundRevision + 1L,
            changedColumns = listOf(column),
        )
        return commit(next, token)
    }

    fun assertBounds(
        assertions: List<LpBoundAssertion>,
        token: Cancellation = Cancellation.Never,
    ): LpBoundBatchResult {
        if (token()) return LpBoundBatchResult.Declined(0)
        state.conflict?.let { return LpBoundBatchResult.Conflict(0, it) }
        if (assertions.isEmpty()) return LpBoundBatchResult.Applied(0)
        if (state.toWorkingModel() == null) return assertUnprojectedBounds(assertions, token)
        val before = state.assertions
        val witnesses = before.mapTo(HashSet()) { it.witness }
        val lower = Array(state.model.numVars) { state.activeSide(it, false) }
        val upper = Array(state.model.numVars) { state.activeSide(it, true) }
        val changed = LinkedHashSet<Int>()
        var count = 0
        var conflict = false
        for (assertion in assertions) {
            if (token() || assertion.column !in lower.indices || assertion.depth != state.depth ||
                (assertion.column >= state.model.n && !state.rows.row(assertion.column - state.model.n).active) ||
                assertion.witness < 0L || !witnesses.add(assertion.witness) ||
                state.boundRevision > Long.MAX_VALUE - count - 1L
            ) {
                return LpBoundBatchResult.Declined(count + 1)
            }
            val sides = if (assertion.upper) upper else lower
            val previous = sides[assertion.column]
            val comparison = previous?.let { assertion.side.number.value.compareTo(it.side.number.value) }
            if (comparison == null || (if (assertion.upper) comparison < 0 else comparison > 0) ||
                (comparison == 0 && assertion.side.strict && !previous.side.strict)
            ) {
                val number = assertion.side.number
                if (!(number.ieeeBits?.let { Double.fromBits(it) } ?: number.value.toDouble()).isFinite()) {
                    return LpBoundBatchResult.Declined(count + 1)
                }
                sides[assertion.column] = assertion
            }
            count++
            changed.add(assertion.column)
            if (token()) return LpBoundBatchResult.Declined(count)
            if (!ExactLpBounds(lower[assertion.column]?.side, upper[assertion.column]?.side).consistent) {
                conflict = true
                break
            }
        }
        val next = snapshot(
            assertions = before + assertions.take(count),
            boundRevision = state.boundRevision + count,
            changedColumns = changed.sorted(),
        )
        if (!commit(next, token)) return LpBoundBatchResult.Declined(count)
        return if (conflict) {
            LpBoundBatchResult.Conflict(count, requireNotNull(state.conflict))
        } else {
            LpBoundBatchResult.Applied(count)
        }
    }

    private fun assertUnprojectedBounds(assertions: List<LpBoundAssertion>, token: Cancellation): LpBoundBatchResult {
        val staged = LpBoundTrail(state)
        var count = 0
        for (assertion in assertions) {
            if (assertion.depth != state.depth || !staged.assertBound(
                    assertion.column,
                    assertion.upper,
                    assertion.side,
                    assertion.witness,
                    token,
                )
            ) {
                return LpBoundBatchResult.Declined(count + 1)
            }
            count++
            if (staged.state.conflict != null) break
        }
        val next = snapshot(
            assertions = staged.state.assertions,
            boundRevision = staged.state.boundRevision,
            changedColumns = assertions.take(count).map { it.column }.distinct().sorted(),
        )
        if (!commit(next, token)) return LpBoundBatchResult.Declined(count)
        return state.conflict?.let { LpBoundBatchResult.Conflict(count, it) } ?: LpBoundBatchResult.Applied(count)
    }

    fun pop(targetDepth: Int, token: Cancellation = Cancellation.Never): Boolean {
        if (token() || targetDepth !in 0..state.depth) return false
        if (targetDepth == state.depth) return true
        if (state.boundRevision == Long.MAX_VALUE || state.popRevision == Long.MAX_VALUE) return false
        val retained = state.scopes[targetDepth]
        val assertions = state.assertions.take(retained)
        val removed = state.rows.entries().indices.filter {
            val row = state.rows.row(it)
            row.active && row.depth != null && row.depth > targetDepth
        }.toSet()
        if (!canDeactivate(removed, assertions)) return false
        val next = snapshot(
            assertions = assertions,
            rows = state.rows.deactivate(removed),
            rowRevision = state.rowRevision + if (removed.isEmpty()) 0L else 1L,
            scopes = state.scopes.take(targetDepth),
            boundRevision = state.boundRevision + 1L,
            popRevision = state.popRevision + 1L,
            changedColumns = (
                state.assertions.drop(retained).map { it.column } +
                    removed.map { state.model.n + it }
                ).distinct().sorted(),
        )
        return commit(next, token)
    }

    fun replaceObjective(objective: ExactLpObjective, token: Cancellation = Cancellation.Never): Boolean {
        if (token() || objective.size != state.model.numVars || state.objectiveRevision == Long.MAX_VALUE) return false
        val pricesInactiveRow = (0 until state.model.m).any {
            !state.rows.row(it).active && !objective.cost(state.model.n + it).value.isZero
        }
        if (pricesInactiveRow) return false
        val next = snapshot(
            baseModel = state.baseModel.copy(objective = objective),
            objectiveRevision = state.objectiveRevision + 1L,
        )
        return commit(next, token)
    }

    fun recenter(origins: List<ExactLpNumber>, token: Cancellation = Cancellation.Never): Boolean {
        if (token() || origins.size != state.model.n) return false
        if ((0 until state.model.n).all { origins[it] == state.model.column(it).origin }) return true
        if (state.boundRevision == Long.MAX_VALUE || state.objectiveRevision == Long.MAX_VALUE ||
            state.assertions.any { it.side.number.ieeeBits != null || it.side.premises?.hasIeeeInput == true }
        ) {
            return false
        }
        val base = try {
            state.baseModel.recentered(origins)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val assertions = state.assertions.map { assertion ->
            if (assertion.column >= state.model.n) return@map assertion
            val delta = origins[assertion.column].value - state.model.column(assertion.column).origin.value
            if (delta.isZero) {
                assertion
            } else {
                assertion.copy(
                    side = assertion.side.copy(number = ExactLpNumber.of(assertion.side.number.value - delta)),
                )
            }
        }
        val next = snapshot(
            baseModel = base,
            assertions = assertions,
            boundRevision = state.boundRevision + 1L,
            objectiveRevision = state.objectiveRevision + 1L,
            changedColumns = (0 until state.model.n).filter { origins[it] != state.model.column(it).origin },
        )
        return commit(next, token)
    }

    fun append(row: LpScopedRow, scoped: Boolean, token: Cancellation = Cancellation.Never): Boolean {
        if (token() || row.id <= state.rows.lastId || !row.validFor(state.baseModel) ||
            state.model.numVars >= Int.MAX_VALUE - 1 || !structuralRevisionAvailable()
        ) {
            return false
        }
        val nnz = (0 until state.model.n).sumOf { state.model.entries(it).size.toLong() } + row.coefficients().size
        if (nnz + state.model.m + 1L > Int.MAX_VALUE) return false
        return commit(
            snapshot(
                baseModel = state.baseModel.appendScopedRow(row),
                rows = state.rows.append(row.id, if (scoped) state.depth else null),
                matrixRevision = state.matrixRevision + 1L,
                boundRevision = state.boundRevision + 1L,
                objectiveRevision = state.objectiveRevision + 1L,
                rowRevision = state.rowRevision + 1L,
            ),
            token,
        )
    }

    fun deactivate(id: Long, token: Cancellation = Cancellation.Never): Boolean {
        if (token()) return false
        val index = state.rows.index(id)
        if (index < 0) return false
        if (!state.rows.row(index).active) return true
        if (state.boundRevision == Long.MAX_VALUE || !canDeactivate(setOf(index), state.assertions)) return false
        return commit(
            snapshot(
                rows = state.rows.deactivate(setOf(index)),
                boundRevision = state.boundRevision + 1L,
                rowRevision = state.rowRevision + 1L,
                changedColumns = listOf(state.model.n + index),
            ),
            token,
        )
    }

    fun compact(token: Cancellation = Cancellation.Never): Boolean {
        if (token()) return false
        if (state.rows.activeCount == state.rows.size) return true
        if (!structuralRevisionAvailable()) return false
        val remap = LpRowRemap(state.model.n, state.rows)
        return commit(
            snapshot(
                baseModel = state.baseModel.compactScopedRows(remap),
                assertions = state.assertions.map { it.copy(column = remap.column(it.column)) },
                rows = state.rows.compact(),
                matrixRevision = state.matrixRevision + 1L,
                boundRevision = state.boundRevision + 1L,
                objectiveRevision = state.objectiveRevision + 1L,
                rowRevision = state.rowRevision + 1L,
            ),
            token,
        )
    }

    private fun structuralRevisionAvailable(): Boolean = listOf(
        state.matrixRevision,
        state.boundRevision,
        state.objectiveRevision,
        state.rowRevision,
    ).all { it < Long.MAX_VALUE }

    private fun canDeactivate(indices: Set<Int>, assertions: List<LpBoundAssertion>): Boolean {
        if (indices.isEmpty()) return true
        if (state.rowRevision == Long.MAX_VALUE) return false
        if (indices.any { !state.model.objective.cost(state.model.n + it).value.isZero }) return false
        return assertions.none { it.column >= state.model.n && it.column - state.model.n in indices }
    }

    private fun snapshot(
        baseModel: ExactLpModel = state.baseModel,
        assertions: List<LpBoundAssertion> = state.assertions,
        scopes: List<Int> = state.scopes,
        boundRevision: Long = state.boundRevision,
        objectiveRevision: Long = state.objectiveRevision,
        popRevision: Long = state.popRevision,
        changedColumns: List<Int> = emptyList(),
        matrixRevision: Long = state.matrixRevision,
        rows: LpScopedRows = state.rows,
        rowRevision: Long = state.rowRevision,
    ): LpExactState = LpExactState(
        baseModel,
        assertions,
        scopes,
        matrixRevision,
        boundRevision,
        objectiveRevision,
        popRevision,
        changedColumns,
        rows,
        rowRevision,
    ).also { if (matrixRevision == state.matrixRevision) it.inheritProjection(state) }

    private fun commit(next: LpExactState, token: Cancellation): Boolean {
        if (token() || next.toWorkingModel() == null || token()) return false
        state = next
        return true
    }
}
