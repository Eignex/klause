package com.eignex.klause.localsearch.movesource

import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink

/**
 * Feasibility-Jump / ViolationLS candidate generation. Selects hot-spot jumps by the complete
 * weighted violation change of their coordinated assignments.
 *
 * Per call it samples [candidateVars] variables biased toward currently-violated factors (a random
 * violated factor → a random variable of it) and emits the best admissible coordinated move for each:
 *  - int var: small domains are swept exactly; wide domains are sampled up to [maxValueTries].
 *    Each target uses channeling and inverse definition repairs through [MoveSink.addChannelingIntSet].
 *  - bool var: searched variables offer a flip; maintained predicates offer inverse repairs.
 *
 * [LocalSearchState.weightedNetDelta] scores each complete candidate, including maintained outputs,
 * against the adaptive per-constraint weights. Staying put has delta zero, so only strict weighted
 * improvements are emitted. The selected move is retained without regenerating its repair alternatives.
 */
class ArgminJump(
    /** Hot-spot variables sampled (and jumped) per [generate] call. */
    private val candidateVars: Int,
    /** Cap on domain values evaluated per int variable when the domain is wider than this. */
    private val maxValueTries: Int = DEFAULT_MAX_VALUE_TRIES,
) : MoveSource {
    init {
        require(candidateVars >= 1) { "candidateVars >= 1, got $candidateVars" }
        require(maxValueTries >= 1) { "maxValueTries >= 1, got $maxValueTries" }
    }

    override val id: MoveSourceId = ID
    override val phase: Phase = Phase.Any
    override val pool: Pool = Pool.NoiseEligible

    override fun generate(state: LocalSearchState, sink: MoveSink) {
        if (state.violated.isEmpty()) return
        val candidates = MoveSink(state.assumptions).also {
            it.setInvariants(state.invariants)
            it.setOwners(state.seeding.ownerInt)
        }
        repeat(candidateVars) {
            val fid = state.violated.random(state.rng)
            val scope = state.problem.factors[fid]
            val nInt = scope.intVars.size
            val nBool = scope.boolVars.size
            if (nInt + nBool == 0) return@repeat
            val pick = state.rng.nextInt(nInt + nBool)
            candidates.clear()
            if (pick < nInt) {
                collectIntJumps(state, scope.intVars[pick], candidates)
            } else {
                candidates.addChannelingBoolFlip(state, scope.boolVars[pick - nInt])
            }
            emitBestJump(state, candidates, sink)
        }
    }

    private fun collectIntJumps(state: LocalSearchState, v: Int, candidates: MoveSink) {
        val cur = state.assignment.intValue(v)
        val d = state.rootDomains[v]
        val few = d.spanOrNull(maxValueTries.toLong())
        if (few != null) {
            for (idx in 0 until few.size) {
                val target = few.valueAt(idx)
                if (target != cur) candidates.addChannelingIntSet(state, v, target)
            }
        } else {
            repeat(maxValueTries) {
                val target = state.randomIntValue(v)
                if (target != cur) candidates.addChannelingIntSet(state, v, target)
            }
        }
    }

    private fun emitBestJump(state: LocalSearchState, candidates: MoveSink, sink: MoveSink) {
        var best: Move? = null
        var bestDelta = 0.0
        for (i in 0 until candidates.size) {
            val candidate = candidates.moveAt(i)
            val delta = state.weightedNetDelta(candidate)
            if (delta < bestDelta) {
                bestDelta = delta
                best = candidate
            }
        }
        when (val move = best ?: return) {
            is Move.BoolFlip -> sink.addBoolFlip(move.varId)
            is Move.IntSet -> sink.addIntSet(move.varId, move.newValue)
            is Move.RealSet -> sink.addRealSet(move.varId, move.newValue)
            is Move.Compound -> sink.addCompound(move.parts)
        }
    }

    /** Catalog identity and defaults. */
    companion object {
        /** Catalog id for this source. */
        val ID: MoveSourceId = MoveSourceId("argmin-jump")

        /** Default cap on domain values evaluated per wide-domain int variable. */
        const val DEFAULT_MAX_VALUE_TRIES: Int = 16
    }
}
