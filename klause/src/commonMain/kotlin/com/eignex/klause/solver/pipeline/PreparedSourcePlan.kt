package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRealProblem
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.objective.LinearObjective

/** Execution data selected from a source, before any mutable search state is allocated. */
enum class PreparedExecutionKind {
    /** Finite and linear components use the shared ownership decomposition. */
    FINITE_LINEAR,

    /** Finite candidates require exact polynomial completion. */
    POLYNOMIAL,

    /** Original open or wide integer polynomial coordinates remain theory-owned. */
    NONLINEAR_INTEGER,

    /** A required source component has no implemented execution adapter. */
    DECLINED,

    /** Original exact real functions retain their sound incomplete source service. */
    TRANSCENDENTAL,
}

/** Semantic responsibility of a component, independent of an engine name. */
enum class ComponentResponsibility {
    /** A finite projection enforces source factors. */
    FINITE,

    /** A shared Boolean component enforces source clauses. */
    BOOLEAN,

    /** Arithmetic completion supplies authoritative coordinates and source checks. */
    COMPLETION,

    /** Source coordinates have no implemented execution owner. */
    UNPLACED,
}

/** Immutable component description; [semantics] is extensible by model-facing theory adapters. */
class ExecutionComponentDescriptor internal constructor(
    /** Stable semantic identifier, rather than an engine or worker label. */
    val semantics: String,
    /** Responsibility that candidate construction must satisfy. */
    val responsibility: ComponentResponsibility,
    factorIndices: List<Int>,
    integerCoordinates: List<Int>,
    realCoordinates: List<Int>,
    booleanCoordinates: List<Int> = emptyList(),
    internal val execution: ComponentExecutionContract = unavailableContract(
        ExecutionDeclineCause.SOURCE_COMPLETION, semantics),
) {
    private val factors = factorIndices.toList()
    private val integers = integerCoordinates.toList()
    private val reals = realCoordinates.toList()
    private val booleans = booleanCoordinates.toList()

    /** Canonical Boolean coordinates owned by this component. */
    val booleanCoordinates: List<Int> get() = booleans.toList()

    /** Canonical source factor indices enforced by this component. */
    val factorIndices: List<Int> get() = factors.toList()

    /** Canonical integer coordinates owned by this component. */
    val integerCoordinates: List<Int> get() = integers.toList()

    /** Canonical continuous coordinates, including private completion auxiliaries. */
    val realCoordinates: List<Int> get() = reals.toList()
}

/** Why preparation cannot construct the required source completion. */
enum class PreparedPlanDecline {
    /** The polynomial-to-finite assembler cannot materialize open integer declarations. */
    OPEN_POLYNOMIAL_INTEGER,

    /** A continuous source factor has no exact polynomial encoding. */
    POLYNOMIAL_ENCODING,

    /** Linear-objective polynomial execution requires a discrete objective. */
    CONTINUOUS_LINEAR_OBJECTIVE,

    /** An open source column or factor has no complete owner. */
    UNOWNED_SOURCE_COMPONENT,
}

/**
 * Immutable model-facing execution plan shared by complete and heuristic route construction.
 *
 * [originalSource] retains the source object by identity. Source arrays and factors remain borrowed
 * read-only under the model contract. [model] may contain proved bound narrowing; its coordinate
 * namespaces agree with the source. Arithmetic-owned values never become guessed finite domains.
 */
