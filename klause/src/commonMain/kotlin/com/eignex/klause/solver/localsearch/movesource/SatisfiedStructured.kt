package com.eignex.klause.solver.localsearch.movesource

import com.eignex.klause.solver.localsearch.MoveSink

/**
 * Feasibility-preserving structured moves drawn from currently-satisfied factors — the single
 * implementation behind both `Cbls.sampleFromSatisfied` and `LocalSearchSolver.structuredMoveStep`
 * (epic #710). Each satisfied factor pushes moves it knows preserve its own satisfaction (e.g. a
 * `Linear EQ` pair-shift that keeps the sum, a `Cardinality.exactlyOne` swap that keeps the count)
 * via [com.eignex.klause.solver.Factor.proposeStructuredMoves].
 *
 * The two former call sites differed only in *which* satisfied factors they consulted, captured
 * here by [scope]:
 *  - [Scope.Sampled] — `Cbls.sampleFromSatisfied`: draw [sampleCount] uniformly-random factors and
 *    keep the ones not in the maintained violated set. Cheaper than enumerating when the
 *    satisfied/violated split is not materialised.
 *  - [Scope.All] — `LocalSearchSolver.structuredMoveStep`: walk every factor and consult the ones
 *    that are not violated (queried directly via [com.eignex.klause.solver.Factor.isViolated]).
 *
 * The two scopes preserve the *exact* violated-skip predicate each former site used (set-membership
 * vs per-factor query) so the extraction is behaviour-identical. Both belong to [Phase.Feasible]:
 * structured moves only matter once the search is at `cost == 0` looking for objective-improving
 * steps — the gate the callers re-checked is now declarative.
 */
class SatisfiedStructured private constructor(
    private val scope: Scope,
    /** Factors to sample per call in [Scope.Sampled] (ignored for [Scope.All]). */
    private val sampleCount: Int,
) : MoveSource {

    /** Which satisfied factors a [SatisfiedStructured] consults. */
    enum class Scope {
        /** Draw a fixed number of uniformly-random factors (the CBLS sampling loop). */
        Sampled,

        /** Walk every factor (the minimize-engine enumeration). */
        All,
    }

    override val id: MoveSourceId = ID
    override val phase: Phase = Phase.Feasible
    override val pool: Pool = Pool.NoiseEligible

    override fun generate(ctx: MoveGenContext, sink: MoveSink) {
        val state = ctx.state
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
    }
}
