package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.formats.LinComb
import com.eignex.klause.formats.channelBoolTo01
import com.eignex.klause.formats.reifyLinear
import com.eignex.klause.formats.trueLit
import com.eignex.klause.formats.tseitinAnd
import com.eignex.klause.formats.tseitinIff
import com.eignex.klause.formats.tseitinOr
import com.eignex.klause.solver.Lit

internal fun SmtLibQfLia.Builder.assert(t: SExpr): Unit = unwindingLets(t) { node ->
    if (node is SExpr.SList && node.items.isNotEmpty()) {
        val h = (node.items[0] as? SExpr.Atom)?.text
        val args = node.items.drop(1)
        when (h) {
            "and" -> {
                args.forEach { assert(it) }
                return@unwindingLets
            }

            "<=", "<", ">=", ">" -> {
                assertLinear(node)
                return@unwindingLets
            }

            "=" -> if (isArithmeticRelation(node) && args.size == 2) {
                assertLinear(node)
                return@unwindingLets
            }

            "distinct" -> {
                assertDistinct(args)
                return@unwindingLets
            }
        }
    }
    forceTrue(compileBool(node))
}

internal fun SmtLibQfLia.Builder.forceTrue(lit: Int) {
    factors.add(Clause(intArrayOf(lit)))
}

internal fun SmtLibQfLia.Builder.compileBool(t: SExpr): Int = unwindingLets(t) { node ->
    when (node) {
        is SExpr.Atom -> when (node.text) {
            "true" -> trueLit()

            "false" -> Lit.negate(trueLit())

            else -> lookup(node.text)?.let { boolBinding(node.text, it) }
                ?: Lit.make(
                    boolNames[node.text] ?: throw UnsupportedSmtException("unknown bool '${node.text}'"),
                    true,
                )
        }

        is SExpr.SList -> {
            val h = (node.items[0] as? SExpr.Atom)?.text ?: throw UnsupportedSmtException("bad term")
            val args = node.items.drop(1)
            when (h) {
                "not" -> Lit.negate(compileBool(args[0]))

                "and" -> tseitinAnd(args.map { compileBool(it) })

                "or" -> tseitinOr(args.map { compileBool(it) })

                "xor" -> args.map { compileBool(it) }.reduce { a, b -> Lit.negate(tseitinIff(a, b)) }

                "=>" -> args.dropLast(1).foldRight(compileBool(args.last())) { a, acc ->
                    tseitinOr(listOf(Lit.negate(compileBool(a)), acc))
                }

                "<=", "<", ">=", ">" -> reifyRelation(node)

                "distinct" -> compileDistinct(args)

                "ite" -> tseitinIte(compileBool(args[0]), compileBool(args[1]), compileBool(args[2]))

                "=" -> if (isArithmeticRelation(node)) {
                    if (args.size == 2) {
                        reifyRelation(node)
                    } else {
                        chainEqToFirst(args.map { linearTerm(it) }, ::reifyEq)
                    }
                } else {
                    chainEqToFirst(args.map { compileBool(it) }, ::tseitinIff)
                }

                else -> throw UnsupportedSmtException("unsupported boolean op '$h'")
            }
        }
    }
}

internal fun SmtLibQfLia.Builder.boolBinding(name: String, b: SmtLibQfLia.Builder.Binding): Int {
    if (!b.isBool) throw UnsupportedSmtException("'$name' used as Bool but bound to an Int term")
    return b.lit ?: throw UnsupportedSmtException("'$name' has no compiled Bool value")
}

/** Reify boolean ite as `(c and x) or (!c and y)`. */
internal fun SmtLibQfLia.Builder.tseitinIte(c: Int, x: Int, y: Int): Int =
    tseitinOr(listOf(tseitinAnd(listOf(c, x)), tseitinAnd(listOf(Lit.negate(c), y))))

/** Compile n-ary equality as pairwise equality to the first operand. */
internal fun <T> SmtLibQfLia.Builder.chainEqToFirst(items: List<T>, relate: (T, T) -> Int): Int =
    tseitinAnd((1 until items.size).map { relate(items[0], items[it]) })

