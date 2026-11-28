package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.bool.internals.CoalescedTerms
import com.eignex.klause.factor.bool.internals.coalesceLinearTerms
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.IntVars
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.LinearConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.MixedVars
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.RealConsts
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.WideConstants
import com.eignex.klause.ir.WideConsts
import com.eignex.klause.ir.constsOf
import com.eignex.klause.ir.hashRemappedKey
import com.eignex.klause.ir.indices
import com.eignex.klause.ir.materializeKey
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.propagation.NoPropagator
import com.eignex.util.arrays.emptyDoubleArray
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.arrays.emptyLongArray
import com.eignex.util.collections.IntHashSet
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.unaryMinus

/**
 * `Σ coeffs(i)·vars(i) ⟨op⟩ bound`. Terms pair [vars] with the coefficients in [constants]; the sum is
 * compared by [op] against that shape's bound. Local search keeps the weighted sum as the factor's
 * payload, updated incrementally by [Invariant.applyIntSet].
 */
class Linear private constructor(
    terms: CoalescedTerms,
    rawOp: LinearOp,
    rawBound: Long,
    realVarsIn: IntArray = emptyIntArray(),
    realCoeffsIn: DoubleArray = emptyDoubleArray(),
    // LP-only rows only, whose integer-term coefficients and bound need not be integral.
    intCoeffsRealIn: DoubleArray = emptyDoubleArray(),
    realBoundIn: Double = 0.0,
    strictRealIn: Boolean = false,
    // Over-64-bit integer constants only.
    wideCoeffsIn: Array<BigInt>? = null,
    wideBoundIn: BigInt? = null,
) : Factor,
    LinearRow {

    // Inequalities canonicalise to ≤, the convention cut separators expect, so a ≥ row and its negated ≤
    // form share a structural key.
    val op: LinearOp = if (rawOp == LinearOp.GE) LinearOp.LE else rawOp
    val vars: IntArray = terms.vars

    /**
     * The row's coefficients and right-hand side at the width that holds them exactly; narrow through
     * [integerConstants], [wideConstants] or [realConstants]. A `≥` row reports them negated.
     */
    override val constants: LinearConstants = run {
        val negate = rawOp == LinearOp.GE
        when {
            wideCoeffsIn != null -> {
                val wide = WideConsts(wideCoeffsIn)
                WideConstants(if (negate) wide.negated() else wide, if (negate) -wideBoundIn!! else wideBoundIn!!)
            }

            realVarsIn.isNotEmpty() -> {
                val intCoeffs = RealConsts(intCoeffsRealIn)
                val realCoeffs = RealConsts(realCoeffsIn)
                RealConstants(
                    intCoefficients = if (negate) intCoeffs.negated() else intCoeffs,
                    realCoefficients = if (negate) realCoeffs.negated() else realCoeffs,
                    bound = if (negate) -realBoundIn else realBoundIn,
                    strict = strictRealIn,
                )
            }

            negate && (rawBound == Long.MIN_VALUE || terms.coeffs.any { it == Long.MIN_VALUE }) -> {
                WideConstants(
                    WideConsts(Array(terms.coeffs.size) { -bigIntOf(terms.coeffs[it]) }),
                    -bigIntOf(rawBound),
                )
            }

            else -> {
                val integerCoeffs = constsOf(terms.coeffs)
                IntegerConstants(
                    if (negate) integerCoeffs.negated() else integerCoeffs,
                    if (negate) -rawBound else rawBound,
                )
            }
        }
    }

    /** The row's 64-bit integer constants, or `null` for a wide or real row. */
    val integerConstants: IntegerConstants? get() = constants as? IntegerConstants

    /** The row's exact integer constants of any width, or `null` for a real row. */
    val integralConstants: IntegralConstants? get() = constants as? IntegralConstants

    /** The row's over-64-bit constants, or `null` when it is not one. */
    val wideConstants: WideConstants? get() = constants as? WideConstants

    /** The row's continuous constants, or `null` when it is not an LP-only row. */
    val realConstants: RealConstants? get() = constants as? RealConstants

    /**
     * Real variable terms, paired with [RealConstants.realCoefficients]. A row with any real term is
     * LP-only: its propagator is [NoPropagator], and the LP relaxation and search leaf enforce it.
     */
    val realVars: IntArray = realVarsIn

    init {
        require(vars.isNotEmpty() || realVars.isNotEmpty()) { "linear sum must have at least one term" }
        val real = realConstants
        require(real == null || real.realCoefficients.size == realVars.size) { "real vars/coeffs length mismatch" }
        require(real == null || real.intCoefficients.size == vars.size) { "real int-coeff/var length mismatch" }
        require(real == null || realVars.isNotEmpty()) { "a real linear row needs at least one real variable" }
        require(real == null || real.bound.isFinite()) { "real linear bound must be finite" }
        require(real == null || (0 until real.intCoefficients.size).all { real.intCoefficients.at(it).isFinite() }) {
            "real linear integer coefficients must be finite"
        }
        require(real == null || (0 until real.realCoefficients.size).all { real.realCoefficients.at(it).isFinite() }) {
            "real linear coefficients must be finite"
        }
        require(real == null || !real.strict || op == LinearOp.LE) { "strictness needs an LP-only inequality row" }
        val wide = wideConstants
        require(wide == null || wide.coefficients.size == vars.size) { "wide coeff/var length mismatch" }
        require(wide == null || realVars.isEmpty()) { "a row cannot be both wide and real" }
        // Wide and real forms pass empty integer coefficients; an integer row must not carry those placeholders.
        val integer = integerConstants
        require(integer == null || integer.coefficients.size == vars.size) { "int coeff/var length mismatch" }
    }

    override val variables: VarList = if (realVars.isEmpty()) {
        IntVars(vars)
    } else {
        MixedVars(boundInts = vars, reals = realVars)
    }

    /** `Σ coeffs(i) * vars(i) ⟨op⟩ bound`. Duplicate variables are coalesced by summing coefficients. */
    constructor(coeffs: IntArray, vars: IntArray, op: LinearOp, bound: Int) :
        this(coalesceLinearTerms(vars, coeffs), op, bound.toLong())

    /** `Long` form of the coalescing constructor, for constants beyond 32-bit range. */
    constructor(coeffs: LongArray, vars: IntArray, op: LinearOp, bound: Long) :
        this(coalesceLinearTerms(vars, coeffs), op, bound)

    /**
     * Over-64-bit integer form: coefficients and/or bound beyond the [Long] range, carried exactly as
     * [BigInt]. The row propagates via [WideLinearPropagator] and is excluded from the LP relaxation;
     * [vars] must be distinct — this form does not coalesce duplicates.
     */
    constructor(vars: IntArray, wideCoeffs: Array<BigInt>, op: LinearOp, wideBound: BigInt) : this(
        wideLinearTerms(vars, wideCoeffs),
        op,
        0L,
        wideCoeffsIn = wideCoeffs.copyOf(),
        wideBoundIn = wideBound,
    )

    /**
     * LP-only mixed form with `Long` integer-side constants: `Σ intCoeffs(i)·intVars(i) +
     * Σ realCoeffs(j)·realVars(j) ⟨op⟩ bound`. Terms keep their order; nothing is coalesced.
     */
    constructor(
        intCoeffs: LongArray,
        intVars: IntArray,
        realCoeffs: DoubleArray,
        realVars: IntArray,
        op: LinearOp,
        bound: Long,
    ) : this(
        CoalescedTerms(intVars.copyOf(), emptyLongArray()),
        op,
        0L,
        realVars.copyOf(),
        realCoeffs.copyOf(),
        DoubleArray(intCoeffs.size) { intCoeffs[it].toDouble() },
        bound.toDouble(),
    )

    /**
     * LP-only mixed form with fractional integer-side coefficients and bound: `Σ intCoeffs(i)·intVars(i) +
     * Σ realCoeffs(j)·realVars(j) ⟨op⟩ bound`. [realVars] must be non-empty. Terms keep their order.
     */
    constructor(
        intVars: IntArray,
        intCoeffs: DoubleArray,
        realVars: IntArray,
        realCoeffs: DoubleArray,
        op: LinearOp,
        bound: Double,
        strict: Boolean = false,
    ) : this(
        CoalescedTerms(intVars.copyOf(), emptyLongArray()),
        op,
        0L,
        realVars.copyOf(),
        realCoeffs.copyOf(),
        intCoeffs.copyOf(),
        bound,
        strict,
    )

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.LINEAR, ::buildKey)

    // Allocation-free because symmetry refinement calls it per incident variable each round.
    // `pairsByVarKeyCoalescing` reproduces remap's coalescing, so the hash equals
    // `remap().structuralKey().hashCode()` even when the colouring map collapses two variables.
    override fun remapStructuralHash(mapping: VarRemap): Int = hashRemappedKey(FactorKind.LINEAR, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.enum(op)
        when (val c = constants) {
            is RealConstants -> {
                sink.long(if (c.strict) 1L else 0L)
                sink.long(c.bound.toRawBits())
                for (i in vars.indices) {
                    sink.intVar(vars[i])
                    sink.long(c.intCoefficients.at(i).toRawBits())
                }
                for (j in realVars.indices) {
                    sink.realVar(realVars[j])
                    sink.long(c.realCoefficients.at(j).toRawBits())
                }
            }

            is WideConstants -> {
                // Feed the decimal strings char-by-char with an out-of-char-range separator; unambiguous,
                // so different wide rows never share a key.
                val digits = c.keyDigits
                for (code in digits[0]) sink.long(code)
                for (i in vars.indices) {
                    sink.long(Long.MIN_VALUE)
                    sink.intVar(vars[i])
                    for (code in digits[i + 1]) sink.long(code)
                }
            }

            is IntegerConstants -> {
                sink.long(c.bound)
                sink.pairsByVarKeyCoalescing(vars) { c.coeff(it) }
            }
        }
    }

    override fun remap(mapping: VarRemap): Factor = when (val c = constants) {
        is RealConstants -> Linear(
            mapping.ints(vars),
            c.intCoefficients.toDoubleArray(),
            mapping.reals(realVars),
            c.realCoefficients.toDoubleArray(),
            op,
            c.bound,
            c.strict,
        )

        // A colouring map can collapse two vars onto one image, and the propagator needs one term per variable.
        is WideConstants -> {
            val (rv, rc) = coalesceWide(mapping.ints(vars), c.coefficients.toTypedArray())
            Linear(rv, rc, op, c.bound)
        }

        is IntegerConstants -> Linear(c.coeffs, mapping.ints(vars), op, c.bound)
    }

    // `c·x ⟨=|≠⟩ c·y` keeps its tuple set under any uniform value relabeling; other linear rows do not.
    private fun isBinaryValueRelation(): Boolean {
        val c = integerConstants ?: return false
        return (op == LinearOp.EQ || op == LinearOp.NE) && c.bound == 0L &&
            vars.size == 2 && c.coeff(0) != 0L && c.coeff(0) == -c.coeff(1)
    }

    override fun isValueAnonymous(): Boolean = isBinaryValueRelation()

    // A value-anonymous factor names no value as a constant, so a relabeling maps it to itself.
    override fun remapValues(valueMap: (Long) -> Long): Factor? = if (isBinaryValueRelation()) this else null

    override val size: Int get() = vars.size + realVars.size
    override val relation: LinearOp get() = op
    override val isIntegerOnly: Boolean get() = realVars.isEmpty()
    override fun ref(k: Int): Int = if (k < vars.size) {
        Term.ofIntVar(vars[k])
    } else {
        Term.ofRealVar(realVars[k - vars.size])
    }
    override val linearForm: LinearForm = LinearForm.Conjunction(listOf(this))
}

