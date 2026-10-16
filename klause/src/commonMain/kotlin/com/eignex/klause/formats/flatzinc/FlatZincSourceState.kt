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
import com.eignex.klause.util.IntDisjointSet
import com.eignex.klause.util.Cancellation

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
) {
    private val geometryOwner = sharedGeometry ?: lazy { prepareGeometry() }
    val geometry: FlatZincGridGeometry get() = geometryOwner.value
    internal val materialized: Boolean get() = geometryOwner.isInitialized()
    val legacy: FlatZincProgram by lazy { fixedLegacy ?: projectedProgram(original, geometry) }

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
        return FlatZincGridGeometry(requireNotNull(invocation.floatGrid).projection.mapping.definition(), representations)
    }

    private fun selection(cancellation: Cancellation): Pair<Map<String, FloatRepresentation>, List<FloatGridVariable>>? {
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

    fun prepare(invocation: PreparedSourceInvocation) {
        val stop = invocation.cancellation
        if (stop() || original.problem.numRealVars == 0 || original.semantics.effective.floats !is FloatSemantics.Grid) return
        if (geometryOwner.isInitialized()) {
            geometry.definition?.let {
                SourceSetup.prepareSelectedFloatGrid(invocation, it, FloatGridConsumerPurpose.FINITE_COMPATIBILITY)
            }
        } else {
            val (_, variables) = selection(stop) ?: return
            if (variables.isNotEmpty()) SourceSetup.prepareSelectedFloatGrid(invocation, variables,
                FloatGridConsumerPurpose.FINITE_COMPATIBILITY)
        }
    }

    fun executionProgram(invocation: PreparedSourceInvocation): FlatZincProgram {
        val binding = invocation.floatGrid ?: return original
        val view = FlatZincGridGeometry(binding.projection.mapping.definition(), original.floatRepresentations)
        val program = projectedProgram(original, view)
        return program.suppliedCopy().retainSource(FlatZincSourceState(original, declaredReals, selectLegacy,
            lazyOf(view), program))
    }

    fun copyFor(copied: FlatZincProgram): FlatZincSourceState? {
        if (copied.problem !== legacy.problem || copied.semantics !== original.semantics) return null
        val definition = geometry.definition
        val sourceCopy = if (definition == null) copied.suppliedCopy() else {
            val floats = LinkedHashMap<String, FloatBucketing>()
            for ((name, value) in copied.floatVarsByName) {
                floats[name] = originalBucketing(value, definition) ?: return null
            }
            val arrays = LinkedHashMap<String, FlatZincArray>()
            for ((name, array) in copied.arraysByName) {
                arrays[name] = originalArray(array, definition) ?: return null
            }
            FlatZincProgram(original.problem, copied.solve, copied.boolVarsByName,
                copied.intVarsByName.filter { (name, id) -> name !in floats && id < original.problem.numIntVars },
                floats, arrays, copied.outputItems, copied.searchHints, copied.enumLabelsByVar, copied.setVarsByName,
                copied.floatRepresentations, copied.semantics, copied.definitionHints)
        }
        return FlatZincSourceState(sourceCopy, declaredReals, selectLegacy, geometryOwner, copied.suppliedCopy())
    }
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
    return FloatBucketing(id, definition.source.realLower[id], definition.source.realUpper[id], value.buckets, lpOnly = true)
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
fun FlatZincProgram.prepareFiniteCompatibility(request: SourceSetupRequest): PreparedSourceInvocation {
    require(request.source.originalSource === sourceProgram.problem) { "FlatZinc request belongs to another source" }
    val invocation = SourceSetup.prepare(request)
    retainedSource?.prepare(invocation)
    return invocation
}

internal fun FlatZincProgram.forSample(sample: Sample): FlatZincProgram {
    val frame = sample.coordinateFrame ?: return this
    if (frame === sourceProgram.problem) return sourceProgram
    require(frame === problem) { "FlatZinc sample belongs to a different coordinate frame" }
    return this
}
