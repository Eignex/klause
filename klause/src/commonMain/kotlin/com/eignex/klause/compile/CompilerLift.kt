package com.eignex.klause.compile

import com.eignex.klause.ast.BoolExpr
import com.eignex.klause.ast.Implies
import com.eignex.klause.ast.IntAbs
import com.eignex.klause.ast.IntCmpOp
import com.eignex.klause.ast.IntCompare
import com.eignex.klause.ast.IntDiv
import com.eignex.klause.ast.IntElement
import com.eignex.klause.ast.IntExpr
import com.eignex.klause.ast.IntIfThenElse
import com.eignex.klause.ast.IntLit
import com.eignex.klause.ast.IntMax
import com.eignex.klause.ast.IntMin
import com.eignex.klause.ast.IntMod
import com.eignex.klause.ast.IntMul
import com.eignex.klause.ast.IntRef
import com.eignex.klause.ast.IntScale
import com.eignex.klause.ast.IntSum
import com.eignex.klause.ast.Not
import com.eignex.klause.ast.Or
import com.eignex.klause.ast.SetCard
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.factor.Product

/**
 * Affine-fragment lift for [Compiler.Build]. Rewrites a tree of [IntExpr] so the
 * residual is affine (sums of [IntScale] over [IntRef] plus [IntLit] constants) — every
 * non-affine subexpression ([IntMul], [IntDiv], [IntMod], [IntElement], [IntIfThenElse],
 * [IntMax] / [IntMin], [IntAbs]) is replaced with a fresh aux [IntRef], and auxiliary
 * constraints are emitted that pin the aux to the correct value. After lifting, the
 * top-level assertion path ([CompilerAssertions]) and the reification path
 * ([CompilerLowering]) deal only with linear forms.
 */
internal fun Compiler.Build.lift(expr: IntExpr): IntExpr = when (expr) {
    is IntRef, is IntLit -> expr
    is IntScale -> IntScale(expr.coeff, lift(expr.child))
    is IntSum -> IntSum(expr.children.map { lift(it) })
    is IntMin -> liftMinMax(expr.children, isMin = true)
    is IntMax -> liftMinMax(expr.children, isMin = false)
    is IntAbs -> liftAbs(expr.child)
    is IntIfThenElse -> liftIfThenElse(expr.cond, expr.thenE, expr.elseE)
    is IntElement -> liftElement(expr.index, expr.items)
    is IntMul -> liftMul(expr.left, expr.right)
    is IntDiv -> liftDivMod(expr.num, expr.den, returnRemainder = false)
    is IntMod -> liftDivMod(expr.num, expr.den, returnRemainder = true)
    is SetCard -> liftSetCard(expr)
}

/**
 * Lower `n div d` and `n mod d` together with Euclidean semantics (matching
 * SMT-LIB QF_LIA):
 *
 *   q * d + r = n,    0 ≤ r < |d|,    d ≠ 0.
 *
 * The remainder is always non-negative regardless of the signs of `n` and `d`.
 * For example `(-7) mod 3 = 2` (with `q = -3`) and `(-7) mod (-3) = 2` (with
 * `q = 3`). This disagrees with `kotlin.Int.rem` and Java's `%` operator, which
 * keep the sign of the dividend; callers porting from Java-style semantics need
 * to adjust.
 */
internal fun Compiler.Build.liftDivMod(num: IntExpr, den: IntExpr, returnRemainder: Boolean): IntExpr {
    val nLifted = lift(num)
    val dLifted = lift(den)
    val nDom = domainOf(nLifted)
    val dDom = domainOf(dLifted)
    require(0 !in dDom) { "div/mod requires denominator domain to exclude 0; got $dDom" }
    val nRef = materializeIntVar(nLifted)
    val dRef = materializeIntVar(dLifted)

    val nAbsMax = maxOf(if (nDom.min < 0) -nDom.min else nDom.min, nDom.max)
    val dAbsMax = maxOf(if (dDom.min < 0) -dDom.min else dDom.min, dDom.max)
    val qDomain = IntDomain(-nAbsMax, nAbsMax)
    val rDomain = IntDomain(0, dAbsMax - 1)
    val qName = newAuxIntVar(qDomain)
    val rName = newAuxIntVar(rDomain)
    val dqAbsMaxLong = nAbsMax.toLong() * dAbsMax + dAbsMax
    require(dqAbsMaxLong <= Int.MAX_VALUE) {
        "div/mod intermediate domain overflows Int: |q*d| up to $dqAbsMaxLong " +
            "(numerator domain $nDom, denominator domain $dDom)"
    }
    val dqAbsMax = dqAbsMaxLong.toInt()
    val dqDomain = IntDomain(-dqAbsMax, dqAbsMax)
    val dqName = newAuxIntVar(dqDomain)
    factors += Product(intVarOf(dRef.name), intVarOf(qName), intVarOf(dqName))

    // dq + r = n.
    assertExpr(
        IntCompare(IntSum(listOf(IntRef(dqName), IntRef(rName))), IntCmpOp.EQ, nRef),
    )
    // r < |d|. The rDomain pin already enforces r ≥ 0.
    assertExpr(
        IntCompare(IntRef(rName), IntCmpOp.LT, IntAbs(dRef)),
    )
    // d ≠ 0 is required regardless of domain; handled by the require above (0 ∉ dDom).

    return if (returnRemainder) IntRef(rName) else IntRef(qName)
}

