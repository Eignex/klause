package com.eignex.klause.localsearch

import com.eignex.klause.factor.global.AllDifferentInvariant

internal class RetainedBoardRestart private constructor(
    private val boards: IntArray,
    private val inputs: IntArray,
    private val before: LongArray,
) {
    fun changed(state: LocalSearchState): Boolean =
        inputs.indices.any { state.assignment.intValue(inputs[it]) != before[it] }

    fun perturb(state: LocalSearchState, strength: Int, kind: PerturbationKind) {
        val sink = MoveSink(state.assumptions)
        sink.setInvariants(state.invariants)
        sink.setOwners(state.seeding.ownerInt)
        val attempts = if (kind == PerturbationKind.Uniform) strength / 2 else strength
        repeat(attempts) {
            sink.clear()
            val fid = boards[state.rng.nextInt(boards.size)]
            if (state.factors[fid].isViolated(state, fid)) return@repeat
            sink.proposer = fid
            state.factors[fid].proposeStructuredMoves(state, fid, sink)
            val swaps = sink.list.filterIsInstance<Move.Compound>().filter { admissibleSwap(state, it) }
            if (swaps.isEmpty()) return@repeat
            val move = swaps[state.rng.nextInt(swaps.size)]
            val reverse = Move.Compound(move.parts.map {
                val part = it as Move.IntSet
                Move.IntSet(part.varId, state.assignment.intValue(part.varId))
            })
            state.apply(move)
            if (!ownedGlobalsFeasible(state)) state.apply(reverse)
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
            val owners = state.seeding.ownerInt
            val inputs = boards.flatMap { fid ->
                state.problem.factors[fid].intVars.filter { variable ->
                    !state.assumptions.isFrozenInt(variable) && state.invariants?.isDefinedInt(variable) != true &&
                        (owners == null || owners[variable] < 0 || owners[variable] == fid)
                }
            }.distinct().toIntArray()
            return RetainedBoardRestart(
                boards.toIntArray(), inputs, LongArray(inputs.size) { state.assignment.intValue(inputs[it]) },
            )
        }

        fun ownedGlobalsFeasible(state: LocalSearchState): Boolean =
            state.seeding.implicitSeedFactors.all { !state.factors[it].isViolated(state, it) }

        fun canPreserveOwners(state: LocalSearchState): Boolean {
            val owners = state.seeding.ownerInt ?: return false
            return ownedGlobalsFeasible(state) && state.seeding.implicitSeedFactors.all { fid ->
                state.problem.factors[fid].intVars.all { owners[it] == fid }
            }
        }
    }
}
