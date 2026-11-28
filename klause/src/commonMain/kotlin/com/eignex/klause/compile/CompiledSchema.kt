package com.eignex.klause.compile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.lowering.FloatRepresentation
import com.eignex.klause.lowering.SetLayout
import com.eignex.klause.model.FloatSpec
import com.eignex.klause.schema.BoolHandle
import com.eignex.klause.schema.FloatHandle
import com.eignex.klause.schema.IntHandle
import com.eignex.klause.schema.IntSetHandle
import com.eignex.klause.schema.NominalHandle
import com.eignex.klause.schema.NominalSetHandle
import com.eignex.klause.schema.OptBoolHandle
import com.eignex.klause.schema.OptFloatHandle
import com.eignex.klause.schema.OptIntHandle
import com.eignex.klause.schema.OptNominalHandle
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.maximizeBool
import com.eignex.klause.solver.objective.maximizeInt
import com.eignex.klause.solver.objective.minimizeBool
import com.eignex.klause.solver.objective.minimizeInt

/**
 * Result of compiling a [com.eignex.klause.schema.VariableSchema] to a solver-side `Problem`,
 * carrying the index needed to decode an assignment back to schema values.
 *
 * Names retain original Boolean, integer and real coordinates. Decoding checks the assignment frame.
 */
