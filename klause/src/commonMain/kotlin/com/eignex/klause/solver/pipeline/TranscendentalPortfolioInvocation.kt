package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.portfolio.ArmFamily
import com.eignex.klause.portfolio.PortfolioEvidence
import com.eignex.klause.portfolio.SourceWorkerBinding
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.ArithmeticCancellation
import com.eignex.klause.util.ArithmeticWorkSource
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkCreditPool
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.WorkReservation
import com.eignex.klause.util.arithmeticSource
import com.eignex.klause.util.reserveWork
import com.eignex.kumulant.core.Concurrency

internal fun TranscendentalProblem.prepareTranscendentalExecutionPlan(): PreparedSourcePlan = PreparedSourcePlan(
    this, PreparedExecution.Transcendental(this),
    listOf(
        ExecutionComponentDescriptor(
        "transcendental",
        ComponentResponsibility.COMPLETION,
        emptyList(),
        integerCoordinates,
        realCoordinates,
        (0 until boolCount).toList(),
        ComponentExecutionContract(
            ComponentStrategySupport(
                ProcedureCompleteness.WITNESS_SEARCH,
                setOf(ExecutionClaimAuthority.WITNESS, ExecutionClaimAuthority.REFUTATION),
            ),
            ComponentStrategySupport(ProcedureCompleteness.WITNESS_SEARCH, witnessAuthority),
        ),
    )
    ),
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
    private val params = params.copy(
        realLower = params.realLower.toMap(),
        realUpper = params.realUpper.toMap(),
        strictRealLower = params.strictRealLower.toSet(),
        strictRealUpper = params.strictRealUpper.toSet(),
    )

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
        val scope = TranscendentalNativeScope(params, cores)
        val workers = ArrayList<TranscendentalWorkerAdapter>()
        val factories = plan.capabilities.admission(engine).arms.map { strategy ->
            val arm = when (strategy) {
                ExecutionStrategy.COMPLETE -> 0
                ExecutionStrategy.LOCAL_SEARCH -> 1
                ExecutionStrategy.ALNS -> 2
                ExecutionStrategy.MIXED -> error("admission supplies participating arms")
            }
            val family = when (arm) {
                0 -> ArmFamily.Backtrack;
                1 -> ArmFamily.LocalSearch;
                else -> ArmFamily.Lns
            }
            PortfolioArmFactory(plan, arm, family, binding = {
                SourceWorkerBinding(binding, provesResults = it.permitsSourceProofs)
            }) { slot ->
                val strategyParams = params.strategy
                val controls = params.copy(
                    maximumWork = params.maximumWork?.let(slot::nativeAllowance),
                    cancellation = scope.cancellation,
                    nodeBudget = slot.nodeBudget,
                    strategy = strategyParams.copy(
                        assumptions = binding.assumptions,
                        backtrack = strategyParams.backtrack.copy(
                            randomSeed = slot.seed,
                            assumptions = Assumptions.None,
                            maxDecisions = slot.nativeAllowance(strategyParams.backtrack.maxDecisions),
                            maxInstructions = strategyParams.backtrack.maxInstructions?.let(slot::nativeAllowance),
                            nodeBudget = slot.nodeBudget,
                        ),
                        localSearch = strategyParams.localSearch.copy(
                            randomSeed = slot.seed,
                            assumptions = Assumptions.None,
                            maxFlips = slot.heuristicAllowance(strategyParams.localSearch.maxFlips),
                            maxInstructions = strategyParams.localSearch.maxInstructions?.let(slot::heuristicAllowance),
                            nodeBudget = slot.nodeBudget,
                        ),
                        alnsIterations = slot.heuristicAllowance(strategyParams.alnsIterations.toLong()).toInt(),
                    ),
                )
                val selected = when (arm) {
                    0 -> FiniteEngine.BACKTRACK
                    1 -> FiniteEngine.LOCAL_SEARCH
                    else -> FiniteEngine.ALNS
                }
                val adapter = slot.own(TranscendentalWorkerAdapter(request.problem, controls, selected, binding))
                workers += adapter
                adapter.worker(arm)
            }
        }
        val budget = params.nodeBudget ?: params.strategy.backtrack.nodeBudget ?: params.strategy.localSearch.nodeBudget
        val seed = if (engine == FiniteEngine.BACKTRACK) {
            params.strategy.backtrack.randomSeed
        } else {
            params.strategy.localSearch.randomSeed
        }
        val invocation = PortfolioInvocation(
            plan,
            factories,
            PortfolioInvocationControls(
                parallelism = cores,
                seed = seed ?: 0,
                cancellation = scope.cancellation,
                nodeBudget = budget,
                reseedStaleThreshold = 0,
            ),
            PortfolioEvidence(identity, polynomialWitnessVerifier(binding, scope.cancellation))
                .bindInvocation(binding, scope.cancellation),
        ) { materialized ->
            val complete = materialized.indices.filter { materialized[it].family == ArmFamily.Backtrack }
            PortfolioInvocationPolicy(
                shares = DoubleArray(materialized.size) {
                if (it in complete) 0.75 / complete.size else 0.0
            }
            )
        }
        val result = invocation.use { it.solve(scope.cancellation) }
        val published = when (result) {
            is SolveResult.Sat -> OpenTheoryResult.Sat(TranscendentalAssignment(result.assignment), result.stats)

            is SolveResult.Unsat -> OpenTheoryResult.Unsat(result.stats)

            is SolveResult.Unknown -> OpenTheoryResult.Unknown(
                when {
                    params.timeout() -> TerminationReason.Timeout

                    params.cancellation() -> TerminationReason.Cancelled

                    (engine == FiniteEngine.BACKTRACK || engine == FiniteEngine.MIXED) &&
                        params.strategy.backtrack.cancellation() -> TerminationReason.Cancelled

                    engine in setOf(FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS, FiniteEngine.MIXED) &&
                        params.strategy.localSearch.cancellation() -> TerminationReason.Cancelled

                    else -> TerminationReason.BudgetExhausted
                },
                result.stats,
            )
        }
        return TranscendentalTheoryExecution(published, scope.work, workers.any { it.representationDeclined })
    }
}

private class TranscendentalNativeScope(private val params: TranscendentalTheoryParams, cores: Int) {
    private val pool = WorkCreditPool(params.maximumWork, if (cores > 1) Concurrency.Relaxed else Concurrency.None)
    private val external = params.timeout or params.cancellation
    private val parentAccounting = external.arithmeticSource()
    val work: Long get() = pool.spent
    private val meter = WorkMeter { units ->
        require(units >= 0)
        pool.debit(units)
        external.charge(units)
    }
    val cancellation: Cancellation = object : ArithmeticCancellation {
        override fun isCancelled(): Boolean = external() || params.maximumWork?.let { work >= it } == true
        override fun deadline() = external.deadline()
        override fun workMeter(): WorkMeter = meter
        override fun arithmeticSource(): ArithmeticWorkSource? {
            if (external.workMeter() != null && parentAccounting == null) return null
            return object : ArithmeticWorkSource {
                override fun isCancelled(): Boolean = cancellation.isCancelled()
                override fun reserve(maximum: Int): WorkReservation? =
                    reserveWork(maximum, listOf(pool), parentAccounting) {}
            }
        }
    }
}
