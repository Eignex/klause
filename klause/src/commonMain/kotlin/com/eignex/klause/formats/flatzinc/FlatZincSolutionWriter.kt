package com.eignex.klause.formats.flatzinc

import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.narrowToNearestDoubles
import com.eignex.klause.lowering.FloatCoordinate
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.algebraicRealValue
import com.eignex.klause.solver.pipeline.publicationNarrowing
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.parseBigInt

/** Render one solved sample in FlatZinc output format. */
fun writeFlatZincSolution(program: FlatZincProgram, sample: Sample, outputObjective: Boolean = false): String =
    writeFlatZincSolution(program.forSample(sample), FlatZincValues(sample), outputObjective)

/** Render an open-theory witness in the FlatZinc solution protocol. */
fun writeFlatZincSolution(
    program: FlatZincProgram,
    assignment: OpenTheoryAssignment,
    outputObjective: Boolean = false,
): String = writeFlatZincSolution(program, assignment, outputObjective, publicationNarrowing())

internal fun writeFlatZincSolution(
    program: FlatZincProgram,
    assignment: OpenTheoryAssignment,
    outputObjective: Boolean,
    narrowing: ExecutionControl,
): String = writeFlatZincSolution(program, FlatZincValues(assignment, narrowing), outputObjective)

internal class FlatZincValues private constructor(
    val boolValue: (Int) -> Boolean,
    val intValue: (Int) -> String,
    private val approximateRealValue: (Int) -> Double,
    val exactRealValue: (Int) -> String?,
    private val displayed: DisplayedReals,
) {
    private constructor(sample: Sample, displayed: DisplayedReals) : this(
        { sample.boolValue(it) },
        { sample.exactIntValue(it).toString() },
        { sample.approximateRealValue(it) },
        { id ->
            sample.exactReals?.get(id)?.toString() ?: sample.algebraicReals?.get(id)?.let { value ->
                value.rational?.toString()
                    ?: "root(${value.rootIndex};[${value.definingCoefficients.joinToString(",")}])"
            }
        },
        displayed,
    )

    constructor(sample: Sample, narrowing: ExecutionControl = publicationNarrowing()) : this(
        sample,
        DisplayedReals(narrowing) { id -> sample.algebraicReals?.get(id)?.takeIf { sample.exactReals == null } },
    )

    constructor(assignment: OpenTheoryAssignment, narrowing: ExecutionControl = publicationNarrowing()) : this(
        assignment::boolValue,
        assignment::intValue,
        { id ->
            val parts = assignment.realValue(id).split('/')
            BigFraction.of(parseBigInt(parts[0]), if (parts.size == 1) BIG_ONE else parseBigInt(parts[1])).toDouble()
        },
        assignment::realValue,
        DisplayedReals(narrowing) { id -> assignment.algebraicRealValue(id) },
    )

    private val printed = HashSet<Int>()
    private var published: DoubleArray? = null

    fun checked(program: FlatZincProgram): FlatZincValues = apply {
        published = program.publishedReals(boolValue, intValue, ::nearest, displayed.narrowing)
    }

    // A real coordinate as a decimal literal: an algebraic one narrowed to its nearest binary64 value, and one past
    // binary64 as the largest finite value of its sign, which [rounding] notes.
    fun realValue(id: Int): Double {
        printed += id
        val value = published?.get(id) ?: nearest(id)
        return if (value.isInfinite()) (if (value > 0) Double.MAX_VALUE else -Double.MAX_VALUE) else value
    }

    // Why a printed [realValue] is not the coordinate's nearest binary64 value, or null when it is.
    fun rounding(id: Int): String? = when {
        id !in printed -> null
        published?.get(id)?.let { it != nearest(id) } == true -> "tolerance"
        displayed.undetermined(id) -> "undetermined"
        nearest(id).isInfinite() -> "overflow"
        else -> null
    }

    private fun nearest(id: Int): Double = displayed.value(id) ?: approximateRealValue(id)

    fun floatValue(b: FloatCoordinate): Double =
        realValue(b.varId)
}

// Algebraic coordinates narrowed on first display, all under one private allowance for the solution.
private class DisplayedReals(val narrowing: ExecutionControl, private val algebraic: (Int) -> AlgebraicReal?) {
    private val narrowed = HashMap<Int, AlgebraicReal>()

    private fun root(id: Int): AlgebraicReal? = narrowed[id] ?: algebraic(id)?.let { value ->
        narrowToNearestDoubles(listOf(value), narrowing).single().also { narrowed[id] = it }
    }

    fun value(id: Int): Double? = root(id)?.approximateDouble

    fun undetermined(id: Int): Boolean = root(id)?.let { it.nearestDouble == null } == true
}

private fun writeFlatZincSolution(program: FlatZincProgram, sample: FlatZincValues, outputObjective: Boolean): String {
    sample.checked(program)
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
    if (program.floatVarsByName.isNotEmpty()) {
        sb.append("% klause-float-semantics: flatzinc-binary64\n")
        for ((name, b) in program.floatVarsByName.entries.sortedBy { it.key }) {
            val representation = program.floatRepresentations[name]
            val kind = "exact"
            sb.append("% klause-float-component: $name = $kind")
            representation?.let { sb.append(" component=${it.component}") }
            sb.append('\n')
            sample.rounding(b.varId)?.let { sb.append("% klause-float-rounding: $name = $it\n") }
        }
    }
    if (program.floatVarsByName.isNotEmpty() &&
        program.floatVarsByName.values.all { sample.exactRealValue(it.varId) != null }
    ) {
        val prefix = "klause-exact"
        for ((name, id) in program.boolVarsByName) {
            sb.append("% $prefix: $name = ${sample.boolValue(id)};\n")
        }
        for ((name, id) in program.intVarsByName) {
            if (name !in program.floatVarsByName) {
                sb.append("% $prefix: $name = ${sample.intValue(id)};\n")
            }
        }
        for ((name, b) in program.floatVarsByName) {
            val value = sample.exactRealValue(b.varId)
            sb.append("% $prefix: $name = $value;\n")
        }
        if (outputObjective) {
            objectiveVarName(program.solve)?.let { name ->
                val b = program.floatVarsByName[name]
                val value = if (b != null) sample.exactRealValue(b.varId) else renderScalar(program, sample, name)
                sb.append("% $prefix: _objective = $value;\n")
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
                        val b = requireNotNull(arr.floatCoordinates)[i]
                        sb.append(sample.floatValue(b))
                    }
                }
            }
        }
    }
    sb.append("]")
    return sb.toString()
}
