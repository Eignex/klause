package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.formats.LinComb
import com.eignex.klause.formats.WideLinComb
import com.eignex.klause.formats.isConstant
import com.eignex.klause.formats.reifyLinear
import com.eignex.klause.formats.trueLit
import com.eignex.klause.formats.tseitinAnd
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.objective.LinearObjective
import com.ionspin.kotlin.bignum.integer.BigInteger

// Real-arithmetic lowering for the SMT-LIB front-end (LRA / the real half of LIRA): real variables
// become LP-only continuous columns and top-level linear real constraints become real Linear rows —
// no discretisation. Coefficients are folded as exact rationals (BigFraction), and each emitted row
// is multiplied through by the least common denominator so the row's doubles are exact integers:
// the LP-side certifiers then reason about precisely the asserted constraint. Fragment: conjunctive
// real atoms, strict or not (strictness rides the delta-rational exact deciders), plus real OMT
// objectives; distinct and real atoms under boolean structure reject with a clear message until the
// real-atom reification lands.

/** The integer combination this real term embeds, or null when any coefficient is fractional or
 *  a real column appears — the inverse of [toRealComb], for `to_int` over an integral real term. */
internal fun RealComb.toLinCombOrNull(): LinComb? {
    if (realCoeffs.isNotEmpty()) return null
    val coeffs = HashMap<Int, Long>()
    for ((v, c) in intCoeffs) coeffs[v] = c.toExactLongOrNull() ?: return null
    val const = constant.toExactLongOrNull() ?: return null
    return LinComb(coeffs, const)
}

/** Embed an integer linear combination into the reals (the `to_real` bridge). */
internal fun LinComb.toRealComb(): RealComb = RealComb(
    coeffs.mapValues { BigFraction.ofLong(it.value) },
    emptyMap(),
    BigFraction.ofLong(constant),
)

/** Embed an arbitrary-precision integer combination into the reals; [RealComb] is exact rational
 *  already, so a magnitude past 64 bits needs no approximation on the way in. */
internal fun WideLinComb.toRealComb(): RealComb = RealComb(
    coeffs.mapValues { BigFraction.of(it.value, BigInteger.ONE) },
    emptyMap(),
    BigFraction.of(constant, BigInteger.ONE),
)

/** Syntactic real classifier, worklist-driven like [SmtLib.Builder.isBoolExpr] so a deeply
 *  nested arithmetic chain cannot overflow the call stack: true when the term is real-sorted (a real
 *  variable / literal, `to_real`, `/`, or an arithmetic combination with a real operand). */
internal fun SmtLib.Builder.isRealExpr(t: SExpr): Boolean {
    val work = ArrayDeque<SExpr>()
    work.addLast(t)
    while (work.isNotEmpty()) {
        when (val node = work.removeLast()) {
            is SExpr.Atom -> {
                if (node.text in realNames || isRealLiteral(node.text) || lookup(node.text)?.isReal == true) {
                    return true
                }
                if (macros[node.text]?.isReal == true) return true
            }

            is SExpr.SList -> when (val head = (node.items.firstOrNull() as? SExpr.Atom)?.text) {
                "to_real", "/" -> return true

                "to_int" -> Unit

                "+", "-", "*" -> for (i in 1 until node.items.size) work.addLast(node.items[i])

                // Either branch decides the ite's sort: a real may hide in the else-branch alone.
                "ite" -> {
                    work.addLast(node.argAt(2, "ite then branch"))
                    work.addLast(node.argAt(3, "ite else branch"))
                }

                "let" -> work.addLast(node.argAt(2, "let body"))

                else -> if (macros[head]?.isReal == true) return true
            }
        }
    }
    return false
}

/** Whether a relation node compares real-sorted operands (either side suffices — SMT-LIB mixed
 *  comparisons arrive through `to_real`, but be permissive about which side carries it). */
internal fun SmtLib.Builder.isRealRelation(node: SExpr.SList): Boolean {
    for (i in 1 until node.items.size) if (isRealExpr(node.items[i])) return true
    return false
}