class PreparedSourcePlan internal constructor(
    /** Original neutral source object, including its declaration identities and representation. */
    val originalSource: Any,
    /** Canonical factor model, absent for a nonlinear integer source retaining its own representation. */
    val model: Problem?,
    /** Representation selected for execution. */
    val kind: PreparedExecutionKind,
    /** Finite/linear projection data, absent for polynomial or declined preparation. */
    val componentPlan: ComponentPlan?,
    internal val polynomialModel: SharedPolynomialModel?,
    components: List<ExecutionComponentDescriptor>,
    /** Original Boolean declarations, excluding private auxiliaries. */
    val sourceBoolCount: Int,
    /** Original integer declarations. */
    val sourceIntCount: Int,
    /** Original real declarations, excluding private auxiliaries. */
    val sourceRealCount: Int,
    /** Source objective preserved across coordinate-identical bound preparation. */
    val objective: LinearObjective? = null,
    /** Direction of [objective]. */
    val maximize: Boolean = false,
    /** Explicit unsupported cause; resource stops belong to the invocation. */
    val decline: PreparedPlanDecline? = null,
    integerCoordinates: List<Int> = (0 until sourceIntCount).toList(),
    realCoordinates: List<Int> = (0 until sourceRealCount).toList(),
    private val operations: Set<ExecutionOperation> = sourceOperations,
    /** Exact quadratic source objective captured for this operation, or null. */
    val quadraticObjective: QuadraticObjectiveSpec? = null,
    /** Direction of [quadraticObjective]. */
    val quadraticSense: ObjectiveSense = ObjectiveSense.MINIMIZE,
    /** Immutable mechanism descriptors captured against [model], absent for a non-factor source. */
    val factorProjections: PreparedFactorProjections? = componentPlan?.factorProjections
        ?: model?.let { FactorProjectionProviders.Default.prepare(it) },
) {
    internal val finiteFactorProjections: PreparedFactorProjections? = polynomialModel?.let { polynomial ->
        factorProjections?.projected(polynomial.finite, polynomial.finiteFactorIndices.toIntArray())
    } ?: factorProjections

    private val descriptions = components.toList()
    private val integerMapping = integerCoordinates.toList()
    private val realMapping = realCoordinates.toList()

    init {
        require(integerMapping.size == sourceIntCount && realMapping.size == sourceRealCount)
        model?.let { factorProjections?.requireSource(it) }
    }

    /** Operation and strategy admission derived from this plan's installed component contracts. */
    val capabilities: ExecutionCapabilities = ExecutionCapabilities(descriptions, operations, objectiveSupported())

    private fun objectiveSupported(): Boolean {
        if (quadraticObjective != null) {
            return (polynomialModel != null || kind == PreparedExecutionKind.NONLINEAR_INTEGER) &&
                quadraticObjective.terms.all { term -> listOfNotNull(term.first, term.second).all { variable ->
                    when (variable) {
                        is QuadraticVariable.Bool -> variable.id.index in 0 until sourceBoolCount
                        is QuadraticVariable.Int -> variable.id.index in 0 until sourceIntCount
                        is QuadraticVariable.Real -> variable.id.index in 0 until sourceRealCount
                    }
                } }
        }
        val linear = objective ?: return false
        if (linear.realCoefficientValues.any { !it.isFinite() } ||
            linear.realCoefficientValues.size > sourceRealCount || linear.intCoefficientValues.size > sourceIntCount ||
            linear.boolWeightValues.size > sourceBoolCount
        ) return false
        if (kind == PreparedExecutionKind.NONLINEAR_INTEGER ||
            kind == PreparedExecutionKind.TRANSCENDENTAL
        ) return false
        return true
    }

    /** Bind an immutable linear objective without reselecting source ownership. */
    fun withObjective(objective: LinearObjective, maximize: Boolean = false): PreparedSourcePlan = PreparedSourcePlan(
        originalSource, model, reboundKind(), componentPlan, polynomialModel, descriptions,
        sourceBoolCount, sourceIntCount, sourceRealCount, objective, maximize, structuralDecline(),
        integerMapping, realMapping, operations, factorProjections = factorProjections,
    )

    /** Bind an exact quadratic objective while preserving source coordinate mappings and completion. */
    fun withObjective(
        objective: QuadraticObjectiveSpec,
        sense: ObjectiveSense = ObjectiveSense.MINIMIZE,
    ): PreparedSourcePlan = PreparedSourcePlan(
        originalSource, model, reboundKind(), componentPlan, polynomialModel, descriptions,
        sourceBoolCount, sourceIntCount, sourceRealCount, decline = structuralDecline(),
        integerCoordinates = integerMapping, realCoordinates = realMapping, operations = operations,
        quadraticObjective = objective, quadraticSense = sense, factorProjections = factorProjections,
    )

    private fun structuralDecline(): PreparedPlanDecline? =
        decline.takeUnless { it == PreparedPlanDecline.CONTINUOUS_LINEAR_OBJECTIVE }

    private fun reboundKind(): PreparedExecutionKind = if (
        decline == PreparedPlanDecline.CONTINUOUS_LINEAR_OBJECTIVE && polynomialModel != null
    ) PreparedExecutionKind.POLYNOMIAL else kind

    /** Independent component list whose values own their coordinate and factor lists. */
    val components: List<ExecutionComponentDescriptor> get() = descriptions.toList()

    /**
     * Prepare the same ownership for exact linear coordinate windows and strict objective cutoffs.
     * Returns null when a required source factor has no such completion. The returned plan admits
     * registered source rows; each query's conditions and mutable session belong to its worker.
     */
    fun prepareConditionedLinearQuery(): PreparedSourcePlan? {
        val canonical = model ?: return null
        val ownership = componentPlan?.forLinearConditions(canonical) ?: return null
        val original = originalSource as? Problem ?: return null
        return canonical.preparedWithComponents(ownership, original, objective, maximize)
    }

    /** Whether this plan retains [source] as its original source object. */
    fun belongsTo(source: Any): Boolean = originalSource === source

    /** Original Boolean coordinate's canonical location. */
    fun booleanCoordinate(sourceId: Int): Int = sourceId.also { require(it in 0 until sourceBoolCount) }

    /** Compact finite projection coordinate, or null for a theory-owned integer. */
    fun finiteIntegerCoordinate(sourceId: Int): Int? {
        val id = integerCoordinate(sourceId)
        if (polynomialModel != null) return id
        return componentPlan?.cpIntVars?.indexOf(id)?.takeIf { it >= 0 }
    }

    /** Original integer coordinate's canonical location. */
    fun integerCoordinate(sourceId: Int): Int {
        require(sourceId in 0 until sourceIntCount)
        return integerMapping[sourceId]
    }

    /** Original real coordinate's canonical location; lowering auxiliaries are not source coordinates. */
    fun realCoordinate(sourceId: Int): Int {
        require(sourceId in 0 until sourceRealCount)
        return realMapping[sourceId]
    }

    /** Polynomial completion column for a finite integer, or null when no embedding is required. */
    fun polynomialIntegerCoordinate(sourceId: Int): Int? =
        polynomialModel?.integerColumns?.get(integerCoordinate(sourceId))
}

