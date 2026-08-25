package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.remapVars
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.lp.Contribution
import com.eignex.klause.lp.HullFamily
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.FactorReduction
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.KeySink
import com.eignex.klause.solver.SpanIntVars
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.VarList
import com.eignex.klause.solver.hashRemappedKey
import com.eignex.klause.solver.materializeKey

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

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        ArrayMinMax(intMap[result], xs.remapVars(intMap), max)

    // A single operand makes min/max degenerate to the plain equality `result = xs[0]`; the dedicated
    // propagator and relaxation carry no strength over the equality once the array is a singleton.
    @Suppress("UNUSED_PARAMETER")
    override fun structuralReduce(domains: Array<IntDomain>): FactorReduction = if (xs.size == 1) {
        FactorReduction.Rewrite(listOf(Linear(intArrayOf(1, -1), intArrayOf(result, xs[0]), LinearOp.EQ, 0)))
    } else {
        FactorReduction.Unchanged
    }

    /** [max] (min vs max) and the output [result] are positional; the operands [xs] are a set
     *  (min/max is symmetric in them), so they are sorted. */
    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.ARRAY_MIN_MAX, ::buildKey)

    override fun remapStructuralHash(boolMap: IntArray, intMap: IntArray): Int =
        hashRemappedKey(FactorKind.ARRAY_MIN_MAX, boolMap, intMap, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.bool(max)
        sink.intVar(result)
        sink.sortedIntVars(xs)
    }

    override val variables: VarList = SpanIntVars(xs + intArrayOf(result))

    override val extendsObjectiveCone: Boolean = true

    override fun asPropagator(): Propagator = ArrayMinMaxPropagator(result, xs, max, boolVars, intVars)

    override fun asInvariant(): Invariant = ArrayMinMaxInvariant(result, xs, max)

    override val hullFamily: HullFamily = HullFamily.ARRAY_MIN_MAX

    /**
     * LP relaxation: the always-emitted envelope (`result ≥ xs[i]` for max, `result ≤ xs[i]` for min) as
     * CORE rows, plus the tight convex-hull face as HULL — one-hot selectors `z_i` with `Σ z_i = 1` and a
     * per-operand big-M row forcing `result = xs[i]` when `z_i = 1`. Each `M_i` comes from the declared
     * domains, so it bounds `|result − xs[i]|` globally and the rows hold at every integer solution.
     */
    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        val resultCol = builder.intColumn(result)
        val op = if (max) LinearOp.GE else LinearOp.LE
        for (x in xs) {
            builder.row(intArrayOf(resultCol, builder.intColumn(x)), longArrayOf(1L, -1L), op, 0L)
        }
        if (builder.hullEnabled()) tightFace(builder, resultCol)
    }

    private fun tightFace(builder: RelaxationBuilder, resultCol: Int) {
        val n = xs.size
        if (n == 0) return
        val sel = IntArray(n) { builder.auxColumn(0L, 1L) } // free binaries z_i ∈ [0,1]
        builder.row(sel, LongArray(n) { 1L }, LinearOp.EQ, 1L, Contribution.HULL) // Σ z_i = 1
        val rDom = builder.declaredDomain(result)
        for (i in 0 until n) {
            val x = xs[i]
            val xDom = builder.declaredDomain(x)
            val m = maxOf(rDom.max, xDom.max) - minOf(rDom.min, xDom.min)
            if (m < 0L) continue
            val xCol = builder.intColumn(x)
            val z = sel[i]
            // max: result − xs[i] + M·z_i ≤ M.  min: xs[i] − result + M·z_i ≤ M.
            val cols = if (max) intArrayOf(resultCol, xCol, z) else intArrayOf(xCol, resultCol, z)
            builder.row(cols, longArrayOf(1L, -1L, m), LinearOp.LE, m, Contribution.HULL)
        }
    }
}