/** Parse a decimal or integer numeral as an exact rational (`12.345` = 12345/1000). */
internal fun parseRealLiteral(s: String): BigFraction? {
    val negative = s.startsWith('-')
    val body = if (negative) s.substring(1) else s
    if (body.isEmpty()) return null
    val dot = body.indexOf('.')
    val digits = if (dot < 0) body else body.substring(0, dot) + body.substring(dot + 1)
    if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
    var num = BigInteger.parseString(digits, 10)
    if (negative) num = -num
    var den = BigInteger.ONE
    val ten = BigInteger.TEN
    if (dot >= 0) repeat(body.length - dot - 1) { den *= ten }
    return BigFraction.of(num, den)
}

/** Post a top-level real relation as hard LP-only rows; strictness rides through to the delta-rational
 *  deciders. An n-ary chain `(op a1 … an)` posts its n−1 consecutive relations. `distinct` needs the
 *  real-atom machinery and rejects. */
internal fun SmtLib.Builder.assertRealLinear(node: SExpr.SList) {
    val op = node.atomAt(0, "relation operator")
    requireChainableRelation(node, op)
    val terms = (1 until node.items.size).map { realTerm(node.items[it]) }
    for (i in 0 until terms.size - 1) assertRealRelation(op, terms[i], terms[i + 1])
}

/** Post one real relation `a ⟨op⟩ b`, resolving a variable-free comparison to trivially true or unsat. */
private fun SmtLib.Builder.assertRealRelation(op: String, a: RealComb, b: RealComb) {
    val (linOp, strict) = when (op) {
        "<=" -> LinearOp.LE to false
        ">=" -> LinearOp.GE to false
        "<" -> LinearOp.LE to true
        ">" -> LinearOp.GE to true
        "=" -> LinearOp.EQ to false
        else -> smtUnsupported("real relation '$op'")
    }
    val d = a.plus(b.scaled(BigFraction.MINUS_ONE))
    if (d.isConstant) {
        val sign = d.constant.signum()
        val holds = when {
            linOp == LinearOp.LE -> if (strict) sign < 0 else sign <= 0
            linOp == LinearOp.GE -> if (strict) sign > 0 else sign >= 0
            else -> sign == 0
        }
        if (!holds) forceTrue(Lit.negate(trueLit()))
        return
    }
    if (d.realCoeffs.isEmpty()) {
        // Integer variables compared against a rational (e.g. `x <= 1.5`): multiplying through by the
        // least common denominator gives an exact all-integer row — no real column is involved, and
        // strictness folds into the integer bound.
        val scaled = integerRow(d) ?: smtUnsupported("rational comparison exceeds the 64-bit range")
        val (vars, coeffs, bound) = scaled
        val adjusted = when {
            !strict -> bound
            linOp == LinearOp.LE -> bound - 1
            else -> bound + 1
        }
        assertLinearRow(coeffs, vars, linOp, adjusted)
        return
    }
    factors.add(realRow(d, linOp, strict))
}

/** Scale an all-integer-variable `d ⟨op⟩ 0` by the least common denominator into an exact Long row
 *  `Σ coeffs·vars ⟨op⟩ bound`, or null when a scaled value escapes 64 bits. */
internal fun SmtLib.Builder.integerRow(d: RealComb): Triple<IntArray, LongArray, Long>? {
    var lcm = d.constant.den
    for (c in d.intCoeffs.values) lcm = lcmOf(lcm, c.den)
    val scale = BigFraction.of(lcm, BigInteger.ONE)
    val vars = IntArray(d.intCoeffs.size)
    val coeffs = LongArray(d.intCoeffs.size)
    var i = 0
    for ((v, c) in d.intCoeffs) {
        vars[i] = v
        coeffs[i] = (c * scale).toExactLongOrNull() ?: return null
        i++
    }
    val bound = (d.constant.negated() * scale).toExactLongOrNull() ?: return null
    return Triple(vars, coeffs, bound)
}

/** Scale `d ⟨op⟩ 0` by the least common denominator and emit the exact-integer double row. A scaled
 *  value past the exactly-representable double range routes through the [wideRealRow] chain encoding
 *  instead — exact either way, never approximated. */
