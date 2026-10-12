package com.eignex.klause.compile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.ir.Problem
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
 * Boolean-side names map to packed-bit ids in `boolVarIdByName`; integer/float-side names map
 * to int-array ids in `intVarIdByName`. Float vars round-trip through their bucket index
 * using the [FloatSpec] in `floatDecoders`; real columns retain certified coordinates separately.
 */
class CompiledSchema internal constructor(
    /** The compiled solver problem. */
    val problem: Problem,
    /** Boolean variable id by schema name. */
    boolVarIdByName: Map<String, Int>,
    /** Integer variable id by schema name. */
    intVarIdByName: Map<String, Int>,
    /** Per-nominal one-hot indicator bool ids, by schema name. */
    nominalIndicators: Map<String, Map<String, Int>>,
    /** Per-float bucket→real decoders, by schema name. */
    floatDecoders: Map<String, FloatSpec>,
    /** Default branching params derived from the schema's `search { … }` annotation, if
     *  any. `null` when the schema didn't declare one — callers should fall back to
     *  [BacktrackPresets.conflictDriven] in that case. The convenience [backtrackParams] handles that
     *  fallback. */
    val defaultBacktrackParams: BacktrackParams? = null,
    /** Per-set-var indicator layout: parallel `(universe[i], indicatorBoolId[i])`. Used by
     *  the set decoders to read indicator bools back into a [Set]. Nominal-set vars stash
     *  their label order in [setNominalLabels] alongside this. */
    internal val setLayouts: Map<String, SetLayout> = emptyMap(),
    /** Label list (in universe-index order) for each nominal-set var. Empty map entry for
     *  int-universe set vars. */
    setNominalLabels: Map<String, List<String>> = emptyMap(),
    /** Real (LP-only continuous) variable id by schema name, for floats lowered as LP-only columns
     * rather than bucketed; their value is read from [Sample.reals]. Empty when floats
     *  are bucketed (see [floatDecoders]). */
    internal val realVarIdByName: Map<String, Int> = emptyMap(),
    /** Per-component representation and approximation reason by source float name. */
    floatRepresentations: Map<String, FloatRepresentation> = emptyMap(),
) {
    private val booleanIds: Map<String, Int> = boolVarIdByName.toMap()

    /** Independent source metadata for boolVarIdByName. */
    val boolVarIdByName: Map<String, Int> get() = booleanIds.toMap()

    private val integerIds: Map<String, Int> = intVarIdByName.toMap()

    /** Independent source metadata for intVarIdByName. */
    val intVarIdByName: Map<String, Int> get() = integerIds.toMap()

    private val nominalIds: Map<String, Map<String, Int>> = nominalIndicators.mapValues { it.value.toMap() }

    /** Independent source metadata for nominalIndicators. */
    val nominalIndicators: Map<String, Map<String, Int>> get() = nominalIds.mapValues { it.value.toMap() }

    private val floatSpecs: Map<String, FloatSpec> = floatDecoders.toMap()

    /** Independent source metadata for floatDecoders. */
    val floatDecoders: Map<String, FloatSpec> get() = floatSpecs.toMap()

    private val setLabels: Map<String, List<String>> = setNominalLabels.mapValues { it.value.toList() }

    /** Independent source metadata for setNominalLabels. */
    val setNominalLabels: Map<String, List<String>> get() = setLabels.mapValues { it.value.toList() }

    private val representations: Map<String, FloatRepresentation> = floatRepresentations.toMap()

    /** Independent source metadata for floatRepresentations. */
    val floatRepresentations: Map<String, FloatRepresentation> get() = representations.toMap()

    /** Source Boolean location for a compiled schema handle. */
    fun sourceId(handle: BoolHandle): SourceBoolId = SourceBoolId(
        requireNotNull(booleanIds[handle.name]) { "No Boolean variable named '${handle.name}'" },
    )

    /** Source integer location for a compiled schema handle. */
    fun sourceId(handle: IntHandle): SourceIntId = SourceIntId(
        requireNotNull(integerIds[handle.name]) { "No integer variable named '${handle.name}'" },
    )

    /** Source continuous location, or null for a float represented by an integer bucket. */
    fun sourceRealId(handle: FloatHandle): SourceRealId? = realVarIdByName[handle.name]?.let(::SourceRealId)

    /** Return the schema's declared `BacktrackParams` if any, else a fresh default
     *  [BacktrackParams]. Convenience for the common
     *  pattern `BacktrackSolver(p.problem).solve(p.backtrackParams())`. */
    fun backtrackParams(): BacktrackParams = defaultBacktrackParams ?: BacktrackPresets.conflictDriven()

    /** Decode [handle]'s Boolean value from [sample]. */
    fun decode(handle: BoolHandle, sample: Sample): Boolean {
        val id = booleanIds[handle.name]
            ?: error("No Boolean variable named '${handle.name}'")
        return sample.boolValue(id)
    }

    /** Decode [handle]'s label from [sample]. */
    fun decode(handle: NominalHandle, sample: Sample): String {
        val map = nominalIds[handle.name]
            ?: error("No nominal variable named '${handle.name}'")
        return map.entries.firstOrNull { sample.boolValue(it.value) }?.key
            ?: error("Nominal '${handle.name}' has no label set in assignment")
    }

    /** Decode [handle]'s integer value from [sample]. */
    fun decode(handle: IntHandle, sample: Sample): Long {
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

    /** Decodes an optional nominal: `null` when absent. The underlying one-hot indicators may
     *  be unconstrained when the variable is absent, so we don't insist on exactly-one in
     *  that case — but a present nominal still has its standard exactly-one invariant. */
    fun decode(handle: OptNominalHandle, sample: Sample): String? {
        if (!decode(handle.present, sample)) return null
        return decode(handle.value, sample)
    }

    /** Read a set var's indicators back into a `Set<Int>`. Universe elements whose
     *  indicator bool is `true` in [sample] form the result; others are filtered out. */
    fun decode(handle: IntSetHandle, sample: Sample): Set<Int> {
        val layout = setLayouts[handle.name]
            ?: error("No set variable named '${handle.name}'")
        val out = LinkedHashSet<Int>()
        for (i in 0 until layout.size) {
            if (sample.boolValue(layout.indicatorBoolIds[i])) out.add(layout.universe[i])
        }
        return out
    }

    /** Read a nominal-set var's indicators back into a `Set<String>` of currently-selected
     *  labels. */
    fun decode(handle: NominalSetHandle, sample: Sample): Set<String> {
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

    /** Decode [handle]'s real value from [sample] — from [Sample.approximateRealValue] for an LP-only continuous
     *  column, else from the bucket index. */
    fun decode(handle: FloatHandle, sample: Sample): Double {
        realVarIdByName[handle.name]?.let { return sample.approximateRealValue(it) }
        val spec = floatSpecs[handle.name]
            ?: error("No float variable named '${handle.name}'")
        val id = integerIds[handle.name]
            ?: error("Float '${handle.name}' has no int-side id")
        return spec.realValue(sample.intValue(id).toInt())
    }

    /** A [LinearObjective] that minimises [handle], resolving its compiled variable id. */
    fun minimize(handle: IntHandle): LinearObjective {
        val id = integerIds[handle.name]
            ?: error("No integer variable named '${handle.name}'")
        return problem.minimizeInt(id)
    }

    /** A [LinearObjective] that maximises [handle]. */
    fun maximize(handle: IntHandle): LinearObjective {
        val id = integerIds[handle.name]
            ?: error("No integer variable named '${handle.name}'")
        return problem.maximizeInt(id)
    }

    /** A [LinearObjective] that minimises [handle]. */
    fun minimize(handle: BoolHandle): LinearObjective {
        val id = booleanIds[handle.name]
            ?: error("No Boolean variable named '${handle.name}'")
        return problem.minimizeBool(id)
    }

    /** A [LinearObjective] that maximises [handle]. */
    fun maximize(handle: BoolHandle): LinearObjective {
        val id = booleanIds[handle.name]
            ?: error("No Boolean variable named '${handle.name}'")
        return problem.maximizeBool(id)
    }

    /**
     * Minimise [handle] through its continuous column or integer bucket index. Bucket values
     * `min + bucket · (max − min) / (buckets − 1)` increase with the index, so the integer objective
     * preserves their ordering; decoding recovers the real value.
     */
    fun minimize(handle: FloatHandle): LinearObjective {
        realVarIdByName[handle.name]?.let { return realObjective(it, maximize = false) }
        floatSpecs[handle.name] ?: error("No float variable named '${handle.name}'")
        val id = integerIds[handle.name]
            ?: error("Float '${handle.name}' has no int-side id")
        return problem.minimizeInt(id)
    }

    /** A [LinearObjective] that maximises [handle] — minimise the negated bucket id (or real column). */
    fun maximize(handle: FloatHandle): LinearObjective {
        realVarIdByName[handle.name]?.let { return realObjective(it, maximize = true) }
        floatSpecs[handle.name] ?: error("No float variable named '${handle.name}'")
        val id = integerIds[handle.name]
            ?: error("Float '${handle.name}' has no int-side id")
        return problem.maximizeInt(id)
    }

    /** A single-real-column [LinearObjective] over LP-only continuous var [realId]; the always-minimising
     *  frame negates the coefficient to maximise. */
    private fun realObjective(realId: Int, maximize: Boolean): LinearObjective {
        val coeffs = DoubleArray(problem.numRealVars)
        coeffs[realId] = if (maximize) -1.0 else 1.0
        return LinearObjective(realCoefficients = coeffs)
    }
}
