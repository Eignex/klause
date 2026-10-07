package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.ExactWitness
import com.eignex.klause.lp.asFraction
import com.eignex.klause.lp.objectiveUnboundedBelow
import com.eignex.klause.lp.statesOneBranch
import com.eignex.klause.presolve.OpenPresolveResult
import com.eignex.klause.presolve.PreparedSource
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolvePipeline
import com.eignex.klause.presolve.closeOpenBounds
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.incumbent.Publication
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.ProblemPipeline
import com.eignex.klause.solver.pipeline.componentPlan
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * What an optimizing open-model route answers.
 *
 * An integer route states an [Optimal] because an integer optimum, where one exists over a feasible
 * bounded-below model, is attained. A route over the reals needs a case this does not carry: an
 * objective may approach a greatest lower bound it never reaches, and reporting that as optimal would
 * name a value no assignment achieves. An open model needs one more: an objective may be bounded by
 * nothing at all, which is [Unbounded] and is not a bound on anything.
 */
sealed interface OpenTheoryOptimum {

    /** Statistics collected while optimizing. */
    val stats: SolveStats

    /** An assignment, and a proof that nothing feasible lies below [value]. */
    data class Optimal(
        /** The optimal assignment. */
        val assignment: OpenTheoryAssignment,
        /** Its objective value. */
        val value: BigInt,
        override val stats: SolveStats,
    ) : OpenTheoryOptimum

    /** The model has no feasible assignment, at any objective value. */
    data class Infeasible(override val stats: SolveStats) : OpenTheoryOptimum

    /**
     * The model is feasible and its objective descends without limit, so it has no optimum.
     *
     * [witness] proves the model feasible and [value] is what the objective takes on it. Neither is a
     * bound: the certificate is a ray of the model along which the objective strictly decreases, so every
     * value below [value] is attained too.
     */
    data class Unbounded(
        /** An assignment proving the model feasible. */
        val witness: OpenTheoryAssignment,
        /** The objective value at [witness]; every value below it is attained as well. */
        val value: BigInt,
        override val stats: SolveStats,
    ) : OpenTheoryOptimum

    /**
     * The budget stopped the descent. [incumbent] is the best assignment proved feasible, so the optimum
     * is at or below [value]; no bound under it was refuted. Both are null when nothing was proved
     * feasible, which says only that the run ran out — not that the model is infeasible.
     */
    data class Bounded(
        /** Best assignment proved feasible, or null when none was. */
        val incumbent: OpenTheoryAssignment?,
        /** Objective value of [incumbent], or null when there is none. */
        val value: BigInt?,
        /** Why the descent stopped before an optimum proof. */
        val reason: TerminationReason,
        override val stats: SolveStats,
    ) : OpenTheoryOptimum
}

/** Retain a round's outcome while reporting the enclosing optimization's elapsed time. */
internal fun SolveStats.withOptimizationEnvelope(envelope: SolveStats): SolveStats = copy(
    run = run.copy(wallMs = envelope.run.wallMs),
    lp = envelope.lp.mergedWith(lp),
)

/**
 * Minimizes a linear objective over an open source model by refuting bounds below the incumbent.
 *
 * Each round asks the model's own theory route to decide the model plus one row stating that the
 * objective beats the incumbent. A satisfying answer tightens the row; a refutation proves the incumbent
 * optimal. So the descent needs no optimizing simplex — the route's feasibility answer carries it, and
 * the objective enters as a constraint the route already reasons about.
 *
 * A bound tightened by one per round reaches an optimum in as many rounds as there are objective values
 * between the first witness and it, and an objective unbounded below has no optimum for it to reach at
 * all. Every witness is therefore also asked for a ray ([objectiveUnboundedBelow]): a direction of its
 * own branch along which the objective strictly decreases states [OpenTheoryOptimum.Unbounded], where the
 * descent would otherwise improve until a budget fired.
 *
 * Source-safe preparation runs once, on the model without the bound row: the row is the descent's own,
 * and no source pass reads its constant. The route and the ownership plan are then selected once from
 * the prepared model carrying the row, so re-bounding re-reads neither the factors nor the preparation.
 * Closing the open sides runs once with them, over that same row-less model: each round adds a strictly
 * tighter row, and a row only removes solutions, so a bound proved for the model holds for every round of
 * the descent. The opening round drops the row: with no incumbent there is nothing to beat, and stating
 * any bound there would refute a model whose own optimum lies above it.
 */
