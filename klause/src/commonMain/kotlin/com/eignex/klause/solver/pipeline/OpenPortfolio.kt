@file:OptIn(ExperimentalAtomicApi::class)

package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchEngine
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.localSearchSupports
import com.eignex.klause.lp.relaxation.lpSeed
import com.eignex.klause.portfolio.ArmFamily
import com.eignex.klause.portfolio.LocalSearchCatalog
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioEvidence
import com.eignex.klause.portfolio.PortfolioIncumbents
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.portfolio.kind
import com.eignex.klause.solver.ProblemProfile
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.EvidenceKind
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.abs
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.toDouble
import com.eignex.klause.util.toLongExact
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
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
    private val sourceObjective: OpenSourceObjective? = null,
    private val publication: SourcePublicationObserver? = null,
    preparedSource: PreparedSourcePlan? = null,
) {
    private val descentVerdict = AtomicReference<OpenTheoryOptimum.Unbounded?>(null)
    private val lifetime = theoryParams.cancellation or theoryParams.timeout
    private val preparedPlan = preparedSource ?: request?.preparedPlan ?: model.prepareExecutionPlan()
    private val sourceModel = preparedPlan.originalSource as? Problem ?: model

    private fun workerParams(slot: PortfolioWorkerSlot): TheoryParams = theoryParams.copy(
        randomSeed = slot.seed,
        openWorkLimit = slot.nativeAllowance(theoryParams.openWorkLimit),
        maxLeaves = slot.nativeAllowance(theoryParams.maxLeaves),
        maxDecisions = slot.nativeAllowance(theoryParams.maxDecisions),
        maxFlips = slot.heuristicAllowance(theoryParams.maxFlips),
        maxInstructions = theoryParams.maxInstructions?.let(slot::heuristicAllowance),
        cancellation = theoryParams.cancellation or slot.cancellation,
        nodeBudget = slot.nodeBudget,
    )

    /** Run the portfolio until an arm settles the model or [cancellation] fires. */
    fun solve(cancellation: Cancellation): OpenTheoryResult {
        val firstLocalArm = if (request != null) 1 else 0
        val localSearch = localSearchFactories(firstLocalArm, lifetime or cancellation, objective = null)
        val factories = buildList {
            request?.let { source -> add(PortfolioArmFactory(preparedPlan, 0, ArmFamily.Backtrack) { slot ->
                theoryWorker(source, slot).bindEvidence(ModelIdentity.of(sourceModel))
            }) }
            addAll(localSearch)
        }
        if (factories.isEmpty()) return OpenTheoryResult.Unknown(TerminationReason.Unsupported, SolveStats.EMPTY)
        val identity = ModelIdentity.of(sourceModel)
        val binding = EngineInvocation(preparedPlan, identity)
        val boundFactories = factories.map { factory ->
            PortfolioArmFactory(preparedPlan, factory.armId, factory.family,
                bind = { it.bindInvocation(binding) }) { slot -> factory.materialize(preparedPlan, slot) }
        }
        val portfolio = PortfolioInvocation(preparedPlan, boundFactories,
            PortfolioInvocationControls(parallelism = lanes, seed = seed, cancellation = lifetime or cancellation,
                nodeBudget = theoryParams.nodeBudget),
            PortfolioEvidence(identity, openWitnessVerifier(sourceModel, null))
                .bindInvocation(binding, lifetime or cancellation)) { workers ->
            PortfolioInvocationPolicy(ProblemProfile.of(model, optimizing = false),
                reservedShares(workers, if (request != null) THEORY_SHARE else 0.0))
        }
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
        val stop = lifetime or cancellation
        descentVerdict.store(null)
        require(minimizer == null || objective.realCoefficientValues.none { it != 0.0 }) {
            "the theory's descent minimizes no continuous column"
        }
        val sourceValues = sourceObjective ?: OpenSourceObjective(objective,
            request?.objective ?: objective, request?.maximize ?: false,
            request?.linearAuthorityContext(stop), stop)
        val context = sourceValues.context
        val integral = minimizer.takeIf { context == null }
        val rational = request != null && integral == null
        val firstLocalArm = if (integral != null || rational) 1 else 0
        val localSearch = localSearchFactories(firstLocalArm, stop, objective)
        val incumbents = sourceValues.incumbents()
        val identity = ModelIdentity.of(sourceModel, objective)
        val binding = EngineInvocation(preparedPlan, identity, objective, scope = context?.scope)
        val linear = context?.let { OpenLinearPortfolioResults(binding, it, sourceValues, incumbents,
            stop) }
        val factories = buildList {
            integral?.let { source -> add(PortfolioArmFactory(preparedPlan, 0, ArmFamily.Backtrack) { slot ->
                descentWorker(source, slot) {
                    incumbents.exchange.current()?.objective?.execution?.let { if (it.den == BIG_ONE) it.num else null }
                }.bindEvidence(ModelIdentity.of(sourceModel, objective))
            }) }
            if (rational) add(PortfolioArmFactory(preparedPlan, 0, ArmFamily.Backtrack) { slot ->
                val retained = AtomicReference<LinearPortfolioSearch?>(null)
                val worker = PortfolioWorker.ofMinimize("theory/exact-rational-descent", 0, resumable = {
                    val descent = RationalTheoryDescent(sourceModel, objective, workerParams(slot), context) {
                        incumbents.exchange.current()?.let { it.assignment to it.objective.execution }
                    }
                    if (linear == null) descent else {
                        OpenLinearPortfolioSearch(LinearPortfolioSearch(descent, binding).also(retained::store))
                    }
                }) { _, _, _, _ -> error("the rational descent runs only by slice") }
                    .bindEvidence(identity)
                linear?.retain(worker, retained)
                worker
            })
            addAll(localSearch)
        }
        if (factories.isEmpty()) return OpenTheoryOptimum.Bounded(null, null,
            TerminationReason.Unsupported, SolveStats.EMPTY)
        val boundFactories = factories.map { factory ->
            PortfolioArmFactory(preparedPlan, factory.armId, factory.family,
                bind = { it.bindInvocation(binding) }) { slot -> factory.materialize(preparedPlan, slot) }
        }
        val verifier = openWitnessVerifier(sourceModel, objective, context, stop)
        val evidence = PortfolioEvidence(identity, verifier).bindInvocation(binding, stop)
        val portfolio = PortfolioInvocation(preparedPlan, boundFactories,
            PortfolioInvocationControls(parallelism = lanes, seed = seed, cancellation = stop,
                nodeBudget = theoryParams.nodeBudget, reseedStaleThreshold = 0), evidence) { workers ->
            PortfolioInvocationPolicy(ProblemProfile.of(model, optimizing = true),
                reservedShares(workers, if (integral != null || rational) DESCENT_SHARE else 0.0))
        }
        val result = portfolio.use { it.minimize(cancellation, onImprovement = null, incumbents,
            terminalCheck = linear, onAccepted = { accepted -> publication?.accepted(accepted.assignment,
                SourceValue.Rational(accepted.objective.source), accepted.version) }) }
        return linear?.fold(result) ?: optimumOf(result, incumbents)
    }

    private fun optimumOf(
        result: MinimizeResult,
        incumbents: PortfolioIncumbents<OpenIncumbentValue>,
    ): OpenTheoryOptimum = when (result) {
            is MinimizeResult.Optimal -> {
                val incumbent = checkNotNull(incumbents.exchange.current())
                OpenTheoryOptimum.Optimal(assignmentOf(incumbent.assignment),
                    incumbent.objective.execution, result.stats)
            }

            is MinimizeResult.BestFound -> {
                val incumbent = checkNotNull(incumbents.exchange.current())
                OpenTheoryOptimum.Bounded(
                    assignmentOf(incumbent.assignment),
                    incumbent.objective.execution,
                    result.reason,
                    result.stats,
                )
            }

            // Only the descent finds a ray.
            is MinimizeResult.Unbounded -> checkNotNull(descentVerdict.load()) {
                "an unbounded verdict no descent reached"
            }.copy(stats = result.stats)

            is MinimizeResult.Infeasible -> OpenTheoryOptimum.Infeasible(result.stats)

            is MinimizeResult.Unknown -> OpenTheoryOptimum.Bounded(null, null, result.reason, result.stats)
        }

    private fun assignmentOf(sample: Sample): OpenTheoryAssignment = OpenTheoryAssignment.Sampled(sample)

    // The theory exactly as the default open route decides it.
    private fun decide(request: OpenTheoryRequest, params: TheoryParams): OpenTheoryResult =
        (OpenTheoryPipeline.execute(request, params) as OpenTheoryExecution.Satisfy).result

    private fun theoryWorker(request: OpenTheoryRequest, slot: PortfolioWorkerSlot): PortfolioWorker =
        PortfolioWorker.ofSolve(
            "theory/${request.route.name.lowercase()}", 0,
            resumable = { theorySlices(request, workerParams(slot)) },
        ) { slice, _ ->
            toSolveResult(decide(request, workerParams(slot).copy(
                cancellation = theoryParams.cancellation or slot.cancellation or slice,
            )))
        }

    private fun theorySlices(request: OpenTheoryRequest, params: TheoryParams): ResumableSolve =
        object : ResumableSolve {
            private val search = ResumableOpenTheory(OpenTheoryPipeline.engineFor(request), params)
            override val isDone: Boolean get() = search.isDone
            override val stats: SolveStats get() = search.stats
            override val work: Long get() = search.work

            override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long): SolveResult? =
                search.runSlice(global, sliceMillis, sliceNodes)?.let(::toSolveResult)

            override fun close() = search.close()
        }

    private fun toSolveResult(result: OpenTheoryResult): SolveResult = when (result) {
        is OpenTheoryResult.Sat -> SolveResult.Sat(result.assignment.toSample(sourceModel), result.stats)
        is OpenTheoryResult.Unsat -> SolveResult.Unsat(stats = result.stats)
        is OpenTheoryResult.Unknown -> SolveResult.Unknown(result.reason, result.stats)
    }

    private fun descentWorker(minimizer: OpenTheoryMinimizer, slot: PortfolioWorkerSlot,
        readBound: () -> BigInt?): PortfolioWorker =
        PortfolioWorker.ofMinimize(
            "theory/${minimizer.theoryPipeline.name.lowercase()}",
            0,
            resumable = { descentSlices(minimizer, workerParams(slot), readBound) },
        ) { _, _, _, _ -> error("the descent runs only by slice") }

    // An integral descent reads the attained value directly, including beyond floating-point and 64-bit ranges.
    private fun descentSlices(minimizer: OpenTheoryMinimizer, params: TheoryParams,
        readBound: () -> BigInt?): ResumableSearch =
        object : ResumableSearch {
            private val descent = minimizer.descent(params, readBound)
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
                    val sample = assignment.toSample(sourceModel)
                    val objective = value.toDouble()
                    onIncumbent(MinimizeResult.BestFound(sample, objective, TerminationReason.BudgetExhausted))
                } ?: return null
                if (verdict is OpenTheoryOptimum.Unbounded) descentVerdict.compareAndSet(null, verdict)
                return when (verdict) {
                    is OpenTheoryOptimum.Optimal -> MinimizeResult.Optimal(
                        verdict.assignment.toSample(sourceModel),
                        verdict.value.toDouble(),
                        verdict.stats,
                    )

                    is OpenTheoryOptimum.Infeasible -> MinimizeResult.Infeasible(stats = verdict.stats)

                    is OpenTheoryOptimum.Unbounded -> MinimizeResult.Unbounded(
                        verdict.witness.toSample(sourceModel),
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

    private fun reservedShares(workers: List<PortfolioWorker>, share: Double): DoubleArray {
        val complete = workers.indices.filter { workers[it].family == ArmFamily.Backtrack }
        return DoubleArray(workers.size) { if (it in complete) share / complete.size else 0.0 }
    }

    private fun localSearchFactories(
        firstArm: Int,
        cancellation: Cancellation,
        objective: LinearObjective?,
    ): List<PortfolioArmFactory> {
        val route = preparedPlan.componentPlan?.theoryPipeline ?: ProblemPipeline.UNSUPPORTED_OPEN
        if (route != ProblemPipeline.UNSUPPORTED_OPEN && route != ProblemPipeline.FINITE_CP) {
            return List(localSearchArms) { index ->
                val adaptive = index == localSearchArms - 1 && localSearchArms > 1
                PortfolioArmFactory(preparedPlan, firstArm + index,
                    if (adaptive) ArmFamily.Lns else ArmFamily.LocalSearch) { slot ->
                    val local = workerParams(slot)
                    val adapter = slot.own(OpenExactNeighborhood(sourceModel, local, objective, preparedPlan))
                    exactNeighborhoodWorker(if (adaptive) "alns/exact-neighborhood" else "ls/exact-neighborhood",
                        firstArm + index, objective, local.cancellation or local.timeout, timeout = local.timeout,
                        exhausted = { adapter.exhausted }, open = { adapter.search(adaptive,
                            slot.baseSeed + slot.index - firstArm,
                            maximumImprovementMoves = if (adaptive) local.alnsIterations.toLong() else null) })
                        .bindEvidence(ModelIdentity.of(sourceModel, objective), provesResults = false)
                }
            }
        }
        if (!searchesContinuousOnly && model.numIntVars == 0 && model.numBoolVars == 0) return emptyList()
        val searchModel = LocalSearchModel.open(model)
        if (!localSearchSupports(searchModel, completes = model.numRealVars > 0)) return emptyList()
        val start = model.lpSeed(searchModel.domains, cancellation or Cancellation.after(SEED_BUDGET))
        val profile = ProblemProfile.of(model, optimizing = objective != null)
        return LocalSearchCatalog.diverse(profile.kind, localSearchArms, profile.problemClass).mapIndexed { i, recipe ->
            PortfolioArmFactory(preparedPlan, firstArm + i, ArmFamily.LocalSearch) { slot ->
                val local = workerParams(slot)
                val completion = if (model.numRealVars > 0) {
                    val theory = slot.own(TheoryCompletion(sourceModel, local, preparedPlan))
                    if (objective != null && objective.realCoefficientValues.any { it != 0.0 }) {
                        OptimizingCompletion(model, objective, theory)
                    } else theory
                } else null
                val engine = LocalSearchEngine(searchModel, strategy = recipe.strategy,
                    optimizeStrategy = recipe.optimizeStrategy, seedImplicitOnRestart = recipe.seedImplicitOnRestart,
                    completion = completion)
                val params = LocalSearchParams(randomSeed = slot.baseSeed + slot.index - firstArm,
                    maxFlips = local.maxFlips, maxInstructions = local.maxInstructions,
                    cancellation = local.cancellation or local.timeout, initialAssignment = start,
                    nodeBudget = slot.nodeBudget)
                val label = "ls/${recipe.label}"
                val worker = if (objective == null) {
                    PortfolioWorker.ofSolve(label, firstArm + i, countsInstructions = true,
                        resumable = { engine.resumableSolve(params) }) { stop, budget ->
                        engine.solve(params.copy(cancellation = params.cancellation or stop,
                            maxInstructions = budget?.let { minOf(it, params.maxInstructions ?: it) }), warm = null)
                    }
                } else {
                    PortfolioWorker.ofMinimize(label, firstArm + i, countsInstructions = true,
                        resumable = { engine.resumable(objective, params) }) { _, warm, stop, budget ->
                        val from = warm?.let { inWindows(it, searchModel) } ?: start
                        engine.improvements(objective, params.copy(cancellation = params.cancellation or stop,
                            initialAssignment = from,
                            maxInstructions = budget?.let { minOf(it, params.maxInstructions ?: it) }), warm = null)
                    }
                }
                worker.bindEvidence(ModelIdentity.of(sourceModel, objective), provesResults = false)
            }
        }
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

internal fun openWitnessVerifier(
    model: Problem,
    objective: LinearObjective?,
    context: SourceProofContext? = null,
    cancellation: Cancellation = Cancellation.Never,
): CandidateVerifier<Sample, Double?> =
    CandidateVerifier { candidate ->
        val sample = candidate.assignment
        val claimed = candidate.objective
        val certificate = sample.witnessCertificate
        if (certificate != null) {
            if (!certificate.model.sameModel(ModelIdentity.of(model)) || certificate.kind != EvidenceKind.Witness) {
                return@CandidateVerifier Verification.Rejected(
                    "witness certificate belongs to a different model or claim",
                )
            }
        }
        if (claimed?.isNaN() == true) return@CandidateVerifier Verification.Rejected("objective is NaN")
        if (context != null) return@CandidateVerifier context.linearWitnessVerifier(cancellation).verify(candidate)
        if (certificate == null) {
            when (val checked = verifyOpenWitness(model, sample)) {
                is Verification.Rejected -> return@CandidateVerifier checked
                is Verification.Indeterminate -> return@CandidateVerifier checked
                is Verification.Accepted -> Unit
            }
        }
        if (objective == null || claimed == null) return@CandidateVerifier Verification.Accepted(candidate)
        val exact = objective.evaluateExact(sample)
        val value = exact.toDouble()
        val agrees = if (objective.realCoefficientValues.any { it != 0.0 }) {
            abs(value - claimed) <= OBJECTIVE_TOLERANCE * maxOf(1.0, abs(value))
        } else {
            exact.num.abs() > bigIntOf(EXACT_DOUBLE_INTEGER) || value == claimed
        }
        if (agrees) Verification.Accepted(candidate) else {
            Verification.Rejected("objective $claimed, but the assignment scores $exact")
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
    if ((0 until sample.numIntVars).all { domains[it].contains(sample.intValue(it)) }) return sample
    return sample.copy(ints = LongArray(domains.size) { domains[it].clamp(sample.intValue(it)) })
}
