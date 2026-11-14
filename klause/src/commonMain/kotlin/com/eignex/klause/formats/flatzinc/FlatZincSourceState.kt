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
import com.eignex.klause.solver.pipeline.SourceRouteSelection
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.prepareSelectedFloatGrid
import com.eignex.klause.solver.projection.FloatGridDefinition
import com.eignex.klause.solver.projection.FloatGridVariable
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.util.Cancellation
import com.eignex.util.collections.IntDisjointSet

internal class FlatZincGridGeometry(
    val definition: FloatGridDefinition?,
    val representations: Map<String, FloatRepresentation>,
)

internal class FlatZincSourceState(
    val original: FlatZincProgram,
    private val declaredReals: Int,
    private val selectRepresentations: () -> Map<String, FloatRepresentation>,
    sharedGeometry: Lazy<FlatZincGridGeometry>? = null,
    private val fixedCompatibility: FlatZincProgram? = null,
    private val projectSearch: (FlatZincProgram) -> FlatZincSearchHints? = { it.searchHints },
) {
    private val geometryOwner = sharedGeometry ?: lazy { prepareGeometry() }
    val geometry: FlatZincGridGeometry get() = geometryOwner.value
    internal val materialized: Boolean get() = geometryOwner.isInitialized()
    val compatibility: FlatZincProgram by lazy {
        fixedCompatibility ?: withSearch(projectedProgram(original, geometry))
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
        val (representations, variables) = requireNotNull(gridVariables(Cancellation.Never))
        if (variables.isEmpty()) return FlatZincGridGeometry(null, representations)
        val selection = SourceSetup.selectRoute(SourceSetupRequest(attachment(), ExecutionOperation.SATISFY,
            FiniteEngine.LOCAL_SEARCH))
        val prepared = SourceSetup.prepareSelectedFloatGrid(selection, variables,
            FloatGridConsumerPurpose.FINITE_COMPATIBILITY, declaredGridSemantics = true)
        if (prepared !is SelectedFloatGridPreparation.Prepared) {
            return FlatZincGridGeometry(null, original.floatRepresentations)
        }
        val definition = requireNotNull(prepared.invocation.floatGrid).projection.mapping.definition()
        return FlatZincGridGeometry(definition, representations)
    }

    private fun gridVariables(
        cancellation: Cancellation,
    ): Pair<Map<String, FloatRepresentation>, List<FloatGridVariable>>? {
        cancellation.charge(original.problem.numFactors.toLong() + original.problem.numRealVars)
        if (cancellation()) return null
        val representations = selectRepresentations()
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

    fun selectGrid(selection: SourceRouteSelection, purpose: FloatGridConsumerPurpose): SelectedFloatGridPreparation? {
        val stop = selection.cancellation
        if (stop() || original.problem.numRealVars == 0 ||
            original.semantics.effective.floats !is FloatSemantics.Grid
        ) return null
        if (geometryOwner.isInitialized()) {
            return geometry.definition?.let {
                SourceSetup.prepareSelectedFloatGrid(selection, it, purpose, declaredGridSemantics = true)
            }
        }
        val (_, variables) = gridVariables(stop) ?: return null
        return if (variables.isEmpty()) null else SourceSetup.prepareSelectedFloatGrid(selection, variables, purpose,
            declaredGridSemantics = true)
    }

    fun executionProgram(invocation: PreparedSourceInvocation): FlatZincProgram {
        val binding = invocation.floatGrid ?: return original
        val view = FlatZincGridGeometry(binding.projection.mapping.definition(), original.floatRepresentations)
        val program = withSearch(projectedProgram(original, view))
        return program.suppliedCopy().retainSource(FlatZincSourceState(original, declaredReals, selectRepresentations,
            lazyOf(view), program, projectSearch))
    }

    fun copyFor(copied: FlatZincProgram): FlatZincSourceState? {
        if (copied.problem !== compatibility.problem || copied.semantics !== original.semantics) return null
        val definition = geometry.definition
        val sourceCopy = if (definition == null) copied.suppliedCopy() else {
            copiedSourceProgram(original, compatibility, copied, definition, sourceSearch(copied)) ?: return null
        }
        val copiedSearch = { program: FlatZincProgram -> program.searchHints }
        val search = if (copied.searchHints === compatibility.searchHints) projectSearch else copiedSearch
        return FlatZincSourceState(sourceCopy, declaredReals, selectRepresentations, geometryOwner,
            copied.suppliedCopy(), search)
    }

    private fun sourceSearch(copied: FlatZincProgram): FlatZincSearchHints? =
        if (copied.searchHints === compatibility.searchHints) original.searchHints else {
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
    compatibility: FlatZincProgram,
    copied: FlatZincProgram,
    definition: FloatGridDefinition,
    sourceSearch: FlatZincSearchHints?,
): FlatZincProgram? {
    val floats = LinkedHashMap<String, FloatBucketing>()
    for ((name, value) in copied.floatVarsByName) {
        floats[name] = if (value === compatibility.floatVarsByName[name]) {
            original.floatVarsByName[name] ?: return null
        } else originalBucketing(value, definition) ?: return null
    }
    val arrays = LinkedHashMap<String, FlatZincArray>()
    for ((name, array) in copied.arraysByName) {
        arrays[name] = if (array === compatibility.arraysByName[name]) {
            original.arraysByName[name] ?: return null
        } else originalArray(array, definition) ?: return null
    }
    return FlatZincProgram(original.problem, copied.solve, copied.boolVarsByName,
        copied.intVarsByName.filter { (name, id) -> name !in floats && id < original.problem.numIntVars },
        floats, arrays, copied.outputItems, sourceSearch, copied.enumLabelsByVar, copied.setVarsByName,
        if (copied.floatRepresentations === compatibility.floatRepresentations) original.floatRepresentations
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

/** Attach original real source declarations without requesting a compatibility finite view. */
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
    val state = retainedSource
    return SourceSetup.prepareWithGrid(request, { selection -> state?.selectGrid(selection, purpose) },
        onLpStats = onLpStats)
}

internal fun FlatZincProgram.forSample(sample: Sample): FlatZincProgram {
    val frame = sample.coordinateFrame ?: return this
    if (frame === sourceProgram.problem) return sourceProgram
    require(frame === problem) { "FlatZinc sample belongs to a different coordinate frame" }
    return this
}
