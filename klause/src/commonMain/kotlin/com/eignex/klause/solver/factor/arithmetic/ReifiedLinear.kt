package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.factor.ReifiedFactor
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.factor.bool.CoalescedTerms
import com.eignex.klause.solver.factor.bool.coalesceLinearTerms
import com.eignex.klause.solver.factor.remapVars
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.IntEvent

/**
 * `auxBoolVar ↔ (Σ coeffs[i] * intVars[i] ⟨op⟩ bound)`. Created by the compiler when a
 * multi-variable [com.eignex.klause.model.IntCompare] appears non-top-level so the rest of the
 * Tseitin lowering can treat its truth as a Boolean literal. Payload at `intPayload[factorId]`
 * is the current weighted sum, mirrored from [Linear].
 */
class ReifiedLinear private constructor(
    override val auxBoolVar: Int,
    terms: CoalescedTerms,
    op: LinearOp,
    bound: Int,
) : LinearSumFactor(terms, op, bound),
    ReifiedFactor,
    ReifiedLinearPropagator,
    ReifiedLinearInvariant {

    /**
     * `auxBoolVar ↔ (Σ coeffs(i) * vars(i) ⟨op⟩ bound)`. Duplicate variables are coalesced
     * (their coefficients summed) so the local-search payload stays consistent regardless of
     * caller (issue #84).
     */
    constructor(auxBoolVar: Int, coeffs: IntArray, vars: IntArray, op: LinearOp, bound: Int) :
        this(auxBoolVar, coalesceLinearTerms(vars, coeffs), op, bound)

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        ReifiedLinear(boolMap[auxBoolVar], coeffs, vars.remapVars(intMap), op, bound)

    /** [Linear.structuralKey] plus the reifying [auxBoolVar]; the `rlin` prefix keeps it disjoint from
     *  a bare linear's key, so a reified row and an asserted one never share a bucket (#443). */
    override fun structuralKey(): String =
        "rlin:$auxBoolVar:$op:$bound:" + vars.indices.sortedBy { vars[it] }.joinToString(
            ",",
        ) { "${vars[it]}=${coeffs[it]}" }

    override val boolVars: IntArray = intArrayOf(auxBoolVar)

    /**
     * Advisor subscription (#623): like [Linear], the integer reasoning is purely interval-based —
     * `propagate` reads `linearSumRange` (the `[c·min, c·max]` envelope) to decide whether the body
     * is forced and then delegates to `propagateLinearBounds`, never inspecting interior holes. So it
     * subscribes its term variables to [IntEvent.LB_RAISED] / [IntEvent.UB_LOWERED] and skips interior
     * `VALUE_REMOVED` wakes. The indicator [auxBoolVar] keeps its separate Boolean wakeup.
     */
    override val initialIntEventWatches: IntArray = IntEvent.boundEventWatches(intVars)

    override fun holdsNow(state: LocalSearchState, factorId: Int): Boolean = holds(state.longPayload[factorId])

    override fun residualNow(state: LocalSearchState, factorId: Int, softCap: Int): Int =
        residual(state.longPayload[factorId], softCap)
}
