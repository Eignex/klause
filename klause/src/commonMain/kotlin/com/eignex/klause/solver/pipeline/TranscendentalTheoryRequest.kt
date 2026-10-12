package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.ir.TranscendentalExpression
import com.eignex.klause.ir.TranscendentalFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.RunStats
import com.eignex.klause.solver.result.SearchStats
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.search.SearchDecisionBudget
import com.eignex.klause.solver.search.SearchRunEvent
import com.eignex.klause.solver.search.SearchRunObserver
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.theory.transcendental.TranscendentalCheckResult
import com.eignex.klause.theory.transcendental.TranscendentalCondition
import com.eignex.klause.theory.transcendental.TranscendentalSearchComponent
import com.eignex.klause.theory.transcendental.TranscendentalTheoryService
import com.eignex.klause.theory.transcendental.TranscendentalWitness
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.toLongExact
import com.eignex.kumulant.stat.summary.SumResult
import kotlin.time.TimeSource

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
        ), engine)
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
): TranscendentalTheoryExecution = if (request.problem.hasObjective || !params.supportsInvocation(engine)) {
    TranscendentalTheoryExecution(
        OpenTheoryResult.Unknown(TerminationReason.Unsupported, SolveStats.EMPTY), 0, false,
    )
} else TranscendentalInvocation(request.problem, params, engine).execute()

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

