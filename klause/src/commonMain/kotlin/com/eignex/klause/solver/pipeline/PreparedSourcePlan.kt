package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRealProblem
import com.eignex.klause.ir.Problem
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
}

/** Semantic responsibility of a component, independent of an engine name. */
enum class ComponentResponsibility {
    /** A finite projection enforces source factors. */
    FINITE,

    /** A shared Boolean component enforces source clauses. */
    BOOLEAN,

    /** Arithmetic completion supplies authoritative coordinates and source checks. */
    COMPLETION,
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
) {
    private val descriptions = components.toList()
    private val integerMapping = integerCoordinates.toList()
    private val realMapping = realCoordinates.toList()

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

internal fun Problem.prepareExecutionPlan(
    original: Problem,
    objective: LinearObjective?,
    maximize: Boolean,
    preferFinite: Boolean,
): PreparedSourcePlan {
    val continuousObjective = objective?.realCoefficientValues?.any { it != 0.0 } == true
    if (factors.any { it is ContinuousProduct } || (!preferFinite && !continuousObjective && factors.any { it is RealProduct })) {
        return preparePolynomialExecutionPlan(original = original, objective = objective, maximize = maximize)
    }
    return preparedWithComponents(componentPlan(preferFinite), original, objective, maximize)
}

internal fun Problem.preparedWithComponents(
    ownership: ComponentPlan,
    original: Problem = this,
    objective: LinearObjective? = null,
    maximize: Boolean = false,
): PreparedSourcePlan {
    ownership.requireSource(this)
    val descriptors = listOf(
        ExecutionComponentDescriptor("finite", ComponentResponsibility.FINITE,
            factors.indices.filter { ownership.factorOwner(it) == FactorOwner.CP },
            ownership.cpIntVars.toList(), emptyList()),
        ExecutionComponentDescriptor("boolean", ComponentResponsibility.BOOLEAN,
            factors.indices.filter { ownership.factorOwner(it) == FactorOwner.SHARED }, emptyList(), emptyList(),
            (0 until numBoolVars).toList()),
        ExecutionComponentDescriptor(ownership.theoryPipeline.name, ComponentResponsibility.COMPLETION,
            factors.indices.filter { ownership.factorOwner(it) == FactorOwner.THEORY },
            ownership.theoryIntVars.toList(), (0 until numRealVars).toList()),
    ).filter { it.factorIndices.isNotEmpty() || it.booleanCoordinates.isNotEmpty() ||
        it.integerCoordinates.isNotEmpty() || it.realCoordinates.isNotEmpty() }
    return PreparedSourcePlan(original, this, PreparedExecutionKind.FINITE_LINEAR, ownership, null, descriptors,
        original.numBoolVars, original.numIntVars, original.numRealVars, objective, maximize,
        PreparedPlanDecline.UNOWNED_SOURCE_COMPONENT.takeIf { ownership.theoryPipeline == ProblemPipeline.UNSUPPORTED_OPEN })
}

internal fun Problem.preparePolynomialExecutionPlan(
    sourceFormula: PolynomialRealFormula? = null,
    formulaForFactor: (Factor) -> PolynomialRealFormula? = { null },
    original: Any = this,
    objective: LinearObjective? = null,
    maximize: Boolean = false,
): PreparedSourcePlan {
    val openInteger = (0 until numIntVars).any { !intBounds.hasLower(it) || !intBounds.hasUpper(it) }
    val polynomial = if (openInteger) null else SharedPolynomialModel.lower(this, sourceFormula, formulaForFactor)
    val cause = when {
        openInteger -> PreparedPlanDecline.OPEN_POLYNOMIAL_INTEGER
        polynomial == null -> PreparedPlanDecline.POLYNOMIAL_ENCODING
        objective?.realCoefficientValues?.any { it != 0.0 } == true -> PreparedPlanDecline.CONTINUOUS_LINEAR_OBJECTIVE
        else -> null
    }
    val finiteFactors = polynomial?.finiteFactorIndices.orEmpty()
    val descriptors = listOf(
        ExecutionComponentDescriptor("finite", ComponentResponsibility.FINITE, finiteFactors,
            (0 until numIntVars).toList(), emptyList(), (0 until numBoolVars).toList()),
        ExecutionComponentDescriptor("polynomial-real", ComponentResponsibility.COMPLETION,
            factors.indices.filter { it !in finiteFactors }, emptyList(), (0 until numRealVars).toList()),
    )
    val sourceBools = (original as? PolynomialRealProblem)?.sourceBoolCount ?: numBoolVars
    val sourceReals = (original as? PolynomialRealProblem)?.sourceRealCount ?: numRealVars
    return PreparedSourcePlan(original, this,
        if (cause == null) PreparedExecutionKind.POLYNOMIAL else PreparedExecutionKind.DECLINED,
        null, polynomial, descriptors, sourceBools, numIntVars, sourceReals, objective, maximize, cause)
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
        (0 until boolCount).toList())),
    boolCount, integerCoordinates.size, realCoordinates.size,
    integerCoordinates = integerCoordinates, realCoordinates = realCoordinates,
)
