package com.eignex.klause.presolve

import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.presolve.structural.RedundantConstraints.SubsumeIncremental
import com.eignex.klause.presolve.structural.RedundantConstraints.SubsumeMemo
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation

/**
 * Cost tier of a presolve pass. A
 * [PresolveEmphasis] enables a set of tiers, so the level dial is "how expensive a pass may be".
 */
enum class PresolveTiming {
    /** Cheap, run every round (bound/coefficient reductions, substitution). */
    FAST,

    /** Moderate cost (symmetry detection); run from the default level up. */
    MEDIUM,

    /** Expensive (SAC probing); run only at the aggressive level. */
    EXHAUSTIVE,
}

/** Round cap for the iterating emphasis levels; the fixpoint is almost always reached well before. */
internal const val MAX_PRESOLVE_ROUNDS = 16

/** Integer-variables-to-factors ratio above which a model is treated as *underdetermined*, so affine
 *  elimination caps its wide folds. Well below every model where affine folds
 *  productively (all observed ≤ ~2.4) and well below the pathological dense case (~23). */
private const val UNDERDETERMINED_RATIO = 8

/** Bake-time SAC probe budgets (the only EXHAUSTIVE-tier work). The unit is a `propagate` call.
 *  The capped tier bounds an EXHAUSTIVE pass turned on by an explicit override under a non-aggressive
 *  level so it can't dominate presolve time; the aggressive tier is larger but still bounded so a big
 *  instance with wide domains terminates. */
internal const val CAPPED_PROBE_BUDGET_PER_VAR = 256
internal const val CAPPED_PROBE_TOTAL_BUDGET = 20_000
internal const val AGGRESSIVE_PROBE_BUDGET_PER_VAR = 4_096
internal const val AGGRESSIVE_PROBE_TOTAL_BUDGET = 250_000

/** Cheap problem-complexity measure for the effectiveness abort: constraint count plus total domain
 *  span. Drops when a pass removes a constraint or tightens a domain; a round that instead grows the
 *  problem (symmetry breaking adding ordering constraints) simply doesn't trip the abort. */
private fun complexity(problem: Problem): Long {
    var c = problem.factors.size.toLong()
    for (d in problem.requireFiniteIntDomains()) c += d.max - d.min
    return c
}

/** Runs enabled problem-transform passes to a bounded fixpoint. */
object Presolver {

    /** Apply [config]'s passes to [problem] under [context], returning the transformed problem and a
     *  reconstruct mapping its solutions back to the original. [cancellation] is polled between passes
     *  and rounds: a fired deadline returns the partial result so far — every pass is individually
     *  sound, so stopping early only forgoes further reduction, never correctness. */
    fun run(
        problem: Problem,
        config: PresolveConfig,
        context: PresolveContext = PresolveContext.EMPTY,
        cancellation: Cancellation = Cancellation.Never,
    ): Presolved {
        val passes = config.problemPasses(context)
        val maxRounds = config.emphasis.maxRounds
        if (passes.isEmpty() || maxRounds == 0) return Presolved(problem, { it })
        // Coefficient strengthening runs first, before the [PresolveSession] seed forces the root bake:
        // a gcd-indivisible equality (`Σ cᵢ·xᵢ = b`, `gcd(cᵢ) ∤ b`) is infeasible independent of the
        // variable bounds, so it is caught here in O(factors) rather than letting the seed's bake narrow
        // it toward the empty domain one step per round — O(span) on a wide domain. Only the infeasible
        // case short-circuits; a feasible strengthening is recomputed (idempotently) by the round engine
        // below. (The CLI presolve driver runs the same check before its own [RootBaker] reseed.)
        if (PresolvePass.STRENGTHEN_COEFFICIENTS in passes &&
            Presolve.strengthenCoefficients(problem, cancellation).infeasible
        ) {
            return Presolved(problem, { it }, infeasible = true)
        }
        // Hand the round-engine cancellation to the passes so the long-running ones (affine fixpoint,
        // symmetry search) poll it internally — a presolve budget then bounds them, not just the gaps
        // between passes. Derive the underdetermined flag once from the original problem — later rounds
        // shrink the factor set, so a per-round ratio would spuriously read as underdetermined.
        val underdetermined = problem.factors.isNotEmpty() &&
            problem.numIntVars.toLong() > problem.factors.size.toLong() * UNDERDETERMINED_RATIO
        val ctx = context.withCancellation(cancellation)
            .withAffineUnderdetermined(underdetermined)
            .withAffinePivotOrder(config.affinePivotOrder)
            .withPresolveBudget(context.presolveBudget)

        // Incremental path for the default FAST+MEDIUM rounds: one persistent [PresolveSession] absorbs
        // each pass's delta instead of rebuilding a [Problem] per firing pass. Scoped away from any
        // EXHAUSTIVE (SAC / LP-harvest) pass, whose order-sensitive probing keeps the fresh-rebuild path.
        if (passes.none { it.timing == PresolveTiming.EXHAUSTIVE }) {
            return runIncremental(problem, passes, maxRounds, ctx, cancellation)
        }

        val host = object : RoundHost {
            var current = problem

            override fun passInput(): Problem = current

            override fun passContext(pass: PresolvePass): PresolveContext = ctx

            override fun applyDelta(delta: PassDelta) {
                current = current.withPassDelta(delta, ctx.bakeConfig)
            }

            override fun complexity(): Long = complexity(current)
        }
        val rounds = runRounds(passes, maxRounds, cancellation, host, ctx.presolveBudget)
        return Presolved(
            host.current,
            composeReconstructs(rounds.reconstructs),
            rounds.fired,
            host.current.baked is PropagationResult.Unsat,
        )
    }

