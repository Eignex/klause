package com.eignex.klause.solver.pipeline

/** Source operation whose installed execution surface is inspected. */
enum class ExecutionOperation {
    /** Find one source witness or decide infeasibility. */
    SATISFY,
    /** Improve a captured source objective, with separately checked terminal claims. */
    OPTIMIZE,
    /** Enumerate the source's discrete projection. */
    ENUMERATE,
    /** Count its discrete projection exactly. */
    EXACT_COUNT,
    /** Estimate its discrete projection through bounded cells. */
    APPROXIMATE_COUNT,
    /** Produce heuristic source samples. */
    SAMPLE,
    /** Sample the discrete projection through complete bounded cells. */
    UNIFORM_SAMPLE,
}

/** Requested strategy, independent of worker labels and backend names. */
enum class ExecutionStrategy {
    /** Source traversal with complete checks. */
    COMPLETE,
    /** Heuristic candidates with source completion. */
    LOCAL_SEARCH,
    /** Adaptive source neighborhoods with source completion. */
    ALNS,
    /** Compose the admitted complete and heuristic arms. */
    MIXED,
}

/** Mathematical procedure guarantee, separate from supported operations and claim authority. */
enum class ProcedureCompleteness {
    /** A terminating decision procedure with unrestricted resources. */
    DECISION,
    /** Exhaustive traversal whose termination is not guaranteed on every admitted source. */
    EXHAUSTIVE,
    /** Checked witnesses without a source exhaustion guarantee. */
    WITNESS_SEARCH,
    /** No installed procedure for this source operation. */
    UNAVAILABLE,
}

/** Claims an admitted worker may offer; actual claims still require source-scoped evidence. */
enum class ExecutionClaimAuthority {
    /** A checked source witness. */
    WITNESS,
    /** A source-wide refutation. */
    REFUTATION,
    /** A checked bound for the source objective. */
    OBJECTIVE_BOUND,
    /** An attained optimum or certified unattained limit. */
    OPTIMALITY,
    /** Unlimited improvement of the source objective. */
    UNBOUNDEDNESS,
    /** A sound bound on the number of projected source assignments. */
    PROJECTED_COUNT_BOUND,
    /** A probabilistic projected count estimate under the counting contract. */
    COUNT_ESTIMATE,
}

/** Unsupported admission causes; resource exhaustion belongs to invocation results. */
enum class ExecutionDeclineCause {
    /** Source ownership cannot construct a finite projection. */
    FINITE_PROJECTION,
    /** A factor has no direct heuristic projection. */
    HEURISTIC_PROJECTION,
    /** No installed check completes every required source constraint. */
    SOURCE_COMPLETION,
    /** This source representation has no entry point for the operation. */
    OPERATION_SURFACE,
    /** The captured objective is outside the installed operation contract. */
    OBJECTIVE,
    /** A prepared source assembler declined the required component. */
    PREPARATION,
}

/** A specific component or operation preventing admission. */
class ExecutionDecline internal constructor(
    /** Stable unsupported cause. */
    val cause: ExecutionDeclineCause,
    /** Component's semantic identifier when a component caused the decline. */
    val component: String? = null,
    /** Canonical source factor index when a direct mechanism is missing. */
    val factorIndex: Int? = null,
    /** Original preparation decline, without a resource reinterpretation. */
    val preparation: PreparedPlanDecline? = null,
)

/** Immutable admission of one source operation and strategy. */
class StrategyAdmission internal constructor(
    /** Operation inspected under this prepared source and objective. */
    val operation: ExecutionOperation,
    /** Requested strategy. */
    val strategy: ExecutionStrategy,
    /** Whether at least one requested arm can run the operation. */
    val runnable: Boolean,
    /** Strongest procedure available from the participating complete arm. */
    val completeness: ProcedureCompleteness,
    authorities: Set<ExecutionClaimAuthority>,
    arms: List<ExecutionStrategy>,
    declines: List<ExecutionDecline>,
) {
    private val claims = authorities.toSet()
    private val participating = arms.toList()
    private val refusals = declines.toList()

    /** Worker authority under this source scope, before evidence verification. */
    val claimAuthorities: Set<ExecutionClaimAuthority> get() = claims.toSet()
    /** Participating strategy arms, excluding declined arms. */
    val arms: List<ExecutionStrategy> get() = participating.toList()
    /** Explicit declines; a mixed strategy may retain declines beside admitted siblings. */
    val declines: List<ExecutionDecline> get() = refusals.toList()
}

internal class ComponentStrategySupport(
    val completeness: ProcedureCompleteness,
    val authorities: Set<ExecutionClaimAuthority>,
    val declines: List<ExecutionDecline> = emptyList(),
) {
    val runnable: Boolean get() = completeness != ProcedureCompleteness.UNAVAILABLE
}

internal class ComponentExecutionContract(
    private val complete: ComponentStrategySupport,
    private val local: ComponentStrategySupport,
    private val adaptive: ComponentStrategySupport = local,
    private val optimization: ComponentStrategySupport = complete,
) {
    fun support(
        strategy: ExecutionStrategy,
        operation: ExecutionOperation = ExecutionOperation.SATISFY,
    ): ComponentStrategySupport = when (strategy) {
        ExecutionStrategy.COMPLETE -> if (operation == ExecutionOperation.OPTIMIZE) optimization else complete
        ExecutionStrategy.LOCAL_SEARCH -> local
        ExecutionStrategy.ALNS -> adaptive
        ExecutionStrategy.MIXED -> error("mixed admission composes component arms")
    }
}

