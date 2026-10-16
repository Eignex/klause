package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRealProblem
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.PropagationCapability
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.theory.EncoderDecline
import com.eignex.klause.theory.EncodingTarget
import com.eignex.klause.theory.conditionedLinearSupported
import com.eignex.klause.util.Cancellation

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
        ExecutionDeclineCause.SOURCE_COMPLETION,
        semantics,
    ),
    participatingFactorIndices: List<Int> = factorIndices,
) {
    private val factors = factorIndices.toList()
    private val participatingFactors = participatingFactorIndices.toList()
    private val integers = integerCoordinates.toList()
    private val reals = realCoordinates.toList()
    private val booleans = booleanCoordinates.toList()

    /** Canonical Boolean coordinates owned by this component. */
    val booleanCoordinates: List<Int> get() = booleans.toList()

    /** Canonical source factor indices enforced by this component. */
    val factorIndices: List<Int> get() = factors.toList()

    /** Factors consumed by this participant; a factor can participate in several components. */
    val participatingFactorIndices: List<Int> get() = participatingFactors.toList()

    /** Canonical integer coordinates owned by this component. */
    val integerCoordinates: List<Int> get() = integers.toList()

    /** Canonical continuous coordinates, including private completion auxiliaries. */
    val realCoordinates: List<Int> get() = reals.toList()
}

/** Why preparation cannot construct the required source completion. */
enum class PreparedPlanDecline {
    /** The containing invocation interrupted required encoder construction. */
    RESOURCE,

    /** The polynomial-to-finite assembler cannot materialize open integer declarations. */
    OPEN_POLYNOMIAL_INTEGER,

    /** A continuous source factor has no exact polynomial encoding. */
    POLYNOMIAL_ENCODING,

