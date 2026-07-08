package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.ReifiedFactor
import com.eignex.klause.factor.bool.internals.CoalescedTerms
import com.eignex.klause.factor.bool.internals.coalesceLinearTerms
import com.eignex.klause.factor.bool.internals.linearHolds
import com.eignex.klause.factor.bool.internals.linearResidual
import com.eignex.klause.factor.remapVars
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.lp.LpOverflowException
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.lp.addExact
import com.eignex.klause.lp.mulExact
import com.eignex.klause.lp.subExact
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.StructuralKey

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
    val bound: Long,
) : ReifiedFactor {

    val vars: IntArray = terms.vars
    val coeffs: LongArray = terms.coeffs

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
        this(auxBoolVar, coalesceLinearTerms(vars, coeffs), op, bound.toLong())

    /** Wide form: coefficients and bound that may exceed 32-bit range. */
    constructor(auxBoolVar: Int, coeffs: LongArray, vars: IntArray, op: LinearOp, bound: Long) :
        this(auxBoolVar, coalesceLinearTerms(vars, coeffs), op, bound)

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        ReifiedLinear(boolMap[auxBoolVar], coeffs, vars.remapVars(intMap), op, bound)

    /** [Linear.structuralKey] plus the reifying [auxBoolVar]; the distinct factor kind keeps it disjoint
     *  from a bare linear's key, so a reified row and an asserted one never share a bucket (#443). */
    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.REIFIED_LINEAR) {
        int(auxBoolVar)
        enum(op)
        long(bound)
        pairsByKey(vars) { coeffs[it] }
    }

    override val boolVars: IntArray = intArrayOf(auxBoolVar)

    override fun holdsNow(state: LocalSearchState, factorId: Int): Boolean =
        linearHolds(state.longPayload[factorId], op, bound)

    override fun residualNow(state: LocalSearchState, factorId: Int, softCap: Int): Int =
        linearResidual(state.longPayload[factorId], op, bound, softCap)

    override fun asPropagator(): Propagator =
        ReifiedLinearPropagator(auxBoolVar, boolVars, intVars, coeffs, vars, op, bound)

    override fun asInvariant(): Invariant = ReifiedLinearInvariant(auxBoolVar, coeffs, vars, op, bound)

    /**
     * Indicator rows for `auxBoolVar ↔ (L op bound)`, where `L = Σ coeffs·vars`. The big-Ms are the
     * tightest possible from the live range `[lMin, lMax]` of `L`, and the `¬(L op bound)` side uses
     * integrality (`¬(L ≤ bound) ⇔ L ≥ bound + 1`) so the rows are as strong as a single indicator allows.
     * For `EQ` only the `aux = 1 ⇒ L = bound` direction is emitted, and for `NE` only the `aux = 0 ⇒ L =
     * bound` direction (the complement is a disjunction with no single LP cut).
     *
     * A live big-M bakes branch-tightened bounds into a row's constants, so the row is marked global only
     * when its M equals the M the declared range would give; a non-global row carries the live bounds it
     * leaned on as premises (the engine derives them — see [RelaxationBuilder.bigMRow]).
     *
     * Best-effort: a reified row whose activity or big-M overflows Long is left unrelaxed (the
     * propagator/invariant still enforce it). Sound to skip.
     */
    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        // Best-effort: a reified row whose activity or big-M overflows Long is left unrelaxed (the
        // exact helpers below signal it); the propagator and invariant still enforce the constraint.
        try {
            emitBigMRows(builder)
        } catch (_: LpOverflowException) {
            return
        }
    }

    private fun emitBigMRows(builder: RelaxationBuilder) {
        var lMin = 0L
        var lMax = 0L
        var lMinD = 0L
        var lMaxD = 0L
        for (k in vars.indices) {
            val c = coeffs[k]
            val dom = builder.liveDomain(vars[k])
            val dec = builder.declaredDomain(vars[k])
            if (c >= 0L) {
                lMin = addExact(lMin, mulExact(c, dom.min))
                lMax = addExact(lMax, mulExact(c, dom.max))
                lMinD = addExact(lMinD, mulExact(c, dec.min))
                lMaxD = addExact(lMaxD, mulExact(c, dec.max))
            } else {
                lMin = addExact(lMin, mulExact(c, dom.max))
                lMax = addExact(lMax, mulExact(c, dom.min))
                lMinD = addExact(lMinD, mulExact(c, dec.max))
                lMaxD = addExact(lMaxD, mulExact(c, dec.min))
            }
        }
        val a = builder.boolColumn(auxBoolVar)
        val b = bound
        val boundUp = addExact(b, 1L) // L ≥ bound + 1 is the integer negation of L ≤ bound
        val boundDown = subExact(b, 1L)

        // Emit `Σ coeffs·vars + auxCoeff·aux  op  rhs`, marked [global] when the live M matches the
        // declared-range M; non-global rows cite their [maxSide] live bounds as premises.
        fun emit(auxCoeff: Long, rowOp: LinearOp, rhs: Long, global: Boolean, maxSide: Boolean) {
            val cols = IntArray(vars.size + 1)
            val vals = LongArray(vars.size + 1)
            for (k in vars.indices) {
                cols[k] = builder.intColumn(vars[k])
                vals[k] = coeffs[k]
            }
            cols[vars.size] = a
            vals[vars.size] = auxCoeff
            builder.bigMRow(cols, vals, rowOp, rhs, global, maxSide)
        }

        when (op) {
            LinearOp.LE -> {
                val m1 = maxOf(0L, subExact(lMax, b)) // aux=1 ⇒ L ≤ bound
                emit(m1, LinearOp.LE, addExact(b, m1), m1 == maxOf(0L, subExact(lMaxD, b)), maxSide = true)
                val m2 = maxOf(0L, subExact(boundUp, lMin)) // aux=0 ⇒ L ≥ bound+1
                emit(m2, LinearOp.GE, boundUp, m2 == maxOf(0L, subExact(boundUp, lMinD)), maxSide = false)
            }

            LinearOp.GE -> {
                val m1 = maxOf(0L, subExact(b, lMin)) // aux=1 ⇒ L ≥ bound
                emit(-m1, LinearOp.GE, subExact(b, m1), m1 == maxOf(0L, subExact(b, lMinD)), maxSide = false)
                val m2 = maxOf(0L, subExact(lMax, boundDown)) // aux=0 ⇒ L ≤ bound-1
                emit(-m2, LinearOp.LE, boundDown, m2 == maxOf(0L, subExact(lMaxD, boundDown)), maxSide = true)
            }

            LinearOp.EQ -> {
                val mHi = maxOf(0L, subExact(lMax, b)) // aux=1 ⇒ L ≤ bound
                emit(mHi, LinearOp.LE, addExact(b, mHi), mHi == maxOf(0L, subExact(lMaxD, b)), maxSide = true)
                val mLo = maxOf(0L, subExact(b, lMin)) // aux=1 ⇒ L ≥ bound
                emit(-mLo, LinearOp.GE, subExact(b, mLo), mLo == maxOf(0L, subExact(b, lMinD)), maxSide = false)
            }

            LinearOp.NE -> {
                val mHi = maxOf(0L, subExact(lMax, b)) // aux=0 ⇒ L ≤ bound
                emit(-mHi, LinearOp.LE, b, mHi == maxOf(0L, subExact(lMaxD, b)), maxSide = true)
                val mLo = maxOf(0L, subExact(b, lMin)) // aux=0 ⇒ L ≥ bound
                emit(mLo, LinearOp.GE, b, mLo == maxOf(0L, subExact(b, lMinD)), maxSide = false)
            }
        }
    }
}