internal fun Compiler.Build.liftMul(left: IntExpr, right: IntExpr): IntExpr {
    val l = lift(left)
    val r = lift(right)
    // Constant folding: const * x or x * const → IntScale.
    if (l is IntLit) return IntScale(l.value, r)
    if (r is IntLit) return IntScale(r.value, l)
    val aRef = materializeIntVar(l)
    val bRef = materializeIntVar(r)
    val aDom = intDomains[intVarOf(aRef.name)]
    val bDom = intDomains[intVarOf(bRef.name)]
    val cornersLong = longArrayOf(
        aDom.min.toLong() * bDom.min,
        aDom.min.toLong() * bDom.max,
        aDom.max.toLong() * bDom.min,
        aDom.max.toLong() * bDom.max,
    )
    val pMin = cornersLong.min()
    val pMax = cornersLong.max()
    require(pMin >= Int.MIN_VALUE && pMax <= Int.MAX_VALUE) {
        "IntMul product domain overflows Int: $aDom * $bDom = [$pMin, $pMax]"
    }
    val productDomain = IntDomain(pMin.toInt(), pMax.toInt())
    val resultName = newAuxIntVar(productDomain)
    factors += Product(intVarOf(aRef.name), intVarOf(bRef.name), intVarOf(resultName))
    return IntRef(resultName)
}

/** Force [expr] into a single [IntRef] so a factor that takes raw int var ids (like
 *  [Product]) can reference it. Affine `IntScale`/`IntSum` get pinned to a fresh aux. */
internal fun Compiler.Build.materializeIntVar(expr: IntExpr): IntRef = when (expr) {
    is IntRef -> expr

    else -> {
        val d = domainOf(expr)
        val name = newAuxIntVar(d)
        val ref = IntRef(name)
        assertExpr(IntCompare(ref, IntCmpOp.EQ, expr))
        ref
    }
}

internal fun Compiler.Build.liftElement(index: IntExpr, items: List<IntExpr>): IntExpr {
    val idxLifted = lift(index)
    val itemsLifted = items.map { lift(it) }
    val itemDoms = itemsLifted.map { domainOf(it) }
    val auxDomain = IntDomain(itemDoms.minOf { it.min }, itemDoms.maxOf { it.max })
    val auxName = newAuxIntVar(auxDomain)
    val auxRef = IntRef(auxName)
    val idxDom = domainOf(idxLifted)
    // For each j the index could take, link the aux to items[j] when index = j; for
    // out-of-bounds j, force index ≠ j.
    for (j in idxDom.min..idxDom.max) {
        if (j in items.indices) {
            assertExpr(
                Implies(
                    IntCompare(idxLifted, IntCmpOp.EQ, IntLit(j)),
                    IntCompare(auxRef, IntCmpOp.EQ, itemsLifted[j]),
                ),
            )
        } else {
            assertExpr(IntCompare(idxLifted, IntCmpOp.NE, IntLit(j)))
        }
    }
    return auxRef
}

internal fun Compiler.Build.liftIfThenElse(cond: BoolExpr, thenE: IntExpr, elseE: IntExpr): IntExpr {
    val tLifted = lift(thenE)
    val eLifted = lift(elseE)
    val tDom = domainOf(tLifted)
    val eDom = domainOf(eLifted)
    val auxName = newAuxIntVar(IntDomain(minOf(tDom.min, eDom.min), maxOf(tDom.max, eDom.max)))
    val auxRef = IntRef(auxName)
    // cond ⇒ aux = thenE; ¬cond ⇒ aux = elseE.
    assertExpr(Implies(cond, IntCompare(auxRef, IntCmpOp.EQ, tLifted)))
    assertExpr(Implies(Not(cond), IntCompare(auxRef, IntCmpOp.EQ, eLifted)))
    return auxRef
}