/** Inspectable operation and strategy contract reduced from the prepared component ownership. */
class ExecutionCapabilities internal constructor(
    private val components: List<ExecutionComponentDescriptor>,
    operations: Set<ExecutionOperation>,
    private val optimizationSupported: Boolean,
) {
    private val surface = operations.toSet()

    /** Operations installed for this source representation and captured objective. */
    val supportedOperations: Set<ExecutionOperation> get() = surface.filterTo(mutableSetOf()) {
        admission(ExecutionStrategy.MIXED, it).runnable
    }

    /** Inspect the actual component requirements of [operation] and [strategy]. */
    fun admission(
        strategy: ExecutionStrategy,
        operation: ExecutionOperation = ExecutionOperation.SATISFY,
    ): StrategyAdmission {
        if (operation !in surface || operation == ExecutionOperation.OPTIMIZE && !optimizationSupported) {
            val cause = if (operation !in surface) ExecutionDeclineCause.OPERATION_SURFACE
            else ExecutionDeclineCause.OBJECTIVE
            return StrategyAdmission(operation, strategy, false, ProcedureCompleteness.UNAVAILABLE,
                emptySet(), emptyList(), listOf(ExecutionDecline(cause)))
        }
        if (strategy == ExecutionStrategy.MIXED) {
            val admissions = listOf(ExecutionStrategy.COMPLETE, ExecutionStrategy.LOCAL_SEARCH, ExecutionStrategy.ALNS)
                .map { admission(it, operation) }
            val admitted = admissions.filter { it.runnable }
            return StrategyAdmission(operation, strategy, admitted.isNotEmpty(),
                admitted.firstOrNull { it.strategy == ExecutionStrategy.COMPLETE }?.completeness
                    ?: if (admitted.isNotEmpty()) ProcedureCompleteness.WITNESS_SEARCH
                    else ProcedureCompleteness.UNAVAILABLE,
                admitted.flatMap { it.claimAuthorities }.toSet(), admitted.flatMap { it.arms }.distinct(),
                admissions.flatMap { it.declines })
        }
        val completeCell = operation in setOf(ExecutionOperation.EXACT_COUNT, ExecutionOperation.APPROXIMATE_COUNT,
            ExecutionOperation.UNIFORM_SAMPLE)
        val executedStrategy = if (completeCell) ExecutionStrategy.COMPLETE else strategy
        val support = components.map { it.execution.support(executedStrategy, operation) }
        val runnable = support.all { it.runnable }
        val completeness = when {
            !runnable -> ProcedureCompleteness.UNAVAILABLE
            executedStrategy != ExecutionStrategy.COMPLETE || operation == ExecutionOperation.SAMPLE ||
                operation == ExecutionOperation.UNIFORM_SAMPLE -> ProcedureCompleteness.WITNESS_SEARCH
            support.any { it.completeness == ProcedureCompleteness.WITNESS_SEARCH } ->
                ProcedureCompleteness.WITNESS_SEARCH
            operation == ExecutionOperation.OPTIMIZE ||
                support.any { it.completeness == ProcedureCompleteness.EXHAUSTIVE } ->
                ProcedureCompleteness.EXHAUSTIVE
            else -> ProcedureCompleteness.DECISION
        }
        val offered = support.flatMap { it.authorities }.toSet()
        val authority = when {
            !runnable -> emptySet()
            executedStrategy != ExecutionStrategy.COMPLETE -> offered.intersect(setOf(ExecutionClaimAuthority.WITNESS))
            operation == ExecutionOperation.OPTIMIZE -> offered - setOf(
                ExecutionClaimAuthority.PROJECTED_COUNT_BOUND, ExecutionClaimAuthority.COUNT_ESTIMATE)
            operation == ExecutionOperation.EXACT_COUNT -> offered.intersect(
                setOf(ExecutionClaimAuthority.PROJECTED_COUNT_BOUND))
            operation == ExecutionOperation.APPROXIMATE_COUNT -> offered.intersect(
                setOf(ExecutionClaimAuthority.PROJECTED_COUNT_BOUND, ExecutionClaimAuthority.COUNT_ESTIMATE))
            operation == ExecutionOperation.UNIFORM_SAMPLE || operation == ExecutionOperation.SAMPLE ->
                offered.intersect(setOf(ExecutionClaimAuthority.WITNESS))
            else -> offered.intersect(setOf(ExecutionClaimAuthority.WITNESS, ExecutionClaimAuthority.REFUTATION))
        }
        return StrategyAdmission(operation, strategy, runnable, completeness, authority,
            if (runnable) listOf(executedStrategy) else emptyList(), support.flatMap { it.declines })
    }
}

/** Map a caller's strategy selection without inferring source support from its engine name. */
fun ExecutionCapabilities.admission(
    engine: FiniteEngine,
    operation: ExecutionOperation = ExecutionOperation.SATISFY,
): StrategyAdmission = admission(when (engine) {
    FiniteEngine.BACKTRACK, FiniteEngine.FIXED -> ExecutionStrategy.COMPLETE
    FiniteEngine.LOCAL_SEARCH -> ExecutionStrategy.LOCAL_SEARCH
    FiniteEngine.ALNS -> ExecutionStrategy.ALNS
    FiniteEngine.MIXED -> ExecutionStrategy.MIXED
}, operation)
