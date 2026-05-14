package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.Sample

/**
 * Render a klause [Sample] as a FlatZinc-compatible solution string. If the program had an
 * `output` declaration, items are rendered in order. Otherwise the writer emits one
 * `name = value;` line per declared variable in declaration order, then the terminating
 * `----------` line that MiniZinc expects per solution.
 */
fun writeFlatZincSolution(program: FlatZincProgram, sample: Sample): String {
    val sb = StringBuilder()
    val items = program.outputItems
    if (items != null) {
        for (item in items) {
            when (item) {
                is OutputItem.Literal -> sb.append(item.text)
                is OutputItem.ShowVar -> sb.append(renderScalar(program, sample, item.name))
                is OutputItem.ShowArray -> sb.append(renderArray(program, sample, item.name))
            }
        }
    } else {
        // Default output: every declared bool / int / float var, in declaration order.
        for ((name, id) in program.boolVarsByName) {
            sb.append("$name = ${sample.bools[id]};\n")
        }
        for ((name, id) in program.intVarsByName) {
            if (program.floatVarsByName.containsKey(name)) continue
            sb.append("$name = ${sample.ints[id]};\n")
        }
        for ((name, b) in program.floatVarsByName) {
            sb.append("$name = ${b.valueOf(sample.ints[b.varId])};\n")
        }
    }
    sb.append("----------\n")
    return sb.toString()
}

private fun renderScalar(program: FlatZincProgram, sample: Sample, name: String): String {
    program.boolVarsByName[name]?.let { return sample.bools[it].toString() }
    program.floatVarsByName[name]?.let { b -> return b.valueOf(sample.ints[b.varId]).toString() }
    program.intVarsByName[name]?.let { return sample.ints[it].toString() }
    throw IllegalArgumentException("output: unknown var `$name`")
}

private fun renderArray(program: FlatZincProgram, sample: Sample, name: String): String {
    val arr = program.arraysByName[name]
        ?: throw IllegalArgumentException("output: unknown array `$name`")
    val sb = StringBuilder("[")
    when (arr) {
        is FlatZincArray.BoolParam -> arr.values.joinTo(sb, ", ") { it.toString() }
        is FlatZincArray.IntParam -> arr.values.joinTo(sb, ", ") { it.toString() }
        is FlatZincArray.FloatParam -> arr.values.joinTo(sb, ", ") { it.toString() }
        is FlatZincArray.Vars -> {
            for (i in arr.varIds.indices) {
                if (i > 0) sb.append(", ")
                when (arr.elementKind) {
                    FlatZincArray.Vars.ElementKind.Bool -> sb.append(sample.bools[arr.varIds[i]])
                    FlatZincArray.Vars.ElementKind.Int -> sb.append(sample.ints[arr.varIds[i]])
                    FlatZincArray.Vars.ElementKind.Float -> {
                        val b = arr.floatBucketings!![i]
                        sb.append(b.valueOf(sample.ints[arr.varIds[i]]))
                    }
                }
            }
        }
    }
    sb.append("]")
    return sb.toString()
}
