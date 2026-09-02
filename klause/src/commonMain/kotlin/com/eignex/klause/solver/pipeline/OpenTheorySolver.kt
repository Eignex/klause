package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.OpenPresolveResult
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.closeOpenBounds
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.ProblemPipeline
import com.eignex.klause.solver.result.OpenTheoryClauseStats
import com.eignex.klause.solver.result.OpenTheoryWorkSink
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchLearnedDbParams
import com.eignex.klause.solver.search.SearchRestart
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.theory.qflra.ExactLiraAssignment
import com.eignex.klause.theory.qflra.ExactLraAssignment
import com.eignex.klause.util.Cancellation

/** A complete witness emitted by an open-model theory route. */
sealed interface OpenTheoryAssignment {
    /** Boolean value at source variable [id]. */
    fun boolValue(id: Int): Boolean

    /** Exact integer value at source variable [id], in decimal form. */
    fun intValue(id: Int): String

    /** Exact real value at source variable [id], in rational or decimal form. */
    fun realValue(id: Int): String

    /** A Long-backed integer witness from the difference theory. */
    data class Difference(
        /** Difference-theory witness. */
        val sample: Sample,
    ) : OpenTheoryAssignment {
        override fun boolValue(id: Int): Boolean = sample.bools[id]
        override fun intValue(id: Int): String = sample.ints[id].toString()
        override fun realValue(id: Int): String = sample.reals.getOrElse(id) { 0.0 }.toString()
    }

    /** A rational real witness from exact QF_LRA. */
    data class ExactLra(
        /** Exact QF_LRA witness. */
        val assignment: ExactLraAssignment,
    ) : OpenTheoryAssignment {
        override fun boolValue(id: Int): Boolean = assignment.bools[id]
        override fun intValue(id: Int): String = error("exact LRA has no integer variables: $id")
        override fun realValue(id: Int): String = assignment.reals[id].toString()
    }

    /** A mixed arbitrary-precision integer and rational witness from exact QF_LIRA. */
    data class ExactLira(
        /** Exact QF_LIRA witness. */
        val assignment: ExactLiraAssignment,
    ) : OpenTheoryAssignment {
        override fun boolValue(id: Int): Boolean = assignment.bools[id]
        override fun intValue(id: Int): String = assignment.ints[id].toString()
        override fun realValue(id: Int): String = assignment.reals[id].toString()
    }
}

/** The common verdict surface of the complete open-model theory routes. */
sealed interface OpenTheoryResult {
    /** Statistics collected while deciding the model. */
    val stats: SolveStats

    /** A satisfying witness. */
    data class Sat(
        /** Satisfying assignment. */
        val assignment: OpenTheoryAssignment,
        override val stats: SolveStats,
    ) : OpenTheoryResult

    /** A proof that the source model is infeasible. */
    data class Unsat(override val stats: SolveStats) : OpenTheoryResult

    /** An explicit budget or cancellation interrupted exact search. */
    data class Unknown(
        /** Reason exact search stopped. */
        val reason: TerminationReason,
        override val stats: SolveStats,
    ) : OpenTheoryResult
}

/**
 * Executes the complete theory route selected for a prepared open source model.
 *
 * The route and the ownership plan come from the prepared source, never from the model a caller handed
 * in: source-safe preparation is the one phase ahead of them, and it may rewrite the factors ownership
 * is indexed by. Frontends consume this uniform result rather than importing or dispatching to
 * individual theory implementations.
 */
