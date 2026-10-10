package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchEngine
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.localSearchSupports
import com.eignex.klause.lp.relaxation.lpSeed
import com.eignex.klause.portfolio.LocalSearchCatalog
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioIncumbents
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.portfolio.WitnessCheck
import com.eignex.klause.portfolio.kind
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.ProblemProfile
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.abs
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.toDouble
import com.eignex.klause.util.toLongExact
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Decides or optimizes an open model on the shared [Portfolio] harness: the complete theory route as one arm,
 * local-search arms over the model's source columns as the rest.
 *
 * The theory arm pauses at branches and resumes by slice, counted in `openWork`, so it keeps its search across the
 * segments it is scheduled. Optimizing, it is the theory's descent, each round refuting the better of its own
 * incumbent and the pool's.
 *
 * Local search proposes witnesses only, never refuting an open model or proving a bound, and every witness it
 * proposes is checked against the source model before it counts. A model no theory decides ([request] null) runs
 * local search alone, so it can be shown satisfiable and never shown unsatisfiable or optimal.
 *
 * The arms run on up to [lanes] threads, each arm on at most one at a time.
 */
internal class OpenPortfolio(
    private val model: Problem,
    private val request: OpenTheoryRequest?,
    private val theoryParams: TheoryParams,
    private val localSearchArms: Int = DEFAULT_LS_ARMS,
    private val seed: Long = 0L,
    private val lanes: Int = 1,
    private val searchesContinuousOnly: Boolean = false,
) {
    private var descentVerdict: OpenTheoryOptimum? = null

    /** Run the portfolio until an arm settles the model or [cancellation] fires. */
    fun solve(cancellation: Cancellation): OpenTheoryResult {
        val firstLocalArm = if (request != null) 1 else 0
        val localSearch = localSearchWorkers(firstLocalArm, cancellation, objective = null)
        if (localSearch.isEmpty()) {
            // With the theory as the only arm there is nothing to schedule, and slicing it would only rerun it
            // from scratch each segment.
            return request?.let { decide(it, theoryParams) }
                ?: OpenTheoryResult.Unknown(TerminationReason.Unsupported, SolveStats.EMPTY)
        }
        val workers = buildList {
            request?.let { add(theoryWorker(it, armId = 0)) }
            addAll(localSearch)
        }
        // A refutation earns the theory nothing until it lands, and one that needs most of the budget is lost if
        // local search's early finds talk the policy out of it, so the theory is owed most of the time outright.
        val portfolio = Portfolio.thompson(
            workers,
            lanes = lanes.coerceIn(1, workers.size),
            seed = seed,
            profile = ProblemProfile.of(model, optimizing = false),
            witnessCheck = witnessCheck(null),
            minShares = DoubleArray(workers.size).also { if (request != null) it[0] = THEORY_SHARE },
        )
        val result = portfolio.use { it.solve(cancellation) }
        return when (result) {
            is SolveResult.Sat -> OpenTheoryResult.Sat(assignmentOf(result.assignment), result.stats)
            is SolveResult.Unsat -> OpenTheoryResult.Unsat(result.stats)
            is SolveResult.Unknown -> OpenTheoryResult.Unknown(result.reason, result.stats)
        }
    }

    /**
     * Minimize [objective], the model's objective in minimized form, until an arm proves the optimum or
     * [cancellation] fires. [minimizer] is the theory's descent over it, or null when no theory decides the model and
     * local search runs alone.
     */
    fun minimize(
        objective: LinearObjective,
        minimizer: OpenTheoryMinimizer?,
        cancellation: Cancellation,
    ): OpenTheoryOptimum {
        require(minimizer == null || objective.realCoefficients.none { it != 0.0 }) {
            "the theory's descent minimizes no continuous column"
        }
        val firstLocalArm = if (minimizer != null) 1 else 0
        val localSearch = localSearchWorkers(firstLocalArm, cancellation, objective)
        if (localSearch.isEmpty()) {
            return minimizer?.minimize(theoryParams)
                ?: OpenTheoryOptimum.Bounded(null, null, TerminationReason.Unsupported, SolveStats.EMPTY)
        }
        val incumbents = PortfolioIncumbents<BigFraction>(
            valueOf = { candidate ->
                objective.evaluateExact(candidate.sample)
            },
            improves = { candidate, standing -> candidate < standing },
            approximateValue = { it.toDouble() },
            gain = { standing, candidate -> (standing - candidate).toDouble() },
        )
        val workers = buildList {
            minimizer?.let { add(descentWorker(it, armId = 0) { incumbents.exchange.integerBound() }) }
            addAll(localSearch)
        }
        // A descent rebuilt for making no progress would prepare the model again and lose the round it was proving.
        // Its rounds earn credit only when they land, so it is owed its share outright rather than left to the
        // policy, which local search's frequent improvements would otherwise talk out of scheduling it.
        val portfolio = Portfolio.thompson(
            workers,
            lanes = lanes.coerceIn(1, workers.size),
            seed = seed,
            reseedStaleThreshold = 0,
            profile = ProblemProfile.of(model, optimizing = true),
            witnessCheck = witnessCheck(objective),
            minShares = DoubleArray(workers.size).also { if (minimizer != null) it[0] = DESCENT_SHARE },
        )
        val result = portfolio.use { it.minimize(cancellation, onImprovement = null, incumbents) }
        return optimumOf(result, incumbents)
    }

    private fun optimumOf(result: MinimizeResult, incumbents: PortfolioIncumbents<BigFraction>): OpenTheoryOptimum =
        when (result) {
            is MinimizeResult.Optimal -> {
                val incumbent = checkNotNull(incumbents.exchange.current())
                OpenTheoryOptimum.Optimal(assignmentOf(incumbent.assignment), incumbent.objective, result.stats)
            }

            is MinimizeResult.BestFound -> {
                val incumbent = checkNotNull(incumbents.exchange.current())
                OpenTheoryOptimum.Bounded(
                    assignmentOf(incumbent.assignment),
                    incumbent.objective,
                    result.reason,
                    result.stats,
                )
            }

            // Only the descent finds a ray.
            is MinimizeResult.Unbounded -> checkNotNull(descentVerdict as? OpenTheoryOptimum.Unbounded) {
                "an unbounded verdict no descent reached"
            }.copy(stats = result.stats)

            is MinimizeResult.Infeasible -> OpenTheoryOptimum.Infeasible(result.stats)

            is MinimizeResult.Unknown -> OpenTheoryOptimum.Bounded(null, null, result.reason, result.stats)
        }

    private fun assignmentOf(sample: Sample): OpenTheoryAssignment = OpenTheoryAssignment.Sampled(sample)

    // The theory exactly as the default open route decides it.
    private fun decide(request: OpenTheoryRequest, params: TheoryParams): OpenTheoryResult =
        (OpenTheoryPipeline.execute(request, params) as OpenTheoryExecution.Satisfy).result

    private fun theoryWorker(request: OpenTheoryRequest, armId: Int): PortfolioWorker = PortfolioWorker.ofSolve(
        "theory/${request.route.name.lowercase()}",
        armId,
        resumable = { theorySlices(request) },
    ) { slice, _ ->
        toSolveResult(
            decide(request, theoryParams.copy(cancellation = theoryParams.cancellation or slice)),
        )
    }

    // The theory paused and resumed by slice, so it keeps its search between the segments it is scheduled.
    private fun theorySlices(request: OpenTheoryRequest): ResumableSolve = object : ResumableSolve {
        private val search = ResumableOpenTheory(OpenTheoryPipeline.engineFor(request), theoryParams)
        override val isDone: Boolean get() = search.isDone
        override val stats: SolveStats get() = search.stats
        override val work: Long get() = search.work

        override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long): SolveResult? =
            search.runSlice(global, sliceMillis, sliceNodes)?.let(::toSolveResult)

        override fun close() = search.close()
    }

    private fun toSolveResult(result: OpenTheoryResult): SolveResult = when (result) {
        is OpenTheoryResult.Sat -> SolveResult.Sat(result.assignment.toSample(model), result.stats)
        is OpenTheoryResult.Unsat -> SolveResult.Unsat(stats = result.stats)
        is OpenTheoryResult.Unknown -> SolveResult.Unknown(result.reason, result.stats)
    }

    private fun descentWorker(minimizer: OpenTheoryMinimizer, armId: Int, readBound: () -> BigInt?): PortfolioWorker =
        PortfolioWorker.ofMinimize(
            "theory/${minimizer.theoryPipeline.name.lowercase()}",
            armId,
            resumable = { descentSlices(minimizer, readBound) },
        ) { _, _, _, _ -> error("the descent runs only by slice") }

    // An integral descent reads the attained value directly, including beyond floating-point and 64-bit ranges.
    private fun descentSlices(minimizer: OpenTheoryMinimizer, readBound: () -> BigInt?): ResumableSearch =
        object : ResumableSearch {
            private val descent = minimizer.descent(theoryParams, readBound)
            override val isDone: Boolean get() = descent.isDone
            override val stats: SolveStats get() = descent.stats
            override val work: Long get() = descent.work

            override fun runSlice(
                global: Cancellation,
                sliceMillis: Long,
                sliceNodes: Long,
                onIncumbent: (MinimizeResult.WithSample) -> Unit,
            ): MinimizeResult? {
                val verdict = descent.runSlice(global, sliceMillis, sliceNodes) { assignment, value ->
                    val sample = assignment.toSample(model)
                    val objective = value.toDouble()
                    onIncumbent(MinimizeResult.BestFound(sample, objective, TerminationReason.BudgetExhausted))
                } ?: return null
                descentVerdict = verdict
                return when (verdict) {
                    is OpenTheoryOptimum.Optimal -> MinimizeResult.Optimal(
                        verdict.assignment.toSample(model),
                        verdict.value.toDouble(),
                        verdict.stats,
                    )

                    is OpenTheoryOptimum.Infeasible -> MinimizeResult.Infeasible(stats = verdict.stats)

                    is OpenTheoryOptimum.Unbounded -> MinimizeResult.Unbounded(
                        verdict.witness.toSample(model),
                        verdict.value.toDouble(),
                        direction = emptyList(),
                        stats = verdict.stats,
                    )

                    // A round that refuted the pool's bound proved the pool's incumbent optimal: nothing is left to
                    // search, whichever arm holds it.
                    is OpenTheoryOptimum.Bounded -> MinimizeResult.Unknown(verdict.reason, verdict.stats)
                }
            }

            override fun close() = descent.close()
        }

    private fun localSearchWorkers(
        firstArm: Int,
        cancellation: Cancellation,
        objective: LinearObjective?,
    ): List<PortfolioWorker> {
        // On a model of continuous columns alone with no Boolean to choose, the completion of any candidate is the
        // whole LP: local search would only hand the theory the problem it already solves, so it runs there only
        // when asked for by name.
        if (!searchesContinuousOnly && model.numIntVars == 0 && model.numBoolVars == 0) return emptyList()
        val searchModel = LocalSearchModel.open(model)
        // An open model's rows can be strict, which only the theory certifies; it decides each candidate's residual.
        // An objective over continuous columns is minimized over that residual first.
        val completion = if (model.numRealVars > 0) {
            val theory = TheoryCompletion(model, theoryParams)
            if (objective != null && objective.realCoefficients.any { it != 0.0 }) {
                OptimizingCompletion(model, objective, theory)
            } else {
                theory
            }
        } else {
            null
        }
        if (!localSearchSupports(searchModel, completes = completion != null)) return emptyList()
        // Every arm starts from the relaxation's optimum inside the search windows rather than near zero; an LP
        // that finds no point in its slice of the budget leaves the arms to their own random starts.
        val start = model.lpSeed(searchModel.domains, cancellation or Cancellation.after(SEED_BUDGET))
        val profile = ProblemProfile.of(model, optimizing = objective != null)
        return LocalSearchCatalog.diverse(profile.kind, localSearchArms, profile.problemClass).mapIndexed { i, recipe ->
            val engine = LocalSearchEngine(
                searchModel,
                strategy = recipe.strategy,
                optimizeStrategy = recipe.optimizeStrategy,
                seedImplicitOnRestart = recipe.seedImplicitOnRestart,
                completion = completion,
            )
            fun params(slice: Cancellation, budget: Long?, from: Sample?) = LocalSearchParams(
                randomSeed = seed + i,
                maxInstructions = budget,
                cancellation = slice,
                initialAssignment = from,
                nodeBudget = theoryParams.nodeBudget,
            )
            val label = "ls/${recipe.label}"
            if (objective == null) {
                PortfolioWorker.ofSolve(label, firstArm + i, countsInstructions = true) { slice, budget ->
                    engine.solve(params(slice, budget, start), warm = null)
                }
            } else {
                PortfolioWorker.ofMinimize(label, firstArm + i, countsInstructions = true) { _, warm, slice, budget ->
                    // The pool's incumbent can lie outside the search windows, which the invariants are sized for.
                    val from = warm?.let { inWindows(it, searchModel) } ?: start
                    engine.improvements(objective, params(slice, budget, from), warm = null)
                }
            }
        }
    }

    // A local-search witness is re-derived from the source model, and its objective must be the one it claims: exactly
    // wherever a Double states an integral value exactly, and within a relative tolerance where continuous terms make
    // the claim a floating-point sum. The theory arm's witnesses are exact by construction.
    private fun witnessCheck(objective: LinearObjective?): WitnessCheck = WitnessCheck { sample, claimed ->
        if (sample.isTheoryWitness) return@WitnessCheck null
        refuteOpenWitness(model, sample)?.let { return@WitnessCheck it }
        if (objective == null || claimed == null) return@WitnessCheck null
        val exact = objective.evaluateExact(sample)
        val value = exact.toDouble()
        val agrees = if (objective.realCoefficients.any { it != 0.0 }) {
            abs(value - claimed) <= OBJECTIVE_TOLERANCE * maxOf(1.0, abs(value))
        } else {
            exact.num.abs() > bigIntOf(EXACT_DOUBLE_INTEGER) || value == claimed
        }
        if (agrees) null else "objective $claimed, but the assignment scores $exact"
    }

    private companion object {
        const val DEFAULT_LS_ARMS: Int = 3

        // The descent's least share of an optimizing run: half, the rest to local search.
        const val DESCENT_SHARE: Double = 0.5

        // The theory's least share of a satisfaction run: enough that a refutation the theory alone finishes in
        // three fifths of the budget still finishes, the rest to local search.
        const val THEORY_SHARE: Double = 0.75

        // Wall-clock ceiling on the seed LP, a small slice beside any portfolio budget.
        val SEED_BUDGET: Duration = 500.milliseconds
    }
}

// Relative slack between a local-search arm's claimed objective and the exact one when continuous terms weigh in.
private const val OBJECTIVE_TOLERANCE = 1e-6

// Every integer up to this magnitude is a Double exactly.
private const val EXACT_DOUBLE_INTEGER: Long = 1L shl 53

// [sample] moved into [searchModel]'s windows, so it seeds a search whose invariants were sized for them.
internal fun inWindows(sample: Sample, searchModel: LocalSearchModel): Sample {
    val domains = searchModel.domains
    if (sample.numIntVars != domains.size) return sample
    val exact = sample.exactInts
    if (exact != null) {
        val projected = LongArray(domains.size) { id ->
            val value = exact[id]
            val domain = domains[id]
            when {
                value < bigIntOf(domain.min) -> domain.min
                value > bigIntOf(domain.max) -> domain.max
                else -> domain.clamp(value.toLongExact())
            }
        }
        return Sample(sample.bools, projected, sample.reals, sample.exactReals)
    }
    if (sample.ints.indices.all { domains[it].contains(sample.ints[it]) }) return sample
    return sample.copy(ints = LongArray(domains.size) { domains[it].clamp(sample.ints[it]) })
}
