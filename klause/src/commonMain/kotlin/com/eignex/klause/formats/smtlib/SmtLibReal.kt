package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.ir.LinearObjectiveSpec
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Term
import com.eignex.klause.lowering.LinComb
import com.eignex.klause.lowering.WideLinComb
import com.eignex.klause.lowering.isConstant
import com.eignex.klause.lowering.trueLit
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_TEN
import com.eignex.klause.util.magnitudeBitLength
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.signum
import com.eignex.klause.util.times
import com.eignex.klause.util.toLong
import com.eignex.klause.util.unaryMinus

// Source rows retain arbitrary-precision rational coefficients. Numerical consumers choose their
// own representable projections; strictness and Boolean equivalence remain exact source data.

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
    coeffs.mapValues { BigFraction.of(it.value, BIG_ONE) },
    emptyMap(),
    BigFraction.of(constant, BIG_ONE),
)

/** Syntactic real classifier, worklist-driven like [Compiler.Builder.isBoolExpr] so a deeply
 *  nested arithmetic chain cannot overflow the call stack: true when the term is real-sorted (a real
 *  variable / literal, `to_real`, `/`, or an arithmetic combination with a real operand). */
internal fun Compiler.Builder.isRealExpr(t: SExpr): Boolean {
    val work = ArrayDeque<SExpr>()
    work.addLast(t)
    while (work.isNotEmpty()) {
        when (val node = work.removeLast()) {
            is SExpr.Atom -> {
                val binding = lookup(node.text)
                if (binding != null) {
                    if (binding.isReal) return true
                } else if (node.text in realNames || isRealLiteral(node.text) || macros[node.text]?.isReal == true) {
                    return true
                }
            }

            is SExpr.SList -> when (val head = (node.items.firstOrNull() as? SExpr.Atom)?.text) {
                "to_real", "/" -> return true

                "to_int" -> Unit

                "+", "-", "*", "^" -> for (i in 1 until node.items.size) work.addLast(node.items[i])

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
internal fun Compiler.Builder.isRealRelation(node: SExpr.SList): Boolean {
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
    var num = parseBigInt(digits)
    if (negative) num = -num
    var den = BIG_ONE
    val ten = BIG_TEN
    if (dot >= 0) repeat(body.length - dot - 1) { den *= ten }
    return BigFraction.of(num, den)
}

/** Post a top-level real relation as hard LP-only rows; strictness rides through to the delta-rational
 *  deciders. An n-ary chain `(op a1 … an)` posts its n−1 consecutive relations. */
internal fun Compiler.Builder.assertRealLinear(node: SExpr.SList) {
    val op = node.atomAt(0, "relation operator")
    requireChainableRelation(node, op)
    val terms = (1 until node.items.size).map { realTerm(node.items[it]) }
    for (i in 0 until terms.size - 1) assertRealRelation(op, terms[i], terms[i + 1])
}

/** Post one real relation `a ⟨op⟩ b`, resolving a variable-free comparison to trivially true or unsat. */
private fun Compiler.Builder.assertRealRelation(op: String, a: RealComb, b: RealComb) {
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
    factors.add(realRow(d, linOp, strict))
}

/** Retain exact rational source coefficients, strictness and tagged numeric coordinates. */
internal fun Compiler.Builder.realRow(d: RealComb, op: LinearOp, strict: Boolean = false): ExactLinear =
    exactRealRow(d, op, strict)

private fun exactRealRow(
    d: RealComb,
    op: LinearOp,
    strict: Boolean,
    activator: Int = LinearRow.ALWAYS,
): ExactLinear = ExactLinear.of(
    (d.intCoeffs.keys.map(Term::ofIntVar) + d.realCoeffs.keys.map(Term::ofRealVar)).toIntArray(),
    d.intCoeffs.values.toList() + d.realCoeffs.values.toList(), op, d.constant.negated(), strict, activator,
)

/**
 * Reify a real relation `(op a b)` as a Boolean literal: an inequality atom becomes one
 * exact reified row; an equality is the conjunction of its two inequality atoms (its complement is
 * a disjunction no single row expresses). Integer-only rows retain exact lattice thresholds.
 */
internal fun Compiler.Builder.reifyRealRel(op: String, a: RealComb, b: RealComb): Int {
    val d = a.plus(b.scaled(BigFraction.MINUS_ONE))
    if (op == "=") {
        return reifyAnd(
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

private fun Compiler.Builder.reifyRealAtom(d: RealComb, linOp: LinearOp, strict: Boolean): Int {
    if (d.isConstant) {
        val sign = d.constant.signum()
        val holds = when {
            linOp == LinearOp.LE -> if (strict) sign < 0 else sign <= 0
            linOp == LinearOp.GE -> if (strict) sign > 0 else sign >= 0
            else -> sign == 0
        }
        return if (holds) trueLit() else Lit.negate(trueLit())
    }
    val w = newBool()
    factors.add(exactRealRow(d, linOp, strict, w))
    return Lit.make(w, true)
}

internal fun Compiler.Builder.realIte(cond: Int, a: RealComb, b: RealComb): RealComb = realSelection(cond, a, b)

/**
 * `to_int` of a fractional real term: a fresh unbounded integer `n` with the floor definition
 * `n ≤ r < n + 1` — the upper half strict, riding the delta-rational deciders.
 */
internal fun Compiler.Builder.realFloor(r: RealComb): LinComb {
    val n = newInt(null, null)
    val nReal = LinComb(mapOf(n to 1), 0).toRealComb()
    factors.add(realRow(nReal.plus(r.scaled(BigFraction.MINUS_ONE)), LinearOp.LE))
    val fracGap = r.plus(nReal.scaled(BigFraction.MINUS_ONE))
        .plus(RealComb(emptyMap(), emptyMap(), BigFraction.MINUS_ONE))
    factors.add(realRow(fracGap, LinearOp.LE, strict = true))
    return LinComb(mapOf(n to 1), 0)
}

/** Fold [t] to a real combination (iteratively, via [evalTerm]). */
internal fun Compiler.Builder.realTerm(t: SExpr): RealComb = (evalTerm(t, Sort.REAL) as Res.R).comb

/** A real OMT objective: exact-double coefficients over real and int variables. Rejects a rational
 *  coefficient a double cannot carry exactly rather than silently mis-reporting the optimum. */
internal fun Compiler.Builder.realObjective(t: SExpr, negate: Boolean): LinearObjectiveSpec {
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
    return LinearObjectiveSpec(intCoefficients = intCoeffs, constant = const, realCoefficients = realCoeffs)
}

internal fun BigFraction.toExactLongOrNull(): Long? =
    if (den == BIG_ONE && num.magnitudeBitLength() <= 63) num.toLong() else null

private fun BigFraction.toExactDoubleOrNull(): Double? {
    val v = toDouble()
    val back = BigFraction.ofDouble(v) ?: return null
    return if (back == this) v else null
}
