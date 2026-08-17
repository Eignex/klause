package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.internals.floorDivLong
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.ALL_DIFFERENT_WITNESS_MIN_ARITY
import com.eignex.klause.formats.IntComb
import com.eignex.klause.formats.LinComb
import com.eignex.klause.formats.WideLinComb
import com.eignex.klause.formats.allDifferentWindowSize
import com.eignex.klause.formats.constProduct
import com.eignex.klause.formats.isConstant
import com.eignex.klause.formats.reifyAllDifferentWitness
import com.eignex.klause.formats.scaleByConst
import com.eignex.klause.formats.scaleIntComb
import com.eignex.klause.formats.sumIntCombs
import com.eignex.klause.formats.trueLit
import com.eignex.klause.formats.tseitinAnd
import com.eignex.klause.formats.tseitinIff
import com.eignex.klause.formats.tseitinOr
import com.eignex.klause.lp.BigFraction
import com.eignex.klause.solver.Lit
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.math.abs

/**
 * Iterative evaluator for SMT-LIB terms: folds an [SExpr] tree into either a boolean literal or an
 * integer [LinComb], driven by an explicit heap work-stack rather than the call stack. SMT-LIB
 * formulas nest thousands of operators deep, so a recursive `compileBool`/`linearTerm` overflows the
 * JVM stack on degenerate input; this keeps the whole descent on the heap. `let` scoping is handled
 * inline via [Frame.MakeScope]/[Frame.PopScope] frames (never a nested [evalTerm] call), so even a
 * deeply nested chain of `let`-bound values stays off the stack.
 */
internal enum class Sort { BOOL, INT, REAL }

/** A folded term result: a boolean literal ([B]), an integer linear combination ([I]), or a real
 *  rational combination ([R]). */
internal sealed interface Res {
    class B(val lit: Int) : Res
    class I(val term: IntComb) : Res
    class R(val comb: RealComb) : Res
}

private fun Res.asLit(): Int = (this as Res.B).lit
private fun Res.asIntComb(): IntComb = (this as Res.I).term

private fun narrowRes(lin: LinComb): Res.I = Res.I(IntComb.Narrow(lin))

/** A folded operand as a real combination: real results directly, int results via the embedding. */
private fun Res.asReal(): RealComb = when (this) {
    is Res.R -> comb

    is Res.I -> when (val t = term) {
        is IntComb.Narrow -> t.lin.toRealComb()
        is IntComb.Wide -> t.lin.toRealComb()
    }

    is Res.B -> smtUnsupported("boolean term used as Real")
}

private sealed interface Frame {
    class Eval(val node: SExpr, val sort: Sort) : Frame
    class Combine(val node: SExpr.SList, val sort: Sort, val argc: Int) : Frame
    class MakeScope(val names: List<String>, val bools: BooleanArray) : Frame
    object PopScope : Frame
}

