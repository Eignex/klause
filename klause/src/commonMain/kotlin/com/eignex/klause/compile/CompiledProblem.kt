package com.eignex.klause.compile

import com.eignex.klause.schema.BoolHandle
import com.eignex.klause.schema.FloatHandle
import com.eignex.klause.schema.IntHandle
import com.eignex.klause.schema.NominalHandle
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample

/**
 * Result of compiling a [com.eignex.klause.schema.VariableSchema] to a solver-side [Problem],
 * carrying the index needed to decode an assignment back to schema values.
 *
 * Boolean-side names map to packed-bit ids in `boolVarIdByName`; integer-side names map
 * to int-array ids in `intVarIdByName`; float-side names map to float-array ids in
 * `floatVarIdByName`. Nominal labels are encoded as one Boolean indicator per label,
 * tracked in `nominalIndicators`.
 */
class CompiledProblem(
    val problem: Problem,
    val boolVarIdByName: Map<String, Int>,
    val intVarIdByName: Map<String, Int>,
    val nominalIndicators: Map<String, Map<String, Int>>,
    val floatVarIdByName: Map<String, Int>,
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
        val id = floatVarIdByName[handle.name]
            ?: error("No float variable named '${handle.name}'")
        return sample.floats[id]
    }
}