    /** The linear objective weighs continuous columns; rebinding a supported objective keeps the source completion. */
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
class PreparedSourcePlan private constructor(
    private val preparation: PreparedSourceStructure,
    private val operation: PreparedOperationBinding,
) {
    internal constructor(
        originalSource: Any,
        execution: PreparedExecution,
        components: List<ExecutionComponentDescriptor>,
        sourceBoolCount: Int,
        sourceIntCount: Int,
        sourceRealCount: Int,
        objective: LinearObjective? = null,
        maximize: Boolean = false,
        integerCoordinates: List<Int> = (0 until sourceIntCount).toList(),
        realCoordinates: List<Int> = (0 until sourceRealCount).toList(),
        operations: Set<ExecutionOperation> = sourceOperations,
        factorProjections: PreparedFactorProjections? = execution.componentPlan?.factorProjections
            ?: execution.model?.let { FactorProjectionProviders.Default.prepare(it) },
    ) : this(
        PreparedSourceStructure(
            originalSource, execution, components, sourceBoolCount, sourceIntCount, sourceRealCount,
            integerCoordinates, realCoordinates, operations, factorProjections,
        ),
        PreparedOperationBinding(objective, maximize),
    )

    internal constructor(
        originalSource: Any,
        model: Problem?,
        kind: PreparedExecutionKind,
        componentPlan: ComponentPlan?,
        polynomialModel: SharedPolynomialModel?,
        components: List<ExecutionComponentDescriptor>,
        sourceBoolCount: Int,
        sourceIntCount: Int,
        sourceRealCount: Int,
        objective: LinearObjective? = null,
        maximize: Boolean = false,
        decline: PreparedPlanDecline? = null,
        integerCoordinates: List<Int> = (0 until sourceIntCount).toList(),
        realCoordinates: List<Int> = (0 until sourceRealCount).toList(),
        operations: Set<ExecutionOperation> = sourceOperations,
        quadraticObjective: QuadraticObjectiveSpec? = null,
        quadraticSense: ObjectiveSense = ObjectiveSense.MINIMIZE,
        factorProjections: PreparedFactorProjections? = componentPlan?.factorProjections
            ?: model?.let { FactorProjectionProviders.Default.prepare(it) },
    ) : this(
        PreparedSourceStructure(
            originalSource,
            legacyPreparedExecution(
            originalSource,
            model,
            kind,
            componentPlan,
            polynomialModel,
            decline,
        ),
            components, sourceBoolCount, sourceIntCount, sourceRealCount,
            integerCoordinates, realCoordinates, operations, factorProjections,
        ),
        PreparedOperationBinding(
            objective,
            maximize,
            quadraticObjective,
            quadraticSense,
            decline.takeIf { it == PreparedPlanDecline.CONTINUOUS_LINEAR_OBJECTIVE },
        ),
    )

    internal val execution: PreparedExecution get() = preparation.execution

    /** Original neutral source object, including its declaration identities and representation. */
    val originalSource: Any get() = preparation.originalSource

    /** Canonical factor model, absent for a native source retaining its own representation. */
    val model: Problem? get() = execution.model

    /** Representation selected for execution. */
    val kind: PreparedExecutionKind get() = if (operation.decline != null) {
        PreparedExecutionKind.DECLINED
    } else {
        execution.kind
    }

    /** Finite/linear projection data, including a retained projection from declined preparation. */
    val componentPlan: ComponentPlan? get() = execution.componentPlan
    internal val polynomialModel: SharedPolynomialModel? get() = execution.polynomial?.completion

    /** Original Boolean declarations, excluding private auxiliaries. */
    val sourceBoolCount: Int get() = preparation.sourceBoolCount

    /** Original integer declarations. */
    val sourceIntCount: Int get() = preparation.sourceIntCount

    /** Original real declarations, excluding private auxiliaries. */
    val sourceRealCount: Int get() = preparation.sourceRealCount

    /** Source objective preserved across coordinate-identical bound preparation. */
    val objective: LinearObjective? get() = operation.objective

    /** Direction of [objective]. */
    val maximize: Boolean get() = operation.maximize

    /** Explicit preparation cause; an operation decline does not erase structural preparation. */
    val decline: PreparedPlanDecline? get() = operation.decline ?: execution.decline

    /** Exact quadratic source objective captured for this operation, or null. */
    val quadraticObjective: QuadraticObjectiveSpec? get() = operation.quadraticObjective

    /** Direction of [quadraticObjective]. */
    val quadraticSense: ObjectiveSense get() = operation.quadraticSense

    /** Immutable mechanism descriptors captured against [model], absent for a non-factor source. */
    val factorProjections: PreparedFactorProjections? get() = preparation.factorProjections
    internal val finiteFactorProjections: PreparedFactorProjections? get() = preparation.finiteFactorProjections

    /** Operation and strategy admission derived from this plan's installed component contracts. */
    val capabilities: ExecutionCapabilities = ExecutionCapabilities(
        preparation.components,
        preparation.operations,
        operation.supportedBy(preparation),
    )

    /** Bind an immutable linear objective without reselecting source ownership. */
    fun withObjective(objective: LinearObjective, maximize: Boolean = false): PreparedSourcePlan =
        PreparedSourcePlan(preparation, PreparedOperationBinding(objective, maximize))

    /** Bind an exact quadratic objective while preserving source coordinate mappings and completion. */
    fun withObjective(
        objective: QuadraticObjectiveSpec,
        sense: ObjectiveSense = ObjectiveSense.MINIMIZE,
    ): PreparedSourcePlan = PreparedSourcePlan(
        preparation,
        PreparedOperationBinding(quadraticObjective = objective, quadraticSense = sense),
    )

    /** Independent component list whose values own their coordinate and factor lists. */
    val components: List<ExecutionComponentDescriptor> get() = preparation.components.toList()

    /**
     * Prepare the same ownership for exact linear coordinate windows and strict objective cutoffs.
     * Returns null when a required source factor has no such completion. The returned plan admits
     * registered source rows; each query's conditions and mutable session belong to its worker.
     */
    fun prepareConditionedLinearQuery(): PreparedSourcePlan? = prepareConditionedLinearQuery(Cancellation.Never)

    /** Prepare the conditioned linear adapter under the current containing construction controls. */
    fun prepareConditionedLinearQuery(cancellation: Cancellation): PreparedSourcePlan? {
        val selected = execution.finiteLinear ?: return null
        val ownership = selected.ownership.forLinearConditions(selected.model, cancellation = cancellation)
            ?: return null
        val original = originalSource as? Problem ?: return null
        return selected.model.preparedWithComponents(ownership, original, objective, maximize)
    }

    /** Whether this plan retains [source] as its original source object. */
    fun belongsTo(source: Any): Boolean = originalSource === source

    /** Original Boolean coordinate's canonical location. */
    fun booleanCoordinate(sourceId: Int): Int = sourceId.also { require(it in 0 until sourceBoolCount) }

    /** Compact finite projection coordinate, or null for a theory-owned integer. */
    fun finiteIntegerCoordinate(sourceId: Int): Int? {
        val id = integerCoordinate(sourceId)
        return when (val selected = execution) {
            is PreparedExecution.Polynomial -> id

            is PreparedExecution.FiniteLinear -> selected.ownership.cpIntVars.indexOf(id).takeIf { it >= 0 }

            is PreparedExecution.Declined -> when (val retained = selected.retained) {
                is PreparedExecution.Polynomial -> id
                is PreparedExecution.FiniteLinear -> retained.ownership.cpIntVars.indexOf(id).takeIf { it >= 0 }
                null -> null
            }

            is PreparedExecution.NonlinearInteger, is PreparedExecution.Transcendental -> null
        }
    }

    /** Original integer coordinate's canonical location. */
    fun integerCoordinate(sourceId: Int): Int {
        require(sourceId in 0 until sourceIntCount)
        return preparation.integerCoordinates[sourceId]
    }

    /** Original real coordinate's canonical location; lowering auxiliaries are not source coordinates. */
    fun realCoordinate(sourceId: Int): Int {
        require(sourceId in 0 until sourceRealCount)
        return preparation.realCoordinates[sourceId]
    }

    /** Polynomial completion column for a finite integer, or null when no embedding is required. */
    fun polynomialIntegerCoordinate(sourceId: Int): Int? =
        execution.polynomial?.completion?.integerColumns?.get(integerCoordinate(sourceId))
}

/** Prepare source ownership and completion without running a solver or inventing finite bounds. */
fun Problem.prepareExecutionPlan(
    objective: LinearObjective? = null,
    maximize: Boolean = false,
    preferFinite: Boolean = false,
): PreparedSourcePlan = prepareExecutionPlan(objective, maximize, preferFinite, Cancellation.Never)

/** Capture per-invocation factor mechanisms before selecting source ownership and admission. */
fun Problem.prepareExecutionPlan(
    providers: FactorProjectionProviders,
    objective: LinearObjective? = null,
    maximize: Boolean = false,
    preferFinite: Boolean = false,
): PreparedSourcePlan = prepareExecutionPlan(providers, objective, maximize, preferFinite, Cancellation.Never)

/** Prepare source ownership and completion without running a solver or inventing finite bounds. */
fun Problem.prepareExecutionPlan(
    objective: LinearObjective? = null,
    maximize: Boolean = false,
    preferFinite: Boolean = false,
    cancellation: Cancellation,
): PreparedSourcePlan = prepareExecutionPlan(this, objective, maximize, preferFinite, cancellation = cancellation)

/** Capture per-invocation factor mechanisms before selecting source ownership and admission. */
fun Problem.prepareExecutionPlan(
    providers: FactorProjectionProviders,
    objective: LinearObjective? = null,
    maximize: Boolean = false,
    preferFinite: Boolean = false,
    cancellation: Cancellation,
): PreparedSourcePlan = prepareExecutionPlan(
    this,
    objective,
    maximize,
    preferFinite,
    providers,
    cancellation = cancellation,
)

internal fun Problem.prepareExecutionPlan(
    original: Problem,
    objective: LinearObjective?,
    maximize: Boolean,
    preferFinite: Boolean,
    providers: FactorProjectionProviders = (this as? BakedProblem)?.projectionProviders
        ?: FactorProjectionProviders.Default,
    projections: PreparedFactorProjections = (this as? BakedProblem)?.factorProjections
        ?.takeIf { it.providers === providers } ?: providers.prepare(this),
    cancellation: Cancellation = Cancellation.Never,
): PreparedSourcePlan {
    projections.requireSource(this)
    if (requiresPolynomialCompletion(projections, preferFinite)) {
        return preparePolynomialExecutionPlan(
            original = original,
            objective = objective,
            maximize = maximize,
            providers = providers,
            projections = projections,
            cancellation = cancellation,
        )
    }
    val ownership = componentPlan(projections, preferFinite, cancellation)
    if (cancellation() || ownership.encodingDecline == EncoderDecline.RESOURCE) {
        return PreparedSourcePlan(
        original, PreparedExecution.Declined(this, PreparedPlanDecline.RESOURCE),
        listOf(
            ExecutionComponentDescriptor(
            "interrupted-preparation",
            ComponentResponsibility.UNPLACED,
            factors.indices.toList(),
            (0 until numIntVars).toList(),
            (0 until numRealVars).toList(),
            (0 until numBoolVars).toList(),
            unavailableContract(
                ExecutionDeclineCause.PREPARATION,
                "interrupted-preparation",
                PreparedPlanDecline.RESOURCE,
            ),
        )
        ),
        original.numBoolVars, original.numIntVars, original.numRealVars, objective, maximize,
        factorProjections = projections,
        )
    }
    return preparedWithComponents(ownership, original, objective, maximize)
}

internal fun Problem.requiresPolynomialCompletion(
    projections: PreparedFactorProjections,
    preferFinite: Boolean = false,
): Boolean {
    val openInteger = (0 until numIntVars).any { !intBounds.hasLower(it) || !intBounds.hasUpper(it) }
    return factors.indices.any { index ->
        !projections.encoder(index, EncodingTarget.LINEAR).enforcesSource &&
            !(openInteger && projections.conditionedLinearSupported(index)) &&
            projections.encoder(index, EncodingTarget.POLYNOMIAL).enforcesSource &&
            (
                factors[index].variables.reals.isNotEmpty() ||
                projections.capabilities(index).propagation == PropagationCapability.UNSUPPORTED
            ) &&
            (!preferFinite || !finiteRealFactorSupported(index, projections))
    }
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

        ProblemPipeline.FINITE_CP -> if (finiteRealCompletionSupported(ownership.factorProjections)) {
            checkedCompletionContract(ProcedureCompleteness.DECISION)
        } else {
            unavailableContract(ExecutionDeclineCause.SOURCE_COMPLETION, "finite-real")
        }

        ProblemPipeline.UNSUPPORTED_OPEN -> unplacedArithmeticContract(ownership.factorProjections)
    }
    val completionNeeded = ownership.hasTheoryComponent || numRealVars != 0
    val completeSource = !completionNeeded || completion.support(ExecutionStrategy.COMPLETE).runnable
    val finite = finiteComponentContract(
        finiteFactors + sharedFactors,
        finiteIntegers,
        ownership,
        completeSource,
        ownership.hasTheoryComponent && ownership.theoryPipeline != ProblemPipeline.UNSUPPORTED_OPEN,
        ownership.factorProjections,
    )
    val descriptors = listOf(
        ExecutionComponentDescriptor(
            "finite",
            ComponentResponsibility.FINITE,
            finiteFactors,
            finiteIntegers,
            emptyList(),
            execution = finite,
        ),
        ExecutionComponentDescriptor(
            "boolean",
            ComponentResponsibility.BOOLEAN,
            sharedFactors,
            emptyList(),
            emptyList(),
            (0 until numBoolVars).toList(),
            checkedCompletionContract(ProcedureCompleteness.DECISION),
        ),
        ExecutionComponentDescriptor(
            "arithmetic",
            ComponentResponsibility.COMPLETION,
            theoryFactors,
            ownership.theoryIntVars.toList(),
            (0 until numRealVars).toList(),
            execution = completion,
            participatingFactorIndices = ownership.encodedFactorIndices,
        ),
    ).filter {
        it.responsibility == ComponentResponsibility.FINITE ||
            it.participatingFactorIndices.isNotEmpty() || it.booleanCoordinates.isNotEmpty() ||
            it.integerCoordinates.isNotEmpty() || it.realCoordinates.isNotEmpty()
    }
    return PreparedSourcePlan(
        original, PreparedExecution.FiniteLinear(this, ownership), descriptors,
        original.numBoolVars, original.numIntVars, original.numRealVars, objective, maximize,
        operations = if (ownership.hasTheoryComponent) sourceOperations else finiteOperations,
    )
}

