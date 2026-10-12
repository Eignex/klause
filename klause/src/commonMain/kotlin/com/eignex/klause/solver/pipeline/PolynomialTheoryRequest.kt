package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.PolynomialResource
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.ir.PolynomialRealProblem
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.theory.qfnra.ExactNraAssignment
import com.eignex.klause.theory.qfnra.QfNraParams
import com.eignex.klause.theory.qfnra.QfNraReason
import com.eignex.klause.util.Cancellation

/** A polynomial request composing exact continuous constraints with finite factors. */
class PolynomialTheoryRequest internal constructor(
    /** Original source identities and polynomial formula. */
    val problem: PolynomialRealProblem,
    internal val sharedModel: SharedPolynomialModel? = null,
)

/** Select the complete polynomial-real theory without materializing finite domains. */
fun PolynomialRealProblem.pipelineRequest(): PolynomialTheoryRequest = PolynomialTheoryRequest(this)

/**
 * Select exact polynomial completion for this shared model.
 * Integer columns require finite source domains; every continuous factor must have an exact encoding.
 */
fun Problem.pipelineRequest(): PolynomialTheoryRequest {
    val shared = requireNotNull(SharedPolynomialModel.assemble(this)) {
        "the shared polynomial pipeline requires finite integers and exactly encodable continuous factors"
    }
    return shared.request()
}

internal fun SharedPolynomialModel.request(): PolynomialTheoryRequest = PolynomialTheoryRequest(
    PolynomialRealProblem(
        service.formula, service.realCount, source.numBoolVars, source.numRealVars, source.numBoolVars,
        emptyMap(), emptyMap(),
    ),
    this,
)

/** Controls for one complete polynomial-real invocation, in native arithmetic units. */
data class PolynomialTheoryParams(
    /** Native polynomial work cap; null disables the cap and zero is exhausted before entry. */
    val maximumWork: Long? = null,
    /** Maximum dense coefficient count; null disables the cap subject to checked dimensions. */
    val maximumDenseCoefficients: Int? = 65536,
    /** Maximum matrix cell count; null disables the cap subject to checked dimensions. */
    val maximumMatrixCells: Long? = 262144,
    /** Wall-time token retained separately for the common termination reason. */
    val timeout: Cancellation = Cancellation.Never,
    /** External cooperative token, forwarding native charges once to its meter. */
    val cancellation: Cancellation = Cancellation.Never,
    /** Finite search, heuristic and neighbourhood controls for theory composition. */
    val strategy: PolynomialStrategyParams = PolynomialStrategyParams(),
) {
    init {
        require(maximumWork == null || maximumWork >= 0)
        require(maximumDenseCoefficients == null || maximumDenseCoefficients > 0)
        require(maximumMatrixCells == null || maximumMatrixCells >= 0)
    }
}

/** Native arithmetic counters; these units are separate from open-theory work. */
data class PolynomialTheoryStats(
    /** Number of captured projection polynomials. */
    val projectedPolynomials: Long = 0,
    /** Number of sections and sectors visited during lifting. */
    val liftedCells: Long = 0,
    /** Native coefficient, formula and traversal work charged by the theory. */
    val workSpent: Long = 0,
)

/** Checked representation resource that prevented completing a polynomial-real invocation. */
enum class PolynomialTheoryResource {
    /** Dense coefficient buffers exceeded the configured count. */
    DenseCoefficients,

    /** A resultant/subresultant matrix exceeded the configured cell count. */
    MatrixCells,

    /** A degree or dimension exceeded the native integer representation. */
    DegreeOverflow,
}

/** Common open-theory verdict with separately scoped native polynomial telemetry. */
data class PolynomialTheoryExecution(
    /** The same SAT, UNSAT or UNKNOWN surface used by the other complete open theories. */
    val result: OpenTheoryResult,
    /** Native polynomial counters, excluding source parsing and frontend publication. */
    val stats: PolynomialTheoryStats,
    /** Representation resource on an incomplete resource-limited result, otherwise null. */
    val resource: PolynomialTheoryResource? = null,
)

/** Finite-objective optimization with exact polynomial witnesses and separately scoped theory work. */
data class PolynomialOptimizationExecution(
    /** The strategy's incumbent or proof verdict. */
    val result: MinimizeResult,
    /** Native polynomial counters over every conditioned check. */
    val stats: PolynomialTheoryStats,
)

/**
 * Optimize a finite objective while completing continuous coordinates exactly.
 * Continuous objective coefficients are unsupported; heuristic strategies publish incumbents only.
 */