/** Syntactic bool/int classifier for a term. */
internal fun SmtLibQfLia.Builder.isBoolExpr(t: SExpr): Boolean = when (t) {
    is SExpr.Atom ->
        t.text == "true" || t.text == "false" ||
            t.text in boolNames || (lookup(t.text)?.isBool == true)

    is SExpr.SList -> when ((t.items[0] as? SExpr.Atom)?.text) {
        "and", "or", "not", "=>", "xor", "=", "distinct", "<", "<=", ">", ">=" -> true
        "+", "-", "*", "to_real", "to_int" -> false
        "ite" -> isBoolExpr(t.items[2])
        "let" -> isBoolExpr(t.items[2])
        else -> false
    }
}

internal fun SmtLibQfLia.Builder.assertDistinct(args: List<SExpr>) {
    if (args.size < 2) return
    if (args.all { !isBoolExpr(it) }) {
        val terms = args.map { linearTerm(it) }
        val simpleVars = terms.mapNotNull { it.asSimpleVar() }
        if (simpleVars.size == terms.size && simpleVars.toSet().size == simpleVars.size) {
            val vars = simpleVars.toIntArray()
            val min = vars.minOf { intDomains[it].min }
            val max = vars.maxOf { intDomains[it].max }
            factors.add(
                AllDifferent(vars = vars, domainMin = min, domainSize = (max - min + 1).toInt()),
            )
        } else {
            assertPairwiseNe(terms)
        }
    } else {
        assertPairwiseNe(args.map { if (isBoolExpr(it)) litToIntTerm(compileBool(it)) else linearTerm(it) })
    }
}

/** Post pairwise `!=` as linear NE constraints. */
internal fun SmtLibQfLia.Builder.assertPairwiseNe(terms: List<LinComb>) {
    for ((i, j) in pairs(terms.size)) factors.add(neLinear(terms[i], terms[j]))
}

internal fun SmtLibQfLia.Builder.compileDistinct(args: List<SExpr>): Int {
    if (args.size < 2) return trueLit()
    val terms = args.map { if (isBoolExpr(it)) litToIntTerm(compileBool(it)) else linearTerm(it) }
    return tseitinAnd(pairs(terms.size).map { (i, j) -> reifyNe(terms[i], terms[j]) })
}

/** Channel a bool literal to a fresh 0/1 int term. */
internal fun SmtLibQfLia.Builder.litToIntTerm(lit: Int): LinComb {
    val z = newInt(0L, 1L)
    val w = newBool() // w ⇔ lit
    val wlit = Lit.make(w, true)
    factors.add(Clause(intArrayOf(Lit.negate(wlit), lit)))
    factors.add(Clause(intArrayOf(wlit, Lit.negate(lit))))
    channelBoolTo01(factors, w, z)
    return LinComb(mapOf(z to 1), 0)
}

internal fun SmtLibQfLia.Builder.pairs(n: Int): List<Pair<Int, Int>> =
    buildList { for (i in 0 until n) for (j in i + 1 until n) add(i to j) }

internal fun SmtLibQfLia.Builder.neLinear(a: LinComb, b: LinComb): Linear {
    val (vars, coeffs, bound) = diff(a, b)
    return Linear(coeffs, vars, LinearOp.NE, bound)
}

internal fun SmtLibQfLia.Builder.reifyNe(a: LinComb, b: LinComb): Int = reifyRelTerms(a, b, LinearOp.NE)
internal fun SmtLibQfLia.Builder.reifyEq(a: LinComb, b: LinComb): Int = reifyRelTerms(a, b, LinearOp.EQ)

internal fun SmtLibQfLia.Builder.reifyRelTerms(a: LinComb, b: LinComb, op: LinearOp): Int {
    val (vars, coeffs, bound) = diff(a, b)
    return reifyLinear(coeffs, vars, op, bound)
}
