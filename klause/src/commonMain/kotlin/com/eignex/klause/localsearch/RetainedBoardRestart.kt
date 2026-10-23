package com.eignex.klause.localsearch

import com.eignex.klause.factor.global.AllDifferentInvariant
import com.eignex.klause.util.binarySearchInt

internal class RetainedBoardRestart private constructor(
    private val boards: IntArray,
    private val inputs: IntArray,
    private val before: LongArray,
) {
    fun changed(state: LocalSearchState): Boolean {
        val owners = state.seeding.ownerInt
        return inputs.indices.any {
            val variable = inputs[it]
            val owner = owners?.get(variable) ?: -1
            (owner < 0 || boards.binarySearchInt(owner) >= 0) &&
                state.assignment.intValue(variable) in state.rootDomains[variable] &&
                state.assignment.intValue(variable) != before[it]
        }
    }

    fun perturb(state: LocalSearchState, strength: Int, kind: PerturbationKind) {
        val sink = MoveSink(state.assumptions)
        sink.setInvariants(state.invariants)
        sink.setOwners(state.seeding.ownerInt)
        val attempts = if (kind == PerturbationKind.Uniform) strength / 2 else strength
        repeat(attempts) {
            sink.clear()
            val scope = when (kind) {
                PerturbationKind.BasinHopping ->
                    state.problem.factors[state.rng.nextInt(state.problem.numFactors)].intVars
                PerturbationKind.Uniform -> {
                    val target = state.rng.nextInt(state.problem.numBoolVars + state.problem.numIntVars)
                    intArrayOf(target - state.problem.numBoolVars)
                }
            }
            val eligible = boards.filter { board ->
                state.problem.factors[board].intVars.any { it in scope }
            }
            if (eligible.isEmpty()) return@repeat
            val fid = eligible[state.rng.nextInt(eligible.size)]
            if (state.factors[fid].isViolated(state, fid)) return@repeat
            sink.proposer = fid
            state.factors[fid].proposeStructuredMoves(state, fid, sink)
            val swaps = sink.list.filterIsInstance<Move.Compound>().filter { move ->
                admissibleSwap(state, move) && move.parts.any { (it as Move.IntSet).varId in scope }
            }
            if (swaps.isEmpty()) return@repeat
            val move = swaps[state.rng.nextInt(swaps.size)]
            if (state.preservesImplicitFeasibility(move)) state.apply(move)
        }
        state.resetStepCounters()
    }

    private fun admissibleSwap(state: LocalSearchState, move: Move.Compound): Boolean {
        if (move.parts.size != 2) return false
        val first = move.parts[0] as? Move.IntSet ?: return false
        val second = move.parts[1] as? Move.IntSet ?: return false
        if (first.varId == second.varId || first.newValue == second.newValue) return false
        return first.newValue == state.assignment.intValue(second.varId) &&
            second.newValue == state.assignment.intValue(first.varId) &&
            first.newValue in state.rootDomains[first.varId] && second.newValue in state.rootDomains[second.varId]
    }

    companion object {
        fun capture(state: LocalSearchState): RetainedBoardRestart? {
            val boards = state.seeding.electedImplicit.filter { state.factors[it] is AllDifferentInvariant }
            if (boards.isEmpty()) return null
            val inputs = boards.flatMap { fid ->
                state.problem.factors[fid].intVars.filter { variable ->
                    !state.assumptions.isFrozenInt(variable) && state.invariants?.isDefinedInt(variable) != true
                }
            }.distinct().toIntArray()
            return RetainedBoardRestart(
                boards.toIntArray(), inputs, LongArray(inputs.size) { state.assignment.intValue(inputs[it]) },
            )
        }
    }
}
