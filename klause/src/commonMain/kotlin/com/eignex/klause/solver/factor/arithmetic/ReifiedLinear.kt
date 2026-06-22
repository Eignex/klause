package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.factor.ReifiedFactor
import com.eignex.klause.solver.factor.bool.internals.CoalescedTerms
import com.eignex.klause.solver.factor.bool.internals.coalesceLinearTerms
import com.eignex.klause.solver.factor.bool.internals.linearHolds
import com.eignex.klause.solver.factor.bool.internals.linearResidual
import com.eignex.klause.solver.factor.remapVars
import com.eignex.klause.solver.localsearch.LocalSearchState

/**
 * `auxBoolVar ↔ (Σ coeffs[i] * intVars[i] ⟨op⟩ bound)`. Created by the compiler when a
 * multi-variable [com.eignex.klause.model.IntCompare] appears non-top-level so the rest of the
 * Tseitin lowering can treat its truth as a Boolean literal. Payload at `intPayload[factorId]`
 * is the current weighted sum, mirrored from [Linear]. Terms pair [coeffs] with [vars]; the sum
 * is compared by [op] against [bound].
 */
class ReifiedLinear private constructor(
    override val auxBoolVar: Int,
    terms: CoalescedTerms,
    val op: LinearOp,
    val bound: Int,
) : ReifiedFactor {

    val vars: IntArray = terms.vars
    val coeffs: IntArray = terms.coeffs

    init {
        require(coeffs.isNotEmpty()) { "linear sum must have at least one term" }
    }

    override val intVars: IntArray = vars

    /**
     * `auxBoolVar ↔ (Σ coeffs(i) * vars(i) ⟨op⟩ bound)`. Duplicate variables are coalesced
     * (their coefficients summed) so the local-search payload stays consistent regardless of
     * caller (issue #84).
     */
    constructor(auxBoolVar: Int, coeffs: IntArray, vars: IntArray, op: LinearOp, bound: Int) :
        this(auxBoolVar, coalesceLinearTerms(vars, coeffs), op, bound)

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        ReifiedLinear(boolMap[auxBoolVar], coeffs, vars.remapVars(intMap), op, bound)

    /** [Linear.structuralKey] plus the reifying [auxBoolVar]; the distinct factor kind keeps it disjoint
     *  from a bare linear's key, so a reified row and an asserted one never share a bucket (#443). */
    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.REIFIED_LINEAR) {
        int(auxBoolVar)
        enum(op)
        int(bound)
        pairsByKey(vars) { coeffs[it].toLong() }
    }

    override val boolVars: IntArray = intArrayOf(auxBoolVar)

    override fun holdsNow(state: LocalSearchState, factorId: Int): Boolean =
        linearHolds(state.longPayload[factorId], op, bound)

    override fun residualNow(state: LocalSearchState, factorId: Int, softCap: Int): Int =
        linearResidual(state.longPayload[factorId], op, bound, softCap)

    override fun asPropagator(): Propagator =
        ReifiedLinearPropagator(auxBoolVar, boolVars, intVars, coeffs, vars, op, bound)

    override fun asInvariant(): Invariant = ReifiedLinearInvariant(auxBoolVar, coeffs, vars, op, bound)
}
