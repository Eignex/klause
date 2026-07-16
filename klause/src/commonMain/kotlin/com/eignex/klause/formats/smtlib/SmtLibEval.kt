package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.LinComb
import com.eignex.klause.formats.reifyLinear
import com.eignex.klause.formats.trueLit
import com.eignex.klause.formats.tseitinAnd
import com.eignex.klause.formats.tseitinIff
import com.eignex.klause.formats.tseitinOr
import com.eignex.klause.solver.Lit
import kotlin.math.abs

/**
 * Iterative evaluator for SMT-LIB terms: folds an [SExpr] tree into either a boolean literal or an
 * integer [LinComb], driven by an explicit heap work-stack rather than the call stack. SMT-LIB
 * formulas nest thousands of operators deep, so a recursive `compileBool`/`linearTerm` overflows the
 * JVM stack on degenerate input; this keeps the whole descent on the heap. `let` scoping is handled
 * inline via [Frame.MakeScope]/[Frame.PopScope] frames (never a nested [evalTerm] call), so even a
 * deeply nested chain of `let`-bound values stays off the stack.
 */
internal enum class Sort { BOOL, INT }

/** A folded term result: a boolean literal ([B]) or an integer linear combination ([I]). */
internal sealed interface Res {
    class B(val lit: Int) : Res
    class I(val lin: LinComb) : Res
}

private fun Res.asLit(): Int = (this as Res.B).lit
private fun Res.asLin(): LinComb = (this as Res.I).lin

private sealed interface Frame {
    class Eval(val node: SExpr, val sort: Sort) : Frame
    class Combine(val node: SExpr.SList, val sort: Sort, val argc: Int) : Frame
    class MakeScope(val names: List<String>, val bools: BooleanArray) : Frame
    object PopScope : Frame
}