internal fun SmtLib.Builder.realRow(d: RealComb, op: LinearOp, strict: Boolean = false): Linear {
    var lcm = d.constant.den
    for (c in d.intCoeffs.values) lcm = lcmOf(lcm, c.den)
    for (c in d.realCoeffs.values) lcm = lcmOf(lcm, c.den)
    val scale = BigFraction.of(lcm, BigInteger.ONE)
    fun scaled(c: BigFraction): BigInteger = (c * scale).num
    fun wide(c: BigFraction): Boolean = scaled(c).abs() > MAX_EXACT_ROW_BIG
    if (d.intCoeffs.values.any { wide(it) } || d.realCoeffs.values.any { wide(it) } || wide(d.constant)) {
        return wideRealRow(d, scale, op, strict)
    }
    fun exact(c: BigFraction): Double = scaled(c).longValue().toDouble()
    val intVars = IntArray(d.intCoeffs.size)
    val intCoeffs = DoubleArray(d.intCoeffs.size)
    var i = 0
    for ((v, c) in d.intCoeffs) {
        intVars[i] = v
        intCoeffs[i] = exact(c)
        i++
    }
    val realVars = IntArray(d.realCoeffs.size)
    val realCoeffs = DoubleArray(d.realCoeffs.size)
    i = 0
    for ((v, c) in d.realCoeffs) {
        realVars[i] = v
        realCoeffs[i] = exact(c)
        i++
    }
    val bound = exact(d.constant.negated())
    return Linear(intVars, intCoeffs, realVars, realCoeffs, op, bound, strict)
}

// Exact encoding of a row whose least-common-denominator scale pushes an integer coefficient past the
// exact-double range: each oversized coefficient `C·x` decomposes in base `B = 2⁴⁰` over chain
// variables `x⁽ᵏ⁾ = Bᵏ·x` (fresh reals tied by exact defining rows, cached per variable), so every
// emitted coefficient is a base-B digit — an exact double. An oversized constant moves onto the
// auxiliary real pinned to 1. Depth is capped at WIDE_MAX_DIGITS digits; beyond that the row rejects
// (exact-or-reject, never approximated).
private fun SmtLib.Builder.wideRealRow(d: RealComb, scale: BigFraction, op: LinearOp, strict: Boolean): Linear {
    val realVarsL = ArrayList<Int>()
    val realCoeffsL = ArrayList<Double>()
    val intVarsL = ArrayList<Int>()
    val intCoeffsL = ArrayList<Double>()
    fun emit(varId: Int, isInt: Boolean, c: BigInteger) {
        val neg = c.signum() < 0
        var mag = c.abs()
        var k = 0
        while (mag.signum() != 0) {
            if (k >= WIDE_MAX_DIGITS) smtUnsupported("real coefficient exceeds the exactly-representable range")
            val digit = (mag % WIDE_BASE_BIG).longValue()
            mag /= WIDE_BASE_BIG
            if (digit != 0L) {
                val coeff = (if (neg) -digit else digit).toDouble()
                if (k == 0 && isInt) {
                    intVarsL.add(varId)
                    intCoeffsL.add(coeff)
                } else {
                    realVarsL.add(if (k == 0) varId else chainVar(varId, k, isInt))
                    realCoeffsL.add(coeff)
                }
            }
            k++
        }
    }
    for ((v, c) in d.intCoeffs) emit(v, true, (c * scale).num)
    for ((v, c) in d.realCoeffs) emit(v, false, (c * scale).num)
    val rhs = (d.constant.negated() * scale).num
    val bound: Double
    if (rhs.abs() <= MAX_EXACT_ROW_BIG) {
        bound = rhs.longValue().toDouble()
    } else {
        emit(oneVar(), false, -rhs)
        bound = 0.0
    }
    return Linear(
        intVarsL.toIntArray(),
        intCoeffsL.toDoubleArray(),
        realVarsL.toIntArray(),
        realCoeffsL.toDoubleArray(),
        op,
        bound,
        strict,
    )
}

