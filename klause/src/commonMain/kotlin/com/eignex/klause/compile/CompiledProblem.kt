package com.eignex.klause.compile

import com.eignex.klause.ast.FloatSpec
import com.eignex.klause.schema.BoolHandle
import com.eignex.klause.schema.FloatHandle
import com.eignex.klause.schema.IntHandle
import com.eignex.klause.schema.IntSetHandle
import com.eignex.klause.schema.NominalHandle
import com.eignex.klause.schema.NominalSetHandle
import com.eignex.klause.schema.OptBoolHandle
import com.eignex.klause.schema.OptIntHandle
import com.eignex.klause.schema.OptNominalHandle
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.maximizeBool
import com.eignex.klause.solver.maximizeInt
import com.eignex.klause.solver.minimizeBool
import com.eignex.klause.solver.minimizeInt

/**
 * Result of compiling a [com.eignex.klause.schema.VariableSchema] to a solver-side [Problem],
 * carrying the index needed to decode an assignment back to schema values.
 *
 * Boolean-side names map to packed-bit ids in `boolVarIdByName`; integer/float-side names map
 * to int-array ids in `intVarIdByName`. Float vars round-trip through their bucket index
 * using the [FloatSpec] in `floatDecoders`.
 */
class CompiledProblem(
    val problem: Problem,
    val boolVarIdByName: Map<String, Int>,
    val intVarIdByName: Map<String, Int>,
    val nominalIndicators: Map<String, Map<String, Int>>,
    val floatDecoders: Map<String, FloatSpec>,
    /** Default branching params derived from the schema's `search { … }` annotation, if
     *  any. `null` when the schema didn't declare one — callers should fall back to
     *  `BacktrackParams()` in that case. The convenience [backtrackParams] handles that
     *  fallback. */
    val defaultBacktrackParams: com.eignex.klause.solver.backtrack.BacktrackParams? = null,
    /** Per-set-var indicator layout: parallel `(universe[i], indicatorBoolId[i])`. Used by
     *  the set decoders to read indicator bools back into a [Set]. Nominal-set vars stash
     *  their label order in [setNominalLabels] alongside this. */
    val setLayouts: Map<String, com.eignex.klause.compile.SetLayout> = emptyMap(),
    /** Label list (in universe-index order) for each nominal-set var. Empty map entry for
     *  int-universe set vars. */
    val setNominalLabels: Map<String, List<String>> = emptyMap(),
) {
    /** Return the schema's declared `BacktrackParams` if any, else a fresh default
     *  [com.eignex.klause.solver.backtrack.BacktrackParams]. Convenience for the common
     *  pattern `BacktrackSolver(p.problem).solve(p.backtrackParams())`. */
    fun backtrackParams(): com.eignex.klause.solver.backtrack.BacktrackParams =
        defaultBacktrackParams ?: com.eignex.klause.solver.backtrack.BacktrackParams()

    fun decode(handle: BoolHandle, sample: Sample): Boolean {
        val id = boolVarIdByName[handle.name]
            ?: error("No Boolean variable named '${handle.name}'")
        return sample.bools[id]
    }

    fun decode(handle: NominalHandle, sample: Sample): String {
        val map = nominalIndicators[handle.name]
            ?: error("No nominal variable named '${handle.name}'")
        return map.entries.firstOrNull { sample.bools[it.value] }?.key
            ?: error("Nominal '${handle.name}' has no label set in assignment")
    }

    fun decode(handle: IntHandle, sample: Sample): Int {
        val id = intVarIdByName[handle.name]
            ?: error("No integer variable named '${handle.name}'")
        return sample.ints[id]
    }

    /** Decodes an optional integer: `null` when the presence bit is false, the value otherwise. */
    fun decode(handle: OptIntHandle, sample: Sample): Int? {
        if (!decode(handle.present, sample)) return null
        return decode(handle.value, sample)
    }

    /** Decodes an optional Boolean: `null` when absent. */
    fun decode(handle: OptBoolHandle, sample: Sample): Boolean? {
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
            if (sample.bools[layout.indicatorBoolIds[i]]) out.add(layout.universe[i])
        }
        return out
    }

    /** Read a nominal-set var's indicators back into a `Set<String>` of currently-selected
     *  labels. */
    fun decode(handle: NominalSetHandle, sample: Sample): Set<String> {
        val layout = setLayouts[handle.name]
            ?: error("No nominal-set variable named '${handle.name}'")
        val labels = setNominalLabels[handle.name]
            ?: error("set '${handle.name}' is not a nominal-set var; use `decode(IntSetHandle, ...)` instead")
        val out = LinkedHashSet<String>()
        for (i in 0 until layout.size) {
            if (sample.bools[layout.indicatorBoolIds[i]]) out.add(labels[i])
        }
        return out
    }

    fun decode(handle: FloatHandle, sample: Sample): Double {
        val spec = floatDecoders[handle.name]
            ?: error("No float variable named '${handle.name}'")
        val id = intVarIdByName[handle.name]
            ?: error("Float '${handle.name}' has no int-side id")
        val bucket = sample.ints[id]
        return spec.min + (bucket.toDouble() / (spec.buckets - 1)) * (spec.max - spec.min)
    }

    /**
     * MiniZinc-style `solve minimize x`: build the [LinearObjective] that points at this
     * one variable. The symbolic form is less error-prone than hand-building a
     * coefficient array, especially when the schema mutates and variable ids shift.
     */
    fun minimize(handle: IntHandle): LinearObjective {
        val id = intVarIdByName[handle.name]
            ?: error("No integer variable named '${handle.name}'")
        return problem.minimizeInt(id)
    }

    fun maximize(handle: IntHandle): LinearObjective {
        val id = intVarIdByName[handle.name]
            ?: error("No integer variable named '${handle.name}'")
        return problem.maximizeInt(id)
    }

    fun minimize(handle: BoolHandle): LinearObjective {
        val id = boolVarIdByName[handle.name]
            ?: error("No Boolean variable named '${handle.name}'")
        return problem.minimizeBool(id)
    }

    fun maximize(handle: BoolHandle): LinearObjective {
        val id = boolVarIdByName[handle.name]
            ?: error("No Boolean variable named '${handle.name}'")
        return problem.maximizeBool(id)
    }

    /** Minimise the real-valued float. Floats are bucketed on the int side post-compile;
     *  the objective scales the bucket id by `(max - min) / (buckets - 1)` and folds
     *  `min` into the constant so the objective value matches what `decode(handle, sample)`
     *  reports — same units as the original schema declaration, not bucket indices. */
    fun minimize(handle: FloatHandle): LinearObjective {
        val spec = floatDecoders[handle.name]
            ?: error("No float variable named '${handle.name}'")
        val id = intVarIdByName[handle.name]
            ?: error("Float '${handle.name}' has no int-side id")
        val scale = (spec.max - spec.min) / (spec.buckets - 1)
        val arr = DoubleArray(problem.numIntVars)
        arr[id] = scale
        return LinearObjective(intCoefficients = arr, constant = spec.min)
    }

    fun maximize(handle: FloatHandle): LinearObjective {
        val spec = floatDecoders[handle.name]
            ?: error("No float variable named '${handle.name}'")
        val id = intVarIdByName[handle.name]
            ?: error("Float '${handle.name}' has no int-side id")
        val scale = (spec.max - spec.min) / (spec.buckets - 1)
        val arr = DoubleArray(problem.numIntVars)
        arr[id] = -scale
        // For maximise we minimise the negated real, so the constant flips too.
        return LinearObjective(intCoefficients = arr, constant = -spec.min)
    }
}
