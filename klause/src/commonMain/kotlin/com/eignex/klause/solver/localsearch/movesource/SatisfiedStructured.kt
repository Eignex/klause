package com.eignex.klause.solver.localsearch.movesource

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
 *  - [Scope.Elected] — the implicit-neighbourhood variant (`sampleElectedStructured`): consult a
 *    caller-supplied set of *elected* factor ids (the implicit-neighbourhood factors a model
 *    elects), keeping the ones not in the maintained violated set. The set's origin is a call-site
 *    concern; the generator over it is identical structured sampling, so it lives here too rather
 *    than as a copy-pasted loop.
 *
 * Each scope preserves the *exact* violated-skip predicate its former site used (set-membership vs
 * per-factor query) so the extraction is behaviour-identical. All belong to [Phase.Feasible]:
 * structured moves only matter once the search is at `cost == 0` looking for objective-improving
 * steps — the gate the callers re-checked is now declarative.
 */
class SatisfiedStructured private constructor(
    private val scope: Scope,
    /** Factors to sample per call in [Scope.Sampled] (ignored otherwise). */
    private val sampleCount: Int,
    /** Elected factor ids consulted in [Scope.Elected] (empty otherwise). */
    private val electedFactors: IntArray,
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

            Scope.Elected -> for (fid in electedFactors) {
                if (!state.violated.contains(fid)) {
                    state.factors[fid].proposeStructuredMoves(state, fid, sink)
                }
            }
        }
    }

    /** Factory + identity. */
    companion object {
        /** Catalog id for this source. */
        val ID: MoveSourceId = MoveSourceId("satisfied-structured")

        private val NO_FACTORS = IntArray(0)

        /** The CBLS random-sampling variant: keep [sampleCount] (≥ 0) random non-violated factors. */
        fun sampled(sampleCount: Int): SatisfiedStructured {
            require(sampleCount >= 0) { "sampleCount >= 0, got $sampleCount" }
            return SatisfiedStructured(Scope.Sampled, sampleCount, NO_FACTORS)
        }

        /** The minimize-engine enumerate-all variant. */
        fun all(): SatisfiedStructured = SatisfiedStructured(Scope.All, sampleCount = 0, NO_FACTORS)

        /** The implicit-neighbourhood variant: consult exactly the [electedFactors] not currently
         *  violated. The elected set is supplied by the caller (the model's implicit factors). */
        fun elected(electedFactors: IntArray): SatisfiedStructured =
            SatisfiedStructured(Scope.Elected, sampleCount = 0, electedFactors.copyOf())
    }
}