class OpenTheoryMinimizer internal constructor(
    model: Problem,
    objective: LinearObjective,
    private val presolveConfig: PresolveConfig = PresolveConfig.DEFAULT,
    private val solutionSetSensitive: Boolean = false,
    private val presolveCancellation: Cancellation = Cancellation.Never,
    private val presolveBudget: PresolveBudget? = null,
) {

    constructor(model: Problem, objective: LinearObjective) : this(
        model,
        objective,
        PresolveConfig.DEFAULT,
        false,
        Cancellation.Never,
        null,
    )

    private val objective = objective
    private val terms: IntArray
    private val coefficients: LongArray
    private val source: Problem

    // The route the descent plans under, which the objective row may leave outside every complete open theory.
    private val plannedRoute: ProblemPipeline by lazy { source.boundedForPlanning().componentPlan().theoryPipeline }

    private val route: ProblemPipeline by lazy {
        // A row at PLANNING_RHS carries no potential, so a model whose rows were all differences leaves
        // that fragment by being optimized at all — the row's weight, not its shape, is what moves it.
        // Say so here rather than at the first round's engine build.
        require(decidesEveryRound) { "objective row leaves the model outside every complete open theory" }
        plannedRoute
    }

    /** Whether a complete open theory decides the model with the descent's bound row, and so every round. */
    internal val decidesEveryRound: Boolean
        get() = plannedRoute != ProblemPipeline.UNSUPPORTED_OPEN && plannedRoute != ProblemPipeline.FINITE_CP

    // Set once the certificate has refused a ray over a model that puts every witness in one branch, so
    // the rounds after it read the refusal rather than rebuilding the same cone system.
    private var rayRefusedForEveryWitness = false

    init {
        // A continuous column may exist — a mixed model keeps its reals — but the descent steps by one,
        // which only bounds an objective whose value is integral. A continuous term has no next value
        // below the incumbent, and its greatest lower bound need not be attained at all.
        require(objective.realCoefficients.none { it != 0.0 }) {
            "the integral descent cannot minimize an objective weighting a continuous column"
        }
        require(objective.boolWeights.all { it == 0L }) {
            "an open integer route cannot minimize an objective weighting Boolean columns"
        }
        val present = objective.intCoefficients.indices.filter { objective.intCoefficients[it] != 0L }
        terms = present.toIntArray()
        coefficients = LongArray(present.size) { objective.intCoefficients[present[it]] }
        source = model
    }

    /**
     * The route the source model declares, so a caller can decline before starting.
     *
     * Read from the untransformed model: it is the contract a caller can check up front. The route the
     * descent actually runs is selected again from what preparation produced.
     */
    val theoryPipeline: ProblemPipeline get() = route

    internal fun replacingObjective(next: LinearObjective): OpenTheoryMinimizer = OpenTheoryMinimizer(
        source,
        next,
        presolveConfig,
        solutionSetSensitive,
        presolveCancellation,
        presolveBudget,
    )

    /** Minimizes the objective, tightening the bound until a round refutes it. */
    fun minimize(params: TheoryParams = TheoryParams()): OpenTheoryOptimum = descent(params).use { descent ->
        checkNotNull(descent.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) { _, _ -> }) {
            "an unsliced descent never pauses"
        }
    }

    /**
     * The descent advanced a slice at a time, for a portfolio to schedule beside other arms.
     *
     * [externalBound] is an objective value some other arm has reached, read as each round starts: a round
     * refutes the better of it and the descent's own incumbent, so the descent never re-proves ground another arm
     * has covered. A round that refutes a bound read there proves only that nothing beats that arm's value, which
     * the descent reports as [OpenTheoryOptimum.Bounded] by [TerminationReason.SearchExhausted].
     */
    internal fun descent(params: TheoryParams, externalBound: () -> BigInt? = { null }): Descent =
        Descent(params, externalBound)

    /** The exact objective value of [assignment]. */
    internal fun valueOf(assignment: OpenTheoryAssignment): BigInt = objective.valueOf(assignment)

    /**
     * One descent, its preparation done once and each round a [ResumableOpenTheory] kept across slices.
     *
     * Preparation and each round's setup run whole in the slice that reaches them; a slice ends only inside a
     * round's search, or between rounds once its share is spent.
     */
    internal inner class Descent(private val params: TheoryParams, private val externalBound: () -> BigInt?) :
        AutoCloseable {
        private val sink = SolveStatsSink(backend = "")
        private val state = OpenTheorySolveState(params)

        // One incumbent for the whole descent: every witness a round proves feasible is offered here with
        // the value read off it, and the bound the next round refutes is whatever the offer installed.
        private val incumbents = minimizingWitnessExchange()
        private var opening: Opening? = null
        private var reuseOpeningBoundAttempt = false
        private var round: ResumableOpenTheory? = null

        // Whether the current round's row states a bound another arm reached rather than the descent's own.
        private var roundBoundExternal = false
        private var verdict: OpenTheoryOptimum? = null

        /** Whether the descent has reached its verdict. */
        val isDone: Boolean get() = verdict != null

        /** Work spent so far, in `openWork` units. */
        val work: Long get() = state.work.spent

        /** The verdict's stats once decided, else the work counters so far. */
        val stats: SolveStats get() = verdict?.stats ?: SolveStats(openTheory = state.work.snapshot())

        /**
         * Advance until a verdict, [global] firing, or the slice ending: after [sliceWork] `openWork` units when
         * non-negative, or after [sliceMillis], whichever comes first. [onIncumbent] sees every witness the
         * descent installs, with its objective value. The verdict once there is one, else null with the descent
         * paused.
         */
        fun runSlice(
            global: Cancellation,
            sliceMillis: Long,
            sliceWork: Long,
            onIncumbent: (OpenTheoryAssignment, BigInt) -> Unit,
        ): OpenTheoryOptimum? {
            verdict?.let { return it }
            val endWork = if (sliceWork >= 0L) state.work.spent + sliceWork else -1L
            val end =
                if (sliceMillis == Long.MAX_VALUE) null else TimeSource.Monotonic.markNow() + sliceMillis.milliseconds
            val opened = opening ?: open(global)?.also { opening = it } ?: return verdict
            var first = true
            while (true) {
                val spent = (endWork >= 0L && state.work.spent >= endWork) || end?.hasPassedNow() == true
                if (!first && spent) return null
                first = false
                val current = round ?: startRound(opened).also { round = it }
                val remainingWork = if (endWork < 0L) -1L else (endWork - state.work.spent).coerceAtLeast(0L)
                val remainingMillis = end?.let { (it - TimeSource.Monotonic.markNow()).inWholeMilliseconds }
                    ?.coerceAtLeast(0L) ?: Long.MAX_VALUE
                val result = current.runSlice(global, remainingMillis, remainingWork) ?: return null
                round = null
                endRound(opened, result, onIncumbent)?.let { return decide(it) }
            }
        }

        // Prepare the source and close its open sides once for the whole descent; null with the verdict set when
        // that alone decides the model.
        private fun open(global: Cancellation): Opening? {
            // Preparation is the descent's first phase, so the caller's stop reaches it and its own summary is
            // what a run refuted here has to report — there is no round behind it to carry one.
            sink.start()
            sink.backend = route.backendName()
            val stop = presolveCancellation or params.cancellation or params.timeout or global
            val prepared = PresolvePipeline.prepareSource(
                source,
                presolveConfig,
                objective,
                solutionSetSensitive,
                stop,
                presolveBudget,
            )
            if (prepared.infeasible) {
                sink.presolve = prepared.stats
                sink.stop()
                verdict = OpenTheoryOptimum.Infeasible(sink.snapshot())
                return null
            }
            // Ownership stays selected from the model as prepared, not from the closed one below. Closing a
            // column does not move a factor between components, but it does change what `componentPlan` reads:
            // a descent whose every side closed would select the finite route, which no round of it can run.
            val boundedPlan = prepared.planned(prepared.problem.boundedForPlanning()).plan
            // Close the open sides once for the whole descent. Every round decides this model plus a strictly
            // tighter objective row, and a row only removes solutions, so a bound proved here holds for every
            // round below it. Leaving the close to the rounds has each one re-derive the same bounds from the
            // declared sides, at one LP solve per open side per round.
            //
            // Not extra work: the opening round decides the model under no bound at all, so the close it would
            // have run is this one. A refutation here is over the genuinely open ranges rather than inside an
            // invented box, so it refutes the model itself — which, with no witness yet, is infeasibility.
            var closingInterrupted = false
            val closingStop = prepared.budget?.orSpent(stop) ?: stop
            val closingCancellation = object : Cancellation {
                override fun isCancelled(): Boolean = closingStop().also { if (it) closingInterrupted = true }

                override fun workMeter(): WorkMeter? = closingStop.workMeter()
            }
            val base = when (val closed = prepared.problem.closeOpenBounds(closingCancellation, sink.lp)) {
                OpenPresolveResult.Refuted -> {
                    sink.presolve = prepared.stats
                    sink.stop()
                    verdict = OpenTheoryOptimum.Infeasible(sink.snapshot())
                    return null
                }

                is OpenPresolveResult.Tightened -> closed.spec
            }
            // A second bounded attempt on the same opening model cannot read a stronger input. If the
            // first attempt changed a declared range or saw cancellation, let the round close again.
            reuseOpeningBoundAttempt = base.sameDeclaredIntegerRanges(prepared.problem) &&
                !closingInterrupted && !stop() && prepared.budget?.remaining() != 0L
            return Opening(prepared, base, boundedPlan)
        }

        // The next round: the model under a row refuting the better of the incumbent and the external bound, or
        // the model itself while there is neither.
        private fun startRound(opened: Opening): ResumableOpenTheory {
            val own = incumbents.current()?.objective
            val external = externalBound()?.takeIf { terms.isNotEmpty() && (own == null || it < own) }
            roundBoundExternal = external != null
            val bound = external ?: own
            // The opening round decides the model itself, so the row leaves plan and spec together until a
            // witness gives it a bound to state.
            val (spec, plan) = when {
                terms.isEmpty() -> opened.base to opened.boundedPlan
                bound == null -> opened.base to opened.boundedPlan.withoutAppendedFactor(opened.base)
                else -> opened.base.boundedBy(bound - BIG_ONE) to opened.boundedPlan
            }
            val engine = OpenTheoryEngine(
                OpenSourcePreparation.Planned(opened.prepared, spec, plan),
                presolveCancellation,
                reuseOpeningBoundAttempt,
            )
            reuseOpeningBoundAttempt = false
            return ResumableOpenTheory(engine, params, state)
        }

        // Read a finished round: the descent's verdict when it ends here, else null to go on to the next round.
        private fun endRound(
            opened: Opening,
            result: OpenTheoryResult,
            onIncumbent: (OpenTheoryAssignment, BigInt) -> Unit,
        ): OpenTheoryOptimum? = when (result) {
            is OpenTheoryResult.Sat -> {
                val value = objective.valueOf(result.assignment)
                when (val published = incumbents.offer(result.assignment, value)) {
                    is Publication.Installed -> {
                        val installed = published.incumbent
                        onIncumbent(installed.assignment, installed.objective)
                        when {
                            // A constant objective has no row to tighten: its first witness is
                            // already optimal, and a bound below the constant would exclude every
                            // assignment rather than a worse one.
                            terms.isEmpty() -> proven(finish(result.stats))

                            // A bound row states that nothing feasible sits at the incumbent or
                            // above it, and a model with a ray has a witness below every such row:
                            // the descent would improve forever, so it states the verdict instead.
                            unboundedBelow(opened.prepared.problem, installed.assignment, params, state) ->
                                OpenTheoryOptimum.Unbounded(
                                    installed.assignment,
                                    installed.objective,
                                    finish(result.stats),
                                )

                            else -> null
                        }
                    }

                    // A round carrying the row decided the model *plus* the objective held below the
                    // incumbent, so a witness the gate declines is one that row excluded; the opening
                    // round has no incumbent for the gate to decline against. Either way a declined
                    // witness refutes no bound, which is what an optimum would take.
                    Publication.NotImproving -> error("witness at $value does not improve its own bound")

                    // The exchange trusts the route's certificate, so nothing here judges a witness.
                    // Either outcome means a verifier reached this descent without its proofs being
                    // revisited, and neither says anything about the model.
                    is Publication.Rejected -> error("a trusted witness was refuted: ${published.reason}")

                    is Publication.Indeterminate ->
                        error("a trusted witness was left undecided: ${published.reason}")
                }
            }

            // Nothing beats another arm's value, which proves that arm's witness optimal rather than this one's.
            is OpenTheoryResult.Unsat -> if (roundBoundExternal) {
                standing(TerminationReason.SearchExhausted, finish(result.stats))
            } else {
                proven(finish(result.stats))
            }

            is OpenTheoryResult.Unknown -> standing(result.reason, finish(result.stats))
        }

        private fun standing(reason: TerminationReason, stats: SolveStats): OpenTheoryOptimum {
            val standing = incumbents.current()
            return OpenTheoryOptimum.Bounded(standing?.assignment, standing?.objective, reason, stats)
        }

        /**
         * What the standing incumbent proves once the descent has nothing left to refute: it is optimal, or —
         * with no witness ever installed — the model is infeasible, because the round that ended the descent
         * was the opening one, which decided the model under no bound at all.
         *
         * Only for a descent that ended in a proof. A round the budget stopped refuted nothing, so its
         * incumbent bounds the optimum instead of naming it.
         */
        private fun proven(stats: SolveStats): OpenTheoryOptimum {
            val standing = incumbents.current() ?: return OpenTheoryOptimum.Infeasible(stats)
            return OpenTheoryOptimum.Optimal(standing.assignment, standing.objective, stats)
        }

        private fun finish(round: SolveStats): SolveStats {
            sink.stop()
            return round.withOptimizationEnvelope(sink.snapshot()).copy(smt = state.smt.snapshot())
        }

        private fun decide(result: OpenTheoryOptimum): OpenTheoryOptimum {
            verdict = result
            close()
            return result
        }

        override fun close() {
            round?.close()
            round = null
        }
    }

    // What the descent prepares once: the prepared source, its closed model, and the plan selected with the row.
    private class Opening(val prepared: PreparedSource, val base: Problem, val boundedPlan: ComponentPlan)

    /**
     * Whether the objective descends without limit through the branch [witness] lies in.
     *
     * Asked of the model without the descent's own row. The row states that nothing feasible sits at the
     * incumbent or above it, which is a fact about where the descent has reached rather than one about the
     * model, and the ray the certificate looks for is the model's own.
     *
     * A model whose witnesses all lie in one branch answers this once: the cone system it builds is the
     * same every round, and rebuilding it per improvement would cost an exact rational run to reach the
     * refusal already on hand. Only a refusal is remembered — a run the stop cut short decided nothing,
     * and reading it as a refusal would retire the certificate over a question never asked.
     */
    private fun unboundedBelow(
        model: Problem,
        witness: OpenTheoryAssignment,
        params: TheoryParams,
        state: OpenTheorySolveState,
    ): Boolean {
        if (rayRefusedForEveryWitness) return false
        val ray = model.objectiveUnboundedBelow(
            terms,
            coefficients,
            witness.exactWitness(model.numRealVars),
            (params.timeout or params.cancellation).shorten(0.5),
            state.smt::observeSourceLp,
        )
        rayRefusedForEveryWitness = ray == false && model.statesOneBranch()
        return ray == true
    }

    /**
     * The row `Σ c(i)·x(i) ≤ bound − constant`.
     *
     * Widened only when the right-hand side leaves 64 bits, which the descent can reach because it
     * subtracts one per improvement without knowing how far it will go. Widening unconditionally would
     * be sound and is not free: a wide row routes the exact core through its digit-chain encoder, which
     * on a model with thousands of rows costs more than deciding it.
     */
    private fun boundRow(bound: BigInt): Factor {
        val rhs = bound - bigIntOf(objective.constant)
        if (rhs >= LONG_MIN && rhs <= LONG_MAX) {
            return Linear(coefficients.copyOf(), terms.copyOf(), LinearOp.LE, rhs.toLongExact())
        }
        val wideCoeffs = Array(coefficients.size) { bigIntOf(coefficients[it]) }
        return Linear(terms, wideCoeffs, LinearOp.LE, rhs)
    }

    /**
     * This model plus the row in the shape ownership is selected from.
     *
     * The right-hand side, not the bound, is what the route reads, so the widest one a 64-bit row can
     * state is what is planned under: an objective bound would shift it by [LinearObjective.constant],
     * and a shifted-light row would offer the difference fragment — the one route that could not hold a
     * row the descent later widens — a selection the descent goes on to invalidate.
     */
    private fun Problem.boundedForPlanning(): Problem =
        withObjectiveRow { Linear(coefficients.copyOf(), terms.copyOf(), LinearOp.LE, PLANNING_RHS) }

    /** This model plus the row holding the objective at or below [bound]. */
    private fun Problem.boundedBy(bound: BigInt): Problem = withObjectiveRow { boundRow(bound) }

    /**
     * This model plus [row], or itself when the objective weights no column.
     *
     * A constant objective has nothing to descend and no row to bound it by: the model is decided as it
     * stands and every feasible assignment is optimal. That is also the one case where a plan selected
     * here indexes the same factors as the model it came from, which is what decides whether the
     * appended owner has to come back off the plan for a round that drops the row.
     */
    private fun Problem.withObjectiveRow(row: () -> Factor): Problem = if (terms.isEmpty()) this else withRow(row())

    // The declared metadata rides along, so the projection a round builds does not depend on whether that
    // round carries the row: the mask is parallel to the factors, and the descent's own row is not one of
    // the constraints the model declared implied.
    private fun Problem.withRow(row: Factor): Problem =
        withFactors(factors + row, impliedFactorMask?.let { it + false })

    private fun LinearObjective.valueOf(assignment: OpenTheoryAssignment): BigInt {
        var total = bigIntOf(constant)
        when (assignment) {
            is OpenTheoryAssignment.Difference -> for (i in terms.indices) {
                total += bigIntOf(coefficients[i]) * bigIntOf(assignment.sample.ints[terms[i]])
            }

            is OpenTheoryAssignment.Sampled -> for (i in terms.indices) {
                total += bigIntOf(coefficients[i]) * bigIntOf(assignment.sample.ints[terms[i]])
            }

            // A mixed model carries continuous columns the objective does not weight, so its value is
            // still the integer sum; the reals are decided alongside and contribute nothing to it.
            is OpenTheoryAssignment.ExactLira -> for (i in terms.indices) {
                total += bigIntOf(coefficients[i]) * assignment.assignment.ints[terms[i]]
            }

            // A real-only route has no integer column to read, which only a constant objective may ask of
            // it: every weighted term names one.
            is OpenTheoryAssignment.ExactLra -> check(terms.isEmpty()) {
                "a route with no integer column cannot value an objective weighting one"
            }

            // A rebuild that recovered integer columns is the only place their values exist; one that
            // recovered none leaves them to the witness underneath, at that route's own width.
            is OpenTheoryAssignment.Rebuilt -> {
                val recovered = assignment.ints ?: return valueOf(assignment.base)
                for (i in terms.indices) {
                    total += bigIntOf(coefficients[i]) * recovered[terms[i]]
                }
            }
        }
        return total
    }

    private companion object {
        /**
         * The right-hand side ownership is selected under: the widest a 64-bit row can state.
         *
         * No round asserts it. It is the shape the plan reads, and reading it at the widest right-hand
         * side is what keeps one selection valid for every bound the descent tightens to: an edge this
         * heavy carries no potential, so the difference fragment is declined here rather than left to be
         * invalidated mid-descent.
         */
        const val PLANNING_RHS: Long = Long.MAX_VALUE
        val LONG_MIN: BigInt = bigIntOf(Long.MIN_VALUE)
        val LONG_MAX: BigInt = bigIntOf(Long.MAX_VALUE)
    }
}

