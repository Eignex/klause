package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.MixedVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.materializeKey

/**
 * A reified real linear atom `aux ⟺ (Σ intCoeffs·vars + Σ realCoeffs·realVars ⟨op⟩ bound)` — the
 * real-atom half of boolean structure over linear real arithmetic. [aux] is an ordinary CP Boolean
 * the search branches on; the row is LP-only, so it has no CP propagator or local-search invariant and
 * its two directions are enforced by the LP relaxation:
 *
 *  - The LP relaxation consults the build's live pin of [aux]: pinned true emits the atom's row, pinned
 *    false emits its exact complement (`¬(a ≤ b) ⟺ a > b`, strictness flipping through the
 *    delta-rational machinery), unpinned emits nothing (a sound weakening — every leaf pins every
 *    Boolean, so leaf feasibility enforces the equivalence and search stays complete).
 *  - Each emitted row carries the activating literal as its validity premise, so an LP infeasibility
 *    certificate that leans on it cites the literal in the learned clause — the Farkas-derived
 *    theory lemma over the real atoms.
 *
 * [op] is [LinearOp.LE] or [LinearOp.GE]; an equality atom is two of these conjoined by the caller
 * (its complement is a disjunction, which a single row cannot express).
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

    override val exactTheoryOwnable: Boolean get() = bound.isFinite() &&
        intCoeffs.all(Double::isFinite) &&
        realCoeffs.all(Double::isFinite) &&
        intCoeffs.all(::isExactInteger)

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
