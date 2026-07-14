package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.bool.internals.CoalescedTerms
import com.eignex.klause.factor.bool.internals.coalesceLinearTerms
import com.eignex.klause.factor.remapVars
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.lp.LinearRow
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.KeySink
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.hashRemappedKey
import com.eignex.klause.solver.materializeKey
import com.eignex.klause.util.EmptyIntArray

/**
 * `Σ coeffs(i) * intVars(i) ⟨op⟩ bound`. Payload at `intPayload(factorId)` is the current
 * weighted sum, kept in sync incrementally by [Invariant.applyIntSet]. Repair moves propose, for each
 * variable, the integer value that on its own would put the sum on the right side of `bound`,
 * clamped to the variable's domain. Terms pair [coeffs] with [vars]; the sum is compared by [op]
 * against [bound].
 */
class Linear private constructor(terms: CoalescedTerms, val op: LinearOp, val bound: Long) : Factor {

    val vars: IntArray = terms.vars
    val coeffs: LongArray = terms.coeffs

    init {
        require(coeffs.isNotEmpty()) { "linear sum must have at least one term" }
    }

    override val intVars: IntArray = vars

    /**
     * `Σ coeffs(i) * vars(i) ⟨op⟩ bound`. Duplicate variables are coalesced (their coefficients
     * summed) so the local-search payload stays consistent regardless of caller.
     */
    constructor(coeffs: IntArray, vars: IntArray, op: LinearOp, bound: Int) :
        this(coalesceLinearTerms(vars, coeffs), op, bound.toLong())

    /** Wide form: coefficients and bound that may exceed 32-bit range (SMT cut lemmas, dense folds). */
    constructor(coeffs: LongArray, vars: IntArray, op: LinearOp, bound: Long) :
        this(coalesceLinearTerms(vars, coeffs), op, bound)

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.LINEAR, ::buildKey)

    // Allocation-free per-incidence key hash via the two-mode [KeySink] — symmetry refinement rebuilds
    // this once per incident variable each round. `pairsByVarKeyCoalescing` reproduces `remap()` (whose
    // constructor coalesces same-image terms) followed by the key sort, so the port hash stays equal to
    // `remap().structuralKey().hashCode()` even when the colouring map collapses two variables.
    override fun remapStructuralHash(boolMap: IntArray, intMap: IntArray): Int =
        hashRemappedKey(FactorKind.LINEAR, boolMap, intMap, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.enum(op)
        sink.long(bound)
        sink.pairsByVarKeyCoalescing(vars) { coeffs[it] }
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = Linear(coeffs, vars.remapVars(intMap), op, bound)

    /**
     * A pure binary value relation `c·x ⟨=|≠⟩ c·y` — two terms with opposite-equal coefficients and a
     * zero bound, comparing for equality or distinctness. Its allowed-tuple set (`{x = y}` / `{x ≠ y}`)
     * is invariant under *any* uniform relabeling of values, so it is value-anonymous. Every
     * other linear is value-meaningful: an ordering (`≤`/`≥`) is not relabeling-invariant, and a
     * nonzero bound or non-opposite coefficients tie the variables to specific magnitudes.
     */
    private fun isBinaryValueRelation(): Boolean = (op == LinearOp.EQ || op == LinearOp.NE) && bound == 0L &&
        vars.size == 2 && coeffs[0] != 0L && coeffs[0] == -coeffs[1]

    override fun isValueAnonymous(): Boolean = isBinaryValueRelation()

    // A value-anonymous factor names no value as a constant, so a relabeling maps it to itself (#501).
    override fun remapValues(valueMap: (Long) -> Long): Factor? = if (isBinaryValueRelation()) this else null

    override val boolVars: IntArray = EmptyIntArray

    override val extendsObjectiveCone: Boolean = true

    override fun asPropagator(): Propagator = LinearPropagator(boolVars, intVars, coeffs, vars, op, bound)

    override fun asInvariant(): Invariant = LinearInvariant(coeffs, vars, op, bound)

    override val linearRows: List<LinearRow> by lazy { listOf(LinearRow(coeffs, vars, op, bound)) }
}

/** True when every coefficient and the bound fit 32-bit range — the precondition for the Int-coefficient
 *  reasoning a consumer keeps (ReifiedLinear's big-M rows, GCD modulus fixing, coefficient strengthening). */
internal fun fitsInt32(coeffs: LongArray, bound: Long): Boolean =
    bound in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
        coeffs.all { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }

/** True when every value in [values] fits 32-bit range. The value-symmetry relabel (`remapValues`) is
 *  `(Int)`-typed, so a value-carrying global (GCC cover, Table tuples, Mdd symbols, AllDifferent
 *  except-set) declines value symmetry (`null`) when wide, to avoid truncating two values into one. */
internal fun fitsInt32(values: LongArray): Boolean = values.all { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
