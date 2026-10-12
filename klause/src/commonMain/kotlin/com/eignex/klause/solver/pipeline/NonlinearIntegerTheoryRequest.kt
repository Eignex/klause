package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.RunStats
import com.eignex.klause.solver.result.SearchStats
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.theory.qfnia.NonlinearIntegerParams
import com.eignex.klause.theory.qfnia.NonlinearIntegerStrategy
import com.eignex.klause.theory.qfnia.integerSeeds
import com.eignex.klause.theory.qfnia.solveNonlinearIntegers
import com.eignex.klause.theory.qfnra.ExactNraAssignment
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.theory.qfnra.QfNraAssignment
import com.eignex.klause.theory.qfnra.QfNraParams
import com.eignex.klause.theory.qfnra.QfNraResult
import com.eignex.klause.theory.qfnra.QfNraStats
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.toLongExact
import com.eignex.kumulant.stat.summary.SumResult
import kotlin.time.TimeSource

/** A source-preserving nonlinear integer satisfaction request, including mixed real constraints. */
class NonlinearIntegerTheoryRequest internal constructor(
    /** Original declarations and exact polynomial source formula. */
    val problem: NonlinearIntegerProblem,
)

/** Select sound incomplete integer search with exact continuous completion. */
fun NonlinearIntegerProblem.pipelineRequest(): NonlinearIntegerTheoryRequest = NonlinearIntegerTheoryRequest(this)

/** Invocation controls for integer candidate search and exact source neighborhoods. */
data class NonlinearIntegerTheoryParams(
    /** Polynomial resource controls and call-scoped source assumptions. */
    val polynomial: PolynomialTheoryParams = PolynomialTheoryParams(),
    /** Whole-source integer candidate attempt cap; zero prevents search entry. */
    val maximumCandidates: Long = 1024,
    /** Heuristic neighborhood move cap; zero prevents heuristic entry. */
    val maximumMoves: Long = 256,
    /** Containing decision/move allowance, shared across every strategy. */
    val nodeBudget: NodeBudget? = null,
) {
    init { require(maximumCandidates >= 0 && maximumMoves >= 0) }
}

/**
 * Execute the original nonlinear source across integer search and exact heuristic neighborhoods.
 * Heuristic failures and incomplete integer candidate exhaustion never refute the whole source.
 */
fun OpenTheoryPipeline.execute(
    request: NonlinearIntegerTheoryRequest,
    params: NonlinearIntegerTheoryParams = NonlinearIntegerTheoryParams(),
    engine: FiniteEngine = FiniteEngine.MIXED,
): PolynomialTheoryExecution = NonlinearIntegerInvocation(request.problem, params, engine).execute()

