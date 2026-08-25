package com.eignex.klause.factor.arithmetic

import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.NoInvariant
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.propagation.NoPropagator
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.KeySink
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.MixedVars
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.VarList
import com.eignex.klause.solver.materializeKey

/**
 * A reified real linear atom `aux ⟺ (Σ intCoeffs·vars + Σ realCoeffs·realVars ⟨op⟩ bound)` — the
 * real-atom half of boolean structure over linear real arithmetic. [aux] is an ordinary CP Boolean
 * the search branches on; the row itself carries a continuous term, so it has no CP propagator or
 * local-search invariant (like a real-bearing [Linear]) and its two directions are enforced by the
 * LP relaxation:
 *
 *  - [linearize] consults the build's live pin of [aux]: pinned true emits the atom's row, pinned
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
    /** LP-only real variable ids of the row's continuous terms. */
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
        require(realVars.isNotEmpty()) { "a real atom needs a continuous term" }
    }

    override val variables: VarList =
        MixedVars(boundInts = vars, lits = intArrayOf(aux), reals = realVars)

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = ReifiedRealLinear(
        boolMap[aux],
        IntArray(vars.size) { intMap[vars[it]] },
        intCoeffs,
        realVars,
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
            sink.long(realVars[j].toLong())
            sink.long(realCoeffs[j].toRawBits())
        }
    }

    override fun asPropagator(): Propagator = NoPropagator

    override fun asInvariant(): Invariant = NoInvariant

    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        val pin = builder.liveBool(aux) ?: return
        val cols = IntArray(vars.size + realVars.size)
        val coeffs = DoubleArray(cols.size)
        for (i in vars.indices) {
            cols[i] = builder.intColumn(vars[i])
            coeffs[i] = intCoeffs[i]
        }
        for (j in realVars.indices) {
            val c = builder.realColumn(realVars[j])
            if (c < 0) return // builder has no real-column backing (e.g. a presolve fake)
            cols[vars.size + j] = c
            coeffs[vars.size + j] = realCoeffs[j]
        }
        val premise = intArrayOf(Lit.make(aux, pin))
        if (pin) {
            builder.realRow(cols, coeffs, op, bound, strict, premise)
        } else {
            // Exact complement over the same terms: ¬(a ≤ b) ⟺ a > b, ¬(a < b) ⟺ a ≥ b (mirrored for ≥).
            val flipped = if (op == LinearOp.LE) LinearOp.GE else LinearOp.LE
            builder.realRow(cols, coeffs, flipped, bound, !strict, premise)
        }
    }
}
