package com.eignex.klause.solver.pipeline

import com.eignex.klause.portfolio.ArmFamily
import com.eignex.klause.portfolio.PortfolioEvidence
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason

internal class NonlinearIntegerPortfolio(
    private val request: NonlinearIntegerTheoryRequest,
    private val params: NonlinearIntegerTheoryParams,
    private val cores: Int,
    private val engine: FiniteEngine = FiniteEngine.MIXED,
) {
    fun execute(): PolynomialTheoryExecution {
        val plan = request.preparedPlan
        if (!plan.capabilities.admission(engine).runnable) return PolynomialTheoryExecution(
            OpenTheoryResult.Unknown(TerminationReason.Unsupported, SolveStats.EMPTY), PolynomialTheoryStats())
        val polynomial = params.polynomial
        val scope = QuadraticNativeScope(polynomial)
        val identity = ModelIdentity.of(request.problem)
        val binding = EngineInvocation(plan, identity, sourceAssumptions = polynomial.strategy.assumptions)
        val workers = ArrayList<NonlinearIntegerInvocation>()
        val factories = plan.capabilities.admission(engine).arms.map { strategy ->
            val arm = when (strategy) {
                ExecutionStrategy.COMPLETE -> 0
                ExecutionStrategy.LOCAL_SEARCH -> 1
                ExecutionStrategy.ALNS -> 2
                ExecutionStrategy.MIXED -> error("admission supplies participating arms")
            }
            val family = when (arm) { 0 -> ArmFamily.Backtrack; 1 -> ArmFamily.LocalSearch; else -> ArmFamily.Lns }
            PortfolioArmFactory(plan, arm, family) { slot ->
                val native = scope.worker(slot.index, polynomial.maximumWork?.let(slot::nativeAllowance))
                val strategy = polynomial.strategy
                val controls = params.copy(
                    polynomial = polynomial.copy(cancellation = native.polynomial, maximumWork = null,
                        strategy = strategy.copy(
                            backtrack = strategy.backtrack.copy(randomSeed = slot.seed,
                                maxDecisions = slot.nativeAllowance(strategy.backtrack.maxDecisions),
                                nodeBudget = slot.nodeBudget),
                            localSearch = strategy.localSearch.copy(randomSeed = slot.seed,
                                maxFlips = slot.heuristicAllowance(strategy.localSearch.maxFlips),
                                maxInstructions = strategy.localSearch.maxInstructions?.let(slot::heuristicAllowance),
                                nodeBudget = slot.nodeBudget))),
                    maximumCandidates = slot.nativeAllowance(params.maximumCandidates),
                    maximumMoves = slot.heuristicAllowance(params.maximumMoves), nodeBudget = slot.nodeBudget,
                )
                val engine = when (arm) {
                    0 -> FiniteEngine.BACKTRACK
                    1 -> FiniteEngine.LOCAL_SEARCH
                    else -> FiniteEngine.ALNS
                }
                val worker = NonlinearIntegerInvocation(request, controls, engine, binding)
                workers += worker
                slot.own(AutoCloseable { native.observe(worker.nativeStats) })
                worker.worker(arm, slot).bindInvocation(binding)
            }
        }
        val strategy = polynomial.strategy
        val seed = if (engine == FiniteEngine.BACKTRACK) strategy.backtrack.randomSeed
            else strategy.localSearch.randomSeed
        val budget = params.nodeBudget ?: when (engine) {
            FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS -> strategy.localSearch.nodeBudget
            else -> strategy.backtrack.nodeBudget ?: strategy.localSearch.nodeBudget
        }
        val invocation = PortfolioInvocation(plan, factories,
            PortfolioInvocationControls(parallelism = cores,
                seed = seed ?: 1L, cancellation = scope.polynomial,
                nodeBudget = budget, reseedStaleThreshold = 0),
            PortfolioEvidence(identity, polynomialWitnessVerifier(binding, scope.polynomial))
                .bindInvocation(binding, scope.polynomial)) { materialized ->
            val complete = materialized.indices.filter { materialized[it].family == ArmFamily.Backtrack }
            PortfolioInvocationPolicy(shares = DoubleArray(materialized.size) {
                if (it in complete) 0.75 / complete.size else 0.0
            })
        }
        val result = invocation.use { it.solve(scope.polynomial) }
        val published = when (result) {
            is SolveResult.Sat -> OpenTheoryResult.Sat(nonlinearIntegerAssignment(result.assignment), result.stats)
            is SolveResult.Unsat -> OpenTheoryResult.Unsat(result.stats)
            is SolveResult.Unknown -> OpenTheoryResult.Unknown(if (polynomial.timeout()) TerminationReason.Timeout
                else if (polynomial.cancellation()) TerminationReason.Cancelled else result.reason, result.stats)
        }
        return PolynomialTheoryExecution(published, scope.polynomialStats, workers.firstNotNullOfOrNull { it.resource })
    }
}
