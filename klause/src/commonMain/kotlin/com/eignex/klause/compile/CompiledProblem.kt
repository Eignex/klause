package com.eignex.klause.compile

import com.eignex.klause.ast.FloatSpec
import com.eignex.klause.schema.BoolHandle
import com.eignex.klause.schema.FloatHandle
import com.eignex.klause.schema.IntHandle
import com.eignex.klause.schema.NominalHandle
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
) {
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
