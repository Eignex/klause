package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.LinearSourceObjective
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.SourceSemanticsOwner
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LinearOptimizationResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation

/** Installed execution family, after source routing and operation admission. */
enum class SourceExecutionKind {
    /** Finite execution with its retained source shape. */
    FINITE,

    /** Complete linear arithmetic with the requested complete or heuristic strategy. */
    LINEAR,

    /** Finite composition with exact polynomial completion. */
    POLYNOMIAL,

    /** Source-checked witness search without an available complete arithmetic route. */
    HEURISTIC,

    /** Source deductions already established infeasibility. */
    REFUTED,

    /** Exact integer polynomial search with original open coordinates. */
    NONLINEAR_INTEGER,

    /** Exact real functions with their native source and resource diagnostics. */
    FUNCTIONS,

    /** Numerical linear traversal retaining its native bound and certificate payload. */
    NUMERICAL,
}

/** Original objective captured by this source operation. */
val PreparedSourceInvocation.sourceObjective: LinearObjective? get() = request.objective

/** Original exact quadratic objective captured by this source operation. */
val PreparedSourceInvocation.sourceQuadraticObjective: QuadraticObjectiveSpec? get() = request.quadraticObjective

/** Original rational affine objective, before a working scale or private defining column. */
val PreparedSourceInvocation.sourceLinearObjective: LinearSourceObjective? get() =
    (source.originalSource as? com.eignex.klause.ir.LinearSourceProblem)?.objective

internal val PreparedSourceInvocation.workingLinearObjective: LinearObjective?
    get() = linearSource?.objective ?: request.objective

/** Original direction captured independently of the engine's minimized objective. */
val PreparedSourceInvocation.maximizeSourceObjective: Boolean get() = request.maximize

/** Typed execution controls for one already captured source invocation. */
class SourceExecutionRequest private constructor(
    /** Source and operation selected once by setup. */
    val invocation: PreparedSourceInvocation,
    /** Existing finite controls, constructed only when finite execution is selected. */
    val finiteRequest: ((FiniteSolveShape) -> FiniteSolveRequest)?,
    /** Native linear and heuristic controls, retaining their existing work currencies. */
    val theory: TheoryParams,
    /** Native polynomial and finite-composition controls. */
    val polynomial: PolynomialTheoryParams,
    /** Requested arithmetic portfolio lanes. */
    val cores: Int,
    /** Optional override of linear source preparation. */
    val presolveConfig: PresolveConfig?,
    /** Containing token for the optional source preparation phase. */
    val presolveCancellation: Cancellation,
    /** Shared source preparation ledger, without a renewed allowance. */
    val presolveBudget: PresolveBudget?,
    /** Optional native integer controls, retaining their shared decision allowance. */
    val nonlinearInteger: NonlinearIntegerTheoryParams?,
    /** Optional full native function controls, including representation and depth limits. */
    val transcendental: TranscendentalTheoryParams?,
    /** Containing decision and heuristic allowance when resolved through common execution options. */
    val nodeBudget: NodeBudget?,
) {
    /**
     * Resolve common options once for the captured route. Omitted native limits use their parameter
     * types' defaults. The finite factory supplies optional application hooks and LP policy.
     */
    constructor(
        invocation: PreparedSourceInvocation,
        executionOptions: ExecutionOptions,
        finiteRequest: ((FiniteSolveShape) -> FiniteSolveRequest)? = null,
        cores: Int = 1,
        randomSeed: Long? = null,
        nodeBudget: NodeBudget? = null,
        timeout: Cancellation = Cancellation.Never,
        cancellation: Cancellation = Cancellation.Never,
        presolveConfig: PresolveConfig? = null,
        presolveCancellation: Cancellation = Cancellation.Never,
        presolveBudget: PresolveBudget? = null,
    ) : this(
        invocation,
        bindSourceExecutionControls(
            invocation, executionOptions, finiteRequest, randomSeed, nodeBudget, cores, timeout, cancellation,
            presolveConfig, presolveCancellation, presolveBudget,
        ),
        cores, presolveConfig, presolveCancellation, presolveBudget,
    )

    private constructor(
        invocation: PreparedSourceInvocation,
        controls: SourceExecutionControls,
        cores: Int,
        presolveConfig: PresolveConfig?,
        presolveCancellation: Cancellation,
        presolveBudget: PresolveBudget?,
    ) : this(
        invocation, controls.finiteRequest, controls.theory, controls.polynomial, cores,
        presolveConfig, presolveCancellation, presolveBudget, controls.nonlinearInteger, controls.transcendental,
        controls.nodeBudget,
    )

    /** Bind existing finite, linear or polynomial controls to their captured source. */
    constructor(
        invocation: PreparedSourceInvocation,
        finiteRequest: ((FiniteSolveShape) -> FiniteSolveRequest)? = null,
        theory: TheoryParams = TheoryParams(),
        polynomial: PolynomialTheoryParams = PolynomialTheoryParams(),
        cores: Int = 1,
        presolveConfig: PresolveConfig? = null,
        presolveCancellation: Cancellation = Cancellation.Never,
        presolveBudget: PresolveBudget? = null,
    ) : this(
        invocation, finiteRequest, theory, polynomial, cores, presolveConfig,
        presolveCancellation, presolveBudget, null, null, theory.nodeBudget,
    )

    /** Bind full native integer controls without replacing their polynomial or node ledger. */
    constructor(
        invocation: PreparedSourceInvocation,
        nonlinearInteger: NonlinearIntegerTheoryParams,
        cores: Int = 1,
    ) : this(
        invocation, null, TheoryParams(), nonlinearInteger.polynomial, cores, null,
        Cancellation.Never, null, nonlinearInteger, null, nonlinearInteger.nodeBudget,
    )

    /** Bind full exact function controls without renewing the containing invocation. */
    constructor(
        invocation: PreparedSourceInvocation,
        transcendental: TranscendentalTheoryParams,
        cores: Int = 1,
    ) : this(
        invocation, null, TheoryParams(), PolynomialTheoryParams(), cores, null,
        Cancellation.Never, null, null, transcendental, transcendental.nodeBudget,
    )

    init {
        require(cores > 0)
        if (invocation.request.engine == FiniteEngine.FIXED && cores > 1) {
            throw PipelineConfigException("engine 'fixed' is single-core; use 'backtrack' for a parallel pool")
        }
    }
}

