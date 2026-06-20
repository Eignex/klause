package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.factor.remapVars
import com.eignex.klause.solver.propagation.IntEvent

/**
 * `result = max(xs)` or `result = min(xs)` — covers MiniZinc's `array_int_maximum(result,
 * xs)` / `array_int_minimum(result, xs)`. Mode selected by [max].
 *
 * Propagation tightens [result] against the bound of [xs] and pushes back from [result]
 * to every `xs(i)` (for max: every `xs(i).max <= result.max`; for min the dual). LS keeps
 * a payload holding the index of the current best operand and its value, with a fallback
 * full scan when the best slot changes.
 */
class ArrayMinMax(override val result: Int, override val xs: IntArray, override val max: Boolean) :
    Factor,
    ArrayMinMaxPropagator,
    ArrayMinMaxInvariant {

    init {
        require(xs.isNotEmpty()) { "ArrayMinMax needs at least one operand" }
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        ArrayMinMax(intMap[result], xs.remapVars(intMap), max)

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs + intArrayOf(result)

    /**
     * Advisor subscription (#623): `propagate` tightens `result` against the operands' bounds and
     * pushes `result`'s bound back onto every operand — reading only `min`/`max`. An interior hole
     * never moves a `min`/`max`, so the factor subscribes to [IntEvent.LB_RAISED] /
     * [IntEvent.UB_LOWERED] on each variable and skips interior `VALUE_REMOVED` wakes. A repeated
     * operand is subscribed once.
     */
    override val initialIntEventWatches: IntArray = IntEvent.boundEventWatches(intVars)
}