internal fun Problem.preparePolynomialExecutionPlan(
    sourceFormula: PolynomialRealFormula? = null,
    original: Any = this,
    objective: LinearObjective? = null,
    maximize: Boolean = false,
    providers: FactorProjectionProviders = FactorProjectionProviders.Default,
    projections: PreparedFactorProjections = providers.prepare(this),
    cancellation: Cancellation = Cancellation.Never,
): PreparedSourcePlan {
    projections.requireSource(this)
    val openInteger = (0 until numIntVars).any { !intBounds.hasLower(it) || !intBounds.hasUpper(it) }
    var encodingDecline: EncoderDecline? = null
    val polynomial = if (openInteger) {
        null
    } else {
        SharedPolynomialModel.lower(this, sourceFormula, projections, cancellation) { encodingDecline = it }
    }
    val cause = when {
        cancellation() || encodingDecline == EncoderDecline.RESOURCE -> PreparedPlanDecline.RESOURCE
        openInteger -> PreparedPlanDecline.OPEN_POLYNOMIAL_INTEGER
        polynomial == null -> PreparedPlanDecline.POLYNOMIAL_ENCODING
        else -> null
    }
    val boundedIntegers = (0 until numIntVars).filter { intBounds.hasLower(it) && intBounds.hasUpper(it) }
    val bounded = boundedIntegers.toSet()
    val finiteFactors = polynomial?.finiteFactorIndices ?: if (cause == PreparedPlanDecline.RESOURCE) {
        emptyList()
    } else {
        factors.indices.filter { index ->
            factors[index].variables.reals.isEmpty() && factors[index].intVars.all { it in bounded } &&
                projections.capabilities(index).propagation != PropagationCapability.UNSUPPORTED
        }
    }
    val finiteIndices = finiteFactors.toSet()
    val ownership = if (polynomial != null || cause == PreparedPlanDecline.RESOURCE) {
        null
    } else {
        ComponentPlan(
            this,
            Array(numIntVars) { if (it in bounded) IntVariableOwner.CP else IntVariableOwner.THEORY },
            Array(numFactors) { if (it in finiteIndices) FactorOwner.CP else FactorOwner.THEORY },
            ProblemPipeline.UNSUPPORTED_OPEN,
            factorProjections = projections,
        )
    }
    val descriptors = listOf(
        ExecutionComponentDescriptor(
            "finite",
            ComponentResponsibility.FINITE,
            finiteFactors,
            boundedIntegers,
            emptyList(),
            (0 until numBoolVars).toList(),
            finiteComponentContract(
                finiteFactors,
                boundedIntegers,
                ownership,
                polynomial != null,
                exactNeighborhood = false,
                projections = projections,
                enforcingFactors = polynomial?.encodingAttributions?.map { it.sourceFactorIndex }?.toSet().orEmpty(),
            ),
        ),
        ExecutionComponentDescriptor(
            "polynomial-real",
            ComponentResponsibility.COMPLETION,
            factors.indices.filter { it !in finiteIndices },
            emptyList(),
            (0 until numRealVars).toList(),
            execution = if (polynomial != null) {
                checkedCompletionContract(ProcedureCompleteness.DECISION)
            } else {
                unavailableContract(ExecutionDeclineCause.PREPARATION, "polynomial-real", cause)
            },
            participatingFactorIndices = polynomial?.encodingAttributions?.map { it.sourceFactorIndex }.orEmpty(),
        ),
    ) + if (openInteger) {
        listOf(
            ExecutionComponentDescriptor(
                "unplaced-polynomial-integer",
                ComponentResponsibility.UNPLACED,
                emptyList(),
                (0 until numIntVars).filter { it !in boundedIntegers },
                emptyList(),
            ),
        )
    } else {
        emptyList()
    }
    val sourceBools = (original as? PolynomialRealProblem)?.sourceBoolCount ?: numBoolVars
    val sourceReals = (original as? PolynomialRealProblem)?.sourceRealCount ?: numRealVars
    val execution = if (cause == null) {
        PreparedExecution.Polynomial(requireNotNull(polynomial))
    } else {
        PreparedExecution.Declined(
            this,
            cause,
            polynomial?.let { PreparedExecution.Polynomial(it) }
                ?: ownership?.let { PreparedExecution.FiniteLinear(this, it) },
        )
    }
    return PreparedSourcePlan(
        original, execution, descriptors, sourceBools, numIntVars, sourceReals,
        objective, maximize, factorProjections = projections,
    )
}

