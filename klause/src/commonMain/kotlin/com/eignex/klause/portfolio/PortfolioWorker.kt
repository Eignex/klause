package com.eignex.klause.portfolio

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.ResumableOptimizer
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.Session
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.SolverParams
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult

/**
 * One configured engine instance in a `Portfolio`: a [Session] together with the base
 * params it runs under. The param type is **erased** behind plain `(Cancellation) -> …`
 * closures, so a single portfolio can hold a *heterogeneous* mix of workers — e.g. several
 * local-search workers racing alongside a backtrack worker — even though their param types
 * ([com.eignex.klause.localsearch.LocalSearchParams] vs
 * [com.eignex.klause.backtrack.BacktrackParams]) differ. Construct via [of], which
 * closes over the concrete `Session<P>`/`P` so the only unchecked cast is the
 * already-idiomatic `withCancellation` covariant-return one.
 */
class PortfolioWorker private constructor(
    /** Human-readable id for progress / telemetry (e.g. "cbls/fixed", "backtrack#2"). */
    val label: String,
    private val solveFn: (Cancellation) -> SolveResult,
    private val improvementsFn: (() -> Double, Sample?, Cancellation) -> Sequence<MinimizeResult>,
    private val samplesFn: (Cancellation) -> Sequence<Sample>,
    private val resumableFn: ((readBound: () -> Double) -> ResumableSearch)?,
    private val closeFn: () -> Unit,
) : AutoCloseable {

    /** Solve once, honouring [cancel] (set when a sibling wins the race). */
    fun solve(cancel: Cancellation): SolveResult = solveFn(cancel)

    /**
     * Open a fresh pause/resume handle over this worker's optimisation, or `null` when the engine
     * can't be paused/resumed (local search — it restarts cheaply from a warm-started incumbent
     * instead). [readBound] exposes the portfolio's shared best objective so the resumable backtrack
     * search prunes on it, exactly like [improvements]'s `withBound` seam. The single-threaded
     * [SequentialPortfolio] holds one handle per backtrack arm and resumes it each segment, so the arm
     * never cold-restarts between slices (#381). */
    fun newResumableSearch(readBound: () -> Double): ResumableSearch? = resumableFn?.invoke(readBound)

    /** Stream improving incumbents against this worker's *own* objective representation (the one
     *  it was built with — see [of]). [readBound] exposes the portfolio's shared best objective
     *  scalar; workers that prune on it (backtrack) read it via their injected bound supplier,
     *  workers that don't (LS) simply ignore it. The two representations stay comparable because
     *  both minimise the same FlatZinc objective var, so [MinimizeResult.objectiveValue] is one
     *  scalar the portfolio can fold across a heterogeneous pool.
     *
     *  [warmStart] is the portfolio's current incumbent assignment, handed to workers that can
     *  resume from it (local search via its `initialAssignment` seam; see [of]'s `withWarmStart`).
     *  Workers without that seam ignore it. The concurrent `Portfolio` passes null (workers share
     *  the live bound, not a snapshot); the single-threaded [SequentialPortfolio] passes the
     *  incumbent so a fresh LS segment descends from it rather than a random restart. */
    fun improvements(
        readBound: () -> Double,
        cancel: Cancellation,
        warmStart: Sample? = null,
    ): Sequence<MinimizeResult> = improvementsFn(readBound, warmStart, cancel)

    /** Stream diverse samples, honouring [cancel] (set when the collector stops). */
    fun samples(cancel: Cancellation): Sequence<Sample> = samplesFn(cancel)

    override fun close() {
        closeFn()
    }

    /** Factory for type-erased workers over a concrete typed session. */
    companion object {
        /**
         * Wrap a typed [session] + base [params] as a type-erased worker. [objective] is the
         * canonical [LinearObjective] the worker minimises (engine-specific views, like the LS
         * gradient objective, travel inside the worker's own params). It may be null for a
         * satisfaction-only worker that never streams [improvements]; calling [improvements] on
         * such a worker fails fast. [withBound] injects
         * the portfolio's shared objective bound into the params for [improvements] (e.g.
         * `{ p, supplier -> p.copy(objectiveBoundSupplier = supplier) }` for backtrack); pass
         * null for engines that don't bound-prune (local search). [withWarmStart] injects a
         * portfolio incumbent assignment into the params for [improvements] (e.g.
         * `{ p, sample -> p.copy(initialAssignment = sample) }` for local search); pass null for
         * engines with no warm-start seam (backtrack, which only consumes the shared bound).
         */
        fun <P : SolverParams> of(
            label: String,
            session: Session<P>,
            params: P,
            objective: LinearObjective? = null,
            withWarmStart: ((P, Sample) -> P)? = null,
            withBound: ((P, () -> Double) -> P)? = null,
        ): PortfolioWorker {
            // withCancellation declares a SolverParams return on the interface but every
            // backend overrides it covariantly to return its own P; the cast is sound.
            @Suppress("UNCHECKED_CAST")
            fun withCancel(c: Cancellation): P = params.withCancellation(c) as P
            // A pause/resume handle is available only for an optimising worker over a ResumableOptimizer
            // engine (backtrack). The handle owns its own per-slice cancellation, so only the bound
            // supplier is wired here; warm-start is irrelevant (the live session carries the search).
            val resumableOpt = session.solver as? ResumableOptimizer<P>
            val resumableFn: ((() -> Double) -> ResumableSearch)? =
                if (objective != null && resumableOpt != null) {
                    { readBound ->
                        val p = withBound?.invoke(params, readBound) ?: params
                        resumableOpt.resumable(objective, p)
                    }
                } else {
                    null
                }
            return PortfolioWorker(
                label = label,
                solveFn = { c -> session.solve(withCancel(c)) },
                improvementsFn = { readBound, warmStart, c ->
                    val obj = requireNotNull(objective) {
                        "PortfolioWorker '$label' was built without an objective; cannot stream improvements"
                    }
                    var p = withCancel(c)
                    p = withBound?.invoke(p, readBound) ?: p
                    if (warmStart != null && withWarmStart != null) p = withWarmStart(p, warmStart)
                    session.improvements(obj, p)
                },
                samplesFn = { c -> session.samples(withCancel(c)) },
                resumableFn = resumableFn,
                closeFn = { session.close() },
            )
        }
    }
}
