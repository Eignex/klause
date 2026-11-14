package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.SearchHints
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation

/**
 * Route and operation binding selected by setup. A private grid consumer reads it before setup
 * returns the immutable [PreparedSourceInvocation].
 */
internal class SourceRouteSelection(
    val request: SourceSetupRequest,
    val route: SourceProblemRoute,
    val cancellation: Cancellation,
    val linearSource: PreparedLinearSource? = null,
) {
    val source: SourceAttachment get() = request.source
    val operation: ExecutionOperation get() = request.operation

    val preparedPlan: PreparedSourcePlan? = when (route) {
        is SourceProblemRoute.NativeFormula -> route.preparedPlan
        is SourceProblemRoute.Finite -> route.preparedPlan
        is SourceProblemRoute.OpenTheory -> route.preparedPlan
        is SourceProblemRoute.PolynomialTheory -> route.preparedPlan
        is SourceProblemRoute.UnsupportedOpen -> route.preparedPlan
        SourceProblemRoute.Refuted -> null
    }

    val admission: StrategyAdmission? = preparedPlan?.capabilities?.admission(request.engine, operation)

    private val finite = route as? SourceProblemRoute.Finite

    init {
        require(finite == null || finite.preparedPlan.belongsTo(source.originalSource)) {
            "setup selected an unrelated source"
        }
    }

    val finiteObjective: LinearObjective? =
        finite?.let { request.finiteObjective(linearSource?.objective ?: request.objective) }

    val finiteSearchHints: SearchHints? = finite?.let { selected ->
        source.searchHints?.let { hints ->
            selected.preparedPlan.sourceCoordinates(cancellation)?.let { hints.remapped(it, cancellation) }
        }
    }
}

/** Source, operation, selected plan and optional private grid, fixed before setup returns. */
class PreparedSourceInvocation internal constructor(
    internal val selection: SourceRouteSelection,
    internal val floatGrid: SelectedFloatGridBinding? = null,
) {
    internal constructor(
        request: SourceSetupRequest,
        route: SourceProblemRoute,
        cancellation: Cancellation,
        linearSource: PreparedLinearSource? = null,
    ) : this(SourceRouteSelection(request, route, cancellation, linearSource))

    internal val request: SourceSetupRequest get() = selection.request
    internal val cancellation: Cancellation get() = selection.cancellation
    internal val linearSource: PreparedLinearSource? get() = selection.linearSource

    /** Existing source route selected once by library setup. */
    val route: SourceProblemRoute get() = selection.route

    /** Canonical source attachment whose identity and interpretation bound this invocation. */
    val source: SourceAttachment get() = selection.source

    /** Operation captured before any worker can be constructed. */
    val operation: ExecutionOperation get() = selection.operation

    /** Actual prepared source plan, absent when source deductions refuted the source. */
    val preparedPlan: PreparedSourcePlan? get() = selection.preparedPlan

    /** Captured strategy admission; transformed models retain their own execution admission. */
    val admission: StrategyAdmission? get() = selection.admission

    internal val engineRoute: SourceProblemRoute get() = floatGrid?.route ?: route

    internal val enginePlan: PreparedSourcePlan? get() = floatGrid?.route?.preparedPlan ?: preparedPlan

    internal val canonicalPolynomial: Boolean get() = engineRoute is SourceProblemRoute.PolynomialTheory ||
        (enginePlan?.originalSource is com.eignex.klause.ir.Problem &&
            enginePlan?.execution is PreparedExecution.Polynomial)

    /** Captured private grid geometry, when an explicit finite consumer selected it. */
    val floatProjection: SourceFloatProjection? get() = floatGrid?.metadata

    private val finite: FiniteSolveShape? = if (floatGrid != null) gridShape(floatGrid) else {
        (route as? SourceProblemRoute.Finite)?.let { selected ->
            val objective = selection.finiteObjective
            FiniteSolveShape(
                problem = selected.problem,
                optimize = operation == ExecutionOperation.OPTIMIZE,
                maximize = request.maximize,
                localSearchObjective = null,
                linearObjective = objective,
                objectiveIntVar = objective?.singleIntObjective()?.varId,
                definitionalSweep = null,
                searchHints = selection.finiteSearchHints,
                componentPlan = selected.componentPlan,
                preparedPlan = selected.preparedPlan,
            )
        }
    }?.also { it.bindSourceInvocation(this) }

    private fun gridShape(binding: SelectedFloatGridBinding): FiniteSolveShape {
        require(binding.consumer.selection === selection) { "grid consumer belongs to a different source invocation" }
        binding.projection.mapping.requireSource(source.problem)
        binding.projection.mapping.requireOwner(binding.consumer)
        binding.projection.mapping.requireTarget(binding.route.problem)
        require(binding.route.preparedPlan.belongsTo(binding.route.problem)) {
            "grid plan must retain its private source frame"
        }
        val selected = binding.route
        val objective = binding.nativeObjective
        return FiniteSolveShape(
            problem = selected.problem,
            optimize = operation == ExecutionOperation.OPTIMIZE,
            maximize = request.maximize,
            localSearchObjective = null,
            linearObjective = objective,
            objectiveIntVar = objective?.singleIntObjective()?.varId,
            definitionalSweep = null,
            searchHints = source.searchHints,
            componentPlan = selected.componentPlan,
            preparedPlan = selected.preparedPlan,
        )
    }

    /** Finite mechanism data retaining this setup's captured providers and plan. */
    fun finiteShape(): FiniteSolveShape = requireNotNull(finite) { "source setup did not select a finite route" }

    internal fun requireFiniteExecution(shape: FiniteSolveShape, engine: FiniteEngine, operation: ExecutionOperation) {
        require(shape === finite) { "finite shape belongs to a different source invocation" }
        require(engine == request.engine) { "finite engine differs from its source operation binding" }
        require(
            this.operation in setOf(
                ExecutionOperation.SATISFY,
                ExecutionOperation.OPTIMIZE,
                ExecutionOperation.ENUMERATE,
            ),
        ) {
            "operation has no finite solve execution surface"
        }
        require(operation == this.operation) {
            "finite execution differs from its captured operation"
        }
    }
}