/** Fold [root] to a [Res] of the requested [sort] with no unbounded call-stack recursion. */
internal fun SmtLibQfLia.Builder.evalTerm(root: SExpr, sort: Sort): Res {
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
                val bound = ArrayList<Pair<String, SmtLibQfLia.Builder.Binding>>(k)
                for (i in 0 until k) {
                    val res = popped[k - 1 - i]
                    val b = SmtLibQfLia.Builder.Binding(isBool = fr.bools[i])
                    if (fr.bools[i]) b.lit = res.asLit() else b.lin = res.asLin()
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
private fun SmtLibQfLia.Builder.scheduleLet(node: SExpr.SList, sort: Sort, work: ArrayDeque<Frame>) {
    val bindingList = node.items[1] as? SExpr.SList ?: throw UnsupportedSmtException("malformed let bindings")
    val pairs = bindingList.items.map { it as? SExpr.SList ?: throw UnsupportedSmtException("malformed let binding") }
    val names = pairs.map { (it.items[0] as SExpr.Atom).text }
    val valueExprs = pairs.map { it.items[1] }
    val bools = BooleanArray(pairs.size) { isBoolExpr(valueExprs[it]) }
    // LIFO: values first, then build the scope, then the body, then pop.
    work.addLast(Frame.PopScope)
    work.addLast(Frame.Eval(node.items[2], sort))
    work.addLast(Frame.MakeScope(names, bools))
    for (i in valueExprs.indices.reversed()) {
        work.addLast(Frame.Eval(valueExprs[i], if (bools[i]) Sort.BOOL else Sort.INT))
    }
}

/** Inline a `define-fun` call `(f a…)` by binding its parameters to the arguments like a `let` and
 *  evaluating the body — non-recursive, so this expands to a bounded nest handled on the work-stack. */
private fun SmtLibQfLia.Builder.scheduleMacro(node: SExpr.SList, head: String, sort: Sort, work: ArrayDeque<Frame>) {
    val m = macros.getValue(head)
    val callArgs = node.items.drop(1)
    if (callArgs.size != m.params.size) {
        throw UnsupportedSmtException("'$head' applied to ${callArgs.size} args, expected ${m.params.size}")
    }
    val bindings = SExpr.SList(m.params.indices.map { SExpr.SList(listOf(SExpr.Atom(m.params[it]), callArgs[it])) })
    scheduleLet(SExpr.SList(listOf(SExpr.Atom("let"), bindings, m.body)), sort, work)
}

/** The child sub-terms to fold (with their sorts) for a non-`let` list node. */
private fun SmtLibQfLia.Builder.childTasks(node: SExpr.SList, sort: Sort): List<Pair<SExpr, Sort>> {
    val head = (node.items[0] as? SExpr.Atom)?.text
    val args = node.items.drop(1)
    val kids = if (sort == Sort.BOOL) {
        when (head) {
            "not", "and", "or", "xor", "=>" -> args.map { it to Sort.BOOL }
            "<=", "<", ">=", ">" -> args.map { it to Sort.INT }
            "ite" -> args.map { it to Sort.BOOL }
            "distinct" -> args.map { it to (if (isBoolExpr(it)) Sort.BOOL else Sort.INT) }
            "=" -> if (isArithmeticRelation(node)) args.map { it to Sort.INT } else args.map { it to Sort.BOOL }
            else -> null
        }
    } else {
        when (head) {
            "+", "-", "*" -> args.map { it to Sort.INT }
            "to_real", "to_int", "abs" -> args.map { it to Sort.INT }
            "div", "mod" -> args.map { it to Sort.INT }
            "ite" -> listOf(args[0] to Sort.BOOL, args[1] to Sort.INT, args[2] to Sort.INT)
            "/" -> throw UnsupportedSmtException("real division '/' (QF_LIA is integer-only)")
            else -> null
        }
    }
    return kids ?: throw UnsupportedSmtException(
        "unsupported ${if (sort == Sort.BOOL) "boolean" else "int"} op '$head'",
    )
}

/** Combine a list node's already-folded child results [args] into this node's [Res]. */
private fun SmtLibQfLia.Builder.combine(node: SExpr.SList, sort: Sort, args: List<Res>): Res {
    val head = (node.items[0] as SExpr.Atom).text
    return if (sort == Sort.BOOL) combineBool(node, head, args) else combineInt(head, args)
}

private fun SmtLibQfLia.Builder.combineBool(node: SExpr.SList, head: String, args: List<Res>): Res = when (head) {
    "not" -> Res.B(Lit.negate(args[0].asLit()))

    "and" -> Res.B(tseitinAnd(args.map { it.asLit() }))

    "or" -> Res.B(tseitinOr(args.map { it.asLit() }))

    "xor" -> Res.B(args.map { it.asLit() }.reduce { a, b -> Lit.negate(tseitinIff(a, b)) })

    "=>" -> {
        val lits = args.map { it.asLit() }
        Res.B(lits.dropLast(1).foldRight(lits.last()) { a, acc -> tseitinOr(listOf(Lit.negate(a), acc)) })
    }

    "<=", "<", ">=", ">" -> Res.B(reifyRelArgs(node, head, args))

    "distinct" -> Res.B(distinctFromArgs(args))

    "ite" -> Res.B(tseitinIte(args[0].asLit(), args[1].asLit(), args[2].asLit()))

    "=" -> if (isArithmeticRelation(node)) {
        if (args.size == 2) {
            Res.B(reifyRelArgs(node, head, args))
        } else {
            Res.B(chainEqToFirst(args.map { it.asLin() }, ::reifyEq))
        }
    } else {
        Res.B(chainEqToFirst(args.map { it.asLit() }, ::tseitinIff))
    }

    else -> throw UnsupportedSmtException("unsupported boolean op '$head'")
}

private fun SmtLibQfLia.Builder.combineInt(head: String, args: List<Res>): Res = when (head) {
    "+" -> Res.I(args.map { it.asLin() }.reduce(::add))

    "-" -> if (args.size == 1) {
        Res.I(scale(args[0].asLin(), -1L))
    } else {
        Res.I(args.drop(1).fold(args[0].asLin()) { acc, e -> add(acc, scale(e.asLin(), -1L)) })
    }

    "*" -> {
        val parts = args.map { it.asLin() }
        val nonConst = parts.filter { it.coeffs.isNotEmpty() }
        if (nonConst.size > 1) throw UnsupportedSmtException("nonlinear multiplication")
        val k = parts.filter { it.coeffs.isEmpty() }.fold(1L) { a, c -> a * c.constant }
        if (nonConst.isEmpty()) Res.I(LinComb(emptyMap(), k)) else Res.I(scale(nonConst[0], k))
    }

    "to_real", "to_int" -> Res.I(args[0].asLin())

    "abs" -> Res.I(absTerm(args[0].asLin()))

    "div" -> Res.I(divModTerm(args[0].asLin(), args[1].asLin(), quotient = true))

    "mod" -> Res.I(divModTerm(args[0].asLin(), args[1].asLin(), quotient = false))

    "ite" -> {
        // v = if cond then a else b: a fresh int pinned to each branch by the condition. Its domain is
        // the union of the two branch ranges, so an unbounded default never enters the reified equality.
        val cond = args[0].asLit()
        val a = args[1].asLin()
        val b = args[2].asLin()
        val (aLo, aHi) = linCombRange(a)
        val (bLo, bHi) = linCombRange(b)
        val loU = if (aLo == null || bLo == null) null else minOf(aLo, bLo)
        val hiU = if (aHi == null || bHi == null) null else maxOf(aHi, bHi)
        val self = LinComb(mapOf(newInt(loU, hiU) to 1), 0)
        factors.add(Clause(intArrayOf(Lit.negate(cond), reifyEq(self, a)))) // cond ⇒ v = a
        factors.add(Clause(intArrayOf(cond, reifyEq(self, b)))) // ¬cond ⇒ v = b
        Res.I(self)
    }

    else -> throw UnsupportedSmtException("unsupported int op '$head'")
}

/** Post a hard linear relation `a ⟨op⟩ b`; a variable-free relation is checked for consistency
 *  (posting the false literal when it cannot hold) rather than an empty [Linear] row. */
private fun SmtLibQfLia.Builder.postLinearRel(a: LinComb, b: LinComb, op: LinearOp) {
    val (vars, coeffs, bound) = diff(a, b)
    assertLinearRow(coeffs, vars, op, bound)
}

/** `abs(x)` as a fresh `y ≥ 0` pinned to `|x|`: `y ≥ x`, `y ≥ −x`, and `y = x ∨ y = −x`. The fresh
 *  var is bounded by `x`'s own range so an unbounded default never enters its defining constraints. */
private fun SmtLibQfLia.Builder.absTerm(x: LinComb): LinComb {
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
private fun SmtLibQfLia.Builder.divModTerm(a: LinComb, b: LinComb, quotient: Boolean): LinComb {
    if (b.coeffs.isNotEmpty()) throw UnsupportedSmtException("non-constant divisor in div/mod")
    val d = b.constant
    if (d == 0L) throw UnsupportedSmtException("division by zero in div/mod")
    val absd = if (d < 0) -d else d
    val (aLo, aHi) = linCombRange(a)
    // floorDiv is monotone in `a` (increasing for d>0, decreasing for d<0); pick the a-bound driving
    // each side of q's range, with ±1 slack for the remainder. A null (open) driving bound leaves q open.
    val qLoA = if (d > 0) aLo else aHi
    val qHiA = if (d > 0) aHi else aLo
    val qLo = qLoA?.let { addOrNull(floorDivL(it, d), -1L) }
    val qHi = qHiA?.let { addOrNull(floorDivL(it, d), 1L) }
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

/** Pure-Kotlin floor division (multiplatform). */
private fun floorDivL(a: Long, b: Long): Long {
    val q = a / b
    return if (a xor b < 0 && q * b != a) q - 1 else q
}

/** The value range `[lo, hi]` of [lin] over the current presolve domains; a side is null when
 *  unbounded (an open domain or an arithmetic overflow) — infinity is structural, no `±Long/4`. */
private fun SmtLibQfLia.Builder.linCombRange(lin: LinComb): Pair<Long?, Long?> {
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

/** Reify a two-operand arithmetic relation from its folded operands. */
private fun SmtLibQfLia.Builder.reifyRelArgs(node: SExpr.SList, op: String, args: List<Res>): Int {
    requireBinaryRelation(node, op)
    val rel = relFromOperands(op, args[0].asLin(), args[1].asLin())
    return reifyLinear(rel.coeffs, rel.vars, rel.op, rel.bound)
}

/** Pairwise `!=` over folded distinct operands (bool operands channelled to a 0/1 int term). */
private fun SmtLibQfLia.Builder.distinctFromArgs(args: List<Res>): Int {
    if (args.size < 2) return trueLit()
    val terms = args.map { if (it is Res.B) litToIntTerm(it.lit) else it.asLin() }
    return tseitinAnd(pairs(terms.size).map { (i, j) -> reifyNe(terms[i], terms[j]) })
}

/** Fold an atom to a boolean literal or integer term. */
private fun SmtLibQfLia.Builder.evalAtom(node: SExpr.Atom, sort: Sort): Res = if (sort == Sort.BOOL) {
    when (node.text) {
        "true" -> Res.B(trueLit())

        "false" -> Res.B(Lit.negate(trueLit()))

        else -> lookup(node.text)?.let { Res.B(boolBinding(node.text, it)) }
            ?: Res.B(
                Lit.make(
                    boolNames[node.text] ?: throw UnsupportedSmtException("unknown bool '${node.text}'"),
                    true,
                ),
            )
    }
} else {
    val n = node.text.toLongOrNull()
    when {
        n != null -> Res.I(LinComb(emptyMap(), n))

        isIntegerLiteral(node.text) -> throw UnsupportedSmtException(
            "integer literal '${node.text}' exceeds the 64-bit range of the QF_LIA lowering",
        )

        isRealLiteral(node.text) ->
            throw UnsupportedSmtException("real literal '${node.text}' (QF_LIA is integer-only)")

        else -> lookup(node.text)?.let { Res.I(intBinding(node.text, it)) }
            ?: Res.I(
                LinComb(
                    mapOf(
                        (
                            intNames[node.text] ?: throw UnsupportedSmtException(
                                "unknown int var '${node.text}'",
                            )
                            ) to 1,
                    ),
                    0,
                ),
            )
    }
}
