package com.eignex.klause.localsearch.movesource

import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.presolve.Presolve
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem

/**
 * At-most-one clique swap proposals. Each clique from [Presolve.amoCliques] (Lit-encoded, at most one
 * member satisfied) is treated as a categorical "which member is on, or none" choice. A clique-swap
 * turns the currently-on member off and a chosen other member on in one atomic [Move.Compound], so
 * the search steps directly between clique-feasible states instead of dwelling in the doubly-on
 * violating state two separate flips would pass through; an over-full clique with two members on
 * yields a repair that turns both off.
 *
 * The cliques are recognised once per solve and cached, keyed by problem identity — recognising them
 * per [generate] call would dominate the pick cost. Up to [cap] cliques are sampled per call.
 *
 * [Pool.ScoreOnly]: a coordinated 2-flip taken by the noise draw is a destructive perturbation;
 * score-picked it is the feasibility-preserving exchange the single-flip pool can't express.
 * [Phase.Any]: the swap repairs an over-full clique during the infeasibility fight and relocates a
 * categorical choice during objective descent alike.
 */
class CliqueSwap(
    /** Cap on clique swaps produced per [generate] call. */
    private val cap: Int,
) : MoveSource {
    init {
        require(cap >= 0) { "cap >= 0, got $cap" }
    }

    override val id: MoveSourceId = ID
    override val phase: Phase = Phase.Any
    override val pool: Pool = Pool.ScoreOnly

    /** Per-solve cache of the non-trivial clique literals, keyed by problem identity so a reused
     *  source recognises the cliques exactly once per problem. */
    private var cachedProblem: Problem? = null
    private var cachedCliques: Array<IntArray> = EMPTY_CLIQUES

    private fun cliquesFor(problem: Problem): Array<IntArray> {
        if (cachedProblem === problem) return cachedCliques
        val recognised = Presolve.amoCliques(problem)
            .asSequence()
            .map { it.toIntArray() }
            .filter { it.size >= 2 } // singleton/empty cliques admit no swap
            .toList()
        val cliques = Array(recognised.size) { recognised[it] }
        cachedProblem = problem
        cachedCliques = cliques
        return cliques
    }

    override fun generate(state: LocalSearchState, sink: MoveSink) {
        if (cap <= 0) return
        val cliques = cliquesFor(state.problem)
        if (cliques.isEmpty()) return
        val rng = state.rng
        var budget = cap
        var attempts = cap * ATTEMPTS_PER_SWAP
        while (budget > 0 && attempts-- > 0) {
            val lits = cliques[rng.nextInt(cliques.size)]
            if (proposeSwap(state, lits, sink)) budget--
        }
    }

    /** Push one clique move for [lits] into [sink], returning whether a candidate was produced.
     *  With one member on, a swap turns it off and a randomly chosen other member on; with two on,
     *  a repair turns both off; with none on, there is nothing to exchange. Returns false for the
     *  no-candidate and frozen-variable cases so the caller's budget tracks real candidates. */
    private fun proposeSwap(state: LocalSearchState, lits: IntArray, sink: MoveSink): Boolean {
        var onA = -1
        var onB = -1
        for (lit in lits) {
            if (Lit.evaluate(lit, state.assignment.boolValue(Lit.variable(lit)))) {
                if (onA < 0) onA = lit else onB = lit
            }
        }
        return when {
            onB >= 0 -> emitFlips(state, Lit.variable(onA), Lit.variable(onB), sink)
            onA >= 0 -> emitSwap(state, lits, onA, sink)
            else -> false // no member on: nothing to swap out
        }
    }

    /** Emit a swap that turns the on-member [onLit] off and a uniformly chosen other member on. */
    private fun emitSwap(state: LocalSearchState, lits: IntArray, onLit: Int, sink: MoveSink): Boolean {
        val target = lits[state.rng.nextInt(lits.size)]
        if (target == onLit) return false
        return emitFlips(state, Lit.variable(onLit), Lit.variable(target), sink)
    }

    /** Build the two-flip compound, rejecting same-variable pairs (a self-cancelling no-op) and
     *  frozen variables (the compound bypasses the sink's per-part frozen filter only for the size
     *  check, so guard explicitly). */
    private fun emitFlips(state: LocalSearchState, varOff: Int, varOn: Int, sink: MoveSink): Boolean {
        if (varOff == varOn) return false
        if (state.assumptions.isFrozenBool(varOff) || state.assumptions.isFrozenBool(varOn)) return false
        sink.addCompound(listOf(Move.BoolFlip(varOff), Move.BoolFlip(varOn)))
        return true
    }

    /** Catalog identity and defaults. */
    companion object {
        /** Catalog id for this source. */
        val ID: MoveSourceId = MoveSourceId("clique-swap")

        private val EMPTY_CLIQUES: Array<IntArray> = emptyArray()

        /** Rejection-sampling attempts allowed per requested swap. */
        private const val ATTEMPTS_PER_SWAP = 4
    }
}
