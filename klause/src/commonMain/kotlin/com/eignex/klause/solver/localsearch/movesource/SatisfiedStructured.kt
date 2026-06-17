package com.eignex.klause.solver.localsearch.movesource

import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink

/**
 * Feasibility-preserving structured moves drawn from currently-satisfied factors — the single
 * implementation behind `Cbls.sampleFromSatisfied`, `LocalSearchSolver.structuredMoveStep`, and the
 * implicit-neighbourhood `sampleElectedStructured` (epic #710). Each satisfied factor pushes moves
 * it knows preserve its own satisfaction (e.g. a `Linear EQ` pair-shift that keeps the sum, a
 * `Cardinality.exactlyOne` swap that keeps the count) via
 * [com.eignex.klause.solver.Factor.proposeStructuredMoves].
 *
 * The former call sites differed only in *which* satisfied factors they consulted — exactly the
 * "one generator parameterised by factor set" the epic calls for, captured here by [scope]:
 *  - [Scope.Sampled] — `Cbls.sampleFromSatisfied`: draw [sampleCount] uniformly-random factors and
 *    keep the ones not in the maintained violated set. Cheaper than enumerating when the
 *    satisfied/violated split is not materialised.
 *  - [Scope.All] — `LocalSearchSolver.structuredMoveStep`: walk every factor and consult the ones
 *    that are not violated (queried directly via [com.eignex.klause.solver.Factor.isViolated]).
 *  - [Scope.Elected] — the implicit-neighbourhood variant (`Cbls.sampleElectedStructured`): draw
 *    [sampleCount] random factors from the state's elected implicit set
 *    ([com.eignex.klause.solver.localsearch.LocalSearchState.electedImplicit]) and keep the ones
 *    not in the maintained violated set. Iterates only the small elected set, so it stays cheap
 *    while the search is still closing violations — the implicit-neighbourhood source available to
 *    any strategy by configuration rather than as a copy-pasted loop.
 *
 * Each scope preserves the *exact* violated-skip predicate its former site used (set-membership vs
 * per-factor query) so the extraction is behaviour-identical. All belong to [Phase.Feasible]:
 * structured moves only matter once the search is at `cost == 0` looking for objective-improving
 * steps — the gate the callers re-checked is now declarative.
 */
class SatisfiedStructured private constructor(
    private val scope: Scope,
    /** Factors to sample per call in [Scope.Sampled] / [Scope.Elected] (ignored for [Scope.All]). */
    private val sampleCount: Int,
) : MoveSource {

    /** Which satisfied factors a [SatisfiedStructured] consults. */
    enum class Scope {
        /** Draw a fixed number of uniformly-random factors (the CBLS sampling loop). */
        Sampled,

        /** Walk every factor (the minimize-engine enumeration). */
        All,

        /** Walk a caller-supplied elected set (the implicit-neighbourhood loop). */
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

    /** Factory + identity. */
    companion object {
        /** Catalog id for this source. */
        val ID: MoveSourceId = MoveSourceId("satisfied-structured")

        /** The CBLS random-sampling variant: keep [sampleCount] (≥ 0) random non-violated factors. */
        fun sampled(sampleCount: Int): SatisfiedStructured {
            require(sampleCount >= 0) { "sampleCount >= 0, got $sampleCount" }
            return SatisfiedStructured(Scope.Sampled, sampleCount)
        }

        /** The minimize-engine enumerate-all variant. */
        fun all(): SatisfiedStructured = SatisfiedStructured(Scope.All, sampleCount = 0)

        /** The implicit-neighbourhood variant: draw [sampleCount] (≥ 0) random factors from the
         *  state's elected implicit set, keeping the ones not currently violated. */
        fun elected(sampleCount: Int): SatisfiedStructured {
            require(sampleCount >= 0) { "sampleCount >= 0, got $sampleCount" }
            return SatisfiedStructured(Scope.Elected, sampleCount)
        }
    }
}