/** Prepare source ownership and completion without running a solver or inventing finite bounds. */
fun Problem.prepareExecutionPlan(
    objective: LinearObjective? = null,
    maximize: Boolean = false,
    preferFinite: Boolean = false,
): PreparedSourcePlan = prepareExecutionPlan(this, objective, maximize, preferFinite)

/** Capture per-invocation factor mechanisms before selecting source ownership and admission. */
fun Problem.prepareExecutionPlan(
    providers: FactorProjectionProviders,
    objective: LinearObjective? = null,
    maximize: Boolean = false,
    preferFinite: Boolean = false,
): PreparedSourcePlan = prepareExecutionPlan(this, objective, maximize, preferFinite, providers)

internal fun Problem.prepareExecutionPlan(
    original: Problem,
    objective: LinearObjective?,
    maximize: Boolean,
    preferFinite: Boolean,
    providers: FactorProjectionProviders = (this as? BakedProblem)?.projectionProviders
        ?: FactorProjectionProviders.Default,
): PreparedSourcePlan {
    val projections = (this as? BakedProblem)?.factorProjections?.takeIf { it.providers === providers }
        ?: providers.prepare(this)
    val continuousObjective = objective?.realCoefficientValues?.any { it != 0.0 } == true
    if (factors.any { it is ContinuousProduct } ||
        (!preferFinite && !continuousObjective && factors.any { it is RealProduct })
    ) {
        return preparePolynomialExecutionPlan(original = original, objective = objective, maximize = maximize,
            providers = providers, projections = projections)
    }
    return preparedWithComponents(componentPlan(projections, preferFinite), original, objective, maximize)
}

