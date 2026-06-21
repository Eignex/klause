package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.remapVars

/**
 * `result = max(xs)` or `result = min(xs)` — covers MiniZinc's `array_int_maximum(result,
 * xs)` / `array_int_minimum(result, xs)`. Mode selected by [max].
 *
 * Propagation tightens [result] against the bound of [xs] and pushes back from [result]
 * to every `xs(i)` (for max: every `xs(i).max <= result.max`; for min the dual). LS keeps
 * a payload holding the index of the current best operand and its value, with a fallback
 * full scan when the best slot changes.
 */
class ArrayMinMax(val result: Int, val xs: IntArray, val max: Boolean) : Factor {

    init {
        require(xs.isNotEmpty()) { "ArrayMinMax needs at least one operand" }
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        ArrayMinMax(intMap[result], xs.remapVars(intMap), max)

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs + intArrayOf(result)

    override fun asPropagator(): Propagator = ArrayMinMaxPropagator(result, xs, max, boolVars, intVars)

    override fun asInvariant(): Invariant = ArrayMinMaxInvariant(result, xs, max, boolVars, intVars)
}
