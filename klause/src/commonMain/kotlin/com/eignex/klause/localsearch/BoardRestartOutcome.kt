package com.eignex.klause.localsearch

import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.AllDifferentInvariant
import com.eignex.klause.util.IntHashSet

/** Input-change result of a retained ILS board restart after preparation and greedy repair.
 *  A changed input establishes neither feasibility nor arrival in a different basin. */
enum class BoardRestartOutcome {
    /** At least one admissible searched board input differs from its pre-restart value. */
    ChangedInputs,

    /** The policy produced no admissible searched-input change relative to the preceding board. */
    DeclinedUnchanged,

    /** Ordinary restart preparation returned the searched inputs to the preceding board. */
    DeclinedPreparation,

    /** Greedy repair returned the searched inputs to the preceding board. */
    DeclinedRepair,

    /** Pin, fixed-domain, definition or ownership filters leave no admissible searched board input. */
    DeclinedUnavailable,
}

internal class PreparedBoardRestart private constructor(
    private val scope: BoardRestartInputs,
    private val before: LongArray,
) {
    private val inputs = scope.inputs
    private var policyChanged = false
    private var preparationChanged = false

    fun afterPolicy(state: LocalSearchState) {
        policyChanged = changed(state)
    }

    fun afterPreparation(state: LocalSearchState) {
        preparationChanged = changed(state)
    }

    fun finish(state: LocalSearchState): BoardRestartOutcome = when {
        inputs.none { admissible(state, it) } -> BoardRestartOutcome.DeclinedUnavailable
        changed(state) -> BoardRestartOutcome.ChangedInputs
        preparationChanged -> BoardRestartOutcome.DeclinedRepair
        policyChanged -> BoardRestartOutcome.DeclinedPreparation
        else -> BoardRestartOutcome.DeclinedUnchanged
    }

    private fun changed(state: LocalSearchState): Boolean = inputs.indices.any {
        val variable = inputs[it]
        admissible(state, variable) && state.assignment.intValue(variable) in state.rootDomains[variable] &&
            state.assignment.intValue(variable) != before[it]
    }

    private fun admissible(state: LocalSearchState, variable: Int): Boolean {
        if (state.assumptions.isFrozenInt(variable) || state.rootDomains[variable].isFixed ||
            state.invariants?.isDefinedInt(variable) == true
        ) return false
        val owner = state.seeding.ownerInt?.get(variable) ?: -1
        return owner < 0 || scope.owns(owner)
    }

    companion object {
        fun capture(state: LocalSearchState): PreparedBoardRestart? {
            val scope = state.boardRestartInputs
            if (scope.inputs.isEmpty()) return null
            return PreparedBoardRestart(
                scope, LongArray(scope.inputs.size) { state.assignment.intValue(scope.inputs[it]) },
            )
        }
    }
}

internal class BoardRestartInputs private constructor(
    private val owners: IntHashSet,
    val inputs: IntArray,
) {
    fun owns(factor: Int): Boolean = owners.size != 0 && factor in owners

    companion object {
        fun capture(state: LocalSearchState): BoardRestartInputs {
            val boards = state.seeding.electedImplicit.filter { fid ->
                val factor = state.problem.factors[fid] as? AllDifferent
                factor != null && factor.presents.isEmpty() && factor.exceptSet.isEmpty() &&
                    state.factors[fid] is AllDifferentInvariant
            }
            val owners = IntHashSet()
            val unique = IntHashSet()
            for (fid in boards) {
                owners.add(fid)
                for (variable in state.problem.factors[fid].intVars) unique.add(variable)
            }
            return BoardRestartInputs(owners, unique.toIntArray())
        }
    }
}
