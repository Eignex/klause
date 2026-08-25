package com.eignex.klause.factor.arithmetic

import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.NoInvariant
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.propagation.NoPropagator
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.KeySink
import com.eignex.klause.solver.SpanIntVars
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.VarList
import com.eignex.klause.solver.materializeKey

/**
 * `result = intOperand · realOperand`: a mixed integer·continuous product where
 * [intOperand] is an integer CP search variable and [realOperand] / [result] are LP-only continuous
 * (real) columns — ids in the problem's real-variable namespace, absent from CP search. It carries no
 * CP or local-search semantics ([NoPropagator] / [NoInvariant]); its feasibility is enforced entirely by
 * the LP relaxation and the search leaf, like a real-bearing [Linear] row.
 *
 * [linearize] adapts to how tightly [intOperand] is pinned in the build's domains:
 *  - **Fixed** (`lo == hi == k`, always true at a search leaf, where every integer variable is a
 *    single-point domain) the product is the exact linear equality `result − k·realOperand = 0`. So the
 *    leaf relaxation models the product **exactly**, and the leaf feasibility verdict over it is sound.
 *  - **Free** (a wider domain, e.g. the root/persistent build) the four McCormick envelope inequalities
 *    over `[lo, hi] × [realOperandLo, realOperandHi]` — a valid outer relaxation that tightens the node
 *    bound and can prove infeasibility, but never certifies the product exactly. Emitted only when the
 *    real operand's bounds are finite (an unbounded envelope has no valid linear form; the product is
 *    then simply absent from the relaxation there, a sound weakening).
 */
class RealProduct(
    /** Integer CP operand variable id. */
    val intOperand: Int,
    /** Real (LP-only) operand variable id, in the problem's real-variable namespace. */
    val realOperand: Int,
    /** Real (LP-only) result variable id (`result = intOperand · realOperand`). */
    val result: Int,
    /** Declared lower bound of [realOperand] (the McCormick envelope's `yL`). */
    val realOperandLo: Double,
    /** Declared upper bound of [realOperand] (the McCormick envelope's `yH`). */
    val realOperandHi: Double,
) : Factor {

    override val variables: VarList = SpanIntVars(intArrayOf(intOperand))

    // Only the integer operand lives in the CP id space; the real operand and result ids are in the
    // separate real-variable namespace, which presolve carries through unremapped.
    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        RealProduct(intMap[intOperand], realOperand, result, realOperandLo, realOperandHi)

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.REAL_PRODUCT, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.intVar(intOperand)
        // Real ids and bounds are constants (not CP ports), keyed so two products over the same integer
        // operand but different real operands / results / envelopes do not collide into a false symmetry.
        sink.long(realOperand.toLong())
        sink.long(result.toLong())
        sink.long(realOperandLo.toRawBits())
        sink.long(realOperandHi.toRawBits())
    }

    override fun asPropagator(): Propagator = NoPropagator

    override fun asInvariant(): Invariant = NoInvariant

    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        val resCol = builder.realColumn(result)
        val opCol = builder.realColumn(realOperand)
        if (resCol < 0 || opCol < 0) return // builder has no real-column backing (e.g. a presolve fake)
        val dom = builder.liveDomain(intOperand)
        val lo = dom.min
        val hi = dom.max
        if (lo == hi) {
            // result = lo·realOperand exactly (the operand is a constant in this build).
            builder.realRow(intArrayOf(resCol, opCol), doubleArrayOf(1.0, -lo.toDouble()), LinearOp.EQ, 0.0)
            return
        }
        if (!realOperandLo.isFinite() || !realOperandHi.isFinite()) return

        // McCormick envelope of `w = x·y` with `x = intOperand ∈ [lo, hi]`, `y = realOperand ∈ [yL, yH]`,
        // `w = result`. Each row is `w + cy·y + cx·x ⟨op⟩ rhs` over columns `(result, realOperand, intOperand)`.
        val nCol = builder.intColumn(intOperand)
        val cols = intArrayOf(resCol, opCol, nCol)
        val xL = lo.toDouble()
        val xH = hi.toDouble()
        val yL = realOperandLo
        val yH = realOperandHi
        // w ≥ xL·y + yL·x − xL·yL  and  w ≥ xH·y + yH·x − xH·yH
        builder.realRow(cols, doubleArrayOf(1.0, -xL, -yL), LinearOp.GE, -xL * yL)
        builder.realRow(cols, doubleArrayOf(1.0, -xH, -yH), LinearOp.GE, -xH * yH)
        // w ≤ xH·y + yL·x − xH·yL  and  w ≤ xL·y + yH·x − xL·yH
        builder.realRow(cols, doubleArrayOf(1.0, -xH, -yL), LinearOp.LE, -xH * yL)
        builder.realRow(cols, doubleArrayOf(1.0, -xL, -yH), LinearOp.LE, -xL * yH)
    }
}
