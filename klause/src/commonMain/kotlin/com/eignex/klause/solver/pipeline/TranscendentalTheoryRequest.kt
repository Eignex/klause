package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation

/** Original exact real function source shared by traversal and heuristic neighborhood execution. */
class TranscendentalTheoryRequest internal constructor(
    /** Immutable source declarations, formula and exact function semantics. */
    val problem: TranscendentalProblem,
) : ExactTheorySatisfactionRequest {
    override fun execute(params: PolynomialTheoryParams, engine: FiniteEngine, cores: Int): PolynomialTheoryExecution {
        require(cores > 0)
        val result = OpenTheoryPipeline.execute(this, TranscendentalTheoryParams(
            maximumWork = params.maximumWork,
            maximumDenseCoefficients = params.maximumDenseCoefficients,
            maximumMatrixCells = params.maximumMatrixCells,
            timeout = params.timeout, cancellation = params.cancellation, strategy = params.strategy,
        ), engine, cores)
        return PolynomialTheoryExecution(result.result, PolynomialTheoryStats(workSpent = result.workSpent),
            nativeWorkScope = "transcendental",
            nativeResource = if (result.representationDeclined) "RepresentationLimit" else null)
    }
}

/** Select source-bound transcendental completion without materializing continuous finite domains. */
fun TranscendentalProblem.pipelineRequest(): TranscendentalTheoryRequest = TranscendentalTheoryRequest(this)

/** Invocation limits and shared strategy controls; limits preserve the original exact source semantics. */
data class TranscendentalTheoryParams(
    /** Cumulative native arithmetic cap across every query and strategy; null disables it. */
    val maximumWork: Long? = 100000,
    /** Conservative rational intermediate bit cap, checked before cross products. */
    val maximumBits: Int = 65536,
    /** Maximum real/integer split depth; incomplete leaves retain UNKNOWN. */
    val maximumDepth: Int = 12,
    /** Dense polynomial relaxation coefficient cap. */
    val maximumDenseCoefficients: Int? = 65536,
    /** Polynomial relaxation matrix cell cap. */
    val maximumMatrixCells: Long? = 262144,
    /** Whole-invocation wall-time token. */
    val timeout: Cancellation = Cancellation.Never,
    /** External cooperative token, forwarding observed native charges once. */
    val cancellation: Cancellation = Cancellation.Never,
    /** Shared backtracking, local-search, ALNS and finite pin controls. */
    val strategy: PolynomialStrategyParams = PolynomialStrategyParams(),
    /** Solve-wide node and heuristic instruction allowance. */
    val nodeBudget: NodeBudget? = null,
    /** Invocation-only rational lower bounds on original real output IDs. */
    val realLower: Map<Int, BigFraction> = emptyMap(),
    /** Invocation-only rational upper bounds on original real output IDs. */
    val realUpper: Map<Int, BigFraction> = emptyMap(),
    /** Real lower bounds whose endpoint is excluded. */
    val strictRealLower: Set<Int> = emptySet(),
    /** Real upper bounds whose endpoint is excluded. */
    val strictRealUpper: Set<Int> = emptySet(),
) {
    init {
        require(maximumWork == null || maximumWork >= 0)
        require(maximumBits > 0 && maximumDepth >= 0)
        require(maximumDenseCoefficients == null || maximumDenseCoefficients > 0)
        require(maximumMatrixCells == null || maximumMatrixCells >= 0)
        require(strictRealLower.all { it in realLower } && strictRealUpper.all { it in realUpper })
    }
}

/** Source-preserving verdict and native arithmetic resource accounting. */
data class TranscendentalTheoryExecution(
    /** Common exact SAT, justified UNSAT or incomplete UNKNOWN result. */
    val result: OpenTheoryResult,
    /** Cumulative native arithmetic steps, separate from strategy decisions and moves. */
    val workSpent: Long,
    /** A required arithmetic representation exceeded its configured capacity. */
    val representationDeclined: Boolean,
)

/**
 * Execute one original exact source. Heuristic neighborhood failures never supply a global refutation;
 * incomplete traversal over unrestricted coordinates retains UNKNOWN.
 */
fun OpenTheoryPipeline.execute(
    request: TranscendentalTheoryRequest,
    params: TranscendentalTheoryParams = TranscendentalTheoryParams(),
    engine: FiniteEngine = FiniteEngine.MIXED,
): TranscendentalTheoryExecution = execute(request, params, engine, 1)

/** Execute retained source workers with explicit parallel lanes; FIXED retains its single worker. */
fun OpenTheoryPipeline.execute(
    request: TranscendentalTheoryRequest,
    params: TranscendentalTheoryParams = TranscendentalTheoryParams(),
    engine: FiniteEngine = FiniteEngine.MIXED,
    cores: Int,
): TranscendentalTheoryExecution {
    require(cores > 0)
    return if (request.problem.hasObjective || !params.supportsInvocation(engine)) {
        TranscendentalTheoryExecution(
            OpenTheoryResult.Unknown(TerminationReason.Unsupported, SolveStats.EMPTY), 0, false,
        )
    } else if (engine == FiniteEngine.MIXED || (engine != FiniteEngine.FIXED && cores > 1)) {
        TranscendentalPortfolioInvocation(request, params, engine, cores).execute()
    } else TranscendentalWorkerAdapter(request.problem, params, engine).execute()
}

private fun TranscendentalTheoryParams.supportsInvocation(engine: FiniteEngine): Boolean {
    val complete = strategy.backtrack
    val local = strategy.localSearch
    val traversal = engine in setOf(FiniteEngine.BACKTRACK, FiniteEngine.FIXED, FiniteEngine.MIXED)
    val heuristic = engine in setOf(FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS, FiniteEngine.MIXED)
    if (complete.componentFactory != null || complete.toleranceCheck != null) return false
    if (traversal && complete.onEvent != null) return false
    if (heuristic && (local.onEvent != null || local.initialAssignment != null ||
        local.pooledIncumbents != null || local.lsObjective != null)) return false
    val scopes = listOf(strategy.assumptions, complete.assumptions, local.assumptions)
    if (scopes.any { !it.deductions.isEmpty }) return false
    if (engine == FiniteEngine.MIXED && (!complete.assumptions.isEmpty || !local.assumptions.isEmpty)) return false
    val budgets = listOfNotNull(nodeBudget, complete.nodeBudget, local.nodeBudget)
    return budgets.all { it === budgets.first() }
}