private fun Problem.sameDeclaredIntegerRanges(other: Problem): Boolean {
    if (numIntVars != other.numIntVars || factors !== other.factors ||
        realLower !== other.realLower || realUpper !== other.realUpper
    ) {
        return false
    }
    val left = intBounds
    val right = other.intBounds
    for (v in 0 until numIntVars) {
        if (left.hasLower(v) != right.hasLower(v) || left.hasUpper(v) != right.hasUpper(v)) return false
        if (left.hasLower(v) && left.lower(v) != right.lower(v)) return false
        if (left.hasUpper(v) && left.upper(v) != right.upper(v)) return false
        if (declaredIntDomains.declaredOrNull(v) !== other.declaredIntDomains.declaredOrNull(v)) return false
    }
    return true
}

/**
 * A witness read as the exact point a ray certificate takes its branch at.
 *
 * The routes carry their values at different widths — a difference witness in `Long`, an exact one in
 * arbitrary precision — and every one of them is a rational exactly, which is the arithmetic the
 * certificate reasons in.
 */
private fun OpenTheoryAssignment.exactWitness(realColumns: Int): ExactWitness = when (this) {
    is OpenTheoryAssignment.Difference -> RouteWitness(
        realColumns,
        { sample.bools[it] },
        { sample.reals.getOrElse(it) { 0.0 }.asFraction() },
        { BigFraction.ofLong(sample.ints[it]) },
    )

    is OpenTheoryAssignment.Sampled -> RouteWitness(
        realColumns,
        { sample.bools[it] },
        { requireNotNull(sample.exactReals)[it] },
        { BigFraction.ofLong(sample.ints[it]) },
    )

    is OpenTheoryAssignment.ExactLira -> RouteWitness(
        realColumns,
        { assignment.bools[it] },
        { assignment.reals[it] },
        { assignment.ints[it].asFraction() },
    )

    // A real-only route has no integer column to read, which only a constant objective may ask of it —
    // and a constant objective descends along nothing, so no certificate is taken over this witness.
    is OpenTheoryAssignment.ExactLra -> RouteWitness(
        realColumns,
        { assignment.bools[it] },
        { assignment.reals[it] },
        { error("a route with no integer column cannot value an objective weighting one") },
    )

    // Real columns come from the route underneath, which alone knows their width. The integer columns are
    // the rebuild's whenever it recovered any — reading the witness under it would take their values from
    // before the elimination.
    is OpenTheoryAssignment.Rebuilt -> base.exactWitness(realColumns)
        .withTruth { bools[it] }
        .let { under -> ints?.let { recovered -> under.withInts(realColumns, recovered) } ?: under }
}