// The chain variable standing for `Bᵏ · var`, with its exact defining row added on first use.
private fun SmtLib.Builder.chainVar(varId: Int, k: Int, isInt: Boolean): Int {
    val key = (varId.toLong() shl 3) or (k.toLong() shl 1) or (if (isInt) 1L else 0L)
    realChainVars[key]?.let { return it }
    val fresh = nextReal++
    val prev = if (k == 1) varId else chainVar(varId, k - 1, isInt)
    val base = WIDE_BASE.toDouble()
    // chain − B·prev = 0; at k == 1 the base term is the original variable (integer or real).
    val row = if (k == 1 && isInt) {
        Linear(intArrayOf(varId), doubleArrayOf(-base), intArrayOf(fresh), doubleArrayOf(1.0), LinearOp.EQ, 0.0)
    } else {
        Linear(IntArray(0), DoubleArray(0), intArrayOf(fresh, prev), doubleArrayOf(1.0, -base), LinearOp.EQ, 0.0)
    }
    factors.add(row)
    realChainVars[key] = fresh
    return fresh
}

// The auxiliary real pinned to 1 (for oversized row constants), created on first use.
private fun SmtLib.Builder.oneVar(): Int {
    if (realOneVar >= 0) return realOneVar
    val fresh = nextReal++
    factors.add(Linear(IntArray(0), DoubleArray(0), intArrayOf(fresh), doubleArrayOf(1.0), LinearOp.EQ, 1.0))
    realOneVar = fresh
    return fresh
}

private fun lcmOf(a: BigInteger, b: BigInteger): BigInteger = ((a * b) / a.gcd(b)).abs()

/**
 * Reify a real relation `(op a b)` as a Boolean literal: an inequality atom becomes one
 * [ReifiedRealLinear]; an equality is the conjunction of its two inequality atoms (its complement is
 * a disjunction no single row expresses). Pure-integer sides fall back to the integer reification.
 */
internal fun SmtLib.Builder.reifyRealRel(op: String, a: RealComb, b: RealComb): Int {
    val d = a.plus(b.scaled(BigFraction.MINUS_ONE))
    if (op == "=") {
        return tseitinAnd(
            listOf(reifyRealAtom(d, LinearOp.LE, strict = false), reifyRealAtom(d, LinearOp.GE, strict = false)),
        )
    }
    val (linOp, strict) = when (op) {
        "<=" -> LinearOp.LE to false
        ">=" -> LinearOp.GE to false
        "<" -> LinearOp.LE to true
        ">" -> LinearOp.GE to true
        else -> throw UnsupportedSmtException("real relation '$op'")
    }
    return reifyRealAtom(d, linOp, strict)
}

private fun SmtLib.Builder.reifyRealAtom(d: RealComb, linOp: LinearOp, strict: Boolean): Int {
    if (d.isConstant) {
        val sign = d.constant.signum()
        val holds = when {
            linOp == LinearOp.LE -> if (strict) sign < 0 else sign <= 0
            linOp == LinearOp.GE -> if (strict) sign > 0 else sign >= 0
            else -> sign == 0
        }
        return if (holds) trueLit() else Lit.negate(trueLit())
    }
    if (d.realCoeffs.isEmpty()) {
        val scaled = integerRow(d) ?: throw UnsupportedSmtException("rational comparison exceeds the 64-bit range")
        val (vars, coeffs, bound) = scaled
        val adjusted = when {
            !strict -> bound
            linOp == LinearOp.LE -> bound - 1
            else -> bound + 1
        }
        return reifyLinear(coeffs, vars, linOp, adjusted)
    }
    val row = realRow(d, linOp, strict)
    val constants = checkNotNull(row.realConstants) { "a real row carries continuous constants" }
    val w = newBool()
    factors.add(
        ReifiedRealLinear(
            aux = w,
            vars = row.vars,
            intCoeffs = constants.intCoefficients.toDoubleArray(),
            realVars = row.realVars,
            realCoeffs = constants.realCoefficients.toDoubleArray(),
            op = if (row.op == LinearOp.EQ) LinearOp.LE else row.op,
            bound = constants.bound,
            strict = constants.strict,
        ),
    )
    return Lit.make(w, true)
}

