package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation

internal class LpBoundTrail(initial: ExactLpModel) {
    var state: LpExactState = LpExactState(initial)
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
        if (token() || column !in 0 until state.model.n || witness < 0L ||
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

    fun pop(targetDepth: Int, token: Cancellation = Cancellation.Never): Boolean {
        if (token() || targetDepth !in 0..state.depth) return false
        if (targetDepth == state.depth) return true
        if (state.boundRevision == Long.MAX_VALUE || state.popRevision == Long.MAX_VALUE) return false
        val retained = state.scopes[targetDepth]
        val next = snapshot(
            assertions = state.assertions.take(retained),
            scopes = state.scopes.take(targetDepth),
            boundRevision = state.boundRevision + 1L,
            popRevision = state.popRevision + 1L,
            changedColumns = state.assertions.drop(retained).map { it.column }.distinct().sorted(),
        )
        return commit(next, token)
    }

    fun replaceObjective(objective: ExactLpObjective, token: Cancellation = Cancellation.Never): Boolean {
        if (token() || objective.size != state.model.numVars || state.objectiveRevision == Long.MAX_VALUE) return false
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

    private fun snapshot(
        baseModel: ExactLpModel = state.baseModel,
        assertions: List<LpBoundAssertion> = state.assertions,
        scopes: List<Int> = state.scopes,
        boundRevision: Long = state.boundRevision,
        objectiveRevision: Long = state.objectiveRevision,
        popRevision: Long = state.popRevision,
        changedColumns: List<Int> = emptyList(),
    ): LpExactState = LpExactState(
        baseModel,
        assertions,
        scopes,
        state.matrixRevision,
        boundRevision,
        objectiveRevision,
        popRevision,
        changedColumns,
    ).also { it.inheritProjection(state) }

    private fun commit(next: LpExactState, token: Cancellation): Boolean {
        if (token() || next.toWorkingModel() == null || token()) return false
        state = next
        return true
    }
}
