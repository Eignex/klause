package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.MixedVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.materializeKey
import com.eignex.klause.propagation.NoPropagator

/**
 * `result = intOperand · realOperand`, where [intOperand] is an integer search variable and
 * [realOperand] / [result] are LP-only real columns. Its CP projection is [NoPropagator]; local search
 * scores it heuristically, and the LP search leaf and exact source witness checks decide feasibility.
 *
 * Its LP projection is the exact equality `result − k·realOperand = 0` once [intOperand] is fixed to `k`,
 * as at every search leaf. Otherwise it is the McCormick envelope over `[lo, hi] × [realOperandLo,
 * realOperandHi]`, emitted only when both integer bounds are stated and the real bounds are finite.
 *
 * McCormick, "Computability of global solutions to factorable nonconvex programs: Part I — Convex
 * underestimating problems", Math. Programming 10, 1976, https://doi.org/10.1007/BF01580665.
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

    override val variables: VarList =
        MixedVars(spanInts = intArrayOf(intOperand), reals = intArrayOf(realOperand, result))

    override fun remap(mapping: VarRemap): Factor = RealProduct(
        mapping.int(intOperand),
        mapping.real(realOperand),
        mapping.real(result),
        realOperandLo,
        realOperandHi,
    )

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.REAL_PRODUCT, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.intVar(intOperand)
        // Two products over the same integer operand but different real operands, results or envelopes
        // must not collide into a false symmetry.
        sink.realVar(realOperand)
        sink.realVar(result)
        sink.long(realOperandLo.toRawBits())
        sink.long(realOperandHi.toRawBits())
    }
}
