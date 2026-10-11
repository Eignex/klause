package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.PolynomialResource
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.ir.PolynomialRealProblem
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.solver.result.RunStats
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.theory.qfnra.ExactNraAssignment
import com.eignex.klause.theory.qfnra.QfNraParams
import com.eignex.klause.util.Cancellation
import kotlin.time.TimeSource

/** A complete polynomial-real request selected from lossless neutral source metadata. */
class PolynomialTheoryRequest internal constructor(
    /** Original source identities and polynomial formula. */
    val problem: PolynomialRealProblem,
    internal val sharedModel: SharedPolynomialModel? = null,
)

/** Select the complete polynomial-real theory without materializing finite domains. */
fun PolynomialRealProblem.pipelineRequest(): PolynomialTheoryRequest = PolynomialTheoryRequest(this)

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

/** Native arithmetic counters; these units are separate from legacy open-theory work. */
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

/**
 * Execute a pipeline-selected polynomial source as one complete exact invocation.
 * Only original declared coordinates leave the pipeline; lowering auxiliaries remain private.
 */
fun OpenTheoryPipeline.execute(
    request: PolynomialTheoryRequest,
    params: PolynomialTheoryParams = PolynomialTheoryParams(),
    engine: FiniteEngine = FiniteEngine.MIXED,
): PolynomialTheoryExecution {
    val started = TimeSource.Monotonic.markNow()
    val stop = params.timeout or params.cancellation
    fun envelope(timedOut: Boolean = false): SolveStats = SolveStats(
        run = RunStats("exact-nra", started.elapsedNow().inWholeMilliseconds, timedOut),
    )
    fun incomplete(reason: TerminationReason, stats: PolynomialTheoryStats): PolynomialTheoryExecution =
        PolynomialTheoryExecution(
            OpenTheoryResult.Unknown(reason, envelope(reason == TerminationReason.Timeout)),
            stats,
        )
    val source = request.problem
    val shared = request.sharedModel ?: requireNotNull(SharedPolynomialModel.assemble(
        Problem(
            numBoolVars = source.totalBoolCount, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray(),
            numRealVars = source.totalRealCount,
            realLower = DoubleArray(source.totalRealCount) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(source.totalRealCount) { Double.POSITIVE_INFINITY },
        ),
        source.formula,
    ))
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
        return incomplete(if (params.timeout()) TerminationReason.Timeout else TerminationReason.Cancelled, stats)
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
                incomplete(if (params.timeout()) TerminationReason.Timeout else TerminationReason.Cancelled, stats)
            } else {
                PolynomialTheoryExecution(OpenTheoryResult.Sat(published, result.stats), stats)
            }
        }
        is SolveResult.Unsat -> PolynomialTheoryExecution(OpenTheoryResult.Unsat(result.stats), stats)
        is SolveResult.Unknown -> incomplete(result.reason, stats).copy(resource = when (invocation.lastUnknown?.resource) {
            PolynomialResource.DENSE_COEFFICIENTS -> PolynomialTheoryResource.DenseCoefficients
            PolynomialResource.MATRIX_CELLS -> PolynomialTheoryResource.MatrixCells
            PolynomialResource.DEGREE_OVERFLOW -> PolynomialTheoryResource.DegreeOverflow
            null -> null
        })
    }
}