internal fun Compiler.Build.liftMinMax(children: List<IntExpr>, isMin: Boolean): IntExpr {
    val lifted = children.map { lift(it) }
    val doms = lifted.map { domainOf(it) }
    val auxDomain = if (isMin) {
        IntDomain(doms.minOf { it.min }, doms.minOf { it.max })
    } else {
        IntDomain(doms.maxOf { it.min }, doms.maxOf { it.max })
    }
    val auxName = newAuxIntVar(auxDomain)
    val auxRef = IntRef(auxName)
    val op = if (isMin) IntCmpOp.LE else IntCmpOp.GE
    for (c in lifted) assertExpr(IntCompare(auxRef, op, c))
    val orChildren = lifted.map { IntCompare(auxRef, IntCmpOp.EQ, it) as BoolExpr }
    assertExpr(if (orChildren.size == 1) orChildren[0] else Or(orChildren))
    return auxRef
}

internal fun Compiler.Build.liftAbs(child: IntExpr): IntExpr {
    val lifted = lift(child)
    val d = domainOf(lifted)
    val absMax = maxOf(if (d.min < 0) -d.min else d.min, if (d.max < 0) -d.max else d.max)
    val auxName = newAuxIntVar(IntDomain(0, absMax))
    val auxRef = IntRef(auxName)
    // z >= 0; z >= x; z >= -x; (z = x) ∨ (z = -x).
    assertExpr(IntCompare(auxRef, IntCmpOp.GE, IntLit(0)))
    assertExpr(IntCompare(auxRef, IntCmpOp.GE, lifted))
    assertExpr(IntCompare(auxRef, IntCmpOp.GE, IntScale(-1, lifted)))
    assertExpr(
        Or(
            listOf(
                IntCompare(auxRef, IntCmpOp.EQ, lifted),
                IntCompare(auxRef, IntCmpOp.EQ, IntScale(-1, lifted)),
            ),
        ),
    )
    return auxRef
}

internal fun Compiler.Build.newAuxIntVar(domain: IntDomain): String {
    val name = "__aux_int_${auxIntCounter++}"
    intVarIdByName[name] = newIntVar(domain)
    return name
}

/** Domain of any [IntExpr] post-lift. The expression must reside in the affine
 *  fragment (caller is responsible for lifting non-affine subexpressions first). */
internal fun Compiler.Build.domainOf(expr: IntExpr): IntDomain = when (expr) {
    is IntRef -> intDomains[intVarOf(expr.name)]

    is IntLit -> IntDomain(expr.value, expr.value)

    is IntScale -> {
        val c = expr.coeff
        val d = domainOf(expr.child)
        if (c >= 0) IntDomain(c * d.min, c * d.max) else IntDomain(c * d.max, c * d.min)
    }

    is IntSum -> {
        var lo = 0
        var hi = 0
        for (ch in expr.children) {
            val d = domainOf(ch)
            lo += d.min
            hi += d.max
        }
        IntDomain(lo, hi)
    }

    else -> error("domainOf called on non-affine expression: $expr")
}

// Affine canonical form: Σ coeffs[name] * name + constant.
internal data class Affine(val coeffs: Map<String, Int>, val constant: Int)

internal fun Compiler.Build.affine(expr: IntExpr): Affine = when (expr) {
    is IntRef -> Affine(mapOf(expr.name to 1), 0)

    is IntLit -> Affine(emptyMap(), expr.value)

    is IntScale -> {
        val a = affine(expr.child)
        val coeffs = HashMap<String, Int>(a.coeffs.size)
        for ((k, v) in a.coeffs) coeffs[k] = v * expr.coeff
        Affine(coeffs, a.constant * expr.coeff)
    }

    is IntSum -> {
        val coeffs = HashMap<String, Int>()
        var constant = 0
        for (c in expr.children) {
            val a = affine(c)
            constant += a.constant
            for ((k, v) in a.coeffs) coeffs[k] = (coeffs[k] ?: 0) + v
        }
        coeffs.entries.removeAll { it.value == 0 }
        Affine(coeffs, constant)
    }

    else -> error("affine() called on non-affine expression — caller must lift first: $expr")
}

internal fun Compiler.Build.subtract(left: Affine, right: Affine): Affine {
    val coeffs = HashMap(left.coeffs)
    for ((k, v) in right.coeffs) coeffs[k] = (coeffs[k] ?: 0) - v
    coeffs.entries.removeAll { it.value == 0 }
    return Affine(coeffs, left.constant - right.constant)
}

internal fun Compiler.Build.coeffsToArrays(coeffs: Map<String, Int>): Pair<IntArray, IntArray> {
    val varIds = IntArray(coeffs.size)
    val coeffArr = IntArray(coeffs.size)
    var i = 0
    for ((name, c) in coeffs) {
        varIds[i] = intVarOf(name)
        coeffArr[i] = c
        i++
    }
    return varIds to coeffArr
}
