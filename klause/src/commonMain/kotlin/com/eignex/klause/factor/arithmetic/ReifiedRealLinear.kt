package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.MixedVars
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.RealConsts
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.materializeKey

/**
 * A reified real linear atom `aux ⟺ (Σ intCoeffs·vars + Σ realCoeffs·realVars ⟨op⟩ bound)`. [aux] is an
 * ordinary search Boolean; the finite CP projection is inert and local search scores the atom heuristically.
 *
 * The LP relaxation emits the row while [aux] is pinned true, its strict complement while pinned false, and
 * nothing while unpinned; every leaf pins every Boolean, so leaf feasibility enforces the equivalence. Each
 * emitted row carries the activating literal, so an infeasibility certificate that uses it cites the literal.
 *
 * [op] is [LinearOp.LE] or [LinearOp.GE]; an equality atom is two of these conjoined by the caller, since its
 * complement is a disjunction.
 */
class ReifiedRealLinear(
    /** The reifying Boolean variable. */
    val aux: Int,
    /** Integer CP variable ids of the row's integer terms. */
    val vars: IntArray,
    /** Double coefficient of each integer term (index-aligned with [vars]). */
    val intCoeffs: DoubleArray,
    /** LP-only real variable ids of the row's continuous terms, possibly empty for a real-valued int row. */
    val realVars: IntArray,
    /** Double coefficient of each real term (index-aligned with [realVars]). */
    val realCoeffs: DoubleArray,
    /** [LinearOp.LE] or [LinearOp.GE]. */
    val op: LinearOp,
    /** Right-hand side. */
    val bound: Double,
    /** Strict inequality (`<` / `>`); the complement of a non-strict atom is strict and vice versa. */
    val strict: Boolean = false,
) : Factor {

    init {
        require(op == LinearOp.LE || op == LinearOp.GE) { "reified real atom must be an inequality" }
        require(vars.size == intCoeffs.size) { "int vars/coeffs length mismatch" }
        require(realVars.size == realCoeffs.size) { "real vars/coeffs length mismatch" }
    }

    override val linearForm: LinearForm = LinearForm.Conjunction(
        listOf(
            LinearRow.ofColumns(
                vars,
                realVars,
                RealConstants(RealConsts(intCoeffs), RealConsts(realCoeffs), bound, strict),
                op,
                aux,
            ),
        ),
    )
    override val variables: VarList =
        MixedVars(boundInts = vars, boolVars = intArrayOf(aux), reals = realVars)

    override fun remap(mapping: VarRemap): Factor = ReifiedRealLinear(
        mapping.bool(aux),
        mapping.ints(vars),
        intCoeffs,
        mapping.reals(realVars),
        realCoeffs,
        op,
        bound,
        strict,
    )

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.REIFIED_REAL_LINEAR, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.enum(op)
        sink.long(if (strict) 1L else 0L)
        sink.long(bound.toRawBits())
        sink.boolVar(aux)
        for (i in vars.indices) {
            sink.intVar(vars[i])
            sink.long(intCoeffs[i].toRawBits())
        }
        for (j in realVars.indices) {
            sink.realVar(realVars[j])
            sink.long(realCoeffs[j].toRawBits())
        }
    }
}