/**
 * A real `ite`: a fresh real variable pinned to each branch by the condition through conditional
 * equality atoms (`c ⇒ v = a`, `¬c ⇒ v = b`), each equality being its two reified inequality atoms.
 */
internal fun SmtLib.Builder.realIte(cond: Int, a: RealComb, b: RealComb): RealComb {
    val v = RealComb(emptyMap(), mapOf(nextReal++ to BigFraction.ONE), BigFraction.ZERO)
    val negCond = Lit.negate(cond)
    val dA = v.plus(a.scaled(BigFraction.MINUS_ONE))
    val dB = v.plus(b.scaled(BigFraction.MINUS_ONE))
    forceClause(negCond, reifyRealAtom(dA, LinearOp.LE, strict = false))
    forceClause(negCond, reifyRealAtom(dA, LinearOp.GE, strict = false))
    forceClause(cond, reifyRealAtom(dB, LinearOp.LE, strict = false))
    forceClause(cond, reifyRealAtom(dB, LinearOp.GE, strict = false))
    return v
}

/**
 * `to_int` of a fractional real term: a fresh unbounded integer `n` with the floor definition
 * `n ≤ r < n + 1` — the upper half strict, riding the delta-rational deciders.
 */
internal fun SmtLib.Builder.realFloor(r: RealComb): LinComb {
    val n = newInt(null, null)
    val nReal = LinComb(mapOf(n to 1), 0).toRealComb()
    factors.add(realRow(nReal.plus(r.scaled(BigFraction.MINUS_ONE)), LinearOp.LE))
    val fracGap = r.plus(nReal.scaled(BigFraction.MINUS_ONE))
        .plus(RealComb(emptyMap(), emptyMap(), BigFraction.MINUS_ONE))
    factors.add(realRow(fracGap, LinearOp.LE, strict = true))
    return LinComb(mapOf(n to 1), 0)
}

/** Fold [t] to a real combination (iteratively, via [evalTerm]). */
internal fun SmtLib.Builder.realTerm(t: SExpr): RealComb = (evalTerm(t, Sort.REAL) as Res.R).comb

/** A real OMT objective: exact-double coefficients over real and int variables. Rejects a rational
 *  coefficient a double cannot carry exactly rather than silently mis-reporting the optimum. */
internal fun SmtLib.Builder.realObjective(t: SExpr, negate: Boolean): LinearObjective {
    val comb0 = realTerm(t)
    val comb = if (negate) comb0.scaled(BigFraction.MINUS_ONE) else comb0
    val intCoeffs = LongArray(nextInt)
    for ((v, c) in comb.intCoeffs) {
        intCoeffs[v] = c.toExactLongOrNull()
            ?: smtUnsupported("objective coefficient is not an exact integer")
    }
    val realCoeffs = DoubleArray(nextReal)
    for ((v, c) in comb.realCoeffs) {
        realCoeffs[v] = c.toExactDoubleOrNull()
            ?: smtUnsupported("objective coefficient is not exactly representable")
    }
    val const = comb.constant.toExactLongOrNull()
        ?: smtUnsupported("objective constant is not an exact integer")
    return LinearObjective(intCoefficients = intCoeffs, constant = const, realCoefficients = realCoeffs)
}

internal fun BigFraction.toExactLongOrNull(): Long? =
    if (den == BigInteger.ONE && num.bitLength() <= 63) num.longValue(exactRequired = false) else null

private fun BigFraction.toExactDoubleOrNull(): Double? {
    val v = toDouble()
    val back = BigFraction.ofDouble(v) ?: return null
    return if (back == this) v else null
}

// Coefficient magnitude cap for an emitted row: integers up to 2^53 are exact doubles.
private const val MAX_EXACT_ROW = 1L shl 53

private val MAX_EXACT_ROW_BIG = BigInteger.fromLong(MAX_EXACT_ROW)

// Chain-encoding digit base: digits stay far under the exact-double cap.
private const val WIDE_BASE = 1L shl 40

private val WIDE_BASE_BIG = BigInteger.fromLong(WIDE_BASE)

// Digit cap for the chain encoding (`B⁴ = 2¹⁶⁰` covers any realistic decimal literal).
private const val WIDE_MAX_DIGITS = 4