/** Streaming publications retain their native source-coordinate and exact-value contracts. */
class SourceExecutionCallbacks(
    /** Finite source samples and portfolio improvements. */
    val finite: FiniteSolveCallbacks = FiniteSolveCallbacks({}, {}),
    /** Checked exact linear or heuristic source improvements. */
    val onLinearIncumbent: ((SourceIncumbentPublication) -> Unit)? = null,
    /** Checked exact polynomial source improvements, including algebraic values. */
    val onQuadraticIncumbent: ((QuadraticIncumbentPublication) -> Unit)? = null,
) {
    private var sourceIncumbent: ((com.eignex.klause.solver.validation.LinearSourceWitness) -> Unit)? = null

    /** Checked original-unit linear point, emitted before its ordinary engine callback. */
    val onLinearSourceIncumbent: ((com.eignex.klause.solver.validation.LinearSourceWitness) -> Unit)?
        get() = sourceIncumbent

    /** Observe original declaration coordinates and source-unit values after source checking. */
    constructor(
        onLinearSourceIncumbent: (com.eignex.klause.solver.validation.LinearSourceWitness) -> Unit,
        finite: FiniteSolveCallbacks = FiniteSolveCallbacks({}, {}),
        onLinearIncumbent: ((SourceIncumbentPublication) -> Unit)? = null,
        onQuadraticIncumbent: ((QuadraticIncumbentPublication) -> Unit)? = null,
    ) : this(finite, onLinearIncumbent, onQuadraticIncumbent) {
        sourceIncumbent = onLinearSourceIncumbent
    }
}

