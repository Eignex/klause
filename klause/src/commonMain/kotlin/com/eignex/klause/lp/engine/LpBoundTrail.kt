package com.eignex.klause.lp.engine

import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.unaryMinus

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
        if (!state.canProjectWorkingModel(token)) {
            if (token()) return LpBoundBatchResult.Declined(0)
            return assertUnprojectedBounds(assertions, token)
        }
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
        val rows = state.rows.popped(targetDepth)
        val changedRows = (0 until rows.size).filter { rows.row(it) != state.rows.row(it) }
        if (changedRows.isNotEmpty() && state.rowRevision == Long.MAX_VALUE) return false
        val removed = state.rows.entries().indices.filter {
            val row = state.rows.row(it)
            row.active && row.depth != null && row.depth > targetDepth
        }.toSet()
        if (!canDeactivate(removed, assertions)) return false
        val next = snapshot(
            assertions = assertions,
            // The same rows object when none go, so the popped state can derive from this one.
            rows = rows,
            rowRevision = state.rowRevision + if (changedRows.isEmpty()) 0L else 1L,
            scopes = state.scopes.take(targetDepth),
            boundRevision = state.boundRevision + 1L,
            popRevision = state.popRevision + 1L,
            changedColumns = (
                state.assertions.drop(retained).map { it.column } +
                    changedRows.map { state.model.n + it }
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

    fun resetRoot(token: Cancellation = Cancellation.Never): Boolean {
        if (token() || state.boundRevision == Long.MAX_VALUE || state.popRevision == Long.MAX_VALUE) return false
        val rows = state.rows.popped(0)
        val changedRows = (0 until rows.size).filter { rows.row(it) != state.rows.row(it) }
        if (changedRows.isNotEmpty() && state.rowRevision == Long.MAX_VALUE) return false
        if (!canDeactivate(changedRows.filterTo(HashSet()) { !rows.row(it).active }, emptyList())) return false
        return commit(
            snapshot(
                assertions = emptyList(),
                scopes = emptyList(),
                rows = rows,
                rowRevision = state.rowRevision + if (changedRows.isEmpty()) 0L else 1L,
                boundRevision = state.boundRevision + 1L,
                popRevision = state.popRevision + 1L,
                changedColumns = (
                    state.assertions.map { it.column } + changedRows.map { state.model.n + it }
                    ).distinct().sorted(),
            ),
            token,
        )
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

    fun append(row: LpScopedRow, scoped: Boolean, token: Cancellation = Cancellation.Never): Boolean =
        append(emptyList(), listOf(row), scoped, token)

    fun append(
        columns: List<LpStructuralColumn>,
        rows: List<LpScopedRow>,
        scoped: Boolean,
        token: Cancellation = Cancellation.Never,
        permanentRows: Set<Long> = emptySet(),
    ): Boolean {
        if (token()) return false
        if (columns.isEmpty() && rows.isEmpty()) return permanentRows.isEmpty()
        val newN = state.model.n.toLong() + columns.size
        val newM = state.model.m.toLong() + rows.size
        if (newN + newM >= Int.MAX_VALUE || !structuralRevisionAvailable() ||
            rows.any { it.id <= state.rows.lastId || !it.validFor(newN.toInt()) } ||
            rows.zipWithNext().any { (left, right) -> left.id >= right.id }
        ) {
            return false
        }
        if (!rows.map { it.id }.containsAll(permanentRows) ||
            rows.any { it.id in permanentRows && (!it.metadata.global || it.metadata.premises != null) }
        ) {
            return false
        }
        val nnz = (0 until state.model.n).sumOf { state.model.entries(it).size.toLong() } +
            rows.sumOf { it.coefficients().size.toLong() }
        if (nnz + newM > Int.MAX_VALUE || token()) return false
        val identities = state.rows.append(rows.map { it.id }, if (scoped) state.depth else null, permanentRows)
        val assertions = if (columns.isEmpty()) {
            state.assertions
        } else {
            state.assertions.map { assertion ->
                if (assertion.column < state.model.n) {
                    assertion
                } else {
                    assertion.copy(column = assertion.column + columns.size)
                }
            }
        }
        return commit(
            snapshot(
                baseModel = state.baseModel.appendScopedRows(columns, rows),
                assertions = assertions,
                rows = identities,
                matrixRevision = state.matrixRevision + 1L,
                boundRevision = state.boundRevision + 1L,
                objectiveRevision = state.objectiveRevision + 1L,
                rowRevision = state.rowRevision + 1L,
            ),
            token,
        )
    }

    fun deactivate(id: Long, token: Cancellation = Cancellation.Never): Boolean = deactivate(setOf(id), token)

    fun deactivate(ids: Set<Long>, token: Cancellation = Cancellation.Never): Boolean {
        if (token()) return false
        val indices = rowIndices(ids) ?: return false
        val changed = indices.filterTo(HashSet()) {
            state.rows.row(it).active || state.rows.row(it).suspendedAt != null
        }
        if (changed.isEmpty()) return true
        if (state.boundRevision == Long.MAX_VALUE || !canDeactivate(changed, state.assertions)) return false
        return commit(
            snapshot(
                rows = state.rows.deactivate(changed),
                boundRevision = state.boundRevision + 1L,
                rowRevision = state.rowRevision + 1L,
                changedColumns = changed.map { state.model.n + it }.sorted(),
            ),
            token,
        )
    }

    fun suspend(ids: Set<Long>, token: Cancellation = Cancellation.Never): Boolean {
        if (token() || state.depth == 0) return false
        val indices = rowIndices(ids) ?: return false
        val active = indices.filterTo(HashSet()) { state.rows.row(it).active }
        if (active.isEmpty()) return true
        if (state.boundRevision == Long.MAX_VALUE || !canDeactivate(active, state.assertions)) return false
        return commit(
            snapshot(
                rows = state.rows.suspend(active, state.depth),
                boundRevision = state.boundRevision + 1L,
                rowRevision = state.rowRevision + 1L,
                changedColumns = active.map { state.model.n + it }.sorted(),
            ),
            token,
        )
    }

    private fun rowIndices(ids: Set<Long>): Set<Int>? {
        if (ids.isEmpty()) return emptySet()
        val indices = HashSet<Int>()
        for (index in 0 until state.rows.size) if (state.rows.row(index).id in ids) indices.add(index)
        return indices.takeIf { it.size == ids.size }
    }

    fun compact(token: Cancellation = Cancellation.Never): Boolean =
        compact(LpLayoutRemap(state.model.n, state.rows), token)

    fun compact(remap: LpLayoutRemap, token: Cancellation = Cancellation.Never): Boolean {
        if (token() || !remap.matches(state.model, state.rows)) return false
        if (remap.unchanged) return true
        if (!structuralRevisionAvailable()) return false
        for (column in 0 until state.model.n) {
            if (token()) return false
            if (remap.column(column) < 0 && !canRemoveColumn(column, remap)) return false
        }
        val assertions = ArrayList<LpBoundAssertion>()
        val marks = IntArray(state.assertions.size + 1)
        state.assertions.forEachIndexed { index, assertion ->
            val column = remap.column(assertion.column)
            if (column >= 0) assertions.add(assertion.copy(column = column))
            marks[index + 1] = assertions.size
        }
        return commit(
            snapshot(
                baseModel = state.baseModel.compactLayout(remap),
                assertions = assertions,
                scopes = state.scopes.map { marks[it] },
                rows = state.rows.compact(),
                matrixRevision = state.matrixRevision + 1L,
                boundRevision = state.boundRevision + 1L,
                objectiveRevision = state.objectiveRevision + 1L,
                rowRevision = state.rowRevision + 1L,
            ),
            token,
        )
    }

    private fun canRemoveColumn(column: Int, remap: LpLayoutRemap): Boolean {
        val model = state.model
        if (!model.objective.cost(column).value.isZero ||
            model.columnEntries(column).any { remap.row(it.row) >= 0 && !it.number.value.isZero }
        ) {
            return false
        }
        val source = model.column(column)
        if (!source.bounds.consistent) return false
        if (!source.integral) return true
        val lower = source.bounds.lower ?: return true
        val upper = source.bounds.upper ?: return true
        val lo = lower.number.value + source.origin.value
        val hi = upper.number.value + source.origin.value
        var minimum = lo.ceilInteger()
        var maximum = -hi.negated().ceilInteger()
        if (lower.strict && lo.den == BIG_ONE) minimum += BIG_ONE
        if (upper.strict && hi.den == BIG_ONE) maximum -= BIG_ONE
        return minimum <= maximum
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
        previous = state,
    ).also { if (matrixRevision == state.matrixRevision) it.inheritProjection(state) }

    private fun commit(next: LpExactState, token: Cancellation): Boolean {
        if (token() || !next.canProjectWorkingModel() || token()) return false
        state = next
        return true
    }
}