/** Preserve the original polynomial snapshot while preparing its finite and completion projections. */
fun PolynomialRealProblem.prepareExecutionPlan(): PreparedSourcePlan =
    prepareExecutionPlan(ProblemSettings(), Cancellation.Never)

internal fun PolynomialRealProblem.prepareExecutionPlan(
    settings: ProblemSettings,
    cancellation: Cancellation,
): PreparedSourcePlan = Problem(
    numBoolVars = totalBoolCount, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray(),
    numRealVars = totalRealCount,
    realLower = DoubleArray(totalRealCount) { Double.NEGATIVE_INFINITY },
    realUpper = DoubleArray(totalRealCount) { Double.POSITIVE_INFINITY },
    settings = settings, semantics = semantics,
).preparePolynomialExecutionPlan(formula, original = this, cancellation = cancellation)

/** Preserve nonlinear integer source syntax and its exact declaration-to-numeric projection. */
fun NonlinearIntegerProblem.prepareExecutionPlan(): PreparedSourcePlan = PreparedSourcePlan(
    this,
    PreparedExecution.NonlinearInteger(this),
    listOf(
        ExecutionComponentDescriptor(
            "polynomial-integer",
            ComponentResponsibility.COMPLETION,
            emptyList(),
            integerCoordinates,
            (0 until numericCount).filter { it !in integerCoordinates },
            (0 until boolCount).toList(),
            ComponentExecutionContract(
                ComponentStrategySupport(
                    ProcedureCompleteness.WITNESS_SEARCH,
                    setOf(ExecutionClaimAuthority.WITNESS, ExecutionClaimAuthority.REFUTATION),
                ),
                ComponentStrategySupport(ProcedureCompleteness.WITNESS_SEARCH, witnessAuthority),
                optimization = ComponentStrategySupport(
                    ProcedureCompleteness.EXHAUSTIVE,
                    setOf(
                        ExecutionClaimAuthority.WITNESS,
                        ExecutionClaimAuthority.REFUTATION,
                        ExecutionClaimAuthority.OBJECTIVE_BOUND,
                        ExecutionClaimAuthority.OPTIMALITY,
                        ExecutionClaimAuthority.UNBOUNDEDNESS,
                    ),
                ),
            ),
        ),
    ),
    boolCount,
    integerCoordinates.size,
    realCoordinates.size,
    integerCoordinates = integerCoordinates,
    realCoordinates = realCoordinates,
)
