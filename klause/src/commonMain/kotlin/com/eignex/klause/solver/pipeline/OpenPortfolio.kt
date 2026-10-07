package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchEngine
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.localSearchSupports
import com.eignex.klause.lp.relaxation.lpSeed
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.LocalSearchCatalog
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.portfolio.WitnessCheck
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.math.abs
import kotlin.math.floor
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
 */
internal class OpenPortfolio(
    private val model: Problem,
    private val request: OpenTheoryRequest?,
    private val theoryParams: TheoryParams,
    private val localSearchArms: Int = DEFAULT_LS_ARMS,
    private val seed: Long = 0L,
) {
    // The theory arm's witnesses as the pool saw them: each may hold integers past the 64-bit range a Sample cannot
    // carry, so the pool's copy is only a stand-in for it.
    private val theoryWitnesses = ArrayList<TheoryWitness>()
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
        val portfolio = Portfolio.thompson(workers, lanes = 1, seed = seed, witnessCheck = witnessCheck(null))
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
        require(objective.realCoefficients.none { it != 0.0 }) { "an open optimum weights no continuous column" }
        val firstLocalArm = if (minimizer != null) 1 else 0
        val localSearch = localSearchWorkers(firstLocalArm, cancellation, objective)
        if (localSearch.isEmpty()) {
            return minimizer?.minimize(theoryParams)
                ?: OpenTheoryOptimum.Bounded(null, null, TerminationReason.Unsupported, SolveStats.EMPTY)
        }
        val workers = buildList {
            minimizer?.let { add(descentWorker(it, armId = 0)) }
            addAll(localSearch)
        }
        // A descent rebuilt for making no progress would prepare the model again and lose the round it was proving.
        val portfolio = Portfolio.thompson(
            workers,
            lanes = 1,
            seed = seed,
            reseedStaleThreshold = 0,
            witnessCheck = witnessCheck(objective),
        )
        val result = portfolio.use { it.minimize(cancellation) }
        return optimumOf(result, objective)
    }

    // The pool's verdict with the exact witness behind it: the theory's own where the pool holds its stand-in, and
    // the better of the pool's and the descent's best where a value rounded in the pool hid which is lower.
    private fun optimumOf(result: MinimizeResult, objective: LinearObjective): OpenTheoryOptimum = when (result) {
        is MinimizeResult.Optimal -> best(result.sample, objective).let { (witness, value) ->
            OpenTheoryOptimum.Optimal(witness, value, result.stats)
        }

        is MinimizeResult.BestFound -> best(result.sample, objective).let { (witness, value) ->
            OpenTheoryOptimum.Bounded(witness, value, result.reason, result.stats)
        }

        // Only the descent finds a ray.
        is MinimizeResult.Unbounded -> checkNotNull(descentVerdict as? OpenTheoryOptimum.Unbounded) {
            "an unbounded verdict no descent reached"
        }.copy(stats = result.stats)

        is MinimizeResult.Infeasible -> OpenTheoryOptimum.Infeasible(result.stats)

        is MinimizeResult.Unknown -> OpenTheoryOptimum.Bounded(null, null, result.reason, result.stats)
    }

    private fun best(sample: Sample, objective: LinearObjective): Pair<OpenTheoryAssignment, BigInteger> {
        val pooled = theoryWitnesses.firstOrNull { it.sample === sample }
        val pool = (pooled?.assignment ?: OpenTheoryAssignment.Sampled(sample)) to
            (pooled?.value ?: objective.exactValue(sample))
        val descent = theoryWitnesses.mapNotNull { w -> w.value?.let { w.assignment to it } }.minByOrNull { it.second }
        return if (descent != null && descent.second < pool.second) descent else pool
    }

    private fun assignmentOf(sample: Sample): OpenTheoryAssignment =
        theoryWitnesses.firstOrNull { it.sample === sample }?.assignment ?: OpenTheoryAssignment.Sampled(sample)

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
        is OpenTheoryResult.Sat -> SolveResult.Sat(pooled(result.assignment, value = null).sample, result.stats)
        is OpenTheoryResult.Unsat -> SolveResult.Unsat(stats = result.stats)
        is OpenTheoryResult.Unknown -> SolveResult.Unknown(result.reason, result.stats)
    }

    // The pool's stand-in for a theory witness, remembered so the exact witness can be read back.
    private fun pooled(assignment: OpenTheoryAssignment, value: BigInteger?): TheoryWitness =
        TheoryWitness(assignment.toSampleOrPlaceholder(model), assignment, value)
            .also { theoryWitnesses += it }

    private fun descentWorker(minimizer: OpenTheoryMinimizer, armId: Int): PortfolioWorker = PortfolioWorker.ofMinimize(
        "theory/${minimizer.theoryPipeline.name.lowercase()}",
        armId,
        resumable = { readBound -> descentSlices(minimizer, readBound) },
    ) { _, _, _, _ -> error("the descent runs only by slice") }

    // The descent paused and resumed by slice. Each round reads the pool's bound, but only where a Double states it
    // exactly: a rounded bound could refute a value the pool's witness does not reach.
    private fun descentSlices(minimizer: OpenTheoryMinimizer, readBound: () -> Double): ResumableSearch =
        object : ResumableSearch {
            private val descent = minimizer.descent(theoryParams) { exactInteger(readBound()) }
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
                    val witness = pooled(assignment, value)
                    val objective = value.doubleValue(exactRequired = false)
                    onIncumbent(MinimizeResult.BestFound(witness.sample, objective, TerminationReason.BudgetExhausted))
                } ?: return null
                descentVerdict = verdict
                return when (verdict) {
                    is OpenTheoryOptimum.Optimal -> MinimizeResult.Optimal(
                        standIn(verdict.assignment, verdict.value),
                        verdict.value.doubleValue(exactRequired = false),
                        verdict.stats,
                    )

                    is OpenTheoryOptimum.Infeasible -> MinimizeResult.Infeasible(stats = verdict.stats)

                    is OpenTheoryOptimum.Unbounded -> MinimizeResult.Unbounded(
                        standIn(verdict.witness, verdict.value),
                        verdict.value.doubleValue(exactRequired = false),
                        direction = emptyList(),
                        stats = verdict.stats,
                    )

                    // A round that refuted the pool's bound proved the pool's incumbent optimal: nothing is left to
                    // search, whichever arm holds it.
                    is OpenTheoryOptimum.Bounded -> MinimizeResult.Unknown(verdict.reason, verdict.stats)
                }
            }

            // The descent reports a verdict on a witness it installed, so the pool already holds its stand-in.
            private fun standIn(assignment: OpenTheoryAssignment, value: BigInteger): Sample =
                (theoryWitnesses.firstOrNull { it.assignment === assignment } ?: pooled(assignment, value)).sample

            override fun close() = descent.close()
        }

    private fun localSearchWorkers(
        firstArm: Int,
        cancellation: Cancellation,
        objective: LinearObjective?,
    ): List<PortfolioWorker> {
        // On a model of continuous columns alone with no Boolean to choose, the completion of any candidate is the
        // whole LP: local search would only hand the theory the problem it already solves.
        if (model.numIntVars == 0 && model.numBoolVars == 0) return emptyList()
        val searchModel = LocalSearchModel.open(model)
        // An open model's rows can be strict, which only the theory certifies; it decides each candidate's residual.
        val completion = if (model.numRealVars > 0) TheoryCompletion(model, theoryParams) else null
        if (!localSearchSupports(searchModel, completes = completion != null)) return emptyList()
        // Every arm starts from the relaxation's optimum inside the search windows rather than near zero; an LP
        // that finds no point in its slice of the budget leaves the arms to their own random starts.
        val start = model.lpSeed(searchModel.domains, cancellation or Cancellation.after(SEED_BUDGET))
        val kind = if (objective != null) Kind.COP else Kind.CSP
        return LocalSearchCatalog.diverse(kind, localSearchArms).mapIndexed { i, recipe ->
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

    // A local-search witness is re-derived from the source model, and its objective must be the one it claims
    // wherever a Double states that exactly; the theory arm's witnesses are exact by construction.
    private fun witnessCheck(objective: LinearObjective?): WitnessCheck = WitnessCheck { sample, claimed ->
        if (theoryWitnesses.any { it.sample === sample }) return@WitnessCheck null
        refuteOpenWitness(model, sample)?.let { return@WitnessCheck it }
        if (objective == null || claimed == null) return@WitnessCheck null
        val exact = objective.exactValue(sample)
        val stated = exact.abs() <= BigInteger.fromLong(EXACT_DOUBLE_INTEGER)
        if (!stated || exact.doubleValue(exactRequired = false) == claimed) return@WitnessCheck null
        "objective $claimed, but the assignment scores $exact"
    }

    private class TheoryWitness(val sample: Sample, val assignment: OpenTheoryAssignment, val value: BigInteger?)

    private companion object {
        const val DEFAULT_LS_ARMS: Int = 3

        // Wall-clock ceiling on the seed LP, a small slice beside any portfolio budget.
        val SEED_BUDGET: Duration = 500.milliseconds
    }
}

// Every integer up to this magnitude is a Double exactly.
private const val EXACT_DOUBLE_INTEGER: Long = 1L shl 53

// [bound] as an exact integer, or null where a Double does not state one exactly.
private fun exactInteger(bound: Double): BigInteger? {
    if (abs(bound) > EXACT_DOUBLE_INTEGER.toDouble() || bound != floor(bound)) return null
    return BigInteger.fromLong(bound.toLong())
}

// [sample] moved into [searchModel]'s windows, so it seeds a search whose invariants were sized for them.
private fun inWindows(sample: Sample, searchModel: LocalSearchModel): Sample {
    val domains = searchModel.domains
    if (sample.ints.size != domains.size) return sample
    if (sample.ints.indices.all { domains[it].contains(sample.ints[it]) }) return sample
    return sample.copy(ints = LongArray(domains.size) { domains[it].clamp(sample.ints[it]) })
}

// The exact value of this objective at [sample], over the integer and Boolean terms an open optimum weights.
private fun LinearObjective.exactValue(sample: Sample): BigInteger {
    var total = BigInteger.fromLong(constant)
    for (v in intCoefficients.indices) {
        if (intCoefficients[v] == 0L) continue
        total += BigInteger.fromLong(intCoefficients[v]) * BigInteger.fromLong(sample.ints[v])
    }
    for (b in boolWeights.indices) {
        if (boolWeights[b] != 0L && sample.bools[b]) total += BigInteger.fromLong(boolWeights[b])
    }
    return total
}
