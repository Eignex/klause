package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.encodingPreparationScope
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.util.Cancellation

/** One operation binding over an attached source and its containing preparation allowance. */
class SourceSetupRequest private constructor(
    /** Source identity, interpretation and neutral construction hints. */
    val source: SourceAttachment,
    /** Operation whose execution capabilities are inspected. */
    val operation: ExecutionOperation,
    /** Requested finite strategy, retained when that source route is selected. */
    val engine: FiniteEngine,
    /** Original source objective before consumer-specific direction normalization. */
    val objective: LinearObjective?,
    /** Original maximization direction. */
    val maximize: Boolean,
    /** Source projection providers captured once for this setup. */
    val providers: FactorProjectionProviders,
    /** Containing allowance for required source preparation and native views. */
    val cancellation: Cancellation,
    /** Optional deduction allowance, also bounded by [cancellation]. */
    val boundCancellation: Cancellation,
    /** Original exact quadratic source objective, when the operation supplies one. */
    val quadraticObjective: QuadraticObjectiveSpec?,
    internal val requiresQuadraticCompletion: Boolean = false,
    /** Numerical traversal diagnostic; automatic routing retains finite execution when supported. */
    val linearTraversal: LinearTraversal = LinearTraversal.AUTOMATIC,
) {
    /** Bind an affine operation or the objective already embedded in a native formula. */
    constructor(
        source: SourceAttachment,
        operation: ExecutionOperation,
        engine: FiniteEngine,
        objective: LinearObjective? = null,
        maximize: Boolean = false,
        providers: FactorProjectionProviders = source.providers,
        cancellation: Cancellation = Cancellation.Never,
        boundCancellation: Cancellation = cancellation,
    ) : this(source, operation, engine, objective, maximize, providers, cancellation, boundCancellation, null)

    /** Bind a linear source traversal independently of its declarations and finite worker recipe. */
    constructor(
        source: SourceAttachment,
        operation: ExecutionOperation,
        engine: FiniteEngine,
        objective: LinearObjective? = null,
        maximize: Boolean = false,
        providers: FactorProjectionProviders = source.providers,
        cancellation: Cancellation = Cancellation.Never,
        boundCancellation: Cancellation = cancellation,
        linearTraversal: LinearTraversal,
    ) : this(source, operation, engine, objective, maximize, providers, cancellation, boundCancellation, null,
        linearTraversal = linearTraversal)

    /** Bind an original exact quadratic objective and direction before native preparation. */
    constructor(
        source: SourceAttachment,
        engine: FiniteEngine,
        quadraticObjective: QuadraticObjectiveSpec,
        sense: ObjectiveSense = ObjectiveSense.MINIMIZE,
        providers: FactorProjectionProviders = source.providers,
        cancellation: Cancellation = Cancellation.Never,
        boundCancellation: Cancellation = cancellation,
    ) : this(
        source, ExecutionOperation.OPTIMIZE, engine, null, sense == ObjectiveSense.MAXIMIZE,
        providers, cancellation, boundCancellation, quadraticObjective,
    )

    internal constructor(
        source: SourceAttachment,
        engine: FiniteEngine,
        quadraticObjective: QuadraticObjectiveSpec,
        sense: ObjectiveSense,
        providers: FactorProjectionProviders,
        cancellation: Cancellation,
        boundCancellation: Cancellation,
        requiresQuadraticCompletion: Boolean,
    ) : this(
        source, ExecutionOperation.OPTIMIZE, engine, null, sense == ObjectiveSense.MAXIMIZE,
        providers, cancellation, boundCancellation, quadraticObjective, requiresQuadraticCompletion,
    )

    init {
        require(linearTraversal == LinearTraversal.AUTOMATIC || source.formula is AttachedSourceFormula.Linear) {
            "numerical traversal requires a linear source snapshot"
        }
        val embedded = source.embeddedObjectiveSense
        require(embedded == null || (objective == null && quadraticObjective == null)) {
            "an embedded source objective cannot be replaced by another objective"
        }
        require(embedded == null || maximize == (embedded == ObjectiveSense.MAXIMIZE)) {
            "embedded source objective direction differs from its operation binding"
        }
        require(
            (operation == ExecutionOperation.OPTIMIZE) ==
                (objective != null || quadraticObjective != null || embedded != null),
        ) {
            "optimization and its source objective must be bound together"
        }
        require(
            objective != null || quadraticObjective != null || embedded != null || !maximize,
        ) { "maximization requires a source objective" }
    }

    internal fun finiteObjective(workingObjective: LinearObjective? = objective): LinearObjective? {
        val selected = workingObjective ?: return null
        if (!maximize) return selected
        require(
            selected.constant != Long.MIN_VALUE &&
                selected.intCoefficientValues.none { it == Long.MIN_VALUE } &&
                selected.boolWeightValues.none { it == Long.MIN_VALUE },
        ) {
            "finite maximization objective cannot be normalized within signed-word coefficients"
        }
        return selected.negated()
    }
}

