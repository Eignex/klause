package com.eignex.klause.solver.pipeline

import com.eignex.klause.util.Cancellation

/** Source, operation and selected plan retained through one invocation's construction and execution. */
class PreparedSourceInvocation internal constructor(
    internal val request: SourceSetupRequest,
    /** Existing source route selected once by library setup. */
    val route: SourceProblemRoute,
    internal val cancellation: Cancellation,
) {
    /** Canonical source attachment whose identity and interpretation bound this invocation. */
    val source: SourceAttachment get() = request.source

    /** Operation captured before any worker can be constructed. */
    val operation: ExecutionOperation get() = request.operation

    /** Actual prepared source plan, absent when source deductions refuted the source. */
    val preparedPlan: PreparedSourcePlan? = when (route) {
        is SourceProblemRoute.Finite -> route.preparedPlan
        is SourceProblemRoute.OpenTheory -> route.preparedPlan
        is SourceProblemRoute.PolynomialTheory -> route.preparedPlan
        is SourceProblemRoute.UnsupportedOpen -> route.preparedPlan
        SourceProblemRoute.Refuted -> null
    }

    /** Captured strategy admission; transformed models retain their own execution admission. */
    val admission: StrategyAdmission? = preparedPlan?.capabilities?.admission(request.engine, operation)

    private val finite = (route as? SourceProblemRoute.Finite)?.let { selected ->
        require(selected.preparedPlan.belongsTo(source.problem)) { "setup selected an unrelated source" }
        val objective = request.finiteObjective()
        FiniteSolveShape(
            problem = selected.problem,
            optimize = operation == ExecutionOperation.OPTIMIZE,
            maximize = request.maximize,
            localSearchObjective = null,
            linearObjective = objective,
            objectiveIntVar = objective?.singleIntObjective()?.varId,
            definitionalSweep = null,
            searchHints = source.searchHints?.let { hints ->
                selected.preparedPlan.sourceCoordinates(cancellation)?.let { hints.remapped(it, cancellation) }
            },
            componentPlan = selected.componentPlan,
            preparedPlan = selected.preparedPlan,
        ).also { it.bindSourceInvocation(this) }
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