class CompiledSchema internal constructor(
    problem: Problem,
    boolVarIdByName: Map<String, Int>,
    intVarIdByName: Map<String, Int>,
    nominalIndicators: Map<String, Map<String, Int>>,
    floatDecoders: Map<String, FloatSpec>,
    /** Default branching params from the schema's `search { … }` annotation, or `null` when it declares none. */
    val defaultBacktrackParams: BacktrackParams? = null,
    internal val setLayouts: Map<String, SetLayout> = emptyMap(),
    // Universe-index order per nominal-set var.
    setNominalLabels: Map<String, List<String>> = emptyMap(),
    realVarIdByName: Map<String, Int> = emptyMap(),
    floatRepresentations: Map<String, FloatRepresentation> = emptyMap(),
    sourceBinding: CompiledSourceBinding? = null,
) {
    internal val sourceBinding = sourceBinding ?: CompiledSourceBinding(problem, intVarIdByName, realVarIdByName,
        representations = floatRepresentations)

    /** Original source declarations and relations. */
    val problem: Problem get() = sourceBinding.originalProblem

    internal val realVarIdByName: Map<String, Int> get() = sourceBinding.realNames.toMap()

    private val booleanIds: Map<String, Int> = boolVarIdByName.toMap()

    /** Boolean variable id by schema name (a defensive copy). */
    val boolVarIdByName: Map<String, Int> get() = booleanIds.toMap()

    private val integerIds: Map<String, Int> = intVarIdByName.toMap()

    /** Integer variable id by schema name (a defensive copy). */
    val intVarIdByName: Map<String, Int> get() = integerIds.toMap()

    private val nominalIds: Map<String, Map<String, Int>> = nominalIndicators.mapValues { it.value.toMap() }

    /** Per-nominal one-hot indicator bool ids by schema name (a defensive copy). */
    val nominalIndicators: Map<String, Map<String, Int>> get() = nominalIds.mapValues { it.value.toMap() }

    private val floatSpecs: Map<String, FloatSpec> = floatDecoders.toMap()

    /** Per-float declarations by schema name (a defensive copy). */
    val floatDecoders: Map<String, FloatSpec> get() = floatSpecs.toMap()

    private val setLabels: Map<String, List<String>> = setNominalLabels.mapValues { it.value.toList() }

    /** Label order of each nominal-set variable (a defensive copy). */
    val setNominalLabels: Map<String, List<String>> get() = setLabels.mapValues { it.value.toList() }

    /** Per-component float representation by source name (a defensive copy). */
    val floatRepresentations: Map<String, FloatRepresentation> get() = sourceBinding.floatRepresentations()

    /** Source Boolean location for a compiled schema handle. */
    fun sourceId(handle: BoolHandle): SourceBoolId = SourceBoolId(
        requireNotNull(booleanIds[handle.name]) { "No Boolean variable named '${handle.name}'" },
    )

    /** Source integer location for a compiled schema handle. */
    fun sourceId(handle: IntHandle): SourceIntId = SourceIntId(
        requireNotNull(integerIds[handle.name]) { "No integer variable named '${handle.name}'" },
    )

    /** Original real location. */
    fun sourceRealId(handle: FloatHandle): SourceRealId? = sourceBinding.realNames[handle.name]?.let(::SourceRealId)

    /** The schema's declared [defaultBacktrackParams] if any, else [BacktrackPresets.conflictDriven]. */
    fun backtrackParams(): BacktrackParams = defaultBacktrackParams ?: BacktrackPresets.conflictDriven()

    /**
     * Resolve a helper's exact original-coordinate objective for source execution or evidence capture.
     * An unbound objective uses original source coordinates; a helper from another compilation rejects.
     */
    fun sourceObjective(objective: LinearObjective): QuadraticObjectiveSpec = sourceBinding.originalObjective(objective)

    /** Decode [handle]'s Boolean value from [sample]. */
    fun decode(handle: BoolHandle, sample: Sample): Boolean {
        sourceBinding.isOriginal(sample)
        val id = booleanIds[handle.name]
            ?: error("No Boolean variable named '${handle.name}'")
        return sample.boolValue(id)
    }

    /** Decode [handle]'s label from [sample]. */
    fun decode(handle: NominalHandle, sample: Sample): String {
        sourceBinding.isOriginal(sample)
        val map = nominalIds[handle.name]
            ?: error("No nominal variable named '${handle.name}'")
        return map.entries.firstOrNull { sample.boolValue(it.value) }?.key
            ?: error("Nominal '${handle.name}' has no label set in assignment")
    }

    /** Decode [handle]'s integer value from [sample]. */
    fun decode(handle: IntHandle, sample: Sample): Long {
        sourceBinding.isOriginal(sample)
        val id = integerIds[handle.name]
            ?: error("No integer variable named '${handle.name}'")
        return sample.intValue(id)
    }

    /** Decodes an optional integer: `null` when the presence bit is false, the value otherwise. */
    fun decode(handle: OptIntHandle, sample: Sample): Long? {
        if (!decode(handle.present, sample)) return null
        return decode(handle.value, sample)
    }

    /** Decodes an optional Boolean: `null` when absent. */
    fun decode(handle: OptBoolHandle, sample: Sample): Boolean? {
        if (!decode(handle.present, sample)) return null
        return decode(handle.value, sample)
    }

    /** Decodes an optional float: `null` when the presence bit is false, the real value otherwise. */
    fun decode(handle: OptFloatHandle, sample: Sample): Double? {
        if (!decode(handle.present, sample)) return null
        return decode(handle.value, sample)
    }

    /** Decodes an optional nominal: `null` when absent, whatever its unconstrained indicators hold. */
    fun decode(handle: OptNominalHandle, sample: Sample): String? {
        if (!decode(handle.present, sample)) return null
        return decode(handle.value, sample)
    }

    /** Decode [handle]'s selected universe elements from [sample]. */
    fun decode(handle: IntSetHandle, sample: Sample): Set<Int> {
        sourceBinding.isOriginal(sample)
        val layout = setLayouts[handle.name]
            ?: error("No set variable named '${handle.name}'")
        val out = LinkedHashSet<Int>()
        for (i in 0 until layout.size) {
            if (sample.boolValue(layout.indicatorBoolIds[i])) out.add(layout.universe[i])
        }
        return out
    }

    /** Decode [handle]'s selected labels from [sample]. */
    fun decode(handle: NominalSetHandle, sample: Sample): Set<String> {
        sourceBinding.isOriginal(sample)
        val layout = setLayouts[handle.name]
            ?: error("No nominal-set variable named '${handle.name}'")
        val labels = setLabels[handle.name]
            ?: error("set '${handle.name}' is not a nominal-set var; use `decode(IntSetHandle, ...)` instead")
        val out = LinkedHashSet<String>()
        for (i in 0 until layout.size) {
            if (sample.boolValue(layout.indicatorBoolIds[i])) out.add(labels[i])
        }
        return out
    }

    /** Decode [handle] from the assignment's real coordinates. */
    fun decode(handle: FloatHandle, sample: Sample): Double {
        sourceBinding.isOriginal(sample)
        val id = sourceBinding.realNames[handle.name] ?: error("No float variable named '${handle.name}'")
        return sample.approximateRealValue(id)
    }

    /** A [LinearObjective] that minimises [handle], resolving its compiled variable id. */
    fun minimize(handle: IntHandle): LinearObjective {
        val id = integerIds[handle.name]
            ?: error("No integer variable named '${handle.name}'")
        return sourceBinding.paired(problem.minimizeInt(id))
    }

    /** A [LinearObjective] that maximises [handle]. */
    fun maximize(handle: IntHandle): LinearObjective {
        val id = integerIds[handle.name]
            ?: error("No integer variable named '${handle.name}'")
        return sourceBinding.paired(problem.maximizeInt(id))
    }

    /** A [LinearObjective] that minimises [handle]. */
    fun minimize(handle: BoolHandle): LinearObjective {
        val id = booleanIds[handle.name]
            ?: error("No Boolean variable named '${handle.name}'")
        return sourceBinding.paired(problem.minimizeBool(id))
    }

    /** A [LinearObjective] that maximises [handle]. */
    fun maximize(handle: BoolHandle): LinearObjective {
        val id = booleanIds[handle.name]
            ?: error("No Boolean variable named '${handle.name}'")
        return sourceBinding.paired(problem.maximizeBool(id))
    }

    /** A [LinearObjective] that minimises [handle]. */
    fun minimize(handle: FloatHandle): LinearObjective = realObjective(
        sourceBinding.realNames[handle.name] ?: error("No float variable named '${handle.name}'"), maximize = false,
    )

    /** A [LinearObjective] that maximises [handle]. */
    fun maximize(handle: FloatHandle): LinearObjective = realObjective(
        sourceBinding.realNames[handle.name] ?: error("No float variable named '${handle.name}'"), maximize = true,
    )

    // The objective frame always minimises, so maximising negates the coefficient.
    private fun realObjective(realId: Int, maximize: Boolean): LinearObjective {
        val coeffs = DoubleArray(problem.numRealVars)
        coeffs[realId] = if (maximize) -1.0 else 1.0
        return sourceBinding.paired(LinearObjective(realCoefficients = coeffs))
    }
}