class OpenTheoryEngine internal constructor(
    // The route the caller's source model declared, which is all there is to name a backend by when
    // preparation refutes the model before a plan exists.
    private val declaredRoute: ProblemPipeline,
    // Selecting the plan reads every factor and builds the theory fragment, which on a large model is
    // most of what a short budget has. Select it once and hand the same preparation to every solve.
    private val source: OpenSourcePreparation,
    private val preparationCancellation: Cancellation,
) {
    /**
     * Prepare [model]'s source, then decide it.
     *
     * [route] is the route the source model declares; the one the theory runs on is selected again from
     * what preparation produced, which is the only route that can be indexed by the factors it left.
     */
    constructor(model: Problem, route: ProblemPipeline) : this(
        model,
        route,
        PresolveConfig.DEFAULT,
        false,
        Cancellation.Never,
        null,
    )

    internal constructor(
        model: Problem,
        route: ProblemPipeline,
        presolveConfig: PresolveConfig,
        solutionSetSensitive: Boolean,
        presolveCancellation: Cancellation,
        presolveBudget: PresolveBudget?,
    ) : this(
        route,
        model.prepareOpenSource(
            config = presolveConfig,
            solutionSetSensitive = solutionSetSensitive,
            cancellation = presolveCancellation,
            budget = presolveBudget,
        ),
        presolveCancellation,
    )

    /** Decide an already-prepared model under the plan selected from it. */
    internal constructor(
        planned: OpenSourcePreparation.Planned,
        preparationCancellation: Cancellation,
    ) : this(planned.route, planned, preparationCancellation)

    init {
        require(declaredRoute != ProblemPipeline.FINITE_CP && declaredRoute != ProblemPipeline.UNSUPPORTED_OPEN) {
            "open theory solver requires a supported open source model"
        }
    }

    /** Decides the model through its component plan. */
    fun solve(params: TheoryParams = TheoryParams()): OpenTheoryResult = solve(
        params,
        OpenTheorySolveState(params),
    )

    /** Execute one feasibility round against the caller's solve-wide [state]. */
    internal fun solve(params: TheoryParams, state: OpenTheorySolveState): OpenTheoryResult {
        val work = state.work
        val cancellation = Cancellation { params.timeout() || params.cancellation() }
        val stats = SolveStatsSink(backend = declaredRoute.backendName())
        stats.presolve = source.prepared.stats.takeIf { source.prepared.changed || it.infeasible }
        stats.start()
        // Building the components reads the whole model, so a budget already spent is answered before
        // that work starts rather than after it.
        if (cancellation()) return unknown(params.timeout(), stats = stats, state = state)
        // Source preparation runs before the route exists, so its refutation is the model's verdict.
        val routed = when (source) {
            is OpenSourcePreparation.Refuted -> return OpenTheoryResult.Unsat(stats.finish(state))
            is OpenSourcePreparation.Planned -> source
        }
        val plan = routed.plan
        val route = routed.route
        stats.backend = route.backendName()
        // Close the open sides before the theory sees them. A proved bound narrows the box the theory
        // searches; a refutation here is over the genuinely open ranges, so it refutes the unbounded model
        // rather than an invented box, and is reportable as unsat.
        val model = when (val closed = routed.model.closeOpenBounds(boundCancellation(cancellation))) {
            OpenPresolveResult.Refuted -> return OpenTheoryResult.Unsat(stats.finish(state))
            is OpenPresolveResult.Tightened -> closed.spec
        }
        val cpDomains = plan.cpIntVars.associateWith { column ->
            IntDomain(model.intBounds.lower(column), model.intBounds.upper(column))
        }
        val planned = plan.search(
            model,
            cpDomains,
            maxChecks = params.maxLeaves,
            cancellation = cancellation,
            learnedDb = SearchLearnedDbParams(params.maxLearnedClauses, params.lbdGlue),
        )
        planned.session.attachOpenTheoryWork(work)
        when (planned.session.initialize()) {
            ComponentResult.Consistent -> Unit

            is ComponentResult.Conflict -> return OpenTheoryResult.Unsat(
                stats.finish(state),
            )

            ComponentResult.Indeterminate -> return unknown(
                params.timeout(),
                planned.session.checkBudgetExhausted(),
                stats,
                state,
            )
        }
        val solveParams = SearchSolveParams(
            maxDecisions = state.remainingDecisions(),
            restart = params.sharedRestart?.let(SearchRestart::Every) ?: SearchRestart.Never,
        )
        return when (val result = planned.session.solve(model.numBoolVars, solveParams)) {
            is SearchResult.Satisfied -> OpenTheoryResult.Sat(
                assignment(result.model, checkNotNull(planned.theory), route),
                stats.finish(state, planned.session),
            )

            SearchResult.Exhausted -> OpenTheoryResult.Unsat(stats.finish(state, planned.session))

            SearchResult.Indeterminate -> unknown(
                params.timeout(),
                planned.session.checkBudgetExhausted() || planned.session.decisionBudgetExhausted(),
                stats,
                state,
                planned.session,
            )
        }
    }

    /** The bound-closing phase's allowance: what is left of preparation's, capped by the solve's own. */
    private fun boundCancellation(cancellation: Cancellation): Cancellation = Cancellation {
        preparationCancellation() || cancellation() || source.prepared.budget?.remaining() == 0L
    }

    private fun unknown(
        timedOut: Boolean,
        budgetExhausted: Boolean = false,
        stats: SolveStatsSink,
        state: OpenTheorySolveState,
        session: com.eignex.klause.solver.search.SearchSession? = null,
    ): OpenTheoryResult.Unknown {
        stats.timedOut = timedOut
        return OpenTheoryResult.Unknown(
            if (timedOut) {
                TerminationReason.Timeout
            } else if (budgetExhausted || state.work.exhausted) {
                TerminationReason.BudgetExhausted
            } else {
                TerminationReason.Cancelled
            },
            stats.finish(state, session),
        )
    }

    private fun SolveStatsSink.finish(
        state: OpenTheorySolveState,
        session: com.eignex.klause.solver.search.SearchSession? = null,
    ): SolveStats {
        state.capture(session)
        openTheory = state.work.snapshot()
        openTheoryClauses = state.clauses
        stop()
        return snapshot()
    }

    private fun ProblemPipeline.backendName(): String = when (this) {
        ProblemPipeline.DIFFERENCE_THEORY -> "difference-theory"
        ProblemPipeline.EXACT_LRA -> "exact-lra"
        ProblemPipeline.EXACT_LIRA -> "exact-lira"
        ProblemPipeline.FINITE_CP, ProblemPipeline.UNSUPPORTED_OPEN -> error("not an open theory route")
    }

    private fun assignment(
        model: com.eignex.klause.solver.search.AssembledSearchModel,
        component: Any,
        route: ProblemPipeline,
    ): OpenTheoryAssignment = when (route) {
        ProblemPipeline.DIFFERENCE_THEORY -> OpenTheoryAssignment.Difference(
            checkNotNull(model.valueOf<Sample>(component)),
        )

        ProblemPipeline.EXACT_LRA -> OpenTheoryAssignment.ExactLra(
            checkNotNull(model.valueOf<ExactLraAssignment>(component)),
        )

        ProblemPipeline.EXACT_LIRA -> OpenTheoryAssignment.ExactLira(
            checkNotNull(model.valueOf<ExactLiraAssignment>(component)),
        )

        ProblemPipeline.FINITE_CP, ProblemPipeline.UNSUPPORTED_OPEN -> error("validated open theory route changed")
    }
}

/** Solve-wide controls and telemetry shared by every feasibility round of one open optimization. */
internal class OpenTheorySolveState(params: TheoryParams) {
    val work = OpenTheoryWorkSink(params.openWorkLimit)
    private val maxDecisions = params.maxDecisions
    var clauses = OpenTheoryClauseStats()
        private set

    fun remainingDecisions(): Long {
        val workStats = work.snapshot()
        val committed = workStats.openBoolDecisions + workStats.openIntDecisions + workStats.openTheoryDecisions
        return maxDecisions - committed
    }

    fun capture(session: com.eignex.klause.solver.search.SearchSession?) {
        session ?: return
        clauses = clauses.mergedWith(session.learnedClauseStats())
    }
}