private class NonlinearIntegerInvocation(
    private val source: NonlinearIntegerProblem,
    private val params: NonlinearIntegerTheoryParams,
    private val engine: FiniteEngine,
) : ExactNeighborhoodCompletion {
    private val polynomial = params.polynomial
    private val nodeBudget = params.nodeBudget ?: polynomial.strategy.backtrack.nodeBudget
        ?: polynomial.strategy.localSearch.nodeBudget
    private val started = TimeSource.Monotonic.markNow()
    private var native = QfNraStats(0, 0, 0)
    private var resource: PolynomialTheoryResource? = null
    private var searchStats = SolveStats.EMPTY
    private val external = polynomial.timeout or polynomial.cancellation
    private val stop = external or Cancellation {
        polynomial.maximumWork?.let { native.workSpent >= it } == true || resource != null ||
            nodeBudget?.exhausted() == true
    }
    private val pins: PolynomialCondition = assumptions()

    private fun assumptions(): PolynomialCondition {
        val assumptions = polynomial.strategy.assumptions
        require(assumptions.deductions.isEmpty) { "nonlinear source assumptions require explicit pins" }
        val fixed = assumptions.ints.map { (id, value) ->
            require(id in source.integerCoordinates.indices) { "nonlinear integer assumption id out of range" }
            source.integerCoordinates[id] to BigFraction.ofLong(value)
        }.toMap()
        return PolynomialCondition(assumptions.bools, fixed, fixed)
    }

    fun execute(): PolynomialTheoryExecution {
        val candidates = minOf(
            params.maximumCandidates, polynomial.strategy.backtrack.maxDecisions,
            polynomial.strategy.backtrack.maxInstructions ?: Long.MAX_VALUE,
        )
        val moves = minOf(
            params.maximumMoves, polynomial.strategy.localSearch.maxFlips,
            polynomial.strategy.localSearch.maxInstructions ?: Long.MAX_VALUE,
        )
        val reason = { incompleteReason() }
        var result: OpenTheoryResult? = null
        var heuristicStats = SolveStats.EMPTY
        fun search(cap: Long, nativeCap: Long? = null) {
            if (result != null || stop()) return
            val allowed = minOf(cap, nodeBudget?.decisionsLeft() ?: Long.MAX_VALUE)
            when (val checked = query(pins, allowed, nativeCap = nativeCap)) {
                is QfNraResult.Satisfiable -> result = OpenTheoryResult.Sat(capture(checked.assignment), searchStats)
                is QfNraResult.Unsatisfiable -> result = OpenTheoryResult.Unsat(searchStats)
                is QfNraResult.Unknown -> Unit
            }
        }
        fun walk(adaptive: Boolean, cap: Long) {
            if (result != null || stop()) return
            val centers = source.integerSeeds(stop) {
                native = native.copy(workSpent = sum(native.workSpent, 1))
                external.charge(1)
            }.mapIndexed { id, value ->
                pins.lower[source.integerCoordinates[id]] ?: value
            }
            val neighborhood = ExactNeighborhood(
                source.boolCount, centers, this,
                coordinateValues = { sample ->
                    List(sample.numIntVars) { BigFraction.of(sample.exactIntValue(it), BIG_ONE) }
                },
                adaptive = adaptive, seed = polynomial.strategy.localSearch.randomSeed ?: 1L,
                nodeBudget = nodeBudget, maximumMoves = cap, nativeWork = { native.workSpent },
            )
            neighborhood.advance(stop, Long.MAX_VALUE)
            val solved = neighborhood.solveResult(reason())
            heuristicStats = heuristicStats.mergedWith(solved.stats)
            if (solved is SolveResult.Sat) result = OpenTheoryResult.Sat(capture(solved.assignment), solved.stats)
        }
        when (engine) {
            FiniteEngine.BACKTRACK, FiniteEngine.FIXED -> search(candidates)
            FiniteEngine.LOCAL_SEARCH -> walk(false, moves)
            FiniteEngine.ALNS -> walk(true, minOf(moves, polynomial.strategy.alnsIterations.toLong()))
            FiniteEngine.MIXED -> {
                search(minOf(candidates, 16), nativeCap = 4096)
                walk(false, minOf(moves, 32))
                walk(true, minOf(moves, polynomial.strategy.alnsIterations.toLong()))
                if (candidates > 16) search(candidates - 16)
            }
        }
        val stats = searchStats.mergedWith(heuristicStats).copy(run = RunStats(
            "nonlinear-integer-${engine.id}", started.elapsedNow().inWholeMilliseconds, external(),
        ))
        val published = if (stop()) OpenTheoryResult.Unknown(incompleteReason(), stats)
        else when (val solved = result) {
            is OpenTheoryResult.Sat -> solved.copy(stats = stats)
            is OpenTheoryResult.Unsat -> solved.copy(stats = stats)
            else -> OpenTheoryResult.Unknown(incompleteReason(), stats)
        }
        return PolynomialTheoryExecution(
            published,
            PolynomialTheoryStats(native.projectedPolynomials, native.liftedCells, native.workSpent), resource,
        )
    }

    override fun check(condition: ExactNeighborhoodCondition, cancellation: Cancellation): ExactNeighborhoodResult {
        require(condition.cutoff == null) { "nonlinear integer satisfaction has no objective cutoff" }
        fun remap(values: Map<Int, BigFraction>): Map<Int, BigFraction> = values.mapKeys { (id, _) ->
            require(id in source.integerCoordinates.indices) { "nonlinear neighborhood coordinate out of range" }
            source.integerCoordinates[id]
        }
        fun intersect(
            bounds: Map<Int, BigFraction>, fixed: Map<Int, BigFraction>, lower: Boolean,
        ): Map<Int, BigFraction> {
            val result = bounds.toMutableMap()
            for ((id, value) in fixed) {
                val old = result[id]
                result[id] = if (old == null || if (lower) value > old else value < old) value else old
            }
            return result
        }
        if (condition.bools.any { (id, value) -> pins.bools[id]?.let { it != value } == true }) {
            return ExactNeighborhoodResult.Refuted
        }
        val constrained = PolynomialCondition(
            condition.bools + pins.bools,
            intersect(remap(condition.lower), pins.lower, true), intersect(remap(condition.upper), pins.upper, false),
        )
        val checked = query(constrained, minOf(params.maximumCandidates, 16), cancellation, heuristic = true)
        return when (val result = checked) {
            is QfNraResult.Satisfiable -> ExactNeighborhoodResult.Witness(sample(result.assignment))
            is QfNraResult.Unsatisfiable -> ExactNeighborhoodResult.Refuted
            is QfNraResult.Unknown -> ExactNeighborhoodResult.Undecided
        }
    }

    private fun query(
        condition: PolynomialCondition,
        cap: Long,
        cancellation: Cancellation = Cancellation.Never,
        heuristic: Boolean = false,
        nativeCap: Long? = null,
    ): QfNraResult {
        val totalRemaining = polynomial.maximumWork?.let { (it - native.workSpent).coerceAtLeast(0) }
        val remaining = when {
            nativeCap == null -> totalRemaining
            totalRemaining == null -> nativeCap
            else -> minOf(nativeCap, totalRemaining)
        }
        val execution = solveNonlinearIntegers(source, NonlinearIntegerParams(
            arithmetic = QfNraParams(stop or cancellation, remaining, PolynomialLimits(
                polynomial.maximumDenseCoefficients, polynomial.maximumMatrixCells,
            )), maximumCandidates = cap, strategy = if (heuristic && source.integerCoordinates.count {
                condition.lower[it] == null || condition.lower[it] != condition.upper[it]
            } > 1) NonlinearIntegerStrategy.LOCAL_SEARCH else NonlinearIntegerStrategy.SEARCH,
            seed = polynomial.strategy.backtrack.randomSeed ?: 1L,
        ), condition)
        val observed = execution.stats.polynomial
        native = QfNraStats(
            sum(native.projectedPolynomials, observed.projectedPolynomials),
            sum(native.liftedCells, observed.liftedCells), sum(native.workSpent, observed.workSpent),
        )
        val declined = execution.result as? QfNraResult.Unknown
        resource = declined?.resource?.let {
            when (it) {
                com.eignex.klause.arithmetic.polynomial.PolynomialResource.DENSE_COEFFICIENTS ->
                    PolynomialTheoryResource.DenseCoefficients
                com.eignex.klause.arithmetic.polynomial.PolynomialResource.MATRIX_CELLS ->
                    PolynomialTheoryResource.MatrixCells
                com.eignex.klause.arithmetic.polynomial.PolynomialResource.DEGREE_OVERFLOW ->
                    PolynomialTheoryResource.DegreeOverflow
            }
        } ?: resource
        if (!heuristic) {
            searchStats = searchStats.copy(search = SearchStats(nodes = SumResult(
                searchStats.search.nodes.sum + execution.stats.candidates,
            )))
            if (nodeBudget != null) {
                var decisions = execution.stats.candidates
                while (decisions > 0) {
                    nodeBudget.spend()
                    decisions--
                }
            }
        }
        return execution.result
    }

    private fun sample(assignment: QfNraAssignment): Sample {
        val integers = source.integerCoordinates.map { requireNotNull(assignment.realValue(it).rational).num }
        val reals = source.realCoordinates.map(assignment::realValue)
        return Sample(
            assignment.bools,
            if (integers.all { it.fitsLong() }) {
                LongArray(integers.size) { integers[it].toLongExact() }
            } else longArrayOf(),
            DoubleArray(reals.size) { id ->
                val value = reals[id]
                value.rational?.toDouble() ?: (value.lowerBound.toDouble() / 2 + value.upperBound.toDouble() / 2)
            },
            exactInts = integers, algebraicReals = reals,
        )
    }

    private fun capture(assignment: QfNraAssignment): OpenTheoryAssignment = capture(sample(assignment))
    private fun capture(sample: Sample): OpenTheoryAssignment = OpenTheoryAssignment.Rebuilt(
        OpenTheoryAssignment.ExactNra(ExactNraAssignment(sample.bools, requireNotNull(sample.algebraicReals))),
        sample.bools, Array(sample.numIntVars) { sample.exactIntValue(it) },
    )
    private fun incompleteReason(): TerminationReason = when {
        polynomial.timeout() -> TerminationReason.Timeout
        polynomial.cancellation() -> TerminationReason.Cancelled
        else -> TerminationReason.BudgetExhausted
    }
    private fun sum(left: Long, right: Long): Long = left + minOf(right, Long.MAX_VALUE - left)
}