private class TranscendentalInvocation(
    private val source: TranscendentalProblem,
    private val params: TranscendentalTheoryParams,
    private val engine: FiniteEngine,
) : ExactNeighborhoodCompletion {
    private val started = TimeSource.Monotonic.markNow()
    private val external = params.timeout or params.cancellation
    private val nodeBudget = params.nodeBudget ?: params.strategy.backtrack.nodeBudget
        ?: params.strategy.localSearch.nodeBudget
    private val service = TranscendentalTheoryService(source, external, params.maximumWork, params.maximumBits,
        PolynomialLimits(params.maximumDenseCoefficients, params.maximumMatrixCells))
    private val stop = external or Cancellation { service.exhausted || nodeBudget?.exhausted() == true }
    private val root = assumptions()
    private var strategyStats = SolveStats.EMPTY
    private var decisions = 0L

    private fun assumptions(): TranscendentalCondition {
        val backend = when (engine) {
            FiniteEngine.BACKTRACK, FiniteEngine.FIXED -> params.strategy.backtrack.assumptions
            FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS -> params.strategy.localSearch.assumptions
            FiniteEngine.MIXED -> Assumptions.None
        }
        val pins = backend.mergedWith(params.strategy.assumptions)
        val integers = pins.ints.map { (id, value) ->
            require(id in source.integerCoordinates.indices) { "integer assumption id out of range" }
            source.integerCoordinates[id] to BigFraction.ofLong(value)
        }.toMap()
        fun realBounds(values: Map<Int, BigFraction>) = values.mapKeys { (id, _) ->
            require(id in source.realCoordinates.indices) { "real assumption id out of range" }
            source.realCoordinates[id]
        }
        return TranscendentalCondition(
            PolynomialCondition(
                pins.bools, integers + realBounds(params.realLower), integers + realBounds(params.realUpper),
            ),
            params.strictRealLower.map { source.realCoordinates[it] }.toSet(),
            params.strictRealUpper.map { source.realCoordinates[it] }.toSet(),
        )
    }

    fun execute(): TranscendentalTheoryExecution {
        var result: OpenTheoryResult? = null
        when (engine) {
            FiniteEngine.BACKTRACK, FiniteEngine.FIXED -> result = search()
            FiniteEngine.LOCAL_SEARCH -> result = walk(false)
            FiniteEngine.ALNS -> result = walk(true)
            FiniteEngine.MIXED -> {
                val begin = service.work
                val quota = params.maximumWork?.let { (it - begin).coerceAtLeast(0) / 4 } ?: 25000
                val phase = external.shorten(0.25) or Cancellation { service.work - begin >= quota }
                result = walk(true, phase)
                if (result !is OpenTheoryResult.Sat && !stop()) result = search()
            }
        }
        val stats = strategyStats.copy(
            run = RunStats("transcendental-${engine.id}", started.elapsedNow().inWholeMilliseconds, params.timeout()),
            search = strategyStats.search.copy(nodes = SumResult(strategyStats.search.nodes.sum + decisions)),
        )
        val published = when (val finished = result) {
            is OpenTheoryResult.Sat -> finished.copy(stats = stats)
            is OpenTheoryResult.Unsat -> finished.copy(stats = stats)
            else -> OpenTheoryResult.Unknown(reason(), stats)
        }
        return TranscendentalTheoryExecution(published, service.work, service.representationDeclined)
    }

    private fun search(): OpenTheoryResult {
        val component = TranscendentalSearchComponent(source, service, root, params.maximumDepth)
        val searchStop = stop or params.strategy.backtrack.cancellation
        val session = SearchSession(listOf(component), cancellation = searchStop, branchers = listOf(component))
        val run = session.openRun(source.boolCount, SearchSolveParams(params.strategy.backtrack.maxDecisions),
            decisionBudget = SearchDecisionBudget {
                if (nodeBudget?.exhausted() == true) false else {
                    nodeBudget?.spend()
                    true
                }
            }, observer = object : SearchRunObserver {
                override fun onCommit(decision: com.eignex.klause.solver.search.SearchDecision, decisionLevel: Int) {
                    decisions++
                }
            })
        return when (val event = run.next()) {
            is SearchRunEvent.Satisfied -> OpenTheoryResult.Sat(
                TranscendentalAssignment(capture(requireNotNull(event.model.valueOf(component.witnessKey)))),
                SolveStats.EMPTY,
            )
            SearchRunEvent.Exhausted -> OpenTheoryResult.Unsat(SolveStats.EMPTY)
            else -> OpenTheoryResult.Unknown(reason(), SolveStats.EMPTY)
        }
    }

    private fun walk(adaptive: Boolean, phase: Cancellation = Cancellation.Never): OpenTheoryResult {
        val local = params.strategy.localSearch
        val moves = if (adaptive) minOf(local.maxFlips, params.strategy.alnsIterations.toLong()) else local.maxFlips
        val neighborhood = ExactNeighborhood(source.boolCount, initialCenters(), this,
            coordinateValues = { sample -> centers(sample) }, adaptive = adaptive, seed = local.randomSeed ?: 0,
            nodeBudget = nodeBudget, maximumMoves = moves,
            maximumInstructions = local.maxInstructions ?: Long.MAX_VALUE,
            nativeWork = { service.work })
        neighborhood.advance(stop or local.cancellation or phase, Long.MAX_VALUE)
        val result = neighborhood.solveResult(reason())
        strategyStats = strategyStats.mergedWith(result.stats)
        return if (result is SolveResult.Sat) {
            OpenTheoryResult.Sat(TranscendentalAssignment(result.assignment), result.stats)
        } else OpenTheoryResult.Unknown(reason(), SolveStats.EMPTY)
    }

    override fun check(condition: ExactNeighborhoodCondition, cancellation: Cancellation): ExactNeighborhoodResult {
        require(condition.cutoff == null) { "transcendental satisfaction has no objective cutoff" }
        val pins = root.rational
        if (condition.bools.any { (id, value) -> pins.bools[id]?.let { it != value } == true }) {
            return ExactNeighborhoodResult.Refuted
        }
        fun intersection(
            current: Map<Int, BigFraction>, fixed: Map<Int, BigFraction>, lower: Boolean,
        ): Map<Int, BigFraction> {
            val result = current.toMutableMap()
            for ((id, value) in fixed) {
                val previous = result[id]
                if (previous == null || if (lower) value > previous else value < previous) result[id] = value
            }
            return result
        }
        val lower = intersection(condition.lower, pins.lower, true)
        val upper = intersection(condition.upper, pins.upper, false)
        val scoped = TranscendentalCondition(PolynomialCondition(condition.bools + pins.bools, lower, upper),
            root.lowerStrict.filter { lower[it] == pins.lower[it] }.toSet(),
            root.upperStrict.filter { upper[it] == pins.upper[it] }.toSet())
        return when (val result = service.check(scoped, cancellation)) {
            is TranscendentalCheckResult.Witness -> ExactNeighborhoodResult.Witness(capture(result.value))
            TranscendentalCheckResult.Refuted -> ExactNeighborhoodResult.Refuted
            TranscendentalCheckResult.Unknown -> ExactNeighborhoodResult.Undecided
        }
    }

    private fun capture(witness: TranscendentalWitness): Sample {
        val integers = witness.integers
        val reals = witness.reals
        val approximate = DoubleArray(reals.size) { id ->
            val value = reals[id]
            value.rational?.toDouble() ?: value.algebraic?.let {
                it.lowerBound.toDouble() / 2 + it.upperBound.toDouble() / 2
            }
                ?: Double.NaN
        }
        val finite = if (integers.all { it.fitsLong() }) {
            LongArray(integers.size) { integers[it].toLongExact() }
        } else longArrayOf()
        return Sample(witness.bools, finite,
            approximate, exactInts = integers, symbolicReals = reals,
            exactReals = reals.mapNotNull { it.rational }.takeIf { it.size == reals.size },
            algebraicReals = reals.mapNotNull { it.algebraic }.takeIf { it.size == reals.size })
    }

    private fun centers(sample: Sample): List<BigFraction> {
        val values = MutableList(source.numericCount) { BigFraction.ZERO }
        source.integerCoordinates.forEachIndexed { id, coordinate ->
            values[coordinate] = BigFraction.of(sample.exactIntValue(id), BIG_ONE)
        }
        source.realCoordinates.forEachIndexed { id, coordinate ->
            values[coordinate] = sample.symbolicReals?.get(id)?.rational
                ?: BigFraction.ofDouble(sample.approximateRealValue(id)) ?: BigFraction.ZERO
        }
        return values
    }

    private fun initialCenters(): List<BigFraction> {
        val values = MutableList(source.numericCount) { BigFraction.ZERO }
        val pending = ArrayDeque<TranscendentalFormula>()
        pending.addLast(source.formula)
        while (pending.isNotEmpty() && !stop()) {
            when (val node = pending.removeLast()) {
                is TranscendentalFormula.And -> pending.addAll(node.operands)
                is TranscendentalFormula.Atom -> if (node.relation == PolynomialRelation.EQ) {
                    for ((target, value) in listOf(node.left to node.right, node.right to node.left)) {
                        if (target is TranscendentalExpression.Variable && value is TranscendentalExpression.Constant) {
                            values[target.id.index] = BigFraction.of(value.value.numerator, value.value.denominator)
                        }
                    }
                }
                else -> Unit
            }
        }
        for (id in values.indices) {
            val lower = root.rational.lower[id]
            val upper = root.rational.upper[id]
            if (lower != null && lower == upper) values[id] = lower
            else if (lower != null && values[id] <= lower) values[id] = lower + BigFraction.ONE
            else if (upper != null && values[id] >= upper) values[id] = upper - BigFraction.ONE
        }
        return values
    }

    private fun reason(): TerminationReason = when {
        params.timeout() -> TerminationReason.Timeout
        params.cancellation() -> TerminationReason.Cancelled
        (engine == FiniteEngine.LOCAL_SEARCH || engine == FiniteEngine.ALNS) &&
            params.strategy.localSearch.cancellation() -> TerminationReason.Cancelled
        (engine == FiniteEngine.BACKTRACK || engine == FiniteEngine.FIXED || engine == FiniteEngine.MIXED) &&
            params.strategy.backtrack.cancellation() -> TerminationReason.Cancelled
        else -> TerminationReason.BudgetExhausted
    }
}
