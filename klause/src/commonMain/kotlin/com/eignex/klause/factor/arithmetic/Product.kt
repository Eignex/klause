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
 * `a * b = result`. Operates on signed integer domains (any min/max). The bit-blaster lowers
 * via an unsigned shift-and-add multiplier on absolute values, then conditionally negates the
 * product based on the operand sign bits.
 *
 * No payload: the product is recomputed in O(1) from the current assignment on each query.
 */
class Product(
    /** First factor variable id. */
    val a: Int,
    /** Second factor variable id. */
    val b: Int,
    /** Result variable id (`result = a * b`). */
    val result: Int,
) : Factor {

    override fun remap(mapping: VarRemap): Factor = Product(mapping.int(a), mapping.int(b), mapping.int(result))

    // A fixed operand collapses the nonlinear product to a linear equality the LP/affine machinery can
    // then exploit: with `a = c` the constraint is `result = c·b`, i.e. `result − c·b = 0` (and just
    // `result = 0` when `c = 0`). Solution-set exact given the fixing domain; symmetric in `a` and `b`.
    override fun structuralReduce(domains: Array<IntDomain>): FactorReduction {
        val aDom = domains[a]
        val bDom = domains[b]
        return when {
            aDom.min == aDom.max -> fixOperand(aDom.min, b)
            bDom.min == bDom.max -> fixOperand(bDom.min, a)
            else -> Unchanged
        }
    }

    private fun fixOperand(c: Long, other: Int): FactorReduction = if (c == 0L) {
        Rewrite(listOf(Linear(longArrayOf(1L), intArrayOf(result), LinearOp.EQ, 0L)))
    } else {
        Rewrite(listOf(Linear(longArrayOf(1L, -c), intArrayOf(result, other), LinearOp.EQ, 0L)))
    }

    /** Multiplication is commutative, so the operands [a] and [b] are a set; [result] is positional. */
    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.PRODUCT, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int = hashRemappedKey(FactorKind.PRODUCT, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.intVar(result)
        sink.sortedIntVars(intArrayOf(a, b))
    }

    override val variables: VarList = SpanIntVars(intArrayOf(a, b, result))
}
