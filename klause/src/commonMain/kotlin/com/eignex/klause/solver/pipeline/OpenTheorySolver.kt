package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.OpenPresolveResult
import com.eignex.klause.presolve.PreparedSource
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.SourceMapping
import com.eignex.klause.presolve.SourceRebuilds
import com.eignex.klause.presolve.closeOpenBounds
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.ProblemPipeline
import com.eignex.klause.solver.result.LpRoute
import com.eignex.klause.solver.result.OpenHintStats
import com.eignex.klause.solver.result.OpenTheoryClauseStats
import com.eignex.klause.solver.result.OpenTheoryWorkSink
import com.eignex.klause.solver.result.PresolveStats
import com.eignex.klause.solver.result.SearchStats
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.search.BooleanBranching
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.HeuristicBooleanBranching
import com.eignex.klause.solver.search.SearchBranchKind
import com.eignex.klause.solver.search.SearchBranchSelection
import com.eignex.klause.solver.search.SearchCandidateHints
import com.eignex.klause.solver.search.SearchDecisionBudget
import com.eignex.klause.solver.search.SearchLearnedDbParams
import com.eignex.klause.solver.search.SearchNodePolicy
import com.eignex.klause.solver.search.SearchRestart
import com.eignex.klause.solver.search.SearchRun
import com.eignex.klause.solver.search.SearchRunEvent
import com.eignex.klause.solver.search.SearchRunObserver
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.solver.search.SearchStopCause
import com.eignex.klause.solver.search.Vsids
import com.eignex.klause.theory.EncoderDecline
import com.eignex.klause.theory.qflra.ExactLiraAssignment
import com.eignex.klause.theory.qflra.ExactLiraSearchComponent
import com.eignex.klause.theory.qflra.ExactLraAssignment
import com.eignex.klause.theory.qfnra.ExactNraAssignment
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import kotlin.time.TimeSource
import com.eignex.kumulant.stat.summary.MaxResult
import com.eignex.kumulant.stat.summary.SumResult

/** A complete witness emitted by an open-model theory route. */
sealed interface OpenTheoryAssignment {
    /** Boolean value at source variable [id]. */
    fun boolValue(id: Int): Boolean

    /** Exact integer value at source variable [id], in decimal form. */
    fun intValue(id: Int): String

    /**
     * Exact real value at source variable [id]. Rational routes retain rational or decimal text.
     * An irrational coordinate uses `root(index;[c0,c1,...])`, with rational coefficients in
     * ascending exponent order and a one-based index among distinct ordered real roots.
     * [algebraicRealValue] provides typed reconstruction metadata for polynomial-real witnesses.
     */
    fun realValue(id: Int): String

    /** A Long-backed integer witness from the difference theory. */
    data class Difference(
        /** Difference-theory witness. */
        val sample: Sample,
    ) : OpenTheoryAssignment {
        override fun boolValue(id: Int): Boolean = sample.boolValue(id)
        override fun intValue(id: Int): String = sample.exactIntValue(id).toString()
        override fun realValue(id: Int): String =
            (if (id < sample.numRealVars) sample.approximateRealValue(id) else 0.0).toString()
    }

    /** An assignment carried by the open portfolio, preserving its exact integer and real coordinates. */
    data class Sampled(
        /** Checked witness. */
        val sample: Sample,
    ) : OpenTheoryAssignment {
        override fun boolValue(id: Int): Boolean = sample.boolValue(id)
        override fun intValue(id: Int): String = sample.exactIntValue(id).toString()
        override fun realValue(id: Int): String =
            requireNotNull(sample.exactReals) { "a sampled witness carries certified real values" }[id].toString()
    }

    /** A rational real witness from exact QF_LRA. */
    data class ExactLra(
        /** Exact QF_LRA witness. */
        val assignment: ExactLraAssignment,
    ) : OpenTheoryAssignment {
        override fun boolValue(id: Int): Boolean = assignment.boolValue(id)
        override fun intValue(id: Int): String = error("exact LRA has no integer variables: $id")
        override fun realValue(id: Int): String = assignment.reals[id].toString()
    }