/** Traversal policy over an unchanged linear source, independent of worker recipes and declarations. */
enum class LinearTraversal {
    /**
     * Prefer finite execution with selectively bounded numerical workers;
     * retain numerical execution for open sources.
     */
    AUTOMATIC,

    /** Use numerical LP/MIP traversal without finite bound closure or finite search. */
    NUMERICAL,

    /** Use numerical LP/MIP traversal with a retained bound-ordered frontier. */
    NUMERICAL_FRONTIER,

    /** Pair numerical frontier traversal with admitted finite search and joint repair. */
    NUMERICAL_COMPANIONS,

    /** Use the companion recipe while withholding cross-worker incumbent and bound imports. */
    NUMERICAL_COMPANIONS_INDEPENDENT,

    /** Retain finite recipes and selectively append bounded numerical workers. */
    FINITE_COMPANIONS,
}

/** Captures one source route and retains its operation binding for execution. */
object SourceSetup {
    /** Prepare from neutral source data without allocating heuristic worker state. */
    fun prepare(request: SourceSetupRequest): PreparedSourceInvocation = prepareObserved(request, null)

    /** Prepare the same source binding while observing bound-probing LP work. */
    fun prepare(request: SourceSetupRequest, onLpStats: (LpStats) -> Unit): PreparedSourceInvocation =
        prepareObserved(request, onLpStats)

    internal fun prepare(request: SourceSetupRequest, polynomialCancellation: Cancellation): PreparedSourceInvocation =
        prepareObserved(request, null, polynomialCancellation)

    private fun prepareObserved(
        request: SourceSetupRequest,
        onLpStats: ((LpStats) -> Unit)?,
        polynomialCancellation: Cancellation = request.cancellation,
    ): PreparedSourceInvocation {
        val stop = request.cancellation.encodingPreparationScope()
        request.source.formula?.let { formula ->
            if (formula is AttachedSourceFormula.Linear) {
                return prepareLinearSource(request, formula.problem, stop, onLpStats)
            }
            val route = prepareNativeSource(request, formula, stop)
            return PreparedSourceInvocation(request, route, stop)
        }
        val problem = request.source.problem
        if (problem.factors.any {
            it is com.eignex.klause.factor.arithmetic.ExactRealEquality && it.value.algebraic == null
        }) return PreparedSourceInvocation(request, prepareExactRealSource(request, stop), stop)
        val projections = (problem as? BakedProblem)?.factorProjections
            ?.takeIf { it.providers === request.providers } ?: request.providers.prepare(problem)
        val exactNormalization = request.maximize && request.objective?.let { objective ->
            objective.constant == Long.MIN_VALUE || objective.intCoefficientValues.any { it == Long.MIN_VALUE } ||
                objective.boolWeightValues.any { it == Long.MIN_VALUE }
        } == true
        if (request.quadraticObjective != null || exactNormalization) {
            return PreparedSourceInvocation(
                request,
                prepareFactorQuadratic(request, projections, polynomialCancellation or stop, stop),
                stop,
            )
        }
        val route = problem.pipelineRoute(
            projections = projections,
            objective = request.objective,
            maximize = request.maximize,
            boundCancellation = request.boundCancellation,
            preparationCancellation = stop,
            polynomialCancellation = polynomialCancellation or stop,
            onLpStats = onLpStats,
        )
        return PreparedSourceInvocation(request, route, stop)
    }
}