/** Fold [root] to a [Res] of the requested [sort] with no unbounded call-stack recursion. */
internal fun SmtLib.Builder.evalTerm(root: SExpr, sort: Sort): Res {
    val work = ArrayDeque<Frame>()
    val vals = ArrayDeque<Res>()
    work.addLast(Frame.Eval(root, sort))

    while (work.isNotEmpty()) {
        when (val fr = work.removeLast()) {
            is Frame.PopScope -> popLetScope()

            is Frame.MakeScope -> {
                val k = fr.names.size
                // Values were pushed in binding order, so the top-of-stack is the last binding.
                val popped = ArrayList<Res>(k)
                repeat(k) { popped.add(vals.removeLast()) }
                val bound = ArrayList<Pair<String, SmtLib.Builder.Binding>>(k)
                for (i in 0 until k) {
                    val res = popped[k - 1 - i]
                    val isReal = res is Res.R
                    val b = SmtLib.Builder.Binding(isBool = fr.bools[i], isReal = isReal)
                    when {
                        fr.bools[i] -> b.lit = res.asLit()
                        isReal -> b.real = res.comb
                        else -> b.lin = res.asIntComb()
                    }
                    bound.add(fr.names[i] to b)
                }
                pushScopeBindings(bound)
            }

            is Frame.Combine -> {
                val args = ArrayList<Res>(fr.argc)
                repeat(fr.argc) { args.add(vals.removeLast()) }
                args.reverse()
                vals.addLast(combine(fr.node, fr.sort, args))
            }

            is Frame.Eval -> {
                when (val node = fr.node) {
                    is SExpr.Atom -> {
                        // A bare reference to a 0-parameter `define-fun` inlines its body.
                        val m = macros[node.text]
                        if (m != null && m.params.isEmpty()) {
                            work.addLast(Frame.Eval(m.body, fr.sort))
                        } else {
                            vals.addLast(evalAtom(node, fr.sort))
                        }
                    }

                    is SExpr.SList -> {
                        val head = (node.items.firstOrNull() as? SExpr.Atom)?.text
                        when {
                            head == "let" -> scheduleLet(node, fr.sort, work)

                            head != null && macros.containsKey(head) -> scheduleMacro(node, head, fr.sort, work)

                            else -> {
                                val kids = childTasks(node, fr.sort)
                                work.addLast(Frame.Combine(node, fr.sort, kids.size))
                                for (i in kids.indices.reversed()) {
                                    work.addLast(Frame.Eval(kids[i].first, kids[i].second))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    return vals.removeLast()
}

/** Push the frames for `(let (bindings) body)`: evaluate each binding value, build the scope, run the
 *  body, then pop — all in-stack, so nested lets never recurse through [evalTerm]. */
private fun SmtLib.Builder.scheduleLet(node: SExpr.SList, sort: Sort, work: ArrayDeque<Frame>) {
    val bindingList = node.argAt(1, "let bindings") as? SExpr.SList
        ?: smtUnsupported("malformed let bindings")
    val pairs = bindingList.items.map { it as? SExpr.SList ?: smtUnsupported("malformed let binding") }
    val names = pairs.map { it.atomAt(0, "let binding name") }
    val valueExprs = pairs.map { it.argAt(1, "let binding value") }
    val bools = BooleanArray(pairs.size) { isBoolExpr(valueExprs[it]) }
    // LIFO: values first, then build the scope, then the body, then pop.
    work.addLast(Frame.PopScope)
    work.addLast(Frame.Eval(node.argAt(2, "let body"), sort))
    work.addLast(Frame.MakeScope(names, bools))
    for (i in valueExprs.indices.reversed()) {
        val kidSort = when {
            bools[i] -> Sort.BOOL
            isRealExpr(valueExprs[i]) -> Sort.REAL
            else -> Sort.INT
        }
        work.addLast(Frame.Eval(valueExprs[i], kidSort))
    }
}

/** Inline a `define-fun` call `(f a…)` by binding its parameters to the arguments like a `let` and
 *  evaluating the body — non-recursive, so this expands to a bounded nest handled on the work-stack. */
private fun SmtLib.Builder.scheduleMacro(node: SExpr.SList, head: String, sort: Sort, work: ArrayDeque<Frame>) {
    val m = macros.getValue(head)
    val callArgs = node.items.drop(1)
    if (callArgs.size != m.params.size) {
        smtUnsupported("'$head' applied to ${callArgs.size} args, expected ${m.params.size}")
    }
    val bindings = SExpr.SList(m.params.indices.map { SExpr.SList(listOf(SExpr.Atom(m.params[it]), callArgs[it])) })
    scheduleLet(SExpr.SList(listOf(SExpr.Atom("let"), bindings, m.body)), sort, work)
}

/** The child sub-terms to fold (with their sorts) for a non-`let` list node. */
private fun SmtLib.Builder.childTasks(node: SExpr.SList, sort: Sort): List<Pair<SExpr, Sort>> {
    val head = (node.items[0] as? SExpr.Atom)?.text
    val args = node.items.drop(1)
    val kids = when (sort) {
        Sort.BOOL -> when (head) {
            "not", "and", "or", "xor", "=>" -> args.map { it to Sort.BOOL }

            "<=", "<", ">=", ">" ->
                args.map { it to (if (isRealRelation(node)) Sort.REAL else Sort.INT) }

            "ite" -> args.map { it to Sort.BOOL }

            "distinct" -> args.map { arg ->
                val argSort = when {
                    isBoolExpr(arg) -> Sort.BOOL
                    isRealExpr(arg) -> Sort.REAL
                    else -> Sort.INT
                }
                arg to argSort
            }

            "=" -> if (isArithmeticRelation(node)) {
                args.map { it to (if (isRealRelation(node)) Sort.REAL else Sort.INT) }
            } else {
                args.map { it to Sort.BOOL }
            }

            else -> null
        }

        Sort.INT -> when (head) {
            "+", "-", "*" -> args.map { it to Sort.INT }
            "to_real" -> args.map { it to Sort.INT }
            "to_int" -> args.map { it to (if (isRealExpr(it)) Sort.REAL else Sort.INT) }
            "abs" -> args.map { it to Sort.INT }
            "div", "mod" -> args.map { it to Sort.INT }
            "ite" -> listOf(args[0] to Sort.BOOL, args[1] to Sort.INT, args[2] to Sort.INT)
            "/" -> smtUnsupported("real division '/' in an integer context")
            else -> null
        }

        Sort.REAL -> when (head) {
            "+", "-", "*", "/" -> args.map { it to (if (isRealExpr(it)) Sort.REAL else Sort.INT) }
            "to_real" -> args.map { it to Sort.INT }
            "ite" -> listOf(args[0] to Sort.BOOL, args[1] to Sort.REAL, args[2] to Sort.REAL)
            else -> null
        }
    }
    return kids ?: smtUnsupported(
        "unsupported ${if (sort == Sort.BOOL) "boolean" else "int"} op '$head'",
    )
}

/** Combine a list node's already-folded child results [args] into this node's [Res]. */
private fun SmtLib.Builder.combine(node: SExpr.SList, sort: Sort, args: List<Res>): Res {
    val head = node.atomAt(0, "operator")
    return when (sort) {
        Sort.BOOL -> combineBool(node, head, args)
        Sort.INT -> combineInt(head, args)
        Sort.REAL -> combineReal(head, args)
    }
}

/** Combine a real-sorted node: exact-rational folding; `*` and `/` need a constant side. */
private fun SmtLib.Builder.combineReal(head: String, args: List<Res>): Res = when (head) {
    "+" -> Res.R(sumRealCombs(args.map { it.asReal() }))

    "-" -> if (args.size == 1) {
        Res.R(args[0].asReal().scaled(BigFraction.MINUS_ONE))
    } else {
        Res.R(sumRealCombs(args.map { it.asReal() }, negateTail = true))
    }

    "*" -> {
        val parts = args.map { it.asReal() }
        val nonConst = parts.filter { !it.isConstant }
        if (nonConst.size > 1) smtUnsupported("nonlinear multiplication")
        val k = parts.filter { it.isConstant }.fold(BigFraction.ONE) { a, c -> a * c.constant }
        if (nonConst.isEmpty()) {
            Res.R(RealComb(emptyMap(), emptyMap(), k))
        } else {
            Res.R(nonConst[0].scaled(k))
        }
    }

    "/" -> {
        val den = args.drop(1).map { it.asReal() }
        if (den.any { !it.isConstant || it.constant.isZero }) {
            smtUnsupported("non-constant or zero divisor in real division")
        }
        Res.R(den.fold(args[0].asReal()) { acc, d -> acc.scaled(d.constant.reciprocal()) })
    }

    "to_real" -> Res.R(args[0].asReal())

    "ite" -> Res.R(realIte(args[0].asLit(), args[1].asReal(), args[2].asReal()))

    else -> smtUnsupported("unsupported real op '$head'")
}

private fun SmtLib.Builder.combineBool(node: SExpr.SList, head: String, args: List<Res>): Res = when (head) {
    "not" -> Res.B(Lit.negate(args[0].asLit()))

    "and" -> Res.B(tseitinAnd(args.map { it.asLit() }))

    "or" -> Res.B(tseitinOr(args.map { it.asLit() }))

    "xor" -> Res.B(args.map { it.asLit() }.reduce { a, b -> Lit.negate(tseitinIff(a, b)) })

    "=>" -> {
        val lits = args.map { it.asLit() }
        Res.B(lits.dropLast(1).foldRight(lits.last()) { a, acc -> tseitinOr(listOf(Lit.negate(a), acc)) })
    }

    "<=", "<", ">=", ">" -> if (args.any { it is Res.R }) {
        requireChainableRelation(node, head)
        val terms = args.map { it.asReal() }
        Res.B(chainReified(terms.size) { i -> reifyRealRel(head, terms[i], terms[i + 1]) })
    } else {
        Res.B(reifyRelArgs(node, head, args))
    }

    "distinct" -> if (args.any { it is Res.R }) {
        val terms = args.map { it.asReal() }
        val neLits = ArrayList<Int>()
        for (i in terms.indices) {
            for (j in i + 1 until terms.size) {
                neLits.add(Lit.negate(reifyRealRel("=", terms[i], terms[j])))
            }
        }
        Res.B(tseitinAnd(neLits))
    } else {
        Res.B(distinctFromArgs(args))
    }

    "ite" -> Res.B(tseitinIte(args[0].asLit(), args[1].asLit(), args[2].asLit()))

    "=" -> if (isArithmeticRelation(node)) {
        if (args.any { it is Res.R }) {
            Res.B(chainEqToFirst(args.map { it.asReal() }) { a, b -> reifyRealRel("=", a, b) })
        } else if (args.size == 2) {
            Res.B(reifyRelArgs(node, head, args))
        } else {
            Res.B(chainEqToFirst(args.map { it.asIntComb() }) { x, y -> reifyRelation("=", x, y) })
        }
    } else {
        Res.B(chainEqToFirst(args.map { it.asLit() }, ::tseitinIff))
    }

    else -> smtUnsupported("unsupported boolean op '$head'")
}

private fun SmtLib.Builder.combineInt(head: String, args: List<Res>): Res = when (head) {
    "+" -> Res.I(sumIntCombs(args.map { it.asIntComb() }))

    "-" -> if (args.size == 1) {
        Res.I(scaleIntComb(args[0].asIntComb(), -1L))
    } else {
        Res.I(sumIntCombs(args.map { it.asIntComb() }, negateTail = true))
    }

    "*" -> {
        val parts = args.map { it.asIntComb() }
        val nonConst = parts.filterNot { it.isConstant() }
        if (nonConst.size > 1) smtUnsupported("nonlinear multiplication")
        val k = constProduct(parts.filter { it.isConstant() })
        if (nonConst.isEmpty()) Res.I(k) else Res.I(scaleByConst(nonConst[0], k))
    }

    "to_real" -> Res.I(args[0].asIntComb())

    // `to_int` of an integral real term folds back to its integer combination; a genuinely
    // fractional real gets the floor definition (a fresh integer with `n ≤ r < n + 1`).
    "to_int" -> when (val a = args[0]) {
        is Res.I -> a
        is Res.R -> narrowRes(a.comb.toLinCombOrNull() ?: realFloor(a.comb))
        else -> smtUnsupported("to_int over a boolean term")
    }

    "abs" -> when (val x = args[0].asIntComb()) {
        is IntComb.Narrow -> narrowRes(absTerm(x.lin))
        is IntComb.Wide -> Res.I(wideAbsTerm(x.lin))
    }

    "div" -> divModRes(args[0].asIntComb(), args[1].asIntComb(), quotient = true)

    "mod" -> divModRes(args[0].asIntComb(), args[1].asIntComb(), quotient = false)

    "ite" -> iteRes(args[0].asLit(), args[1].asIntComb(), args[2].asIntComb())

    else -> smtUnsupported("unsupported int op '$head'")
}

/** `div`/`mod` on the 64-bit path when both operands fit it, at arbitrary precision otherwise. */
private fun SmtLib.Builder.divModRes(a: IntComb, b: IntComb, quotient: Boolean): Res =
    if (a is IntComb.Narrow && b is IntComb.Narrow) {
        narrowRes(divModTerm(a.lin, b.lin, quotient))
    } else {
        Res.I(wideDivModTerm(a, b, quotient))
    }

/**
 * `v = if cond then a else b`: a fresh quantity pinned to each branch by the condition.
 *
 * On the 64-bit path its domain is the union of the two branch ranges, so an unbounded default never
 * enters the reified equality. Past 64 bits the quantity is digit columns wide enough for either branch.
 */
private fun SmtLib.Builder.iteRes(cond: Int, a: IntComb, b: IntComb): Res {
    if (a is IntComb.Narrow && b is IntComb.Narrow) {
        val (aLo, aHi) = linCombRange(a.lin)
        val (bLo, bHi) = linCombRange(b.lin)
        val loU = if (aLo == null || bLo == null) null else minOf(aLo, bLo)
        val hiU = if (aHi == null || bHi == null) null else maxOf(aHi, bHi)
        val self = LinComb(mapOf(newInt(loU, hiU) to 1), 0)
        factors.add(Clause(intArrayOf(Lit.negate(cond), reifyEq(self, a.lin)))) // cond ⇒ v = a
        factors.add(Clause(intArrayOf(cond, reifyEq(self, b.lin)))) // ¬cond ⇒ v = b
        return narrowRes(self)
    }
    val magA = intCombMagnitude(a)
    val magB = intCombMagnitude(b)
    val self = freshWideInt(if (magA > magB) magA else magB)
    factors.add(Clause(intArrayOf(Lit.negate(cond), reifyRelation("=", self, a))))
    factors.add(Clause(intArrayOf(cond, reifyRelation("=", self, b))))
    return Res.I(self)
}

/** Post a hard linear relation `a ⟨op⟩ b`; a variable-free relation is checked for consistency
 *  (posting the false literal when it cannot hold) rather than an empty [Linear] row. */
private fun SmtLib.Builder.postLinearRel(a: LinComb, b: LinComb, op: LinearOp) {
    val (vars, coeffs, bound) = diff(a, b)
    assertLinearRow(coeffs, vars, op, bound)
}

/** `abs(x)` as a fresh `y ≥ 0` pinned to `|x|`: `y ≥ x`, `y ≥ −x`, and `y = x ∨ y = −x`. The fresh
 *  var is bounded by `x`'s own range so an unbounded default never enters its defining constraints. */
private fun SmtLib.Builder.absTerm(x: LinComb): LinComb {
    val (xLo, xHi) = linCombRange(x)
    val y = LinComb(mapOf(newInt(0L, absHi(xLo, xHi)) to 1), 0)
    val negX = x.scaled(-1L)
    postLinearRel(y, x, LinearOp.GE)
    postLinearRel(y, negX, LinearOp.GE)
    factors.add(Clause(intArrayOf(reifyEq(y, x), reifyEq(y, negX))))
    return y
}

/** Euclidean `div`/`mod` by a **constant** divisor `d`: fresh `q`, `m` with `a = d·q + m` and
 *  `0 ≤ m < |d|`. `q` is bounded by `a`'s range / `d`; when `a` is open on the driving side `q` stays
 *  open (an [PresolveDomain.Open] aux var). A non-constant divisor is genuinely non-linear, so rejected. */
private fun SmtLib.Builder.divModTerm(a: LinComb, b: LinComb, quotient: Boolean): LinComb {
    if (b.coeffs.isNotEmpty()) smtUnsupported("non-constant divisor in div/mod")
    val d = b.constant
    if (d == 0L) smtUnsupported("division by zero in div/mod")
    val absd = if (d < 0) -d else d
    val (aLo, aHi) = linCombRange(a)
    // floorDiv is monotone in `a` (increasing for d>0, decreasing for d<0); pick the a-bound driving
    // each side of q's range, with ±1 slack for the remainder. A null (open) driving bound leaves q open.
    val qLoA = if (d > 0) aLo else aHi
    val qHiA = if (d > 0) aHi else aLo
    val qLo = qLoA?.let { addOrNull(floorDivLong(it, d), -1L) }
    val qHi = qHiA?.let { addOrNull(floorDivLong(it, d), 1L) }
    val m = LinComb(mapOf(newInt(0L, absd - 1) to 1), 0)
    val q = LinComb(mapOf(newInt(qLo, qHi) to 1), 0)
    postLinearRel(a, q.scaled(d).plus(m), LinearOp.EQ) // a = d·q + m
    return if (quotient) q else m
}

/** Upper bound of `|x|` given `x ∈ [lo, hi]` (each null = infinite); null when unbounded either way. */
private fun absHi(lo: Long?, hi: Long?): Long? {
    if (lo == null || hi == null) return null
    val a = if (lo == Long.MIN_VALUE) null else abs(lo)
    val b = if (hi == Long.MIN_VALUE) null else abs(hi)
    return if (a == null || b == null) null else maxOf(a, b)
}

/** The value range `[lo, hi]` of [lin] over the current presolve domains; a side is null when
 *  unbounded (an open domain or an arithmetic overflow) — infinity is structural, no `±Long/4`. */
private fun SmtLib.Builder.linCombRange(lin: LinComb): Pair<Long?, Long?> {
    var lo: Long? = lin.constant
    var hi: Long? = lin.constant
    for ((v, c) in lin.coeffs) {
        val dLo: Long?
        val dHi: Long?
        when (val d = intDomains[v]) {
            is PresolveDomain.Finite -> {
                dLo = d.domain.min
                dHi = d.domain.max
            }

            is PresolveDomain.Open -> {
                dLo = d.lo
                dHi = d.hi
            }
        }
        val curLo = lo
        if (curLo != null) {
            val bnd = if (c >= 0) dLo else dHi
            lo = if (bnd == null) null else mulAdd(curLo, c, bnd)
        }
        val curHi = hi
        if (curHi != null) {
            val bnd = if (c >= 0) dHi else dLo
            hi = if (bnd == null) null else mulAdd(curHi, c, bnd)
        }
    }
    return lo to hi
}

/** Reify an arithmetic relation from its folded operands (wide when a value exceeds 64 bits); an n-ary
 *  chain reifies as the conjunction of its n−1 consecutive pairs. */
private fun SmtLib.Builder.reifyRelArgs(node: SExpr.SList, op: String, args: List<Res>): Int {
    requireChainableRelation(node, op)
    val terms = args.map { it.asIntComb() }
    return chainReified(terms.size) { i -> reifyRelation(op, terms[i], terms[i + 1]) }
}

/** The literal for a chainable relation over [n] operands: the single pair, or the conjunction of the
 *  n−1 consecutive pairs. The native chain factor is hard-only (no reified form), so a chain under
 *  boolean structure stays a conjunction of reified pairs. */
private inline fun SmtLib.Builder.chainReified(n: Int, reifyPair: (Int) -> Int): Int =
    if (n == 2) reifyPair(0) else tseitinAnd((0 until n - 1).map(reifyPair))

/** Reified `distinct` over folded operands (bool operands channelled to a 0/1 int term): the linear-size
 *  witness encoding where every operand is a bare finite-domain variable, else pairwise `!=`. */
private fun SmtLib.Builder.distinctFromArgs(args: List<Res>): Int {
    if (args.size < 2) return trueLit()
    val terms = args.map {
        if (it is Res.B) IntComb.Narrow(litToIntTerm(it.lit)) else it.asIntComb()
    }
    witnessDistinct(terms)?.let { return it }
    val neLits = ArrayList<Int>()
    for (i in terms.indices) {
        for (j in i + 1 until terms.size) neLits.add(reifyRelation("distinct", terms[i], terms[j]))
    }
    return tseitinAnd(neLits)
}

/** The witness reification of `distinct`, or null when an operand is not a bare variable, repeats another,
 *  or carries an open domain — the same gate the asserted form uses to reach the native global. */
private fun SmtLib.Builder.witnessDistinct(terms: List<IntComb>): Int? {
    if (terms.size < ALL_DIFFERENT_WITNESS_MIN_ARITY) return null
    val vars = IntArray(terms.size) { i ->
        val narrow = terms[i] as? IntComb.Narrow ?: return null
        narrow.lin.asSimpleVar() ?: return null
    }
    if (vars.toHashSet().size != vars.size) return null
    var lo = Long.MAX_VALUE
    var hi = Long.MIN_VALUE
    for (v in vars) {
        val d = intDomains[v] as? PresolveDomain.Finite ?: return null
        if (d.domain.min < lo) lo = d.domain.min
        if (d.domain.max > hi) hi = d.domain.max
    }
    val size = allDifferentWindowSize(lo, hi) ?: return null
    return reifyAllDifferentWitness(vars, lo, size) { min, max -> newInt(min, max) }
}

/** Fold an atom to a boolean literal, integer term, or real term. */
private fun SmtLib.Builder.evalAtom(node: SExpr.Atom, sort: Sort): Res = if (sort == Sort.REAL) {
    evalRealAtom(node)
} else if (sort == Sort.BOOL) {
    when (node.text) {
        "true" -> Res.B(trueLit())

        "false" -> Res.B(Lit.negate(trueLit()))

        else -> lookup(node.text)?.let { Res.B(boolBinding(node.text, it)) }
            ?: Res.B(
                Lit.make(
                    boolNames[node.text] ?: smtUnsupported("unknown bool '${node.text}'"),
                    true,
                ),
            )
    }
} else {
    val n = node.text.toLongOrNull()
    when {
        n != null -> narrowRes(LinComb(emptyMap(), n))

        // An integer literal beyond Long is carried as a wide combination and lowered to a wide factor.
        isIntegerLiteral(node.text) -> Res.I(IntComb.Wide(WideLinComb(emptyMap(), BigInteger.parseString(node.text))))

        isRealLiteral(node.text) ->
            smtUnsupported("real literal '${node.text}' (integer context)")

        node.text.startsWith("#") ->
            smtUnsupported("bitvector literal '${node.text}' (integer context)")

        else -> lookup(node.text)?.let { Res.I(intBinding(node.text, it)) }
            ?: narrowRes(
                LinComb(
                    mapOf(
                        (
                            intNames[node.text] ?: smtUnsupported(
                                "unknown int var '${node.text}'",
                            )
                            ) to 1,
                    ),
                    0,
                ),
            )
    }
}

/** Fold a real-sorted atom: numeral / decimal literals, real variables, bindings, and (via the
 *  implicit embedding) integer variables reached from a real context through `to_real`. */
private fun SmtLib.Builder.evalRealAtom(node: SExpr.Atom): Res {
    val lit = parseRealLiteral(node.text)
    if (lit != null) return Res.R(RealComb(emptyMap(), emptyMap(), lit))
    val binding = lookup(node.text)
    if (binding != null) {
        return when {
            binding.isBool -> smtUnsupported("'${node.text}' used as Real but bound to a Bool term")

            binding.isReal -> Res.R(
                binding.real ?: smtUnsupported("'${node.text}' has no compiled Real value"),
            )

            else -> when (val ic = intBinding(node.text, binding)) {
                is IntComb.Narrow -> Res.R(ic.lin.toRealComb())
                is IntComb.Wide -> smtUnsupported("'${node.text}' beyond the 64-bit range used as Real")
            }
        }
    }
    val rv = realNames[node.text]
    if (rv != null) return Res.R(RealComb(emptyMap(), mapOf(rv to BigFraction.ONE), BigFraction.ZERO))
    val iv = intNames[node.text] ?: smtUnsupported("unknown real var '${node.text}'")
    return Res.R(LinComb(mapOf(iv to 1), 0).toRealComb())
}
