package com.eignex.klause.compile

import com.eignex.klause.ast.FloatSpec
import com.eignex.klause.schema.BoolHandle
import com.eignex.klause.schema.FloatHandle
import com.eignex.klause.schema.IntHandle
import com.eignex.klause.schema.NominalHandle
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample

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
     * one variable. Equivalent to constructing a coefficient vector by hand with `1.0` at
     * the handle's id; the symbolic form is just less error-prone when the schema mutates.
     */
    fun minimize(handle: IntHandle): LinearObjective {
        val id = intVarIdByName[handle.name]
            ?: error("No integer variable named '${handle.name}'")
        return LinearObjective.minimizeInt(id, problem.numIntVars)
    }

    fun maximize(handle: IntHandle): LinearObjective {
        val id = intVarIdByName[handle.name]
            ?: error("No integer variable named '${handle.name}'")
        return LinearObjective.maximizeInt(id, problem.numIntVars)
    }

    fun minimize(handle: BoolHandle): LinearObjective {
        val id = boolVarIdByName[handle.name]
            ?: error("No Boolean variable named '${handle.name}'")
        return LinearObjective.minimizeBool(id, problem.numBoolVars)
    }

    fun maximize(handle: BoolHandle): LinearObjective {
        val id = boolVarIdByName[handle.name]
            ?: error("No Boolean variable named '${handle.name}'")
        return LinearObjective.maximizeBool(id, problem.numBoolVars)
    }

    /** Float vars after compilation live on the int side as bucket indices. The
     *  objective minimises the bucket id; multiply by `(max - min) / (buckets - 1)` if
     *  you need a coefficient in real-valued units (or use [maximize] for the reverse). */
    fun minimize(handle: FloatHandle): LinearObjective {
        val id = intVarIdByName[handle.name]
            ?: error("No float variable named '${handle.name}'")
        return LinearObjective.minimizeInt(id, problem.numIntVars)
    }

    fun maximize(handle: FloatHandle): LinearObjective {
        val id = intVarIdByName[handle.name]
            ?: error("No float variable named '${handle.name}'")
        return LinearObjective.maximizeInt(id, problem.numIntVars)
    }
}
