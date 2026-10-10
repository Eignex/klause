package com.eignex.klause.solver.pipeline

import com.eignex.klause.formats.flatzinc.*
import com.eignex.klause.formats.flatzinc.FlatZincArray
import com.eignex.klause.formats.flatzinc.FlatZincProgram
import com.eignex.klause.formats.flatzinc.OutputItem
import com.eignex.klause.formats.flatzinc.SetVarLayout
import com.eignex.klause.formats.flatzinc.SolveDirective
import com.eignex.klause.lowering.FloatBucketing
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.parseBigInt

/** Render one solved sample in FlatZinc output format. */
fun writeFlatZincSolution(program: FlatZincProgram, sample: Sample, outputObjective: Boolean = false): String =
    writeFlatZincSolution(program, FlatZincValues(sample), outputObjective)

/** Render an open-theory witness in the FlatZinc solution protocol. */
fun writeFlatZincSolution(
    program: FlatZincProgram,
    assignment: OpenTheoryAssignment,
    outputObjective: Boolean = false,
): String = writeFlatZincSolution(program, FlatZincValues(assignment), outputObjective)

internal class FlatZincValues(
    val boolValue: (Int) -> Boolean,
    val intValue: (Int) -> String,
    val realValue: (Int) -> Double,
    val exactRealValue: (Int) -> String?,
) {
    constructor(sample: Sample) : this(
        { sample.bools[it] },
        { sample.exactIntValue(it).toString() },
        { sample.approximateRealValue(it) },
        { sample.exactReals?.get(it)?.toString() },
    )

    constructor(assignment: OpenTheoryAssignment) : this(
        assignment::boolValue,
        assignment::intValue,
        { id ->
            val parts = assignment.realValue(id).split('/')
            BigFraction.of(
                parseBigInt(parts[0]),
                if (parts.size == 1) BIG_ONE else parseBigInt(parts[1]),
            ).toDouble()
        },
        assignment::realValue,
    )

    fun floatValue(b: FloatBucketing): Double =
        if (b.lpOnly) realValue(b.varId) else b.valueOf(intValue(b.varId).toInt())
}

private fun writeFlatZincSolution(program: FlatZincProgram, sample: FlatZincValues, outputObjective: Boolean): String {
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
        // Skip internal set-indicator bools in fallback output.
        val setIndicatorBools = program.setVarsByName.values.flatMap { it.indicatorBoolIds.toList() }.toSet()
        for ((name, id) in program.boolVarsByName) {
            if (id in setIndicatorBools) continue
            sb.append("$name = ${sample.boolValue(id)};\n")
        }
        for ((name, id) in program.intVarsByName) {
            if (program.floatVarsByName.containsKey(name)) continue
            sb.append("$name = ${sample.intValue(id)};\n")
        }
        for ((name, b) in program.floatVarsByName) {
            sb.append("$name = ${sample.floatValue(b)};\n")
        }
        for ((name, layout) in program.setVarsByName) {
            sb.append("$name = ${renderSet(sample, layout)};\n")
        }
    }
    if (outputObjective) {
        objectiveVarName(program.solve)?.let { sb.append("_objective = ${renderScalar(program, sample, it)};\n") }
    }
    sb.append(writeFlatZincExactCoordinates(program, sample, outputObjective))
    sb.append("----------\n")
    return sb.toString()
}

internal fun writeFlatZincExactCoordinates(
    program: FlatZincProgram,
    sample: FlatZincValues,
    outputObjective: Boolean = false,
): String {
    val sb = StringBuilder()
    if (program.floatVarsByName.values.any { it.lpOnly } &&
        program.floatVarsByName.values.all { it.lpOnly && sample.exactRealValue(it.varId) != null }
    ) {
        for ((name, id) in program.boolVarsByName) {
            sb.append("% klause-exact: $name = ${sample.boolValue(id)};\n")
        }
        for ((name, id) in program.intVarsByName) {
            if (name !in program.floatVarsByName) {
                sb.append("% klause-exact: $name = ${sample.intValue(id)};\n")
            }
        }
        for ((name, b) in program.floatVarsByName) {
            val value = sample.exactRealValue(b.varId)
            sb.append("% klause-exact: $name = $value;\n")
        }
        if (outputObjective) {
            objectiveVarName(program.solve)?.let { name ->
                val b = program.floatVarsByName[name]
                val value = if (b?.lpOnly == true) {
                    sample.exactRealValue(b.varId)
                } else {
                    renderScalar(program, sample, name)
                }
                sb.append("% klause-exact: _objective = $value;\n")
            }
        }
    }
    return sb.toString()
}

private fun objectiveVarName(solve: SolveDirective): String? = when (solve) {
    is SolveDirective.Minimize -> solve.objVar
    is SolveDirective.Maximize -> solve.objVar
    SolveDirective.Satisfy -> null
}

private fun renderScalar(program: FlatZincProgram, sample: FlatZincValues, name: String): String {
    program.setVarsByName[name]?.let { return renderSet(sample, it) }
    program.boolVarsByName[name]?.let { return sample.boolValue(it).toString() }
    program.floatVarsByName[name]?.let { b -> return sample.floatValue(b).toString() }
    program.intVarsByName[name]?.let { return sample.intValue(it) }
    throw IllegalArgumentException("output: unknown var `$name`")
}

// Reconstruct MiniZinc set output `{a, b, c}` from indicator bools.
private fun renderSet(sample: FlatZincValues, layout: SetVarLayout): String {
    val sb = StringBuilder("{")
    var first = true
    for (i in layout.elements.indices) {
        if (sample.boolValue(layout.indicatorBoolIds[i])) {
            if (!first) sb.append(", ")
            sb.append(layout.elements[i])
            first = false
        }
    }
    sb.append("}")
    return sb.toString()
}

private fun renderArray(program: FlatZincProgram, sample: FlatZincValues, name: String): String {
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
                    FlatZincArray.Vars.ElementKind.Bool -> sb.append(sample.boolValue(arr.varIds[i]))

                    FlatZincArray.Vars.ElementKind.Int -> sb.append(sample.intValue(arr.varIds[i]))

                    FlatZincArray.Vars.ElementKind.Float -> {
                        val b = requireNotNull(arr.floatBucketings)[i]
                        sb.append(sample.floatValue(b))
                    }
                }
            }
        }
    }
    sb.append("]")
    return sb.toString()
}
