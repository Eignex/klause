package com.eignex.klause.formats.flatzinc

import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.lowering.FloatBucketing
import com.eignex.klause.lowering.FloatRepresentation
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FloatGridConsumerPurpose
import com.eignex.klause.solver.pipeline.PreparedSourceInvocation
import com.eignex.klause.solver.pipeline.SelectedFloatGridPreparation
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.prepareSelectedFloatGrid
import com.eignex.klause.solver.projection.FloatGridDefinition
import com.eignex.klause.solver.projection.FloatGridVariable
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntDisjointSet

internal class FlatZincGridGeometry(
    val definition: FloatGridDefinition?,
    val representations: Map<String, FloatRepresentation>,
)

internal class FlatZincSourceState(
    val original: FlatZincProgram,
    private val declaredReals: Int,
    private val selectLegacy: () -> Map<String, FloatRepresentation>,
    sharedGeometry: Lazy<FlatZincGridGeometry>? = null,
    private val fixedLegacy: FlatZincProgram? = null,
    private val projectSearch: (FlatZincProgram) -> FlatZincSearchHints? = { it.searchHints },
) {
    private val geometryOwner = sharedGeometry ?: lazy { prepareGeometry() }
    val geometry: FlatZincGridGeometry get() = geometryOwner.value
    internal val materialized: Boolean get() = geometryOwner.isInitialized()
    val legacy: FlatZincProgram by lazy {
        fixedLegacy ?: withSearch(projectedProgram(original, geometry))
    }

    private fun withSearch(program: FlatZincProgram): FlatZincProgram =
        program.copy(searchHints = projectSearch(program))

    fun attachment(): SourceAttachment = SourceAttachment(original.problem, original.definitionHints,
        original.searchHints?.toSearchHints(), (0 until original.problem.numRealVars).map {
            FloatGridVariable(SourceRealId(it), auxiliary = it >= declaredReals)
        })

    private fun prepareGeometry(): FlatZincGridGeometry {
        if (original.semantics.effective.floats !is FloatSemantics.Grid) {
            return FlatZincGridGeometry(null, original.floatRepresentations)
        }
        val (representations, variables) = requireNotNull(selection(Cancellation.Never))
        if (variables.isEmpty()) return FlatZincGridGeometry(null, representations)
        val invocation = SourceSetup.prepare(SourceSetupRequest(attachment(), ExecutionOperation.SATISFY,
            FiniteEngine.LOCAL_SEARCH))
        val prepared = SourceSetup.prepareSelectedFloatGrid(invocation, variables,
            FloatGridConsumerPurpose.FINITE_COMPATIBILITY)
        if (prepared !is SelectedFloatGridPreparation.Prepared) {
            return FlatZincGridGeometry(null, original.floatRepresentations)
        }
        val definition = requireNotNull(invocation.floatGrid).projection.mapping.definition()
        return FlatZincGridGeometry(definition, representations)
    }

    private fun selection(
        cancellation: Cancellation,
    ): Pair<Map<String, FloatRepresentation>, List<FloatGridVariable>>? {
        cancellation.charge(original.problem.numFactors.toLong() + original.problem.numRealVars)
        if (cancellation()) return null
        val representations = selectLegacy()
        if (cancellation()) return null
        val components = IntDisjointSet(original.problem.numRealVars)
        for (factor in original.problem.factors) {
            cancellation.charge(1)
            if (cancellation()) return null
            val reals = factor.variables.reals
            for (id in reals.drop(1)) {
                cancellation.charge(1)
                if (cancellation()) return null
                components.union(reals.first(), id)
            }
        }
        val selectedRoots = original.floatVarsByName.filterKeys { representations[it]?.approximate == true }
            .values.mapTo(HashSet()) { components.find(it.varId) }
        val variables = ArrayList<FloatGridVariable>()
        for (id in 0 until original.problem.numRealVars) {
            cancellation.charge(1)
            if (cancellation()) return null
            if (components.find(id) in selectedRoots) {
                variables += FloatGridVariable(SourceRealId(id), auxiliary = id >= declaredReals)
            }
        }
        return if (cancellation()) null else representations to variables
    }

    fun prepare(invocation: PreparedSourceInvocation, purpose: FloatGridConsumerPurpose) {
        val stop = invocation.cancellation
        if (stop() || original.problem.numRealVars == 0 ||
            original.semantics.effective.floats !is FloatSemantics.Grid
        ) return
        if (geometryOwner.isInitialized()) {
            geometry.definition?.let {
                SourceSetup.prepareSelectedFloatGrid(invocation, it, purpose)
            }
        } else {
            val (_, variables) = selection(stop) ?: return
            if (variables.isNotEmpty()) SourceSetup.prepareSelectedFloatGrid(invocation, variables, purpose)
        }
    }

    fun executionProgram(invocation: PreparedSourceInvocation): FlatZincProgram {
        val binding = invocation.floatGrid ?: return original
        val view = FlatZincGridGeometry(binding.projection.mapping.definition(), original.floatRepresentations)
        val program = withSearch(projectedProgram(original, view))
        return program.suppliedCopy().retainSource(FlatZincSourceState(original, declaredReals, selectLegacy,
            lazyOf(view), program, projectSearch))
    }

    fun copyFor(copied: FlatZincProgram): FlatZincSourceState? {
        if (copied.problem !== legacy.problem || copied.semantics !== original.semantics) return null
        val definition = geometry.definition
        val sourceCopy = if (definition == null) copied.suppliedCopy() else {
            copiedSourceProgram(original, legacy, copied, definition, sourceSearch(copied)) ?: return null
        }
        val search = if (copied.searchHints === legacy.searchHints) projectSearch else { program: FlatZincProgram ->
            program.searchHints
        }
        return FlatZincSourceState(sourceCopy, declaredReals, selectLegacy, geometryOwner, copied.suppliedCopy(),
            search)
    }

    private fun sourceSearch(copied: FlatZincProgram): FlatZincSearchHints? =
        if (copied.searchHints === legacy.searchHints) original.searchHints else {
            copied.searchHints?.let { hints ->
                val tiers = hints.tiers.mapNotNull { tier ->
                    val integers = tier.intVars.filter { it in 0 until original.problem.numIntVars }.toIntArray()
                    if (integers.isEmpty() && tier.boolVars.isEmpty()) null else tier.copy(intVars = integers)
                }
                hints.copy(tiers = tiers).takeIf { tiers.isNotEmpty() }
            }
        }
}

