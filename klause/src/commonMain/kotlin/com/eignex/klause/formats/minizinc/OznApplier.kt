package com.eignex.klause.formats.minizinc

import com.eignex.klause.formats.flatzinc.FlatZincArray
import com.eignex.klause.formats.flatzinc.FlatZincProgram
import com.eignex.klause.formats.flatzinc.SetVarLayout
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.IntArrayList

/** Renders solver samples through a parsed `.ozn` template. */
class OznApplier(oznSource: String) {
    private val items: List<OznItem> =
        OznParser(OznLexer(oznSource).tokenize()).parse()
    private val evaluator: OznEvaluator = OznEvaluator(items)

    /** Render one solution block ending with `----------\n`. */
    fun render(program: FlatZincProgram, sample: Sample): String {
        val bindings = extractBindings(program, sample)
        return evaluator.render(bindings)
    }

    private fun extractBindings(program: FlatZincProgram, sample: Sample): Map<String, OznValue> {
        val out = HashMap<String, OznValue>()
        for ((name, id) in program.boolVarsByName) {
            out[name] = OznValue.BoolV(sample.bools[id])
        }
        // Float-backed int vars are rendered under their float names.
        for ((name, id) in program.intVarsByName) {
            if (program.floatVarsByName.containsKey(name)) continue
            out[name] = OznValue.IntV(sample.ints[id])
        }
        for ((name, b) in program.floatVarsByName) {
            out[name] = OznValue.FloatV(b.valueOf(sample.ints[b.varId].toInt()))
        }
        for ((name, layout) in program.setVarsByName) {
            out[name] = setBindingFrom(layout, sample)
        }
        for ((name, arr) in program.arraysByName) {
            out[name] = arrayBindingFrom(arr, sample)
        }
        return out
    }

    private fun setBindingFrom(layout: SetVarLayout, sample: Sample): OznValue.SetV {
        val present = IntArrayList()
        for (i in layout.elements.indices) {
            if (sample.bools[layout.indicatorBoolIds[i]]) present.add(layout.elements[i])
        }
        return OznValue.SetV(present.toIntArray())
    }

    private fun arrayBindingFrom(arr: FlatZincArray, sample: Sample): OznValue = when (arr) {
        is FlatZincArray.BoolParam -> OznValue.ArrayV(arr.values.map { OznValue.BoolV(it) })

        is FlatZincArray.IntParam -> OznValue.ArrayV(arr.values.map { OznValue.IntV(it.toLong()) })

        is FlatZincArray.FloatParam -> OznValue.ArrayV(arr.values.map { OznValue.FloatV(it) })

        is FlatZincArray.IntSetParam -> OznValue.ArrayV(arr.values.map { OznValue.SetV(it) })

        is FlatZincArray.SetVars -> OznValue.ArrayV(arr.layouts.map { setBindingFrom(it, sample) })

        is FlatZincArray.Vars -> OznValue.ArrayV(
            arr.varIds.mapIndexed { idx, v ->
                when (arr.elementKind) {
                    FlatZincArray.Vars.ElementKind.Bool -> OznValue.BoolV(sample.bools[v])

                    FlatZincArray.Vars.ElementKind.Int -> OznValue.IntV(sample.ints[v])

                    FlatZincArray.Vars.ElementKind.Float -> {
                        val b = requireNotNull(arr.floatBucketings)[idx]
                        OznValue.FloatV(b.valueOf(sample.ints[v].toInt()))
                    }
                }
            },
        )
    }
}