    /**
     * Incremental variant of [run]'s round engine (default FAST+MEDIUM emphasis): identical
     * scheduling — priority order, version-stamp fixpoint skip, [PresolvePass.skipAfterEmpty] parking,
     * and the diminishing-returns abort — but a persistent [PresolveSession] absorbs each firing pass's
     * delta instead of rebuilding a [Problem]. Each pass reads a cheap [PresolveSession.passInput] view
     * and its delta is folded back via [PresolveSession.applyDelta]; the heavyweight solver [Problem]
     * is materialized once at the end.
     */
    private fun runIncremental(
        problem: Problem,
        passes: List<PresolvePass>,
        maxRounds: Int,
        ctx: PresolveContext,
        cancellation: Cancellation,
    ): Presolved {
        val session = PresolveSession(problem, ctx.bakeConfig)
        // Persistent subsume indices + the change-mark at subsume's last firing, so each re-run reprocesses
        // only the factors other passes changed in between instead of rescanning the whole live set.
        val subsumeMemo = SubsumeMemo()
        // Infeasibility does not stop the loop, mirroring the fresh path: a pass still fires and records
        // its factors on an already-infeasible problem (e.g. xor-units emitting contradictory units).
        // [PresolveSession.applyDelta] tracks those factor changes but skips re-propagating a conflicted
        // state, and the final materialized problem's bake surfaces the infeasibility.
        val host = object : RoundHost {
            var subsumeMark: PresolveSession.ChangeMark? = null

            // Affine's change-mark at its last firing; a re-run rescans only the variables touched since.
            var affineMark: PresolveSession.ChangeMark? = null

            // Dup-columns' change-mark at its last firing; a re-run's fast-bail tests only the variables
            // touched since instead of re-signing every column.
            var dupMark: PresolveSession.ChangeMark? = null

            override fun passInput(): Problem = session.passInput()

            // Hand the session's incrementally-maintained occurrence index only to the passes that read it
            // (affine / dup-columns), so a firing pass that changes the factor set does not force the
            // O(occurrences) dense-view rebuild for the non-consuming passes between it and the next
            // consumer. The view matches the pass input's factor order exactly, so decisions are unchanged.
            override fun passContext(pass: PresolvePass): PresolveContext {
                var passCtx = ctx
                if (pass == PresolvePass.REMOVE_REDUNDANT) {
                    passCtx = passCtx.withSubsumeIncremental(subsumeIncremental(session, subsumeMark, subsumeMemo))
                }
                if (pass == PresolvePass.ELIMINATE_AFFINE_SINGLETONS) {
                    passCtx = passCtx.withSharedIntOcc(session.passOccurrence())
                        .withAffineTouchedVars(touchedSince(affineMark))
                }
                if (pass == PresolvePass.MERGE_DUPLICATE_COLUMNS) {
                    passCtx = passCtx.withSharedIntOcc(session.passOccurrence())
                        .withDupColumnsTouchedVars(touchedSince(dupMark))
                }
                return passCtx
            }

            override fun applyDelta(delta: PassDelta) {
                session.applyDelta(delta)
            }

            // Advance each incremental pass's mark past its own delta so the next firing sees only what
            // other passes changed since (a pass's own output is folded into its persistent index/scan).
            override fun afterPass(pass: PresolvePass) {
                if (pass == PresolvePass.REMOVE_REDUNDANT) subsumeMark = session.changeMark()
                if (pass == PresolvePass.ELIMINATE_AFFINE_SINGLETONS) affineMark = session.changeMark()
                if (pass == PresolvePass.MERGE_DUPLICATE_COLUMNS) dupMark = session.changeMark()
            }

            // The variables changed since [mark], or `null` when there is no replayable mark (first firing
            // or a reseed invalidated it) so the pass falls back to a full scan.
            fun touchedSince(mark: PresolveSession.ChangeMark?): IntArray? =
                if (mark == null || session.markStale(mark)) null else session.touchedIntVarsSince(mark)

            override fun complexity(): Long = session.complexity()
        }
        val rounds = runRounds(passes, maxRounds, cancellation, host, ctx.presolveBudget)

        // No pass fired: presolve is a no-op, so return the input problem itself (identity) exactly like
        // the fresh path returns `current === problem` — several callers assertSame on a fixpoint.
        if (rounds.fired.isEmpty()) return Presolved(problem, { it }, emptyList())

        return Presolved(
            session.materialize(),
            composeReconstructs(rounds.reconstructs),
            rounds.fired,
            session.infeasible,
        )
    }

