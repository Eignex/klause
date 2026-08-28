package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.FactorReduction
import com.eignex.klause.ir.FactorReduction.Rewrite
import com.eignex.klause.ir.FactorReduction.Unchanged
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.SpanIntVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.hashRemappedKey
import com.eignex.klause.ir.materializeKey

/**
 * `result = max(xs)` or `result = min(xs)` — covers the FlatZinc `array_int_maximum(result,
 * xs)` / `array_int_minimum(result, xs)` builtins. Mode selected by [max].
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

    override fun remap(mapping: VarRemap): Factor = ArrayMinMax(mapping.int(result), mapping.ints(xs), max)

    // A single operand makes min/max degenerate to the plain equality `result = xs[0]`; the dedicated
    // propagator and relaxation carry no strength over the equality once the array is a singleton.
    @Suppress("UNUSED_PARAMETER")
    override fun structuralReduce(domains: Array<IntDomain>): FactorReduction = if (xs.size == 1) {
        Rewrite(listOf(Linear(intArrayOf(1, -1), intArrayOf(result, xs[0]), LinearOp.EQ, 0)))
    } else {
        Unchanged
    }

    /** [max] (min vs max) and the output [result] are positional; the operands [xs] are a set
     *  (min/max is symmetric in them), so they are sorted. */
    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.ARRAY_MIN_MAX, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int =
        hashRemappedKey(FactorKind.ARRAY_MIN_MAX, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.bool(max)
        sink.intVar(result)
        sink.sortedIntVars(xs)
    }

    override val variables: VarList = SpanIntVars(xs + intArrayOf(result))
}
