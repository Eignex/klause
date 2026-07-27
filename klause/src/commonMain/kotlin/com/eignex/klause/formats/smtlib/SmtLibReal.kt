package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.formats.LinComb
import com.eignex.klause.formats.trueLit
import com.eignex.klause.lp.BigFraction
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.BigInt

// Real-arithmetic lowering for the SMT-LIB front-end (LRA / the real half of LIRA): real variables
// become LP-only continuous columns and top-level non-strict linear real constraints become real
// Linear rows — no discretisation. Coefficients are folded as exact rationals (BigFraction), and
// each emitted row is multiplied through by the least common denominator so the row's doubles are
// exact integers: the LP-side certifiers then reason about precisely the asserted constraint.
// Fragment: conjunctive real atoms plus real OMT objectives; distinct and real atoms under boolean
// structure reject with a clear message until the real-atom reification lands.

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
            }

            is SExpr.SList -> when ((node.items[0] as? SExpr.Atom)?.text) {
                "to_real", "/" -> return true
                "to_int" -> Unit
                "+", "-", "*" -> for (i in 1 until node.items.size) work.addLast(node.items[i])
                "ite", "let" -> work.addLast(node.items[2])
                else -> Unit
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
    var num = BigInt.ZERO
    val ten = BigInt.fromLong(10L)
    for (ch in digits) num = num * ten + BigInt.fromLong((ch - '0').toLong())
    if (negative) num = -num
    var den = BigInt.ONE
    if (dot >= 0) repeat(body.length - dot - 1) { den *= ten }
    return BigFraction.of(num, den)
}

/** Post a top-level real relation `(op a b)` as a hard LP-only row. Strict and `distinct` forms
 *  need the real-atom machinery and reject for now. */
internal fun SmtLib.Builder.assertRealLinear(node: SExpr.SList) {
    val op = node.atomAt(0, "relation operator")
    requireBinaryRelation(node, op)
    val linOp = when (op) {
        "<=" -> LinearOp.LE
        ">=" -> LinearOp.GE
        "=" -> LinearOp.EQ
        "<", ">" -> smtUnsupported("strict real relation '$op' (not yet supported over reals)")
        else -> smtUnsupported("real relation '$op'")
    }
    val a = realTerm(node.items[1])
    val b = realTerm(node.items[2])
    val d = a.plus(b.scaled(BigFraction.MINUS_ONE))
    if (d.isConstant) {
        val holds = when (linOp) {
            LinearOp.LE -> d.constant.signum() <= 0
            LinearOp.GE -> d.constant.signum() >= 0
            else -> d.constant.isZero
        }
        if (!holds) forceTrue(Lit.negate(trueLit()))
        return
    }
    if (d.realCoeffs.isEmpty()) {
        // Integer variables compared against a rational (e.g. `x <= 1.5`): multiplying through by the
        // least common denominator gives an exact all-integer row — no real column is involved.
        val scaled = integerRow(d) ?: smtUnsupported("rational comparison exceeds the 64-bit range")
        val (vars, coeffs, bound) = scaled
        assertLinearRow(coeffs, vars, linOp, bound)
        return
    }
    factors.add(realRow(d, linOp))
}

/** Scale an all-integer-variable `d ⟨op⟩ 0` by the least common denominator into an exact Long row
 *  `Σ coeffs·vars ⟨op⟩ bound`, or null when a scaled value escapes 64 bits. */
internal fun SmtLib.Builder.integerRow(d: RealComb): Triple<IntArray, LongArray, Long>? {
    var lcm = d.constant.den
    for (c in d.intCoeffs.values) lcm = lcmOf(lcm, c.den)
    val scale = BigFraction.of(lcm, BigInt.ONE)
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

/** Scale `d ⟨op⟩ 0` by the least common denominator and emit the exact-integer double row. */
internal fun SmtLib.Builder.realRow(d: RealComb, op: LinearOp): Linear {
    var lcm = d.constant.den
    for (c in d.intCoeffs.values) lcm = lcmOf(lcm, c.den)
    for (c in d.realCoeffs.values) lcm = lcmOf(lcm, c.den)
    val scale = BigFraction.of(lcm, BigInt.ONE)
    fun exact(c: BigFraction): Double {
        val whole = (c * scale)
        val v = whole.num.toLongOrNull()
        if (v == null || whole.den != BigInt.ONE || v < -MAX_EXACT_ROW || v > MAX_EXACT_ROW) {
            smtUnsupported("real coefficient exceeds the exactly-representable range")
        }
        return v.toDouble()
    }
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
    return Linear(intVars, intCoeffs, realVars, realCoeffs, op, bound)
}

private fun lcmOf(a: BigInt, b: BigInt): BigInt {
    val g = a.gcd(b)
    return (a * b).divRem(g).first.abs()
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

internal fun BigFraction.toExactLongOrNull(): Long? = if (den == BigInt.ONE) num.toLongOrNull() else null

private fun BigFraction.toExactDoubleOrNull(): Double? {
    val n = num.toLongOrNull() ?: return null
    val d = den.toLongOrNull() ?: return null
    val v = n.toDouble() / d.toDouble()
    val back = BigFraction.ofDouble(v) ?: return null
    return if (back == this) v else null
}

/** Coefficient magnitude cap for an emitted row: integers up to 2^53 are exact doubles. */
private const val MAX_EXACT_ROW = 1L shl 53