private fun copiedSourceProgram(
    original: FlatZincProgram,
    legacy: FlatZincProgram,
    copied: FlatZincProgram,
    definition: FloatGridDefinition,
    sourceSearch: FlatZincSearchHints?,
): FlatZincProgram? {
    val floats = LinkedHashMap<String, FloatBucketing>()
    for ((name, value) in copied.floatVarsByName) {
        floats[name] = if (value === legacy.floatVarsByName[name]) {
            original.floatVarsByName[name] ?: return null
        } else originalBucketing(value, definition) ?: return null
    }
    val arrays = LinkedHashMap<String, FlatZincArray>()
    for ((name, array) in copied.arraysByName) {
        arrays[name] = if (array === legacy.arraysByName[name]) {
            original.arraysByName[name] ?: return null
        } else originalArray(array, definition) ?: return null
    }
    return FlatZincProgram(original.problem, copied.solve, copied.boolVarsByName,
        copied.intVarsByName.filter { (name, id) -> name !in floats && id < original.problem.numIntVars },
        floats, arrays, copied.outputItems, sourceSearch, copied.enumLabelsByVar, copied.setVarsByName,
        if (copied.floatRepresentations === legacy.floatRepresentations) original.floatRepresentations
        else floats.mapValues { (_, value) -> FloatRepresentation(value.varId, null) },
        copied.semantics, copied.definitionHints)
}