    /** A mixed arbitrary-precision integer and rational witness from exact QF_LIRA. */
    data class ExactLira(
        /** Exact QF_LIRA witness. */
        val assignment: ExactLiraAssignment,
    ) : OpenTheoryAssignment {
        override fun boolValue(id: Int): Boolean = assignment.boolValue(id)
        override fun intValue(id: Int): String = assignment.intValue(id).toString()
        override fun realValue(id: Int): String = assignment.reals[id].toString()
    }

    /** A rational or irrational real witness from exact polynomial arithmetic. */
    data class ExactNra(
        /** Owned exact polynomial-real witness. */
        val assignment: ExactNraAssignment,
    ) : OpenTheoryAssignment {
        override fun boolValue(id: Int): Boolean = assignment.boolValue(id)
        override fun intValue(id: Int): String = error("exact NRA has no integer variables: $id")
        override fun realValue(id: Int): String {
            val value = assignment.realValue(id)
            return value.rational?.toString()
                ?: "root(${value.rootIndex};[${value.definingCoefficients.joinToString(",")}])"
        }
    }

    /**
     * A witness of a reduced model, carrying the Boolean columns a source pass eliminated.
     *
     * Only the Booleans are restated: an eliminated column's value is computed here, while every column
     * the reduction left alone keeps whatever precision [base] answers it in.
     */
    class Rebuilt(
        /** Witness of the model the eliminating pass produced. */
        val base: OpenTheoryAssignment,
        /** Boolean values of the model before the elimination. */
        bools: BooleanArray,
        /** Integer values of the model before the elimination, or null when no column was eliminated. */
        ints: Array<BigInt>?,
    ) : OpenTheoryAssignment {
        private val booleanValues = bools.copyOf()
        private val integerValues = ints?.copyOf()

        /** Independent copy of recovered Boolean coordinates. */
        val bools: BooleanArray get() = booleanValues.copyOf()

        /** Independent copy of recovered integer coordinates, when present. */
        val ints: Array<BigInt>? get() = integerValues?.copyOf()

        override fun boolValue(id: Int): Boolean = booleanValues[id]
        override fun intValue(id: Int): String = integerValues?.get(id)?.toString() ?: base.intValue(id)
        override fun realValue(id: Int): String = base.realValue(id)
    }
}

/**
 * Typed exact scalar for a polynomial-real witness, including through source reconstruction.
 * Returns null for routes whose real coordinates retain their existing rational string surface.
 */
fun OpenTheoryAssignment.algebraicRealValue(id: Int): AlgebraicReal? = when (this) {
    is OpenTheoryAssignment.ExactNra -> assignment.realValue(id)
    is TranscendentalAssignment -> exactRealValue(id).algebraic
    is SourceCoordinateWitness -> real(id).algebraic
    is OpenTheoryAssignment.Rebuilt -> base.algebraicRealValue(id)
    else -> null
}

internal fun SourceMapping.lift(assignment: OpenTheoryAssignment): OpenTheoryAssignment =
    rebuild.lift(assignment, source.numBoolVars, source.numIntVars)

/**
 * [assignment] with the Boolean columns this rebuild recovers, over a model of [numBoolVars] columns.
 *
 * The open lane's half of the reconstruction: the steps are stated once, in the presolve layer, and each
 * lane reads its own witness into the `BooleanArray` they evaluate over. A witness answers by accessor
 * and cannot be written through, so the recovered values ride along in a wrapper rather than in place.
 *
 * [numBoolVars] and [numIntVars] count the columns before the elimination, and [assignment] is asked for
 * every one of them — including the eliminated ones, which a pass leaves in place and unconstrained. A
 * pass that renumbered instead would put an id past the end of the witness it produced.
 *
 * Integer values are read at arbitrary precision, which is the width an open route answers in; they are
 * materialized only when a step reads or recovers one, so literal-only reconstruction costs no parsing.
 */
internal fun SourceRebuilds.lift(
    assignment: OpenTheoryAssignment,
    numBoolVars: Int,
    numIntVars: Int,
): OpenTheoryAssignment {
    if (isEmpty) return assignment
    val bools = BooleanArray(numBoolVars) { assignment.boolValue(it) }
    if (!touchesInts) {
        rebuildInto(bools)
        return OpenTheoryAssignment.Rebuilt(assignment, bools, ints = null)
    }
    val ints = Array(numIntVars) { parseBigInt(assignment.intValue(it)) }
    rebuildInto(bools, ints)
    return OpenTheoryAssignment.Rebuilt(assignment, bools, ints)
}

