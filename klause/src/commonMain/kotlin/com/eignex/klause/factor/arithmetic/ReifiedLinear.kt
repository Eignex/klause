package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.ReifiedFactor
import com.eignex.klause.factor.bool.internals.CoalescedTerms
import com.eignex.klause.factor.bool.internals.coalesceLinearTerms
import com.eignex.klause.factor.bool.internals.linearHolds
import com.eignex.klause.factor.bool.internals.linearResidual
import com.eignex.klause.factor.remapVars
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.NoInvariant
import com.eignex.klause.lp.LpOverflowException
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.lp.addExact
import com.eignex.klause.lp.mulExact
import com.eignex.klause.lp.subExact
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.KeySink
import com.eignex.klause.solver.MixedVars
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.VarList
import com.eignex.klause.solver.WideConsts
import com.eignex.klause.solver.constsOf
import com.eignex.klause.solver.hashRemappedKey
import com.eignex.klause.solver.materializeKey
import com.eignex.klause.solver.values
import com.eignex.klause.util.EmptyLongArray
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
            CoalescedTerms(vars.copyOf(), EmptyLongArray),
            op,
            0L,
            wideCoeffsIn = wideCoeffs.copyOf(),
            wideBoundIn = wideBound,
        )

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

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = when (val c = constants) {
        // A colouring map can collapse two of the row's vars onto one image, so coalesce their exact
        // coefficients (summing) to keep one term per variable, which the interval propagator requires.
        is WideConstants -> {
            val (rv, rc) = coalesceWide(vars.remapVars(intMap), c.coefficients.toTypedArray())
            ReifiedLinear(boolMap[auxBoolVar], rv, rc, op, c.bound)
        }

        is IntegerConstants -> ReifiedLinear(boolMap[auxBoolVar], c.coeffs, vars.remapVars(intMap), op, c.bound)
    }

    /** [Linear.structuralKey] plus the reifying [auxBoolVar]; the distinct factor kind keeps it disjoint
     *  from a bare linear's key, so a reified row and an asserted one never share a bucket. */
    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.REIFIED_LINEAR, ::buildKey)

    override fun remapStructuralHash(boolMap: IntArray, intMap: IntArray): Int =
        hashRemappedKey(FactorKind.REIFIED_LINEAR, boolMap, intMap, ::buildKey)

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
                    sink.long(vars[i].toLong())
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

    override fun asPropagator(): Propagator = when (val c = constants) {
        is WideConstants -> WideReifiedLinearPropagator(
            auxBoolVar,
            boolVars,
            intVars,
            c.coefficients.toTypedArray(),
            vars,
            op,
            c.bound,
        )

        is IntegerConstants -> ReifiedLinearPropagator(auxBoolVar, boolVars, intVars, c.coeffs, vars, op, c.bound)
    }

    override fun asInvariant(): Invariant =
        integerConstants?.let { ReifiedLinearInvariant(auxBoolVar, it.coeffs, vars, op, it.bound) } ?: NoInvariant

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
        // A wide reified row is excluded from the LP relaxation entirely — no 64-bit reading of it may
        // enter the LP; [WideReifiedLinearPropagator] is the sole enforcer.
        val row = integerConstants ?: return
        // Best-effort: a reified row whose activity or big-M overflows Long is left unrelaxed (the
        // exact helpers below signal it); the propagator and invariant still enforce the constraint.
        try {
            if (emitExactBinaryEquality(builder, row)) return
            emitBigMRows(builder, row)
        } catch (_: LpOverflowException) {
            return
        }
    }

    /**
     * Exact convex-hull rows for the common case `aux ⇔ (c·v == bound)` where `v`'s declared domain is a
     * single pair `{lo, hi}`. Then `c·v` is two-valued, so the indicator is an affine function of it —
     * `c·v = c·lo + (c·hi − c·lo)·aux` when `bound` is the high value (symmetrically for the low), or
     * `aux = 0` when `bound` is unreachable. This is the tight replacement for the big-M reification a
     * binary variable would otherwise get, and it is global (declared-domain based, valid at every node).
     * It covers the ubiquitous bool↔`{0,1}` channel (`channelBoolTo01`) as well as `±1` product encodings.
     * Returns whether it emitted (and the caller should skip the big-M rows).
     */
    private fun emitExactBinaryEquality(builder: RelaxationBuilder, row: IntegerConstants): Boolean {
        if (op != LinearOp.EQ || vars.size != 1) return false
        val c = row.coeff(0)
        if (c == 0L) return false
        val dec = builder.declaredDomain(vars[0])
        if (dec.values.size != 2) return false // a size-2 domain's two values are exactly its min and max
        val loValue = mulExact(c, dec.min)
        val hiValue = mulExact(c, dec.max)
        val vCol = builder.intColumn(vars[0])
        val auxCol = builder.boolColumn(auxBoolVar)
        when (row.bound) {
            // aux ⇔ (c·v == hi): c·v − (hi − lo)·aux = lo.
            hiValue -> builder.row(
                intArrayOf(vCol, auxCol),
                longArrayOf(c, -subExact(hiValue, loValue)),
                LinearOp.EQ,
                loValue,
            )

            // aux ⇔ (c·v == lo): c·v − (lo − hi)·aux = hi.
            loValue -> builder.row(
                intArrayOf(vCol, auxCol),
                longArrayOf(c, -subExact(loValue, hiValue)),
                LinearOp.EQ,
                hiValue,
            )

            // bound is neither reachable value, so the equality never holds and the indicator is false.
            else -> builder.row(intArrayOf(auxCol), longArrayOf(1L), LinearOp.EQ, 0L)
        }
        return true
    }

    private fun emitBigMRows(builder: RelaxationBuilder, row: IntegerConstants) {
        var lMin = 0L
        var lMax = 0L
        var lMinD = 0L
        var lMaxD = 0L
        for (k in vars.indices) {
            val c = row.coeff(k)
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
        val b = row.bound
        val boundUp = addExact(b, 1L) // L ≥ bound + 1 is the integer negation of L ≤ bound
        val boundDown = subExact(b, 1L)

        // Emit `Σ coeffs·vars + auxCoeff·aux  op  rhs`, marked [global] when the live M matches the
        // declared-range M; non-global rows cite their [maxSide] live bounds as premises.
        fun emit(auxCoeff: Long, rowOp: LinearOp, rhs: Long, global: Boolean, maxSide: Boolean) {
            val cols = IntArray(vars.size + 1)
            val vals = LongArray(vars.size + 1)
            for (k in vars.indices) {
                cols[k] = builder.intColumn(vars[k])
                vals[k] = row.coeff(k)
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