/** This witness reading its integer columns off [ints] instead, with [realColumns] reals ahead of them. */
private fun ExactWitness.withInts(realColumns: Int, ints: Array<BigInt>): ExactWitness = object : ExactWitness {
    override fun at(column: Int): BigFraction = if (column < realColumns) {
        this@withInts.at(column)
    } else {
        BigFraction.of(ints[column - realColumns], BIG_ONE)
    }

    override fun truth(boolVar: Int): Boolean = this@withInts.truth(boolVar)
}

/** This witness reading its Boolean columns through [truth] instead. */
private fun ExactWitness.withTruth(truth: (Int) -> Boolean): ExactWitness = object : ExactWitness {
    override fun at(column: Int): BigFraction = this@withTruth.at(column)

    override fun truth(boolVar: Int): Boolean = truth(boolVar)
}

/** One route's witness over the certificate's mixed column space: reals first, then integer columns. */
private class RouteWitness(
    private val realColumns: Int,
    private val bool: (Int) -> Boolean,
    private val real: (Int) -> BigFraction,
    private val int: (Int) -> BigFraction,
) : ExactWitness {
    override fun at(column: Int): BigFraction = if (column < realColumns) real(column) else int(column - realColumns)

    override fun truth(boolVar: Int): Boolean = bool(boolVar)
}