/** The common verdict surface of the complete open-model theory routes. */
sealed interface OpenTheoryResult {
    /** Effective source interpretation, or null when the producer has no source binding. */
    val semantics: EffectiveSourceSemantics?

    /** Statistics collected while deciding the model. */
    val stats: SolveStats

    /** A satisfying witness. */
    data class Sat(
        /** Satisfying assignment. */
        val assignment: OpenTheoryAssignment,
        override val stats: SolveStats,
        override val semantics: EffectiveSourceSemantics? = null,
    ) : OpenTheoryResult

    /** A proof that the source model is infeasible. */
    data class Unsat(
        override val stats: SolveStats,
        override val semantics: EffectiveSourceSemantics? = null,
    ) : OpenTheoryResult

    /** An explicit budget or cancellation interrupted exact search. */
    data class Unknown(
        /** Reason exact search stopped. */
        val reason: TerminationReason,
        override val stats: SolveStats,
        override val semantics: EffectiveSourceSemantics? = null,
    ) : OpenTheoryResult
}

internal fun OpenTheoryResult.withSourceSemantics(semantics: EffectiveSourceSemantics): OpenTheoryResult {
    val previous = this.semantics
    require(previous == null ||
        (previous.declared == semantics.declared && previous.effective == semantics.effective)
    ) {
        "result belongs to a different source interpretation"
    }
    if (previous === semantics) return this
    return when (this) {
        is OpenTheoryResult.Sat -> copy(semantics = semantics)
        is OpenTheoryResult.Unsat -> copy(semantics = semantics)
        is OpenTheoryResult.Unknown -> copy(semantics = semantics)
    }
}

/**
 * Executes the complete theory route selected for a prepared open source model.
 *
 * The route and ownership plan describe the prepared source. Source-safe preparation may rewrite the
 * factors ownership is indexed by; only an unchanged model and projection binding retain a captured
 * plan. Frontends consume this uniform result rather than importing or dispatching to individual
 * theory implementations.
 */
