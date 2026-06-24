package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Linearizer
import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.StructuralKey

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

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = Product(intMap[a], intMap[b], intMap[result])

    /** Multiplication is commutative, so the operands [a] and [b] are a set; [result] is positional. */
    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.PRODUCT) {
        int(result)
        sortedInts(intArrayOf(a, b))
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = intArrayOf(a, b, result)

    override fun asPropagator(): Propagator = ProductPropagator(a, b, result, boolVars, intVars)

    override fun asInvariant(): Invariant = ProductInvariant(a, b, result)

    override fun asLinearizer(): Linearizer = ProductLinearizer(a, b, result)
}