internal fun Problem.preparedWithComponents(
    ownership: ComponentPlan,
    original: Problem = this,
    objective: LinearObjective? = null,
    maximize: Boolean = false,
): PreparedSourcePlan {
    ownership.requireSource(this)
    val finiteFactors = factors.indices.filter { ownership.factorOwner(it) == FactorOwner.CP }
    val sharedFactors = factors.indices.filter { ownership.factorOwner(it) == FactorOwner.SHARED }
    val theoryFactors = factors.indices.filter { ownership.factorOwner(it) == FactorOwner.THEORY }
    val finiteIntegers = ownership.cpIntVars.toList()
    val completion = when (ownership.theoryPipeline) {
        ProblemPipeline.EXACT_LRA -> checkedCompletionContract(ProcedureCompleteness.DECISION)
        ProblemPipeline.EXACT_LIRA -> checkedCompletionContract(ProcedureCompleteness.EXHAUSTIVE)
        ProblemPipeline.DIFFERENCE_THEORY -> checkedCompletionContract(ProcedureCompleteness.EXHAUSTIVE)
        ProblemPipeline.FINITE_CP -> if (finiteRealCompletionSupported()) {
            checkedCompletionContract(ProcedureCompleteness.DECISION)
        } else unavailableContract(ExecutionDeclineCause.SOURCE_COMPLETION, "finite-real")
        ProblemPipeline.UNSUPPORTED_OPEN -> unplacedArithmeticContract()
    }
    val completionNeeded = ownership.hasTheoryComponent || numRealVars != 0
    val completeSource = !completionNeeded || completion.support(ExecutionStrategy.COMPLETE).runnable
    val finite = finiteComponentContract(finiteFactors + sharedFactors, finiteIntegers, ownership, completeSource,
        ownership.hasTheoryComponent && ownership.theoryPipeline != ProblemPipeline.UNSUPPORTED_OPEN,
        ownership.factorProjections)
    val descriptors = listOf(
        ExecutionComponentDescriptor("finite", ComponentResponsibility.FINITE, finiteFactors,
            finiteIntegers, emptyList(), execution = finite),
        ExecutionComponentDescriptor("boolean", ComponentResponsibility.BOOLEAN, sharedFactors,
            emptyList(), emptyList(), (0 until numBoolVars).toList(),
            checkedCompletionContract(ProcedureCompleteness.DECISION)),
        ExecutionComponentDescriptor("arithmetic", ComponentResponsibility.COMPLETION, theoryFactors,
            ownership.theoryIntVars.toList(), (0 until numRealVars).toList(), execution = completion),
    ).filter { it.responsibility == ComponentResponsibility.FINITE ||
        it.factorIndices.isNotEmpty() || it.booleanCoordinates.isNotEmpty() ||
        it.integerCoordinates.isNotEmpty() || it.realCoordinates.isNotEmpty() }
    return PreparedSourcePlan(original, this, PreparedExecutionKind.FINITE_LINEAR, ownership, null, descriptors,
        original.numBoolVars, original.numIntVars, original.numRealVars, objective, maximize,
        PreparedPlanDecline.UNOWNED_SOURCE_COMPONENT.takeIf {
            ownership.theoryPipeline == ProblemPipeline.UNSUPPORTED_OPEN
        }, operations = if (ownership.hasTheoryComponent) sourceOperations else finiteOperations)

}