class OpenTheoryEngine internal constructor(
    // The route the caller's source model declared, which is all there is to name a backend by when
    // preparation refutes the model before a plan exists.
    private val declaredRoute: ProblemPipeline,
    // Preparation is a phase of the solve, so it runs inside it under the caller's stop rather than
    // ahead of it. Its result is kept: selecting the plan reads every factor and builds the theory
    // fragment, which on a large model is most of what a short budget has, so several feasibility
    // rounds through one engine pay for it once.
    private val prepareSource: (Cancellation) -> OpenSourcePreparation,
    private val preparationCancellation: Cancellation,
    private val reuseOpeningBoundAttempt: Boolean,
    private val sourceSemantics: EffectiveSourceSemantics,
) {
    private var preparation: OpenSourcePreparation? = null

    /**
     * Decide [model], preparing its source as the solve's first phase.
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
        projections: PreparedFactorProjections? = null,
        capturedPlan: ComponentPlan? = null,
    ) : this(
        route,
        { cancellation ->
            model.prepareOpenSource(
                config = presolveConfig,
                solutionSetSensitive = solutionSetSensitive,
                cancellation = cancellation,
                budget = presolveBudget,
                projections = projections,
                presolveCancellation = presolveCancellation or cancellation,
                capturedPlan = capturedPlan,
            )
        },
        presolveCancellation,
        false,
        model.semantics,
    )

    /** Decide an already-prepared model under the plan selected from it. */
    internal constructor(
        planned: OpenSourcePreparation.Planned,
        preparationCancellation: Cancellation,
        reuseOpeningBoundAttempt: Boolean,
        declaredRoute: ProblemPipeline = planned.route,
    ) : this(declaredRoute, { planned }, preparationCancellation, reuseOpeningBoundAttempt,
        planned.prepared.source.semantics)

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
        val result = solvePrepared(params, state)
        val stats = result.stats.copy(smt = state.smt.snapshot())
        return when (result) {
            is OpenTheoryResult.Sat -> result.copy(stats = stats)
            is OpenTheoryResult.Unsat -> result.copy(stats = stats)
            is OpenTheoryResult.Unknown -> result.copy(stats = stats)
        }.withSourceSemantics(sourceSemantics)
    }

    private fun solvePrepared(params: TheoryParams, state: OpenTheorySolveState): OpenTheoryResult =
        when (val begun = begin(params, state, SearchNodePolicy.ExpandAll)) {
            is OpenTheoryStart.Decided -> begun.result

            is OpenTheoryStart.Running -> begun.run.use {
                checkNotNull(
                    it.advance(),
                ) { "an unsliced run never pauses" }
            }
        }

    /**
     * Prepare the source, close its open sides and set the search up, stopping at the first branch: decided when
     * preparation or the root settles the model, else a run [OpenTheoryRun.advance] drives. [nodePolicy] sees every
     * branch, so a caller that pauses there gets a run it can resume slice by slice; the theory's own checks stop
     * only on [params]' tokens, never on a slice.
     */
    @Suppress("TooGenericExceptionCaught") // Invocation cleanup preserves an arbitrary primary failure.
    internal fun begin(
        params: TheoryParams,
        state: OpenTheorySolveState,
        nodePolicy: SearchNodePolicy,
    ): OpenTheoryStart {
        val work = state.work
        val cancellation = params.timeout or params.cancellation
        val stats = SolveStatsSink(backend = declaredRoute.backendName())
        stats.start()
        // Preparation reads the whole model, so a budget already spent is answered before that work
        // starts rather than after it.
        if (cancellation()) return OpenTheoryStart.Decided(unknown(params.timeout(), stats = stats, state = state))
        // Preparation runs before the route exists, so its refutation is the model's verdict.
        val source = prepare(cancellation)
        val prepared = source.prepared
        stats.presolve = prepared.stats
        if (cancellation()) return OpenTheoryStart.Decided(unknown(params.timeout(), stats = stats, state = state))
        val routed = when (source) {
            is OpenSourcePreparation.Refuted -> return OpenTheoryStart.Decided(
                OpenTheoryResult.Unsat(stats.finish(state)),
            )

            is OpenSourcePreparation.Planned -> source
        }
        val selectedPlan = routed.plan
        if (selectedPlan.encodingDecline == EncoderDecline.RESOURCE) {
            return OpenTheoryStart.Decided(unknown(false, budgetExhausted = true, stats = stats, state = state))
        }
        val route = routed.route
        stats.backend = route.backendName()
        // Close the open sides before the theory sees them. A proved bound narrows the box the theory
        // searches; a refutation here is over the genuinely open ranges, so it refutes the unbounded model
        // rather than an invented box, and is reportable as unsat.
        val model = if (reuseOpeningBoundAttempt) {
            routed.model
        } else {
            val mark = TimeSource.Monotonic.markNow()
            val closed = try {
                routed.model.closeOpenBounds(
                    boundCancellation(prepared, cancellation),
                    stats.lp,
                )
            } finally {
                prepared.elapsed += mark.elapsedNow()
                stats.presolve = prepared.stats
            }
            when (closed) {
                OpenPresolveResult.Refuted -> return OpenTheoryStart.Decided(
                    OpenTheoryResult.Unsat(stats.finish(state)),
                )

                is OpenPresolveResult.Tightened -> closed.spec
            }
        }
        val plan = selectedPlan.forObjectiveRound(model, appended = false, cancellation = cancellation)
        if (cancellation()) return OpenTheoryStart.Decided(unknown(params.timeout(), stats = stats, state = state))
        val cpDomains = plan.cpSourceDomains(model)
        val planned = plan.search(
            model,
            cpDomains,
            maxChecks = params.maxLeaves,
            cancellation = cancellation,
            learnedDb = SearchLearnedDbParams(params.maxLearnedClauses, params.lbdGlue),
            smtStats = state.smt,
            theorySolveStop = cancellation,
            openHintFlips = params.openHintFlips,
        )
        var handedOff = false
        var failure: Throwable? = null
        try {
            (planned.theory as? ExactLiraSearchComponent)?.let {
                it.observePrimaryLpWith(stats.lp.certificationObserver(LpRoute.STANDALONE))
                it.observeCompleteTraversal()
                it.useMixedPointCompletion()
                if (params.openLinearIncremental) it.useIncrementalLinearPropagation()
            }
            planned.session.bindOpenTheoryWork(work)
            when (planned.session.initialize()) {
                ComponentResult.Consistent -> Unit

                is ComponentResult.Conflict -> return OpenTheoryStart.Decided(
                    OpenTheoryResult.Unsat(stats.finish(state)),
                )

                ComponentResult.Indeterminate -> return OpenTheoryStart.Decided(
                    unknown(
                        params.timeout(),
                        planned.session.checkBudgetExhausted(),
                        stats,
                        state,
                        cause = planned.session.executionControls.stopCause,
                    ),
                )
            }
            val solveParams = SearchSolveParams(
                maxDecisions = state.remainingDecisions(),
                restart = params.sharedRestart?.let(SearchRestart::Every) ?: SearchRestart.Never,
            )
            // Drawn here rather than beside the plan, so a root that propagation already refuted never pays
            // for a proposal no traversal would have read.
            val hints = state.candidateHints(plan, model, cancellation)
            val selectedBranching = when (params.openBranching) {
                OpenBranching.Automatic -> if (plan.theoryPipeline == ProblemPipeline.EXACT_LIRA &&
                    (planned.theory as? ExactLiraSearchComponent)?.defaultMixedPointOwner == true
                ) OpenBranching.Activity else OpenBranching.SourceOrder
                else -> params.openBranching
            }
            val branching = when (selectedBranching) {
                OpenBranching.Activity -> HeuristicBooleanBranching(Vsids(), model.numBoolVars)
                OpenBranching.SourceOrder, OpenBranching.Automatic -> BooleanBranching.SourceOrder(model.numBoolVars)
            }
            val run = planned.session.openRun(
                model.numBoolVars,
                solveParams,
                decisionBudget = state.decisionBudget,
                booleanBranching = branching,
                observer = branching as? SearchRunObserver ?: SearchRunObserver.None,
                nodePolicy = nodePolicy,
                candidateHints = observedCandidateHints(hints, planned.candidateHints, state.smt.hintUsage),
                branchSelection = if (plan.theoryPipeline == ProblemPipeline.DIFFERENCE_THEORY) {
                    SearchBranchSelection.Priority(listOf(SearchBranchKind.Arithmetic, SearchBranchKind.Boolean,
                        SearchBranchKind.Finite))
                } else if (plan.theoryPipeline == ProblemPipeline.EXACT_LIRA &&
                    plan.cpIntVars.isNotEmpty()
                ) SearchBranchSelection.Priority(listOf(SearchBranchKind.Boolean, SearchBranchKind.Arithmetic,
                    SearchBranchKind.Finite)) else SearchBranchSelection.Default,
            )
            handedOff = true
            return OpenTheoryStart.Running(OpenTheoryRun(planned, run, prepared, stats, state, params))
        } catch (primary: Throwable) {
            failure = primary
            throw primary
        } finally {
            if (!handedOff) planned.closeAfter(failure)
        }
    }

    /** A theory search held open across [advance] calls; closing it releases the search. */
    internal inner class OpenTheoryRun(
        private val planned: PlannedSearch,
        private val run: SearchRun,
        private val prepared: PreparedSource,
        private val stats: SolveStatsSink,
        private val state: OpenTheorySolveState,
        private val params: TheoryParams,
    ) : AutoCloseable {
        private val witnesses = SourceWitnessAssembler(prepared.mapping.target, prepared.source)

        val preparationStats: PresolveStats? get() = stats.presolve

        /** The search this run drives. */
        val session: com.eignex.klause.solver.search.SearchSession get() = planned.session

        /** Continue the search: its verdict, or null when the node policy paused it. */
        fun advance(): OpenTheoryResult? = when (val event = run.next()) {
            is SearchRunEvent.Satisfied -> {
                val assignment = witnesses.assemble(event.model, prepared.mapping)
                if (assignment != null) {
                    OpenTheoryResult.Sat(assignment, stats.finish(state, planned.session))
                } else if (params.timeout() || params.cancellation() || state.work.exhausted) {
                    unknown(params.timeout(), stats = stats, state = state, session = planned.session)
                } else {
                    OpenTheoryResult.Unknown(TerminationReason.Unsupported, stats.finish(state, planned.session))
                }
            }

            SearchRunEvent.Exhausted -> OpenTheoryResult.Unsat(stats.finish(state, planned.session))

            SearchRunEvent.Paused -> null

            is SearchRunEvent.Indeterminate -> unknown(
                params.timeout(),
                planned.session.checkBudgetExhausted() || planned.session.decisionBudgetExhausted(),
                stats,
                state,
                planned.session,
                planned.session.executionControls.stopCause,
            )
        }

        override fun close() = planned.close()
    }

    /** Prepare the source under the caller's stop, once per engine. */
    private fun prepare(cancellation: Cancellation): OpenSourcePreparation = preparation
        ?: prepareSource(cancellation).also {
            if (!cancellation() &&
                (it as? OpenSourcePreparation.Planned)?.plan?.encodingDecline != EncoderDecline.RESOURCE
            ) preparation = it
        }

    /** The bound-closing phase's allowance: what is left of preparation's, capped by the solve's own. */
    private fun boundCancellation(prepared: PreparedSource, cancellation: Cancellation): Cancellation =
        alsoStoppedBy(cancellation).let { stop -> prepared.budget?.orSpent(stop) ?: stop }

    /** The preparation allowance and the solve's own stop, whichever fires first. */
    private fun alsoStoppedBy(cancellation: Cancellation): Cancellation = preparationCancellation or cancellation

    private fun unknown(
        timedOut: Boolean,
        budgetExhausted: Boolean = false,
        stats: SolveStatsSink,
        state: OpenTheorySolveState,
        session: com.eignex.klause.solver.search.SearchSession? = null,
        cause: SearchStopCause? = null,
    ): OpenTheoryResult.Unknown {
        stats.timedOut = timedOut || cause == SearchStopCause.Timeout
        return OpenTheoryResult.Unknown(
            when {
                timedOut || cause == SearchStopCause.Timeout -> TerminationReason.Timeout


                budgetExhausted || state.work.exhausted || cause == SearchStopCause.WorkAllowance ||
                    cause == SearchStopCause.CheckAllowance || cause == SearchStopCause.DecisionAllowance ||
                    cause == SearchStopCause.OperationAllowance -> TerminationReason.BudgetExhausted

                cause == SearchStopCause.Cancelled -> TerminationReason.Cancelled
                cause == SearchStopCause.RepresentationDecline -> TerminationReason.Unsupported

                else -> TerminationReason.Cancelled
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
        smt = state.smt.snapshot()
        openTheoryClauses = state.clauses
        openHints = state.hints
        stop()
        return snapshot().let { it.copy(search = it.search.mergedWith(state.progress(null))) }
    }
}

/** The `-s` backend label for a complete open route. */
internal fun ProblemPipeline.backendName(): String = when (this) {
    ProblemPipeline.DIFFERENCE_THEORY -> "difference-theory"
    ProblemPipeline.EXACT_LRA -> "exact-lra"
    ProblemPipeline.EXACT_LIRA -> "exact-lira"
    ProblemPipeline.FINITE_CP, ProblemPipeline.UNSUPPORTED_OPEN -> error("not an open theory route")
}

/** Solve-wide controls and telemetry shared by every feasibility round of one open optimization. */
internal class OpenTheorySolveState(private val params: TheoryParams) {
    val work = OpenTheoryWorkSink(params.openWorkLimit)
    val smt = SmtStatsSink()
    private val maxDecisions = params.maxDecisions
    val decisionBudget = params.nodeBudget?.let { budget ->
        SearchDecisionBudget {
            val available = !budget.allowance.exhausted()
            // A concurrent stop cannot erase the charge for this committed decision.
            budget.allowance.spend()
            available
        }
    } ?: SearchDecisionBudget.Unlimited
    var clauses = OpenTheoryClauseStats()
        private set
    private var capturedGlue = 0L
    private var capturedRootFixed = 0

    /** What the request's hint draw produced, cost, and has steered so far. */
    val hints: OpenHintStats get() = drawStats.copy(
        steeredSplits = steering?.steeredSplits ?: 0L, usage = smt.hintUsage.snapshot(),
    )

    private var drawStats = OpenHintStats()
    private var steering: CountingCandidateHints? = null
    private var deferred: SearchCandidateHints? = null

    /**
     * The request's one unverified branch-order hint, deferred until enough splits have asked for it.
     *
     * Drawn at most once for the whole request. Every feasibility round of one descent shares the plan's
     * clauses — only the bound row moves, and no clause states it — so a redraw per round would spend
     * the allowance again to reach the same proposal. A draw that proposed nothing is remembered as
     * nothing and is not retried either, since retrying is what a spent or cancelled allowance has
     * already answered. The deferral carries across rounds with it, so a descent whose rounds each
     * branch a little accumulates toward one draw rather than restarting the count.
     */
    fun candidateHints(plan: ComponentPlan, model: Problem, cancellation: Cancellation): SearchCandidateHints {
        deferred?.let { return it }
        params.openHintFlips ?: return SearchCandidateHints.None.also { deferred = it }
        return DeferredCandidateHints(params.openHintMinSplits) { draw(plan, model, cancellation) }
            .also { deferred = it }
    }

    /** Spend the allowance once the traversal has shown it branches, within what the budget has left. */
    private fun draw(plan: ComponentPlan, model: Problem, cancellation: Cancellation): SearchCandidateHints {
        val flips = params.openHintFlips ?: return SearchCandidateHints.None
        // One budget covers the draw and the traversal that reads it: the sink hands out what it still
        // holds and charges what the draw spends.
        val result = plan.openBooleanDraw(
            model,
            OpenCandidateParams(maxFlips = minOf(flips, work.remaining()), cancellation = cancellation),
        )
        work.hintMoves(result.moves)
        val candidate = result.candidate
        drawStats = OpenHintStats(
            draws = 1,
            produced = if (candidate == null) 0 else 1,
            hintedVars = candidate?.boolVars?.size?.toLong() ?: 0,
            moves = result.moves,
        )
        // Kept as the counting wrapper rather than as bare hints, so what it goes on to steer stays
        // readable after every round of a descent has consulted it.
        val counting = candidate?.let { CountingCandidateHints(it.hints()) }
        steering = counting
        return counting ?: SearchCandidateHints.None
    }

    fun remainingDecisions(): Long {
        val workStats = work.snapshot()
        val committed = workStats.openBoolDecisions + workStats.openIntDecisions + workStats.openTheoryDecisions
        return minOf(
            (maxDecisions - committed).coerceAtLeast(0),
            params.nodeBudget?.allowance?.decisionsLeft() ?: Long.MAX_VALUE,
        )
    }

    fun capture(session: com.eignex.klause.solver.search.SearchSession?) {
        session ?: return
        clauses = clauses.mergedWith(session.learnedClauseStats())
        capturedGlue += session.glueClauseCount
        capturedRootFixed = maxOf(capturedRootFixed, session.rootFixedCount)
    }

    /**
     * Root fixings and glue clauses over every round so far, [live] the round still searching, in the counters a
     * backtrack search reports them in: the portfolio credits both routes' progress alike.
     */
    fun progress(live: com.eignex.klause.solver.search.SearchSession?): SearchStats = SearchStats(
        rootFixed = MaxResult(maxOf(capturedRootFixed, live?.rootFixedCount ?: 0).toDouble()),
        glueClauses = SumResult((capturedGlue + (live?.glueClauseCount ?: 0L)).toDouble()),
    )
}

/** How [OpenTheoryEngine.begin] left a solve: decided before the first branch, or running. */
internal sealed interface OpenTheoryStart {
    /** Preparation, bound closing or the root settled the model. */
    class Decided(val result: OpenTheoryResult) : OpenTheoryStart

    /** The search is set up; [run] drives it. */
    class Running(val run: OpenTheoryEngine.OpenTheoryRun) : OpenTheoryStart
}
