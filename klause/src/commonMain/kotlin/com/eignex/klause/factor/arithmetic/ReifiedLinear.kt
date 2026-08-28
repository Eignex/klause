package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.ReifiedFactor
import com.eignex.klause.factor.bool.internals.CoalescedTerms
import com.eignex.klause.factor.bool.internals.coalesceLinearTerms
import com.eignex.klause.factor.bool.internals.linearHolds
import com.eignex.klause.factor.bool.internals.linearResidual
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.MixedVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.hashRemappedKey
import com.eignex.klause.ir.materializeKey
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.solver.WideConsts
import com.eignex.klause.solver.constsOf
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * `auxBoolVar ↔ (Σ coeffs[i] * intVars[i] ⟨op⟩ bound)`. Created by the compiler when a
 * multi-variable [com.eignex.klause.model.IntCompare] appears non-top-level so the rest of the
 * Tseitin lowering can treat its truth as a Boolean literal. Payload at `intPayload[factorId]`
 * is the current weighted sum, mirrored from [Linear]. Terms pair [vars] with the coefficients in
 * [constants]; the sum is compared by [op] against that shape's bound.
 */
class ReifiedLinear private constructor(
    override val auxBoolVar: Int,
    terms: CoalescedTerms,
    val op: LinearOp,
    rawBound: Long,
    // Over-64-bit integer coefficients and bound, carried exactly. Null for the plain integer form.
    wideCoeffsIn: Array<BigInteger>? = null,
    wideBoundIn: BigInteger? = null,
) : ReifiedFactor {

    val vars: IntArray = terms.vars

    /**
     * The row's coefficients and right-hand side, at the width that holds them exactly. A consumer narrows
     * to [integerConstants] to reason in 64 bits, or reads [IntegralConstants] for exact arbitrary
     * precision; a reified row keeps its op, so nothing is negated here.
     */
    val constants: IntegralConstants = if (wideCoeffsIn != null) {
        WideConstants(WideConsts(wideCoeffsIn), wideBoundIn!!)
    } else {
        IntegerConstants(vars, constsOf(terms.coeffs), op, rawBound)
    }

    /** The row read as plain 64-bit integer arithmetic, or `null` when its constants are wider. */
    val integerConstants: IntegerConstants? get() = constants as? IntegerConstants

    /** The row's over-64-bit constants, or `null` when it is not one. */
    val wideConstants: WideConstants? get() = constants as? WideConstants

    init {
        require(vars.isNotEmpty()) { "linear sum must have at least one term" }
        val wide = wideConstants
        require(wide == null || wide.coefficients.size == vars.size) { "wide coeff/var length mismatch" }
    }

    /** Wide form: `auxBoolVar ↔ (Σ wideCoeffs·vars ⟨op⟩ wideBound)` with coefficients or a bound beyond the
     *  64-bit range. Enforced exactly by [WideReifiedLinearPropagator]; kept out of the LP relaxation. */
    constructor(auxBoolVar: Int, vars: IntArray, wideCoeffs: Array<BigInteger>, op: LinearOp, wideBound: BigInteger) :
        this(
            auxBoolVar,
            wideLinearTerms(vars, wideCoeffs),
            op,
            0L,
            wideCoeffsIn = wideCoeffs.copyOf(),
            wideBoundIn = wideBound,
        )

    override val integerTheoryOwnable: Boolean get() = true

    override val exactTheoryOwnable: Boolean get() = when (val c = constants) {
        is IntegerConstants -> vars.indices.all { isExactInteger(c.coeff(it).toDouble()) } &&
            isExactInteger(c.bound.toDouble())

        is WideConstants -> true
    }

    override val variables: VarList = MixedVars(boundInts = vars, boolVars = intArrayOf(auxBoolVar))

    /**
     * `auxBoolVar ↔ (Σ coeffs(i) * vars(i) ⟨op⟩ bound)`. Duplicate variables are coalesced
     * (their coefficients summed) so the local-search payload stays consistent regardless of
     * caller.
     */
    constructor(auxBoolVar: Int, coeffs: IntArray, vars: IntArray, op: LinearOp, bound: Int) :
        this(auxBoolVar, coalesceLinearTerms(vars, coeffs), op, bound.toLong())

    /** Wide form: coefficients and bound that may exceed 32-bit range. */
    constructor(auxBoolVar: Int, coeffs: LongArray, vars: IntArray, op: LinearOp, bound: Long) :
        this(auxBoolVar, coalesceLinearTerms(vars, coeffs), op, bound)

    override fun remap(mapping: VarRemap): Factor = when (val c = constants) {
        // A colouring map can collapse two of the row's vars onto one image, so coalesce their exact
        // coefficients (summing) to keep one term per variable, which the interval propagator requires.
        is WideConstants -> {
            val (rv, rc) = coalesceWide(mapping.ints(vars), c.coefficients.toTypedArray())
            ReifiedLinear(mapping.bool(auxBoolVar), rv, rc, op, c.bound)
        }

        is IntegerConstants -> ReifiedLinear(mapping.bool(auxBoolVar), c.coeffs, mapping.ints(vars), op, c.bound)
    }

    /** [Linear.structuralKey] plus the reifying [auxBoolVar]; the distinct factor kind keeps it disjoint
     *  from a bare linear's key, so a reified row and an asserted one never share a bucket. */
    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.REIFIED_LINEAR, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int =
        hashRemappedKey(FactorKind.REIFIED_LINEAR, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.boolVar(auxBoolVar)
        sink.enum(op)
        when (val c = constants) {
            // A wide row keys on its exact BigIntegers: feed the decimal strings char-by-char with an
            // out-of-char separator so distinct wide rows never collide.
            is WideConstants -> {
                for (ch in c.bound.toString()) sink.long(ch.code.toLong())
                for (i in vars.indices) {
                    sink.long(Long.MIN_VALUE)
                    sink.intVar(vars[i])
                    for (ch in c.coefficients.at(i).toString()) sink.long(ch.code.toLong())
                }
            }

            is IntegerConstants -> {
                sink.long(c.bound)
                sink.pairsByVarKey(vars) { c.coeff(it) }
            }
        }
    }

    // A wide row has no 64-bit [longPayload] to read: local search is gated off entirely for a problem
    // with any wide factor (LocalSearchSolver bails on hasWideFactor), so these are unreachable for one;
    // the conservative "always violated" / max-residual values cannot let LS accept an assignment that
    // breaks it.
    override fun holdsNow(state: LocalSearchState, factorId: Int): Boolean =
        integerConstants?.let { linearHolds(state.longPayload[factorId], op, it.bound) } ?: false

    override fun residualNow(state: LocalSearchState, factorId: Int, softCap: Int): Int =
        integerConstants?.let { linearResidual(state.longPayload[factorId], op, it.bound, softCap) } ?: softCap
}
