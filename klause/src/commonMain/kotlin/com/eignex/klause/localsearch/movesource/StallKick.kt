package com.eignex.klause.localsearch.movesource

import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.localsearch.MoveSizeDistribution
import com.eignex.klause.util.IntHashSet

/**
 * Targeted-kick perturbation. A random walk over the variable–factor occurrence graph starting at a
 * random violated factor, randomizing up to [kickVars] distinct variables along the way, flattened
 * into one atomic perturbation (first-write-wins per slot). A walk rather than a cone sample because
 * coupled structures (successor chains, channeling rings) stretch the stuck region many hops from
 * the violated factor, so following the coupling reaches e.g. the head of a parasitic successor
 * chain whose dangling tail is the only violation.
 *
 * Emits at most one move (the flattened perturbation) into the sink, or nothing when no eligible
 * variable is reachable. [Pool.ScoreOnly] / [Phase.Infeasible]: a kick is a rare, local escape for
 * the feasibility fight, fired on a certified-stuck window, not by the noise draw.
 */
class StallKick(
    /** Variables randomized per kick when [kickSize] is null; otherwise the distribution's mean. */
    private val kickVars: Int,
    /** Optional move-size distribution: when set, each kick draws its variable budget from it instead
     *  of using the fixed [kickVars], so kick strength varies over a principled mix rather than a
     *  constant. Null keeps the fixed-size behaviour. */
    private val kickSize: MoveSizeDistribution? = null,
) : MoveSource {
    init {
        require(kickVars >= 1) { "kickVars >= 1, got $kickVars" }
    }

    override val id: MoveSourceId = ID
    override val phase: Phase = Phase.Infeasible
    override val pool: Pool = Pool.ScoreOnly

    private val scratch: MoveSink = MoveSink()

    override fun generate(state: LocalSearchState, sink: MoveSink) {
        if (state.violated.isEmpty()) return
        val problem = state.problem
        var factorId = state.violated.random(state.rng)
        // Private scratch so frozen/defined filtering applies during the walk; channeling keeps
        // indicator bools consistent with kicked int values.
        scratch.clear()
        scratch.setAssumptions(state.assumptions)
        scratch.setInvariants(state.invariants)
        scratch.setOwners(state.seeding.ownerInt)
        val size = kickSize?.nextSize(state.rng) ?: kickVars
        var budget = size
        var attempts = size * ATTEMPTS_PER_KICK
        while (budget > 0 && attempts-- > 0) {
            // Step 1: a random variable of the current factor.
            val scope = state.problem.factors[factorId]
            val nInts = scope.intVars.size
            val nBools = scope.boolVars.size
            if (nInts + nBools == 0) break
            val pick = state.rng.nextInt(nInts + nBools)
            val occ: IntArray
            if (pick < nInts) {
                val v = scope.intVars[pick]
                val d = problem.intDomains[v]
                if (d.size > 1) {
                    val nv = d.valueAt(state.rng.nextInt(d.size)) // sparse-aware: never lands on a hole
                    if (nv != state.assignment.intValue(v)) {
                        scratch.addChannelingIntSet(state, v, nv)
                        budget--
                    }
                }
                occ = problem.lsIntOccurrences[v]
            } else {
                val v = scope.boolVars[pick - nInts]
                scratch.addBoolFlip(v)
                budget--
                occ = problem.lsBoolOccurrences[v]
            }
            // Step 2: hop to a random factor sharing that variable and continue the walk.
            if (occ.isEmpty()) break
            factorId = occ[state.rng.nextInt(occ.size)]
        }
        // Flatten everything queued into one atomic perturbation, first-write-wins per slot.
        val parts = ArrayList<Move>()
        val seenSlots = IntHashSet()
        fun addPart(p: Move) {
            val slot = when (p) {
                is Move.BoolFlip -> p.varId
                is Move.IntSet -> problem.numBoolVars + p.varId
                is Move.Compound -> return
            }
            if (seenSlots.add(slot)) parts.add(p)
        }
        for (m in scratch.list) {
            when (m) {
                is Move.Compound -> for (p in m.parts) addPart(p)
                else -> addPart(m)
            }
        }
        when (parts.size) {
            0 -> {}

            1 -> when (val p = parts[0]) {
                is Move.BoolFlip -> sink.addBoolFlip(p.varId)
                is Move.IntSet -> sink.addIntSet(p.varId, p.newValue)
                is Move.Compound -> { /* unreachable: parts are primitive */ }
            }

            else -> sink.addCompound(parts)
        }
    }

    /** Catalog identity. */
    companion object {
        /** Catalog id for this source. */
        val ID: MoveSourceId = MoveSourceId("stall-kick")

        /** Rejection-sampling attempts allowed per kicked variable. */
        private const val ATTEMPTS_PER_KICK = 4
    }
}
