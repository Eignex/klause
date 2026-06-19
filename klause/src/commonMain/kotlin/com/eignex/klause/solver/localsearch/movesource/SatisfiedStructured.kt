package com.eignex.klause.solver.localsearch.movesource

import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink

/**
 * Feasibility-preserving structured moves drawn from currently-satisfied factors. Each satisfied
 * factor pushes moves it knows preserve its own satisfaction (e.g. a `Linear EQ` pair-shift that
 * keeps the sum, a `Cardinality.exactlyOne` swap that keeps the count) via
 * [com.eignex.klause.solver.Factor.proposeStructuredMoves].
 *
 * [scope] selects which satisfied factors are consulted:
 *  - [Scope.Sampled]: draw [sampleCount] uniformly-random factors and keep the ones not in the
 *    maintained violated set. Cheaper than enumerating when the satisfied/violated split is not
 *    materialised.
 *  - [Scope.All]: walk every factor and consult the ones not violated (queried directly via
 *    [com.eignex.klause.solver.Factor.isViolated]).
 *  - [Scope.Elected]: draw [sampleCount] random factors from the state's elected implicit set
 *    ([com.eignex.klause.solver.localsearch.LocalSearchState.electedImplicit]) and keep the ones not
 *    in the maintained violated set. Iterates only the small elected set, so it stays cheap while
 *    the search is still closing violations.
 *
 * All scopes belong to [Phase.Feasible]: structured moves only matter once the search is at
 * `cost == 0` looking for objective-improving steps.
 */
class SatisfiedStructured private constructor(
    private val scope: Scope,
    /** Factors to sample per call in [Scope.Sampled] / [Scope.Elected] (ignored for [Scope.All]). */
    private val sampleCount: Int,
) : MoveSource {

    /** Which satisfied factors a [SatisfiedStructured] consults. */
    enum class Scope {
        /** Draw a fixed number of uniformly-random factors. */
        Sampled,

        /** Walk every factor. */
        All,

        /** Walk the elected implicit set. */
        Elected,
    }

    override val id: MoveSourceId = ID
    override val phase: Phase = Phase.Feasible
    override val pool: Pool = Pool.NoiseEligible

    override fun generate(state: LocalSearchState, sink: MoveSink) {
        val total = state.problem.numFactors
        if (total == 0) return
        when (scope) {
            Scope.Sampled -> repeat(sampleCount) {
                val fid = state.rng.nextInt(total)
                if (!state.violated.contains(fid)) {
                    state.factors[fid].proposeStructuredMoves(state, fid, sink)
                }
            }

            Scope.All -> for (fid in 0 until total) {
                val f = state.factors[fid]
                if (!f.isViolated(state, fid)) f.proposeStructuredMoves(state, fid, sink)
            }

            Scope.Elected -> {
                val elected = state.electedImplicit
                val n = elected.size
                if (n == 0) return
                repeat(minOf(sampleCount, n)) {
                    val fid = elected[state.rng.nextInt(n)]
                    if (!state.violated.contains(fid)) {
                        state.factors[fid].proposeStructuredMoves(state, fid, sink)
                    }
                }
            }
        }
    }

    /** Catalog identity and scope factories. */
    companion object {
        /** Catalog id for this source. */
        val ID: MoveSourceId = MoveSourceId("satisfied-structured")

        /** Random-sampling variant: keep [sampleCount] (≥ 0) random non-violated factors. */
        fun sampled(sampleCount: Int): SatisfiedStructured {
            require(sampleCount >= 0) { "sampleCount >= 0, got $sampleCount" }
            return SatisfiedStructured(Scope.Sampled, sampleCount)
        }

        /** Enumerate-all variant. */
        fun all(): SatisfiedStructured = SatisfiedStructured(Scope.All, sampleCount = 0)

        /** Elected-implicit variant: draw [sampleCount] (≥ 0) random factors from the state's elected
         *  implicit set, keeping the ones not currently violated. */
        fun elected(sampleCount: Int): SatisfiedStructured {
            require(sampleCount >= 0) { "sampleCount >= 0, got $sampleCount" }
            return SatisfiedStructured(Scope.Elected, sampleCount)
        }
    }
}
