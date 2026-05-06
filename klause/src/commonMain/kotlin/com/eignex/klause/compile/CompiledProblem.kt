package com.eignex.klause.compile

import com.eignex.klause.ast.FloatSpec
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
    fun decodeBool(name: String, sample: Sample): Boolean {
        val id = boolVarIdByName[name] ?: error("No Boolean variable named '$name'")
        return sample.bools[id]
    }

    fun decodeNominal(name: String, sample: Sample): String {
        val map = nominalIndicators[name] ?: error("No nominal variable named '$name'")
        return map.entries.firstOrNull { sample.bools[it.value] }?.key
            ?: error("Nominal '$name' has no label set in assignment")
    }

    fun decodeInt(name: String, sample: Sample): Int {
        val id = intVarIdByName[name] ?: error("No integer variable named '$name'")
        return sample.ints[id]
    }

    fun decodeFloat(name: String, sample: Sample): Double {
        val spec = floatDecoders[name] ?: error("No float variable named '$name'")
        val id = intVarIdByName[name] ?: error("Float '$name' has no int-side id")
        val bucket = sample.ints[id]
        return spec.min + (bucket.toDouble() / (spec.buckets - 1)) * (spec.max - spec.min)
    }
}
