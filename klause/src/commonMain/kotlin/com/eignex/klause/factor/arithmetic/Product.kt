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
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.unaryMinus

/**
 * `a * b = result` over signed integer domains. Repeated operands and result aliases retain
 * their source roles in finite propagation, local-search scoring and coordinate remapping.
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

    // A fixed operand `c` collapses the product to the linear equality `result = c·b`, which the LP and
    // affine machinery can exploit.
    override fun structuralReduce(domains: Array<IntDomain>): FactorReduction {
        val aDom = domains[a]
        val bDom = domains[b]
        return when {
            aDom.min == aDom.max -> fixOperand(aDom.min, b)
            bDom.min == bDom.max -> fixOperand(bDom.min, a)
            else -> Unchanged
        }
    }

    private fun fixOperand(c: Long, other: Int): FactorReduction = when (c) {
        0L -> Rewrite(listOf(Linear(longArrayOf(1L), intArrayOf(result), LinearOp.EQ, 0L)))
        Long.MIN_VALUE -> {
            val coefficient = -bigIntOf(c)
            val row = if (other == result) {
                Linear(intArrayOf(result), arrayOf(BIG_ONE + coefficient), LinearOp.EQ, BIG_ZERO)
            } else {
                Linear(intArrayOf(result, other), arrayOf(BIG_ONE, coefficient), LinearOp.EQ, BIG_ZERO)
            }
            Rewrite(listOf(row))
        }
        else -> Rewrite(listOf(Linear(longArrayOf(1L, -c), intArrayOf(result, other), LinearOp.EQ, 0L)))
    }

    /** Multiplication is commutative, so the operands [a] and [b] are a multiset; [result] is positional. */
    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.PRODUCT, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int = hashRemappedKey(FactorKind.PRODUCT, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.intVar(result)
        sink.sortedIntVars(intArrayOf(a, b))
    }

    override val variables: VarList = SpanIntVars(intArrayOf(a, b, result))
}
