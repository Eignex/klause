package com.eignex.klause.formats.flatzinc

import com.eignex.klause.formats.minizinc.*
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment

/** Renders solver samples through a parsed `.ozn` template. */
class OznApplier(oznSource: String) {
    private val items: List<OznItem> =
        OznParser(OznLexer(oznSource).tokenize()).parse()
    private val evaluator: OznEvaluator = OznEvaluator(items)

    /** Render one solution block ending with `----------\n`. */
    fun render(program: FlatZincProgram, sample: Sample): String {
        return renderValues(program, FlatZincValues(sample))
    }

    /** Render an open-theory witness through the output model. */
    fun render(program: FlatZincProgram, assignment: OpenTheoryAssignment): String =
        renderValues(program, FlatZincValues(assignment))

    private fun renderValues(program: FlatZincProgram, sample: FlatZincValues): String {
        val rendered = evaluator.render(extractBindings(program, sample))
        val coordinates = writeFlatZincExactCoordinates(program, sample, outputObjective = true)
        return rendered.removeSuffix("----------\n") + coordinates + "----------\n"
    }

    private fun extractBindings(program: FlatZincProgram, sample: FlatZincValues): Map<String, OznValue> {
        val out = HashMap<String, OznValue>()
        for ((name, id) in program.boolVarsByName) {
            out[name] = OznValue.BoolV(sample.boolValue(id))
        }
        // Float-backed int vars are rendered under their float names.
        for ((name, id) in program.intVarsByName) {
            if (program.floatVarsByName.containsKey(name)) continue
            out[name] = intBindingFrom(sample.intValue(id))
        }
        for ((name, b) in program.floatVarsByName) {
            val value = sample.floatValue(b)
            out[name] = OznValue.FloatV(value)
        }
        for ((name, layout) in program.setVarsByName) {
            out[name] = setBindingFrom(layout, sample)
        }
        for ((name, arr) in program.arraysByName) {
            out[name] = arrayBindingFrom(arr, sample)
        }
        return out
    }

    private fun intBindingFrom(value: String): OznValue.IntV = OznValue.IntV(
        value.toLongOrNull() ?: throw OznParseException("integer binding outside signed 64-bit range: $value"),
    )

    private fun setBindingFrom(layout: SetVarLayout, sample: FlatZincValues): OznValue.SetV {
        val present = ArrayList<Long>()
        for (i in layout.elements.indices) {
            if (sample.boolValue(layout.indicatorBoolIds[i])) present.add(layout.elements[i].toLong())
        }
        return OznValue.SetV(present.toLongArray())
    }

    private fun arrayBindingFrom(arr: FlatZincArray, sample: FlatZincValues): OznValue = when (arr) {
        is FlatZincArray.BoolParam -> OznValue.ArrayV(arr.values.map { OznValue.BoolV(it) })

        is FlatZincArray.IntParam -> OznValue.ArrayV(arr.values.map { OznValue.IntV(it) })

        is FlatZincArray.FloatParam -> OznValue.ArrayV(arr.values.map { OznValue.FloatV(it) })

        is FlatZincArray.IntSetParam -> OznValue.ArrayV(
            arr.values.map { values -> OznValue.SetV(LongArray(values.size) { values[it].toLong() }) },
        )

        is FlatZincArray.SetVars -> OznValue.ArrayV(arr.layouts.map { setBindingFrom(it, sample) })

        is FlatZincArray.Vars -> OznValue.ArrayV(
            arr.varIds.mapIndexed { idx, v ->
                when (arr.elementKind) {
                    FlatZincArray.Vars.ElementKind.Bool -> OznValue.BoolV(sample.boolValue(v))

                    FlatZincArray.Vars.ElementKind.Int -> intBindingFrom(sample.intValue(v))

                    FlatZincArray.Vars.ElementKind.Float -> {
                        val b = requireNotNull(arr.floatBucketings)[idx]
                        OznValue.FloatV(sample.floatValue(b))
                    }
                }
            },
        )
    }
}
