package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.global.Distinct
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.encodingPreparationScope
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.solver.validation.statesStrictMargin
import com.eignex.klause.solver.validation.strictMargin
import com.eignex.klause.util.ExecutionControl

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
    val cancellation: ExecutionControl,
    /** Optional deduction allowance, also bounded by [cancellation]. */
    val boundCancellation: ExecutionControl,
    /** Original exact quadratic source objective, when the operation supplies one. */
    val quadraticObjective: QuadraticObjectiveSpec?,
    internal val requiresQuadraticCompletion: Boolean = false,
) {
    /** Bind an affine operation or the objective already embedded in a native formula. */
    constructor(
        source: SourceAttachment,
        operation: ExecutionOperation,
        engine: FiniteEngine,
        objective: LinearObjective? = null,
        maximize: Boolean = false,
        providers: FactorProjectionProviders = source.providers,
        cancellation: ExecutionControl = ExecutionControl.Never,
        boundCancellation: ExecutionControl = cancellation,
    ) : this(source, operation, engine, objective, maximize, providers, cancellation, boundCancellation, null)

    /** Bind an original exact quadratic objective and direction before native preparation. */
    constructor(
        source: SourceAttachment,
        engine: FiniteEngine,
        quadraticObjective: QuadraticObjectiveSpec,
        sense: ObjectiveSense = ObjectiveSense.MINIMIZE,
        providers: FactorProjectionProviders = source.providers,
        cancellation: ExecutionControl = ExecutionControl.Never,
        boundCancellation: ExecutionControl = cancellation,
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
        cancellation: ExecutionControl,
        boundCancellation: ExecutionControl,
        requiresQuadraticCompletion: Boolean,
    ) : this(
        source, ExecutionOperation.OPTIMIZE, engine, null, sense == ObjectiveSense.MAXIMIZE,
        providers, cancellation, boundCancellation, quadraticObjective, requiresQuadraticCompletion,
    )

    init {
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

/** Captures one source route and retains its operation binding for execution. */
object SourceSetup {
    /** Prepare from neutral source data without allocating heuristic worker state. */
    fun prepare(request: SourceSetupRequest): PreparedSourceInvocation = PreparedSourceInvocation(selectRoute(request))

    /** Prepare the same source binding while observing bound-probing LP work. */
    fun prepare(request: SourceSetupRequest, onLpStats: (LpStats) -> Unit): PreparedSourceInvocation =
        PreparedSourceInvocation(selectRoute(request, onLpStats))

    internal fun prepare(request: SourceSetupRequest,
        polynomialCancellation: ExecutionControl): PreparedSourceInvocation =
        PreparedSourceInvocation(selectRoute(request, null, polynomialCancellation))

    // The grid consumer runs on the selected route before the invocation exists, so no execution view escapes first.
    internal fun prepareWithGrid(
        request: SourceSetupRequest,
        privateGrid: (SourceRouteSelection) -> SelectedFloatGridPreparation?,
        polynomialCancellation: ExecutionControl = request.cancellation,
        onLpStats: ((LpStats) -> Unit)? = null,
    ): PreparedSourceInvocation {
        val selection = selectRoute(request, onLpStats, polynomialCancellation)
        val prepared = privateGrid(selection) as? SelectedFloatGridPreparation.Prepared
            ?: return PreparedSourceInvocation(selection)
        require(prepared.invocation.selection === selection) {
            "grid invocation belongs to a different route selection"
        }
        return prepared.invocation
    }

    internal fun selectRoute(
        request: SourceSetupRequest,
        onLpStats: ((LpStats) -> Unit)? = null,
        polynomialCancellation: ExecutionControl = request.cancellation,
    ): SourceRouteSelection {
        val stop = request.cancellation.encodingPreparationScope()
        request.source.formula?.let { formula ->
            if (formula is AttachedSourceFormula.Linear) {
                return prepareLinearSource(request, formula.problem, stop, onLpStats)
            }
            val route = prepareNativeSource(request, formula, stop)
            return SourceRouteSelection(request, route, stop)
        }
        val problem = request.source.problem
        if (problem.factors.any {
                it is com.eignex.klause.factor.arithmetic.ExactRealEquality && it.value.algebraic == null
            }) return SourceRouteSelection(request, prepareExactRealSource(request, stop), stop)
        val projections = (problem as? BakedProblem)?.factorProjections
            ?.takeIf { it.providers === request.providers } ?: request.providers.prepare(problem)
        val exactNormalization = request.maximize && request.objective?.let { objective ->
            objective.constant == Long.MIN_VALUE || objective.intCoefficientValues.any { it == Long.MIN_VALUE } ||
                objective.boolWeightValues.any { it == Long.MIN_VALUE }
        } == true
        if (request.quadraticObjective != null || exactNormalization) {
            return SourceRouteSelection(
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
            routePureRealToTheory = problem.semantics.effective.feasibilityTolerance?.isExact != false ||
                request.operation == ExecutionOperation.ENUMERATE || problem.statesOpenRealComparison(),
        )
        return SourceRouteSelection(request, route, stop)
    }
}

// Whether a relation over reals states an open set: a strict row the absolute slack leaves without a margin, a
// disequality, or a reified row whose false side is a strict complement. The finite lane completes leaves over
// closed sets only, so a supremum such a relation leaves unattained is the linear theory's to report. An
// unconditional strict row with a margin states the closed row `a·x <= b - margin`.
private fun Problem.statesOpenRealComparison(): Boolean {
    val margin = semantics.effective.feasibilityTolerance.strictMargin()
    return factors.any { factor ->
        factor.variables.reals.isNotEmpty() && !factor.statesStrictMargin(margin) &&
            (factor is Distinct || factor.linearForm?.rows?.any { row ->
                row.strict || row.relation == LinearOp.NE || row.activator != LinearRow.ALWAYS
            } == true)
    }
}