fun OpenTheoryPipeline.minimize(
    request: PolynomialTheoryRequest,
    objective: LinearObjective,
    params: PolynomialTheoryParams = PolynomialTheoryParams(),
    engine: FiniteEngine = FiniteEngine.MIXED,
): PolynomialOptimizationExecution {
    val stop = params.timeout or params.cancellation
    val shared = request.resolvedModel()
    val invocation = shared.service.invocation(QfNraParams(
        stop, params.maximumWork,
        PolynomialLimits(params.maximumDenseCoefficients, params.maximumMatrixCells),
    ))
    val completed = invocation.use {
        PolynomialStrategyAdapters(shared, it, params.strategy, stop).minimize(objective, engine)
    }
    val result = when (completed) {
        is MinimizeResult.Optimal -> completed.copy(sample = request.capture(completed.sample))
        is MinimizeResult.BestFound -> completed.copy(sample = request.capture(completed.sample))
        is MinimizeResult.Unbounded -> completed.copy(sample = request.capture(completed.sample))
        else -> completed
    }
    val nativeStopped = invocation.workExhausted || invocation.lastUnknown?.reason == QfNraReason.ResourceLimit
    val reason = when {
        params.timeout() -> TerminationReason.Timeout
        stop() -> TerminationReason.Cancelled
        else -> TerminationReason.BudgetExhausted
    }
    val published = if (!stop() && !nativeStopped) result else when (result) {
        is MinimizeResult.WithSample -> MinimizeResult.BestFound(result.sample, result.objective, reason, result.stats)
        else -> MinimizeResult.Unknown(reason, result.stats)
    }
    val native = invocation.stats
    return PolynomialOptimizationExecution(published, PolynomialTheoryStats(
        native.projectedPolynomials, native.liftedCells, native.workSpent,
    ))
}

private fun PolynomialTheoryRequest.capture(sample: Sample): Sample = Sample(
    BooleanArray(problem.sourceBoolCount) { sample.boolValue(it) }, sample.ints,
    DoubleArray(problem.sourceRealCount) { sample.approximateRealValue(it) },
    algebraicReals = requireNotNull(sample.algebraicReals).take(problem.sourceRealCount),
)

private fun PolynomialTheoryRequest.resolvedModel(): SharedPolynomialModel = sharedModel
    ?: requireNotNull(SharedPolynomialModel.assemble(
        Problem(
            numBoolVars = problem.totalBoolCount, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray(),
            numRealVars = problem.totalRealCount,
            realLower = DoubleArray(problem.totalRealCount) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(problem.totalRealCount) { Double.POSITIVE_INFINITY },
        ),
        problem.formula,
    ))

/**
 * Execute a pipeline-selected polynomial source as one complete exact invocation.
 * Only original declared coordinates leave the pipeline; lowering auxiliaries remain private.
 */
fun OpenTheoryPipeline.execute(
    request: PolynomialTheoryRequest,
    params: PolynomialTheoryParams = PolynomialTheoryParams(),
    engine: FiniteEngine = FiniteEngine.MIXED,
): PolynomialTheoryExecution {
    val stop = params.timeout or params.cancellation
    fun incomplete(
        reason: TerminationReason,
        stats: PolynomialTheoryStats,
        searchStats: SolveStats,
    ): PolynomialTheoryExecution =
        PolynomialTheoryExecution(
            OpenTheoryResult.Unknown(reason, searchStats.copy(
                run = searchStats.run.copy(timedOut = reason == TerminationReason.Timeout),
            )),
            stats,
        )
    val source = request.problem
    val shared = request.resolvedModel()
    val invocation = shared.service.invocation(QfNraParams(
        stop, params.maximumWork,
        PolynomialLimits(params.maximumDenseCoefficients, params.maximumMatrixCells),
    ))
    val result = invocation.use { PolynomialStrategyAdapters(shared, it, params.strategy, stop).solve(engine) }
    val native = invocation.stats
    val stats = PolynomialTheoryStats(
        native.projectedPolynomials,
        native.liftedCells,
        native.workSpent,
    )
    if (stop()) {
        return incomplete(
            if (params.timeout()) TerminationReason.Timeout else TerminationReason.Cancelled, stats, result.stats,
        )
    }
    return when (result) {
        is SolveResult.Sat -> {
            val sample = result.assignment
            val assignment = ExactNraAssignment(
                BooleanArray(source.sourceBoolCount) { sample.boolValue(it) },
                List(source.sourceRealCount) { requireNotNull(sample.algebraicReals)[it] },
            )
            val exact = OpenTheoryAssignment.ExactNra(assignment)
            val published = if (shared.source.numIntVars == 0) exact else OpenTheoryAssignment.Rebuilt(
                exact, assignment.bools,
                Array(shared.source.numIntVars) { bigIntOf(sample.intValue(it)) },
            )
            if (stop()) {
                incomplete(
                    if (params.timeout()) TerminationReason.Timeout else TerminationReason.Cancelled,
                    stats, result.stats,
                )
            } else {
                PolynomialTheoryExecution(OpenTheoryResult.Sat(published, result.stats), stats)
            }
        }
        is SolveResult.Unsat -> PolynomialTheoryExecution(OpenTheoryResult.Unsat(result.stats), stats)
        is SolveResult.Unknown -> incomplete(
            if (invocation.workExhausted || invocation.lastUnknown?.reason == QfNraReason.ResourceLimit) {
                TerminationReason.BudgetExhausted
            } else result.reason,
            stats,
            result.stats,
        ).copy(
            resource = when (invocation.lastUnknown?.resource) {
                PolynomialResource.DENSE_COEFFICIENTS -> PolynomialTheoryResource.DenseCoefficients
                PolynomialResource.MATRIX_CELLS -> PolynomialTheoryResource.MatrixCells
                PolynomialResource.DEGREE_OVERFLOW -> PolynomialTheoryResource.DegreeOverflow
                null -> null
            },
        )
    }
}
