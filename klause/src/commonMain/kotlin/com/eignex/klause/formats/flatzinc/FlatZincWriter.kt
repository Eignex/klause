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
        // Default output: every declared bool / int / float / set var, in declaration order.
        // Skip bool vars whose names start with `__set_` — those are internal set indicator
        // bools synthesized by `allocSetVar`, not user-declared.
        val setIndicatorBools = program.setVarsByName.values.flatMap { it.indicatorBoolIds.toList() }.toSet()
        for ((name, id) in program.boolVarsByName) {
            if (id in setIndicatorBools) continue
            sb.append("$name = ${sample.bools[id]};\n")
        }
        for ((name, id) in program.intVarsByName) {
            if (program.floatVarsByName.containsKey(name)) continue
            sb.append("$name = ${sample.ints[id]};\n")
        }
        for ((name, b) in program.floatVarsByName) {
            sb.append("$name = ${b.valueOf(sample.ints[b.varId])};\n")
        }
        for ((name, layout) in program.setVarsByName) {
            sb.append("$name = ${renderSet(sample, layout)};\n")
        }
    }
    sb.append("----------\n")
    return sb.toString()
}

private fun renderScalar(program: FlatZincProgram, sample: Sample, name: String): String {
    program.setVarsByName[name]?.let { return renderSet(sample, it) }
    program.boolVarsByName[name]?.let { return sample.bools[it].toString() }
    program.floatVarsByName[name]?.let { b -> return b.valueOf(sample.ints[b.varId]).toString() }
    program.intVarsByName[name]?.let { return sample.ints[it].toString() }
    throw IllegalArgumentException("output: unknown var `$name`")
}

/** Reconstruct MiniZinc set output `{a, b, c}` from indicator bools. Emits an empty `{}`
 *  when no element is in the set. */
private fun renderSet(sample: Sample, layout: SetVarLayout): String {
    val sb = StringBuilder("{")
    var first = true
    for (i in layout.elements.indices) {
        if (sample.bools[layout.indicatorBoolIds[i]]) {
            if (!first) sb.append(", ")
            sb.append(layout.elements[i])
            first = false
        }
    }
    sb.append("}")
    return sb.toString()
}

private fun renderArray(program: FlatZincProgram, sample: Sample, name: String): String {
    val arr = program.arraysByName[name]
        ?: throw IllegalArgumentException("output: unknown array `$name`")
    val sb = StringBuilder("[")
    when (arr) {
        is FlatZincArray.BoolParam -> arr.values.joinTo(sb, ", ") { it.toString() }

        is FlatZincArray.IntParam -> arr.values.joinTo(sb, ", ") { it.toString() }

        is FlatZincArray.FloatParam -> arr.values.joinTo(sb, ", ") { it.toString() }

        is FlatZincArray.IntSetParam -> arr.values.joinTo(sb, ", ") { row ->
            row.joinToString(", ", "{", "}") { it.toString() }
        }

        is FlatZincArray.SetVars -> {
            for ((i, layout) in arr.layouts.withIndex()) {
                if (i > 0) sb.append(", ")
                sb.append(renderSet(sample, layout))
            }
        }

        is FlatZincArray.Vars -> {
            for (i in arr.varIds.indices) {
                if (i > 0) sb.append(", ")
                when (arr.elementKind) {
                    FlatZincArray.Vars.ElementKind.Bool -> sb.append(sample.bools[arr.varIds[i]])

                    FlatZincArray.Vars.ElementKind.Int -> sb.append(sample.ints[arr.varIds[i]])

                    FlatZincArray.Vars.ElementKind.Float -> {
                        val b = requireNotNull(arr.floatBucketings)[i]
                        sb.append(b.valueOf(sample.ints[arr.varIds[i]]))
                    }
                }
            }
        }
    }
    sb.append("]")
    return sb.toString()
}
