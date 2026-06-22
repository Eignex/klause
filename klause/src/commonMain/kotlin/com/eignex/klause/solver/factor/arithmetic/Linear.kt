package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.bool.internals.CoalescedTerms
import com.eignex.klause.solver.factor.bool.internals.coalesceLinearTerms
import com.eignex.klause.solver.factor.remapVars

/**
 * `Σ coeffs(i) * intVars(i) ⟨op⟩ bound`. Payload at `intPayload(factorId)` is the current
 * weighted sum, kept in sync incrementally by [Invariant.applyIntSet]. Repair moves propose, for each
 * variable, the integer value that on its own would put the sum on the right side of `bound`,
 * clamped to the variable's domain. Terms pair [coeffs] with [vars]; the sum is compared by [op]
 * against [bound].
 */
class Linear private constructor(terms: CoalescedTerms, val op: LinearOp, val bound: Int) : Factor {

    val vars: IntArray = terms.vars
    val coeffs: IntArray = terms.coeffs

    init {
        require(coeffs.isNotEmpty()) { "linear sum must have at least one term" }
    }

    override val intVars: IntArray = vars

    /**
     * `Σ coeffs(i) * vars(i) ⟨op⟩ bound`. Duplicate variables are coalesced (their coefficients
     * summed) so the local-search payload stays consistent regardless of caller.
     */
    constructor(coeffs: IntArray, vars: IntArray, op: LinearOp, bound: Int) :
        this(coalesceLinearTerms(vars, coeffs), op, bound)

    override fun structuralKey(): String =
        "lin:$op:$bound:" + vars.indices.sortedBy { vars[it] }.joinToString(",") { "${vars[it]}=${coeffs[it]}" }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = Linear(coeffs, vars.remapVars(intMap), op, bound)

    /**
     * A pure binary value relation `c·x ⟨=|≠⟩ c·y` — two terms with opposite-equal coefficients and a
     * zero bound, comparing for equality or distinctness. Its allowed-tuple set (`{x = y}` / `{x ≠ y}`)
     * is invariant under *any* uniform relabeling of values, so it is value-anonymous. Every
     * other linear is value-meaningful: an ordering (`≤`/`≥`) is not relabeling-invariant, and a
     * nonzero bound or non-opposite coefficients tie the variables to specific magnitudes.
     */
    private fun isBinaryValueRelation(): Boolean = (op == LinearOp.EQ || op == LinearOp.NE) && bound == 0 &&
        vars.size == 2 && coeffs[0] != 0 && coeffs[0] == -coeffs[1]

    override fun isValueAnonymous(): Boolean = isBinaryValueRelation()

    // A value-anonymous factor names no value as a constant, so a relabeling maps it to itself (#501).
    override fun remapValues(valueMap: (Int) -> Int): Factor? = if (isBinaryValueRelation()) this else null

    override val boolVars: IntArray = EmptyIntArray

    override fun asPropagator(): Propagator = LinearPropagator(boolVars, intVars, coeffs, vars, op, bound)

    override fun asInvariant(): Invariant = LinearInvariant(coeffs, vars, op, bound)
}
