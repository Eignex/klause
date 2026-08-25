package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.SpanIntVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.hashRemappedKey
import com.eignex.klause.ir.materializeKey
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.lp.Contribution
import com.eignex.klause.lp.HullFamily
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorReduction
import com.eignex.klause.solver.IntDomain

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
            else -> FactorReduction.Unchanged
        }
    }

    private fun fixOperand(c: Long, other: Int): FactorReduction = if (c == 0L) {
        FactorReduction.Rewrite(listOf(Linear(longArrayOf(1L), intArrayOf(result), LinearOp.EQ, 0L)))
    } else {
        FactorReduction.Rewrite(listOf(Linear(longArrayOf(1L, -c), intArrayOf(result, other), LinearOp.EQ, 0L)))
    }

    /** Multiplication is commutative, so the operands [a] and [b] are a set; [result] is positional. */
    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.PRODUCT, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int = hashRemappedKey(FactorKind.PRODUCT, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.intVar(result)
        sink.sortedIntVars(intArrayOf(a, b))
    }

    override val variables: VarList = SpanIntVars(intArrayOf(a, b, result))

    override fun asPropagator(): Propagator = ProductPropagator(a, b, result, boolVars, intVars)

    override fun asInvariant(): Invariant = ProductInvariant(a, b, result)

    override val hullFamily: HullFamily = HullFamily.PRODUCT

    /**
     * LP relaxation — the four McCormick envelope inequalities `(a−aL)(b−bL) ≥ 0`, `(a−aH)(b−bH) ≥ 0`,
     * `(aH−a)(b−bL) ≥ 0`, `(a−aL)(bH−b) ≥ 0`, each expanded to a linear row in `result, a, b`. Bounds are
     * the declared domains, so the rows are global and the relaxation never cuts a feasible point. For
     * `a = b` (a square) the `a` and `b` coefficients coalesce into the secant/tangent relaxation. HULL.
     */
    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        if (!builder.hullEnabled()) return
        val aDom = builder.declaredDomain(a)
        val bDom = builder.declaredDomain(b)
        val aL = aDom.min
        val aH = aDom.max
        val bL = bDom.min
        val bH = bDom.max
        val resCol = builder.intColumn(result)
        val aCol = builder.intColumn(a)
        val bCol = builder.intColumn(b)

        // Each row is `result + ca·a + cb·b ⟨op⟩ rhs`; coefficients coalesce when a and b coincide.
        fun mcCormick(ca: Long, cb: Long, op: LinearOp, rhs: Long) =
            builder.row(intArrayOf(resCol, aCol, bCol), longArrayOf(1L, ca, cb), op, rhs, Contribution.HULL)

        mcCormick(-bL, -aL, LinearOp.GE, -(aL * bL))
        mcCormick(-bH, -aH, LinearOp.GE, -(aH * bH))
        mcCormick(-bL, -aH, LinearOp.LE, -(aH * bL))
        mcCormick(-bH, -aL, LinearOp.LE, -(aL * bH))
    }
}
