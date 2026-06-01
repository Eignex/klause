package com.eignex.klause.formats.minizinc

import com.eignex.klause.formats.flatzinc.FlatZincArray
import com.eignex.klause.formats.flatzinc.FlatZincProgram
import com.eignex.klause.formats.flatzinc.SetVarLayout
import com.eignex.klause.solver.Sample

/**
 * Top-level facade for `.ozn` output rendering. Parses the `.ozn` source once at
 * construction; per solution, extracts the variable bindings from a [Sample] (channeled
 * through a [FlatZincProgram]'s var-name maps) and feeds them to the [OznEvaluator].
 *
 * This is the klause-side analogue of MiniZinc's `solns2out`: take the .ozn template,
 * a solver-produced solution, and produce the human-readable string. Pair with
 * [klause-fzn-cli]'s `--ozn FILE` option to drop the `needsSolns2Out: true` dependency
 * on MiniZinc at runtime.
 */
class OznApplier(oznSource: String) {
    private val items: List<OznItem> =
        OznParser(OznLexer(oznSource).tokenize()).parse()
    private val evaluator: OznEvaluator = OznEvaluator(items)

    /** Render one solution. The output ends with `----------\n` per MZN convention. */
    fun render(program: FlatZincProgram, sample: Sample): String {
        val bindings = extractBindings(program, sample)
        return evaluator.render(bindings)
    }

    private fun extractBindings(program: FlatZincProgram, sample: Sample): Map<String, OznValue> {
        val out = HashMap<String, OznValue>()
        // Scalar bools.
        for ((name, id) in program.boolVarsByName) {
            out[name] = OznValue.BoolV(sample.bools[id])
        }
        // Scalar ints (skip ones that are float buckets — those are written under the
        // float name).
        for ((name, id) in program.intVarsByName) {
            if (program.floatVarsByName.containsKey(name)) continue
            out[name] = OznValue.IntV(sample.ints[id].toLong())
        }
        // Floats.
        for ((name, b) in program.floatVarsByName) {
            out[name] = OznValue.FloatV(b.valueOf(sample.ints[b.varId]))
        }
        // Sets.
        for ((name, layout) in program.setVarsByName) {
            out[name] = setBindingFrom(layout, sample)
        }
        // Arrays (vars + params).
        for ((name, arr) in program.arraysByName) {
            out[name] = arrayBindingFrom(arr, sample)
        }
        return out
    }

    private fun setBindingFrom(layout: SetVarLayout, sample: Sample): OznValue.SetV {
        val present = ArrayList<Int>()
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
                    FlatZincArray.Vars.ElementKind.Int -> OznValue.IntV(sample.ints[v].toLong())
                    FlatZincArray.Vars.ElementKind.Float -> {
                        val b = arr.floatBucketings!![idx]
                        OznValue.FloatV(b.valueOf(sample.ints[v]))
                    }
                }
            }
        )
    }
}
