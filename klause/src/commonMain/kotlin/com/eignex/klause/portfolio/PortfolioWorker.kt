package com.eignex.klause.portfolio

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.Session
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.SolverParams

/**
 * One configured engine instance in a [Portfolio]: a [Session] together with the base
 * params it runs under. The param type is **erased** behind plain `(Cancellation) -> …`
 * closures, so a single portfolio can hold a *heterogeneous* mix of workers — e.g. several
 * local-search workers racing alongside a backtrack worker — even though their param types
 * ([com.eignex.klause.solver.localsearch.LocalSearchParams] vs
 * [com.eignex.klause.solver.backtrack.BacktrackParams]) differ. Construct via [of], which
 * closes over the concrete `Session<P>`/`P` so the only unchecked cast is the
 * already-idiomatic `withCancellation` covariant-return one.
 */
class PortfolioWorker private constructor(
    /** Human-readable id for progress / telemetry (e.g. "cbls/fixed", "backtrack#2"). */
    val label: String,
    private val solveFn: (Cancellation) -> SolveResult,
    private val improvementsFn: (() -> Double, Cancellation) -> Sequence<MinimizeResult>,
    private val samplesFn: (Cancellation) -> Sequence<Sample>,
    private val closeFn: () -> Unit,
) : AutoCloseable {

    /** Solve once, honouring [cancel] (set when a sibling wins the race). */
    fun solve(cancel: Cancellation): SolveResult = solveFn(cancel)

    /** Stream improving incumbents against this worker's *own* objective representation (the one
     *  it was built with — see [of]). [readBound] exposes the portfolio's shared best objective
     *  scalar; workers that prune on it (backtrack) read it via their injected bound supplier,
     *  workers that don't (LS) simply ignore it. The two representations stay comparable because
     *  both minimise the same FlatZinc objective var, so [MinimizeResult.objectiveValue] is one
     *  scalar the portfolio can fold across a heterogeneous pool. */
    fun improvements(readBound: () -> Double, cancel: Cancellation): Sequence<MinimizeResult> =
        improvementsFn(readBound, cancel)

    /** Stream diverse samples, honouring [cancel] (set when the collector stops). */
    fun samples(cancel: Cancellation): Sequence<Sample> = samplesFn(cancel)

    override fun close() {
        closeFn()
    }

    /** Factory for type-erased workers over a concrete typed session. */
    companion object {
        /**
         * Wrap a typed [session] + base [params] as a type-erased worker. [objective] is the
         * worker's *own* objective representation — the functional/gradient objective for a
         * local-search worker, the [com.eignex.klause.solver.LinearObjective] for a backtrack
         * worker — so a heterogeneous pool no longer forces one representation on every engine
         * (see #63). It may be null for a satisfaction-only worker that never streams
         * [improvements]; calling [improvements] on such a worker fails fast. [withBound] injects
         * the portfolio's shared objective bound into the params for [improvements] (e.g.
         * `{ p, supplier -> p.copy(objectiveBoundSupplier = supplier) }` for backtrack); pass
         * null for engines that don't bound-prune (local search).
         */
        fun <P : SolverParams> of(
            label: String,
            session: Session<P>,
            params: P,
            objective: Objective? = null,
            withBound: ((P, () -> Double) -> P)? = null,
        ): PortfolioWorker {
            // withCancellation declares a SolverParams return on the interface but every
            // backend overrides it covariantly to return its own P; the cast is sound.
            @Suppress("UNCHECKED_CAST")
            fun withCancel(c: Cancellation): P = params.withCancellation(c) as P
            return PortfolioWorker(
                label = label,
                solveFn = { c -> session.solve(withCancel(c)) },
                improvementsFn = { readBound, c ->
                    val obj = requireNotNull(objective) {
                        "PortfolioWorker '$label' was built without an objective; cannot stream improvements"
                    }
                    val p = withCancel(c)
                    session.improvements(obj, withBound?.invoke(p, readBound) ?: p)
                },
                samplesFn = { c -> session.samples(withCancel(c)) },
                closeFn = { session.close() },
            )
        }
    }
}