internal fun fitsInt32(coeffs: LongArray, bound: Long): Boolean =
    bound in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
        coeffs.all { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }

internal fun fitsInt32(values: LongArray): Boolean = values.all { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }

// Wide coefficients are exact, so callers must not lose a duplicate term before construction.
internal fun wideLinearTerms(vars: IntArray, coeffs: Array<BigInt>): CoalescedTerms {
    require(vars.size == coeffs.size) { "wide coeffs/vars length mismatch" }
    val seen = IntHashSet(vars.size)
    for (variable in vars) require(seen.add(variable)) { "wide linear vars must be distinct" }
    return CoalescedTerms(vars.copyOf(), emptyLongArray())
}

// Keep zero coefficients: a remap can cancel every nonzero term, but the factor stays representable.
internal fun coalesceWide(vars: IntArray, coeffs: Array<BigInt>): Pair<IntArray, Array<BigInt>> {
    require(vars.size == coeffs.size) { "wide coeffs/vars length mismatch" }
    val order = ArrayList<Int>(vars.size)
    val sum = HashMap<Int, BigInt>(vars.size)
    for (i in vars.indices) {
        val v = vars[i]
        val prev = sum[v]
        if (prev == null) {
            order.add(v)
            sum[v] = coeffs[i]
        } else {
            sum[v] = prev + coeffs[i]
        }
    }
    return order.toIntArray() to Array(order.size) { sum.getValue(order[it]) }
}