/** Native result of a source operation; adapters retain every producing mechanism's payload. */
sealed class SourceExecutionResult protected constructor(
    /** Original source binding, whose source identity and interpretation remain immutable. */
    val invocation: PreparedSourceInvocation,
    /** Original-unit point acceptance and claim qualification, absent for another source representation. */
    val linearSource: LinearSourceOutcome?,
) : SourceSemanticsOwner {
    /** Bind an invocation without an additional original-unit linear qualification. */
    protected constructor(invocation: PreparedSourceInvocation) : this(invocation, null)
    override val semantics: EffectiveSourceSemantics = invocation.source.semantics

    /** Finite preparation, native outcome and scoped engine evidence. */
    class Finite internal constructor(
        invocation: PreparedSourceInvocation,
        /** Unmodified native result. */
        val result: FiniteSolveResult,
        linearSource: LinearSourceOutcome? = null,
    ) : SourceExecutionResult(invocation, linearSource)

    /** Exact linear or source-checked heuristic result, including values, limits and rays. */
    class Linear internal constructor(
        invocation: PreparedSourceInvocation,
        /** Unmodified native result. */
        val result: OpenTheoryExecution,
        linearSource: LinearSourceOutcome? = null,
    ) : SourceExecutionResult(invocation, linearSource)

    /** Numerical traversal's full native source certificate, values, bounds and interruption cause. */
    class Numerical internal constructor(
        invocation: PreparedSourceInvocation,
        /** Unmodified native numerical result. */
        val result: LinearOptimizationResult,
        linearSource: LinearSourceOutcome? = null,
    ) : SourceExecutionResult(invocation, linearSource)

    /** Polynomial source satisfaction and native resource telemetry. */
    class Polynomial internal constructor(
        invocation: PreparedSourceInvocation,
        /** Unmodified native result. */
        val result: PolynomialTheoryExecution,
        linearSource: LinearSourceOutcome? = null,
    ) : SourceExecutionResult(invocation, linearSource)

    /** Exact affine optimization with polynomial completion and its native certificates. */
    class Quadratic internal constructor(
        invocation: PreparedSourceInvocation,
        /** Unmodified native result. */
        val result: QuadraticOptimizationExecution,
        linearSource: LinearSourceOutcome? = null,
    ) : SourceExecutionResult(invocation, linearSource)

    /** Exact function result with its complete native resource observations. */
    class Transcendental internal constructor(
        invocation: PreparedSourceInvocation,
        /** Unmodified native result. */
        val result: TranscendentalTheoryExecution,
        linearSource: LinearSourceOutcome? = null,
    ) : SourceExecutionResult(invocation, linearSource)

    /** Required preparation declined before a native worker could execute. */
    class Declined internal constructor(
        invocation: PreparedSourceInvocation,
        /** Containing interruption or unsupported source preparation. */
        val reason: TerminationReason,
        linearSource: LinearSourceOutcome? = null,
    ) : SourceExecutionResult(invocation, linearSource)

    /** Source preparation refutation, without fabricating a producing engine's certificate. */
    class Refuted internal constructor(
        invocation: PreparedSourceInvocation,
        linearSource: LinearSourceOutcome? = null,
    ) : SourceExecutionResult(invocation, linearSource)
}

/** Inspect the selected execution family without allocating a worker or changing the source plan. */
fun SourceSetup.executionKind(invocation: PreparedSourceInvocation): SourceExecutionKind =
    when (val route = invocation.engineRoute) {
        is SourceProblemRoute.NativeFormula -> when (route.execution) {
            is NativeSourceExecution.Numerical -> SourceExecutionKind.NUMERICAL
            is NativeSourceExecution.IntegerPolynomial -> SourceExecutionKind.NONLINEAR_INTEGER
            is NativeSourceExecution.Functions -> SourceExecutionKind.FUNCTIONS
            is NativeSourceExecution.Polynomial -> SourceExecutionKind.POLYNOMIAL
            null -> when (invocation.source.formula) {
                is AttachedSourceFormula.Linear -> SourceExecutionKind.NUMERICAL
                is AttachedSourceFormula.IntegerPolynomial -> SourceExecutionKind.NONLINEAR_INTEGER
                is AttachedSourceFormula.Functions -> SourceExecutionKind.FUNCTIONS
                is AttachedSourceFormula.Polynomial, null -> SourceExecutionKind.POLYNOMIAL
            }
        }

        is SourceProblemRoute.Finite -> SourceExecutionKind.FINITE

        is SourceProblemRoute.PolynomialTheory -> SourceExecutionKind.POLYNOMIAL

        is SourceProblemRoute.OpenTheory -> if (route.request.objective == null || OpenTheoryPipeline.canMinimize(
                route.request,
            )
        ) {
            SourceExecutionKind.LINEAR
        } else {
            SourceExecutionKind.HEURISTIC
        }

        is SourceProblemRoute.UnsupportedOpen -> SourceExecutionKind.HEURISTIC

        SourceProblemRoute.Refuted -> SourceExecutionKind.REFUTED
    }

/** Execute the captured route and operation while retaining the native result and containing stop. */
fun SourceSetup.execute(
    request: SourceExecutionRequest,
    callbacks: SourceExecutionCallbacks = SourceExecutionCallbacks(),
): SourceExecutionResult {
    if (request.invocation.linearSource != null) return executeLinearSource(request, callbacks, ::executeCapturedSource)
    return executeCapturedSource(request, callbacks)
}

