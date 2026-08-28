package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.ProblemSpec
import com.eignex.klause.lp.OpenIntBounds
import com.eignex.klause.lp.unitCubeSolution
import com.eignex.klause.presolve.OpenPresolveResult
import com.eignex.klause.presolve.presolveOpen
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.ComponentPlan
import com.eignex.klause.solver.pipeline.ProblemPipeline
import com.eignex.klause.solver.pipeline.componentPlan
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
import com.eignex.klause.theory.lia.GeneralLiaAssignment
import com.eignex.klause.theory.qflra.ExactLiraAssignment
import com.eignex.klause.theory.qflra.ExactLraAssignment
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger

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

    /** An arbitrary-precision integer witness from General LIA. */
    data class GeneralLia(
        /** General LIA witness. */
        val assignment: GeneralLiaAssignment,
    ) : OpenTheoryAssignment {
        override fun boolValue(id: Int): Boolean = assignment.bools[id]
        override fun intValue(id: Int): String = assignment.ints[id].toString()
        override fun realValue(id: Int): String = error("General LIA has no real variables: $id")
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
 * Selects and executes the complete theory route for an open source model.
 *
 * Route selection happens once from `ProblemSpec.componentPlan()`. Frontends consume this uniform result
 * rather than importing or dispatching to individual theory implementations.
 */
class OpenTheoryEngine internal constructor(
    model: ProblemSpec,
    route: ProblemPipeline,
    // Selecting the plan reads every factor and builds the theory fragment, which on a large model is
    // most of what a short budget has. Select it once and hand the same plan to every solve.
    private val plan: ComponentPlan,
) {
    private val model = model
    private val route = route

    constructor(model: ProblemSpec, route: ProblemPipeline) : this(model, route, model.componentPlan())

    init {
        require(route != ProblemPipeline.FINITE_CP && route != ProblemPipeline.UNSUPPORTED_OPEN) {
            "open theory solver requires a supported open source model"
        }
        require(plan.theoryPipeline == route) { "open theory route disagrees with component plan" }
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
        val stats = SolveStatsSink(backend = route.backendName())
        stats.start()
        // Building the components reads the whole model, so a budget already spent is answered before
        // that work starts rather than after it.
        if (cancellation()) return unknown(params.timeout(), stats = stats, state = state)
        // Presolve the open sides before the theory sees them. A proved bound narrows the box the theory
        // searches; a refutation here is over the genuinely open ranges, so it refutes the unbounded model
        // rather than an invented box, and is reportable as unsat.
        val (model, plan) = when (val presolved = presolveOpen(cancellation)) {
            OpenPresolveResult.Refuted -> return OpenTheoryResult.Unsat(stats.finish(state))
            is OpenPresolveResult.Tightened -> adopt(presolved)
        }
        // A model that still has an open side is the case the witness box serves worst: the box is derived
        // from the model's own coefficients and runs to millions of bits on the hard instances, and one
        // that wide cannot be bisected. A cube needs no box at all.
        cubeWitness(model, cancellation)?.let { return OpenTheoryResult.Sat(it, stats.finish(state)) }
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
                assignment(result.model, checkNotNull(planned.theory)),
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

    /** Tighten the open sides under [cancellation]; see [com.eignex.klause.presolve.presolveOpen]. */
    private fun presolveOpen(cancellation: Cancellation): OpenPresolveResult = model.presolveOpen(cancellation)

    /**
     * A cube witness for [spec], or null where the test does not apply or finds none.
     *
     * A unit cube satisfies the linear rows and nothing else, so it is offered only over a **conjunctive
     * integer** model — no Booleans, no reals, every factor an unconditional [Linear] over integer terms.
     * Under any other factor the cube's point is a partial assignment the rest of the model may reject.
     * [ProblemPipeline.GENERAL_LIA] is the only route that shape reaches with an open column left; the
     * difference route decides its own fragment completely and gains nothing from a witness.
     *
     * Restricted further to a model with a side still open. Elsewhere the search runs over the model's own
     * domains, where the box is the declared range rather than an invented one and needs no shortcut.
     *
     * The test is incomplete and self-checking: it verifies its rounded centre against the rows before
     * returning it, so a null costs a missed solution and never a wrong verdict.
     */
    private fun cubeWitness(spec: ProblemSpec, cancellation: Cancellation): OpenTheoryAssignment? {
        if (route != ProblemPipeline.GENERAL_LIA) return null
        if (spec.numBoolVars != 0 || spec.numRealVars != 0) return null
        val columns = spec.numIntVars
        if (columns == 0) return null
        val rows = ArrayList<Linear>(spec.factors.size)
        for (f in spec.factors) {
            if (f !is Linear || f.realVars.isNotEmpty()) return null
            rows.add(f)
        }
        val open = Array(columns) { v ->
            OpenIntBounds(
                if (spec.intBounds.hasLower(v)) spec.intBounds.lower(v) else null,
                if (spec.intBounds.hasUpper(v)) spec.intBounds.upper(v) else null,
            )
        }
        if (open.none { it.lo == null || it.hi == null }) return null
        val values = unitCubeSolution(open, rows, cancellation) ?: return null
        return OpenTheoryAssignment.GeneralLia(
            GeneralLiaAssignment(BooleanArray(0), Array(columns) { BigInteger.fromLong(values[it]) }),
        )
    }

    /**
     * The model and plan to decide with, given what the tightening proved.
     *
     * The tighter bounds are always adopted; the plan selected from the declared ones is kept. Ownership
     * is per column and a bound that shrank cannot change it — a theory that could hold an open column
     * can hold a narrower one — so the original plan stays valid over the tighter model, and the theory
     * simply searches a smaller box.
     *
     * Re-planning is deliberately not done. Closing every open side makes the model finite, and a finite
     * model's plan is [ProblemPipeline.FINITE_CP] with no theory component at all; adopting that here
     * would leave nothing to decide with. Routing such a model to the finite lane is the caller's to do.
     */
    private fun adopt(presolved: OpenPresolveResult.Tightened): Pair<ProblemSpec, ComponentPlan> =
        if (presolved.closedSides == 0) model to plan else presolved.spec to plan

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
        ProblemPipeline.GENERAL_LIA -> "general-lia"
        ProblemPipeline.EXACT_LRA -> "exact-lra"
        ProblemPipeline.EXACT_LIRA -> "exact-lira"
        ProblemPipeline.FINITE_CP, ProblemPipeline.UNSUPPORTED_OPEN -> error("not an open theory route")
    }

    private fun assignment(
        model: com.eignex.klause.solver.search.AssembledSearchModel,
        component: Any,
    ): OpenTheoryAssignment = when (route) {
        ProblemPipeline.DIFFERENCE_THEORY -> OpenTheoryAssignment.Difference(
            checkNotNull(model.valueOf<Sample>(component)),
        )

        ProblemPipeline.GENERAL_LIA -> OpenTheoryAssignment.GeneralLia(
            checkNotNull(model.valueOf<GeneralLiaAssignment>(component)),
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