    /** The per-path operations the shared [runRounds] scheduler drives: where a pass reads its input,
     *  how its context is specialised, where a firing pass's delta lands, and the complexity measure
     *  for the diminishing-returns abort. [run] backs it with a rebuilt [Problem] per firing pass,
     *  [runIncremental] with a persistent [PresolveSession]. */

    private interface RoundHost {
        /** The problem view the next pass reads. */
        fun passInput(): Problem

        /** The context for [pass] over the current input (occurrence-index / subsume-memo hooks). */
        fun passContext(pass: PresolvePass): PresolveContext

        /** Fold a firing pass's [delta] into the state. */
        fun applyDelta(delta: PassDelta)

        /** Post-pass hook, invoked whether or not the pass fired. */
        fun afterPass(pass: PresolvePass) {}

        /** Current problem complexity, read at round boundaries for the abort check. */
        fun complexity(): Long
    }

    /** What a [runRounds] run produced: the passes that changed the problem (first-fire order) and
     *  the per-pass reconstructs in application order. */
    private class RoundResult(val fired: List<PresolvePass>, val reconstructs: List<(Sample) -> Sample>)

    /**
     * The fixpoint scheduler shared by [run] and [runIncremental], order-identical for
     * both hosts by construction. A monotone "problem version" is bumped on every change; a pass
     * that already ran at the current version is skipped until some other pass changes the problem —
     * the fixpoint/delay scheme. A [PresolvePass.skipAfterEmpty] pass that ran and changed nothing is
     * parked so the engine does not re-run its expensive, overwhelmingly fruitless search after every
     * later reduction. The effectiveness-based abort stops when a round simplified
     * the problem by less than [PRESOLVE_ABORT_FRACTION] of it — a round that grew the problem
     * (e.g. symmetry adding ordering constraints) or left it unchanged is left to the fixpoint check.
     */
    private fun runRounds(
        passes: List<PresolvePass>,
        maxRounds: Int,
        cancellation: Cancellation,
        host: RoundHost,
        budget: PresolveBudget? = null,
    ): RoundResult = PresolveRoundEngine.run(
        passes,
        maxRounds,
        cancellation,
        budget,
        host::passInput,
        host::passContext,
        host::applyDelta,
        host::afterPass,
        host::complexity,
    ).let { RoundResult(it.fired, it.reconstructs) }

    /** Compose per-pass reconstructs (application order) into the single solution-mapping function,
     *  applied in reverse so a final-problem solution maps all the way back to the original. */
    private fun composeReconstructs(reconstructs: List<(Sample) -> Sample>): (Sample) -> Sample =
        PresolveRoundEngine.compose(reconstructs)

    /** Build subsume's incremental input from the session: a full rebuild when it has no prior mark or the
     *  mark predates a reseed, else the factors added / dropped since. A factor added then dropped between
     *  firings has a null slot now, so `mapNotNull` omits it — it never entered the memo and needs no
     *  retraction. */
    private fun subsumeIncremental(
        session: PresolveSession,
        mark: PresolveSession.ChangeMark?,
        memo: SubsumeMemo,
    ): SubsumeIncremental = if (mark == null || session.markStale(mark)) {
        SubsumeIncremental(rebuild = true, emptyList(), emptyList(), memo)
    } else {
        val ids = session.addedIdsSince(mark)
        val added = ArrayList<Factor>(ids.size)
        for (id in ids) session.factorAt(id)?.let { added.add(it) }
        SubsumeIncremental(rebuild = false, added, session.droppedFactorsSince(mark), memo)
    }
}
