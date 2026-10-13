package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.portfolio.ArmFamily
import com.eignex.klause.portfolio.PortfolioEvidence
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.stream.lock

internal fun TranscendentalProblem.prepareTranscendentalExecutionPlan(): PreparedSourcePlan = PreparedSourcePlan(
    this, null, PreparedExecutionKind.TRANSCENDENTAL, null, null,
    listOf(ExecutionComponentDescriptor("transcendental", ComponentResponsibility.COMPLETION,
        emptyList(), integerCoordinates, realCoordinates, (0 until boolCount).toList(),
        ComponentExecutionContract(
            ComponentStrategySupport(ProcedureCompleteness.WITNESS_SEARCH,
                setOf(ExecutionClaimAuthority.WITNESS, ExecutionClaimAuthority.REFUTATION)),
            ComponentStrategySupport(ProcedureCompleteness.WITNESS_SEARCH, witnessAuthority)))),
    boolCount, integerCoordinates.size, realCoordinates.size,
    integerCoordinates = integerCoordinates, realCoordinates = realCoordinates,
    operations = setOf(ExecutionOperation.SATISFY),
)

internal class TranscendentalPortfolioInvocation(
    private val request: TranscendentalTheoryRequest,
    params: TranscendentalTheoryParams,
    private val engine: FiniteEngine,
    private val cores: Int,
) {
    private val params = params.copy(realLower = params.realLower.toMap(), realUpper = params.realUpper.toMap(),
        strictRealLower = params.strictRealLower.toSet(), strictRealUpper = params.strictRealUpper.toSet())

    fun execute(): TranscendentalTheoryExecution {
        val plan = request.problem.prepareTranscendentalExecutionPlan()
        val identity = ModelIdentity.of(request.problem)
        val backend = when (engine) {
            FiniteEngine.BACKTRACK -> params.strategy.backtrack.assumptions
            FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS -> params.strategy.localSearch.assumptions
            else -> Assumptions.None
        }
        val assumptions = backend.mergedWith(params.strategy.assumptions)
        val binding = EngineInvocation(plan, identity, sourceAssumptions = assumptions)
        val scope = TranscendentalNativeScope(params)
        val workers = ArrayList<TranscendentalWorkerAdapter>()
        val factories = plan.capabilities.admission(engine).arms.map { strategy ->
            val arm = when (strategy) {
                ExecutionStrategy.COMPLETE -> 0
                ExecutionStrategy.LOCAL_SEARCH -> 1
                ExecutionStrategy.ALNS -> 2
                ExecutionStrategy.MIXED -> error("admission supplies participating arms")
            }
            val family = when (arm) { 0 -> ArmFamily.Backtrack; 1 -> ArmFamily.LocalSearch; else -> ArmFamily.Lns }
            PortfolioArmFactory(plan, arm, family) { slot ->
                val strategyParams = params.strategy
                val controls = params.copy(maximumWork = params.maximumWork?.let(slot::nativeAllowance),
                    cancellation = scope.cancellation, nodeBudget = slot.nodeBudget,
                    strategy = strategyParams.copy(
                        assumptions = binding.assumptions,
                        backtrack = strategyParams.backtrack.copy(randomSeed = slot.seed, assumptions = Assumptions.None,
                            maxDecisions = slot.nativeAllowance(strategyParams.backtrack.maxDecisions),
                            maxInstructions = strategyParams.backtrack.maxInstructions?.let(slot::nativeAllowance),
                            nodeBudget = slot.nodeBudget),
                        localSearch = strategyParams.localSearch.copy(randomSeed = slot.seed, assumptions = Assumptions.None,
                            maxFlips = slot.heuristicAllowance(strategyParams.localSearch.maxFlips),
                            maxInstructions = strategyParams.localSearch.maxInstructions?.let(slot::heuristicAllowance),
                            nodeBudget = slot.nodeBudget),
                        alnsIterations = slot.heuristicAllowance(strategyParams.alnsIterations.toLong()).toInt()))
                val selected = when (arm) {
                    0 -> FiniteEngine.BACKTRACK
                    1 -> FiniteEngine.LOCAL_SEARCH
                    else -> FiniteEngine.ALNS
                }
                TranscendentalWorkerAdapter(request.problem, controls, selected, binding).also { workers += it }
                    .worker(arm)
            }
        }
        val budget = params.nodeBudget ?: params.strategy.backtrack.nodeBudget ?: params.strategy.localSearch.nodeBudget
        val seed = if (engine == FiniteEngine.BACKTRACK) params.strategy.backtrack.randomSeed
            else params.strategy.localSearch.randomSeed
        val invocation = PortfolioInvocation(plan, factories,
            PortfolioInvocationControls(parallelism = cores, seed = seed ?: 0,
                cancellation = scope.cancellation, nodeBudget = budget, reseedStaleThreshold = 0),
            PortfolioEvidence(identity, polynomialWitnessVerifier(binding, scope.verification))
                .bindInvocation(binding, scope.verification)) { materialized ->
            val complete = materialized.indices.filter { materialized[it].family == ArmFamily.Backtrack }
            PortfolioInvocationPolicy(shares = DoubleArray(materialized.size) {
                if (it in complete) 0.75 / complete.size else 0.0
            })
        }
        val result = invocation.use { it.solve(scope.cancellation) }
        val published = when (result) {
            is SolveResult.Sat -> OpenTheoryResult.Sat(TranscendentalAssignment(result.assignment), result.stats)
            is SolveResult.Unsat -> OpenTheoryResult.Unsat(result.stats)
            is SolveResult.Unknown -> OpenTheoryResult.Unknown(when {
                params.timeout() -> TerminationReason.Timeout
                params.cancellation() -> TerminationReason.Cancelled
                (engine == FiniteEngine.BACKTRACK || engine == FiniteEngine.MIXED) &&
                    params.strategy.backtrack.cancellation() -> TerminationReason.Cancelled
                (engine == FiniteEngine.LOCAL_SEARCH || engine == FiniteEngine.ALNS || engine == FiniteEngine.MIXED) &&
                    params.strategy.localSearch.cancellation() -> TerminationReason.Cancelled
                else -> TerminationReason.BudgetExhausted
            }, result.stats)
        }
        return TranscendentalTheoryExecution(published, scope.work, workers.any { it.representationDeclined })
    }
}

private class TranscendentalNativeScope(private val params: TranscendentalTheoryParams) {
    private val lock = Concurrency.Strict.lock()
    private var charged = 0L
    private val external = params.timeout or params.cancellation
    val work: Long get() = lock.withLock { charged }
    private val meter = WorkMeter { units ->
        require(units >= 0)
        lock.withLock {
            charged += minOf(units, Long.MAX_VALUE - charged)
            external.charge(units)
        }
    }
    val cancellation: Cancellation = object : Cancellation {
        override fun isCancelled(): Boolean = external() || params.maximumWork?.let { work >= it } == true
        override fun deadline() = external.deadline()
        override fun workMeter(): WorkMeter = meter
    }
    // Native points already passed source checks; admission checks pins without renewing native allowances.
    val verification: Cancellation = Cancellation { external() }
}
