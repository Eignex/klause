package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.factor.bool.CoalescedTerms
import com.eignex.klause.solver.factor.bool.coalesceLinearTerms
import com.eignex.klause.solver.factor.remapVars
import com.eignex.klause.solver.propagation.IntEvent

/**
 * `Σ coeffs(i) * intVars(i) ⟨op⟩ bound`. Payload at `intPayload(factorId)` is the current
 * weighted sum, kept in sync incrementally by [applyIntSet]. Repair moves propose, for each
 * variable, the integer value that on its own would put the sum on the right side of `bound`,
 * clamped to the variable's domain.
 */
class Linear private constructor(terms: CoalescedTerms, op: LinearOp, bound: Int) :
    LinearSumFactor(
        terms,
        op,
        bound,
    ),
    LinearPropagator,
    LinearInvariant {

    /**
     * `Σ coeffs(i) * vars(i) ⟨op⟩ bound`. Duplicate variables are coalesced (their coefficients
     * summed) so the local-search payload stays consistent regardless of caller (issue #84).
     */
    constructor(coeffs: IntArray, vars: IntArray, op: LinearOp, bound: Int) :
        this(coalesceLinearTerms(vars, coeffs), op, bound)

    override fun structuralKey(): String =
        "lin:$op:$bound:" + vars.indices.sortedBy { vars[it] }.joinToString(",") { "${vars[it]}=${coeffs[it]}" }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = Linear(coeffs, vars.remapVars(intMap), op, bound)

    /**
     * A pure binary value relation `c·x ⟨=|≠⟩ c·y` — two terms with opposite-equal coefficients and a
     * zero bound, comparing for equality or distinctness. Its allowed-tuple set (`{x = y}` / `{x ≠ y}`)
     * is invariant under *any* uniform relabeling of values, so it is value-anonymous (#501). Every
     * other linear is value-meaningful: an ordering (`≤`/`≥`) is not relabeling-invariant, and a
     * nonzero bound or non-opposite coefficients tie the variables to specific magnitudes.
     */
    private fun isBinaryValueRelation(): Boolean = (op == LinearOp.EQ || op == LinearOp.NE) && bound == 0 &&
        vars.size == 2 && coeffs[0] != 0 && coeffs[0] == -coeffs[1]

    override fun isValueAnonymous(): Boolean = isBinaryValueRelation()

    // A value-anonymous factor names no value as a constant, so a relabeling maps it to itself (#501).
    override fun remapValues(valueMap: (Int) -> Int): Factor? = if (isBinaryValueRelation()) this else null

    override val boolVars: IntArray = EmptyIntArray

    /**
     * Advisor subscription (#623): [propagateLinearBounds] derives everything from the interval
     * `[c·min, c·max]` of each term — it reads only `min`/`max` and never inspects interior holes
     * (the `NE` branch excludes a value, but only once the *other* terms are fixed, which it detects
     * from their bounds; an interior hole cannot change any term's min/max, so it can never enable a
     * new linear deduction). So the factor subscribes to [IntEvent.LB_RAISED] / [IntEvent.UB_LOWERED]
     * on each variable and is not woken by interior `VALUE_REMOVED` carves a co-constraint punches
     * into its variables — a sound selectivity win across the arithmetic core. Terms are coalesced,
     * so [vars] is already duplicate-free. A var becoming fixed collapses both bounds, so fixing is
     * covered without an explicit `FIXED` subscription.
     */
    override val initialIntEventWatches: IntArray = IntArray(vars.size * 2).also { out ->
        var w = 0
        for (v in vars) {
            out[w++] = IntEvent.pack(v, IntEvent.LB_RAISED)
            out[w++] = IntEvent.pack(v, IntEvent.UB_LOWERED)
        }
    }
}