internal fun Problem.preparePolynomialExecutionPlan(
    sourceFormula: PolynomialRealFormula? = null,
    formulaForFactor: (Factor) -> PolynomialRealFormula? = { null },
    original: Any = this,
    objective: LinearObjective? = null,
    maximize: Boolean = false,
    providers: FactorProjectionProviders = FactorProjectionProviders.Default,
    projections: PreparedFactorProjections = providers.prepare(this),
): PreparedSourcePlan {
    projections.requireSource(this)
    val openInteger = (0 until numIntVars).any { !intBounds.hasLower(it) || !intBounds.hasUpper(it) }
    val polynomial = if (openInteger) null else SharedPolynomialModel.lower(this, sourceFormula, formulaForFactor)
    val cause = when {
        openInteger -> PreparedPlanDecline.OPEN_POLYNOMIAL_INTEGER
        polynomial == null -> PreparedPlanDecline.POLYNOMIAL_ENCODING
        objective?.realCoefficientValues?.any { it != 0.0 } == true -> PreparedPlanDecline.CONTINUOUS_LINEAR_OBJECTIVE
        else -> null
    }
    val finiteFactors = polynomial?.finiteFactorIndices.orEmpty()
    val boundedIntegers = (0 until numIntVars).filter { intBounds.hasLower(it) && intBounds.hasUpper(it) }
    val descriptors = listOf(
        ExecutionComponentDescriptor("finite", ComponentResponsibility.FINITE, finiteFactors,
            boundedIntegers, emptyList(), (0 until numBoolVars).toList(),
            finiteComponentContract(finiteFactors, boundedIntegers, null, polynomial != null,
                exactNeighborhood = false, projections = projections)),
        ExecutionComponentDescriptor("polynomial-real", ComponentResponsibility.COMPLETION,
            factors.indices.filter { it !in finiteFactors }, emptyList(), (0 until numRealVars).toList(),
            execution = if (polynomial != null) checkedCompletionContract(ProcedureCompleteness.DECISION)
            else unavailableContract(ExecutionDeclineCause.PREPARATION, "polynomial-real", cause)),
    ) + if (openInteger) listOf(ExecutionComponentDescriptor("unplaced-polynomial-integer",
        ComponentResponsibility.UNPLACED, emptyList(),
        (0 until numIntVars).filter { it !in boundedIntegers }, emptyList()))
    else emptyList()
    val sourceBools = (original as? PolynomialRealProblem)?.sourceBoolCount ?: numBoolVars
    val sourceReals = (original as? PolynomialRealProblem)?.sourceRealCount ?: numRealVars
    return PreparedSourcePlan(original, this,
        if (cause == null) PreparedExecutionKind.POLYNOMIAL else PreparedExecutionKind.DECLINED,
        null, polynomial, descriptors, sourceBools, numIntVars, sourceReals, objective, maximize, cause,
        factorProjections = projections)
}

/** Preserve the original polynomial snapshot while preparing its finite and completion projections. */
fun PolynomialRealProblem.prepareExecutionPlan(): PreparedSourcePlan = Problem(
    numBoolVars = totalBoolCount, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray(),
    numRealVars = totalRealCount,
    realLower = DoubleArray(totalRealCount) { Double.NEGATIVE_INFINITY },
    realUpper = DoubleArray(totalRealCount) { Double.POSITIVE_INFINITY },
).preparePolynomialExecutionPlan(formula, original = this)

/** Preserve nonlinear integer source syntax and its exact declaration-to-numeric projection. */
fun NonlinearIntegerProblem.prepareExecutionPlan(): PreparedSourcePlan = PreparedSourcePlan(
    this, null, PreparedExecutionKind.NONLINEAR_INTEGER, null, null,
    listOf(ExecutionComponentDescriptor("polynomial-integer", ComponentResponsibility.COMPLETION,
        emptyList(), integerCoordinates, (0 until numericCount).filter { it !in integerCoordinates },
        (0 until boolCount).toList(), ComponentExecutionContract(
            ComponentStrategySupport(ProcedureCompleteness.WITNESS_SEARCH,
                setOf(ExecutionClaimAuthority.WITNESS, ExecutionClaimAuthority.REFUTATION)),
            ComponentStrategySupport(ProcedureCompleteness.WITNESS_SEARCH, witnessAuthority),
            optimization = ComponentStrategySupport(ProcedureCompleteness.EXHAUSTIVE,
                setOf(ExecutionClaimAuthority.WITNESS, ExecutionClaimAuthority.REFUTATION,
                    ExecutionClaimAuthority.OBJECTIVE_BOUND, ExecutionClaimAuthority.OPTIMALITY,
                    ExecutionClaimAuthority.UNBOUNDEDNESS))))),
    boolCount, integerCoordinates.size, realCoordinates.size,
    integerCoordinates = integerCoordinates, realCoordinates = realCoordinates,
)