private fun executeCapturedSource(
    request: SourceExecutionRequest,
    callbacks: SourceExecutionCallbacks,
    bindScope: ((Cancellation, Boolean) -> Unit)? = null,
): SourceExecutionResult {
    val invocation = request.invocation
    invocation.freezeExecution()
    val kind = SourceSetup.executionKind(invocation)
    if (invocation.operation !in setOf(
            ExecutionOperation.SATISFY,
            ExecutionOperation.OPTIMIZE,
            ExecutionOperation.ENUMERATE,
        ) ||
        (
            invocation.operation == ExecutionOperation.ENUMERATE &&
                kind !in setOf(SourceExecutionKind.FINITE, SourceExecutionKind.REFUTED)
            )
    ) {
        throw PipelineConfigException("source execution does not support ${invocation.operation} on $kind")
    }
    require(request.nonlinearInteger == null || kind == SourceExecutionKind.NONLINEAR_INTEGER) {
        "native integer controls require their captured integer source"
    }
    require(request.transcendental == null || kind == SourceExecutionKind.FUNCTIONS) {
        "native function controls require their captured function source"
    }
    val engine = invocation.request.engine
    return when (val route = invocation.engineRoute) {
        is SourceProblemRoute.NativeFormula -> executeNativeSource(request, route, callbacks)

        is SourceProblemRoute.Finite -> {
            val shape = invocation.finiteShape()
            val finite = request.finiteRequest?.invoke(shape)
                ?: throw PipelineConfigException("finite source execution requires its typed finite controls")
            require(finite.shape === shape) { "finite execution belongs to a different source invocation" }
            invocation.requireFiniteExecution(finite.shape, finite.engine, finite.operation)
            bindScope?.invoke(
                finite.cancellation or Cancellation { finite.deadlineExceeded() }, finite.toleranceCheck == null,
            )
            SourceExecutionResult.Finite(invocation, FinitePipeline.solve(finite, callbacks.finite))
        }

        is SourceProblemRoute.OpenTheory -> {
            val params = request.theory.boundTo(invocation)
            val result = if (kind == SourceExecutionKind.HEURISTIC) {
                executeSourceHeuristic(invocation, route.preparedPlan, params, request.cores, callbacks)
            } else {
                val prepared = request.presolveConfig?.let { config ->
                    route.request.withPresolve(
                        config,
                        cancellation = request.presolveCancellation or invocation.cancellation,
                        budget = request.presolveBudget,
                    )
                } ?: route.request
                OpenTheoryPipeline.execute(prepared, params, engine, request.cores, callbacks.onLinearIncumbent)
            }
            SourceExecutionResult.Linear(invocation, result)
        }

        is SourceProblemRoute.PolynomialTheory -> {
            val prepared = if (invocation.linearSource == null) route.request else PolynomialTheoryRequest(
                route.request.problem, route.preparedPlan.linearWorkingPlan(),
            )
            val params = request.polynomial.boundTo(invocation)
            val objective = invocation.workingLinearObjective
            if (objective == null) {
                SourceExecutionResult.Polynomial(
                    invocation,
                    OpenTheoryPipeline.execute(prepared, params, engine, request.cores),
                )
            } else {
                val sense = if (invocation.maximizeSourceObjective) ObjectiveSense.MAXIMIZE else ObjectiveSense.MINIMIZE
                SourceExecutionResult.Quadratic(
                    invocation,
                    OpenTheoryPipeline.optimize(
                        prepared,
                        objective,
                        params,
                        engine,
                        sense,
                        request.cores,
                        callbacks.onQuadraticIncumbent,
                    ),
                )
            }
        }

        is SourceProblemRoute.UnsupportedOpen -> SourceExecutionResult.Linear(
            invocation,
            executeSourceHeuristic(
                invocation,
                route.preparedPlan,
                request.theory.boundTo(invocation),
                request.cores,
                callbacks,
            ),
        )

        SourceProblemRoute.Refuted -> SourceExecutionResult.Refuted(invocation)
    }
}

private fun executeSourceHeuristic(
    invocation: PreparedSourceInvocation,
    plan: PreparedSourcePlan,
    params: TheoryParams,
    cores: Int,
    callbacks: SourceExecutionCallbacks,
): OpenTheoryExecution = OpenTheoryPipeline.searchWithoutTheory(
    plan,
    params,
    invocation.request.finiteObjective(invocation.workingLinearObjective),
    invocation.request.engine,
    invocation.maximizeSourceObjective,
    cores,
    callbacks.onLinearIncumbent,
)

private fun TheoryParams.boundTo(invocation: PreparedSourceInvocation): TheoryParams = copy(
    cancellation = cancellation or invocation.cancellation,
    timeout = timeout or invocation.containingDeadline(),
)

private fun PolynomialTheoryParams.boundTo(invocation: PreparedSourceInvocation): PolynomialTheoryParams = copy(
    cancellation = cancellation or invocation.cancellation,
    timeout = timeout or invocation.containingDeadline(),
)

private fun PreparedSourceInvocation.containingDeadline(): Cancellation =
    cancellation.deadline()?.let { Cancellation.until(it) } ?: Cancellation.Never