private fun projectedProgram(original: FlatZincProgram, geometry: FlatZincGridGeometry): FlatZincProgram {
    val definition = geometry.definition ?: return original
    val columns = definition.columns().associateBy { it.source.index }
    fun convert(value: FloatBucketing): FloatBucketing {
        val grid = columns[value.varId]
        return if (grid == null) value.copy(varId = definition.retainedReal(value.varId)) else {
            FloatBucketing(grid.coordinate.variable, grid.coordinate.lower, grid.coordinate.upper,
                grid.coordinate.buckets)
        }
    }
    val floats = original.floatVarsByName.mapValues { convert(it.value) }
    val arrays = original.arraysByName.mapValues { (_, array) ->
        if (array is FlatZincArray.Vars && array.elementKind == FlatZincArray.Vars.ElementKind.Float) {
            val bucketings = requireNotNull(array.floatBucketings).map(::convert)
            array.copy(varIds = IntArray(bucketings.size) { bucketings[it].varId }, floatBucketings = bucketings)
        } else array
    }
    val ints = original.intVarsByName.toMutableMap()
    for ((name, value) in floats) if (!value.lpOnly) ints[name] = value.varId
    return FlatZincProgram(definition.target, original.solve, original.boolVarsByName, ints, floats, arrays,
        original.outputItems, original.searchHints, original.enumLabelsByVar, original.setVarsByName,
        geometry.representations, original.semantics, original.definitionHints)
}

private fun originalBucketing(value: FloatBucketing, definition: FloatGridDefinition): FloatBucketing? {
    val id = if (value.lpOnly) {
        (0 until definition.source.numRealVars).firstOrNull { definition.retainedReal(it) == value.varId }
    } else definition.columns().firstOrNull { it.coordinate.variable == value.varId }?.source?.index
    id ?: return null
    return FloatBucketing(id, definition.source.realLower[id], definition.source.realUpper[id], value.buckets,
        lpOnly = true)
}

private fun originalArray(array: FlatZincArray, definition: FloatGridDefinition): FlatZincArray? {
    if (array !is FlatZincArray.Vars || array.elementKind != FlatZincArray.Vars.ElementKind.Float) return array
    val values = array.floatBucketings ?: return null
    val converted = ArrayList<FloatBucketing>(values.size)
    for (value in values) converted += originalBucketing(value, definition) ?: return null
    return array.copy(varIds = IntArray(converted.size) { converted[it].varId }, floatBucketings = converted)
}

/** Attach original real source declarations without requesting a legacy finite view. */
fun FlatZincProgram.sourceAttachment(): SourceAttachment = retainedSource?.attachment()
    ?: SourceAttachment(problem, definitionHints, searchHints?.toSearchHints())

/** Bind the documented finite compatibility view while retaining the original source and native fallback. */
fun FlatZincProgram.prepareFiniteCompatibility(request: SourceSetupRequest): PreparedSourceInvocation =
    prepareInvocation(request, FloatGridConsumerPurpose.FINITE_COMPATIBILITY)

/** Prepare original execution, retaining admitted complete arms before selecting an optional private grid. */
fun FlatZincProgram.prepareSourceExecution(request: SourceSetupRequest): PreparedSourceInvocation =
    prepareInvocation(request, FloatGridConsumerPurpose.AUTOMATIC_FINITE_COMPATIBILITY)

/** Observe the canonical source preparation while retaining the same automatic invocation and controls. */
fun FlatZincProgram.prepareSourceExecution(
    request: SourceSetupRequest,
    onLpStats: (LpStats) -> Unit,
): PreparedSourceInvocation = prepareInvocation(
    request,
    FloatGridConsumerPurpose.AUTOMATIC_FINITE_COMPATIBILITY,
    onLpStats,
)

private fun FlatZincProgram.prepareInvocation(
    request: SourceSetupRequest,
    purpose: FloatGridConsumerPurpose,
    onLpStats: ((LpStats) -> Unit)? = null,
): PreparedSourceInvocation {
    require(request.source.originalSource === sourceProgram.problem) { "FlatZinc request belongs to another source" }
    val invocation = if (onLpStats == null) SourceSetup.prepare(request) else SourceSetup.prepare(request, onLpStats)
    retainedSource?.prepare(invocation, purpose)
    return invocation
}

internal fun FlatZincProgram.forSample(sample: Sample): FlatZincProgram {
    val frame = sample.coordinateFrame ?: return this
    if (frame === sourceProgram.problem) return sourceProgram
    require(frame === problem) { "FlatZinc sample belongs to a different coordinate frame" }
    return this
}
