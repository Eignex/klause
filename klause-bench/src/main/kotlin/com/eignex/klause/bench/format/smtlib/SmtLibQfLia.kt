package com.eignex.klause.bench.format.smtlib

import com.eignex.klause.bench.format.Ingested
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.ReifiedLinear

/** Raised when an SMT-LIB construct outside the supported QF_LIA subset is encountered. */
class UnsupportedSmtException(msg: String) : RuntimeException("klause SMT-LIB QF_LIA: $msg")

/**
 * Pragmatic SMT-LIB 2 **QF_LIA** ingest → klause [Problem]. Quantifier-free linear integer
 * arithmetic is exactly klause's wheelhouse, so the mapping is direct:
 *
 *  - `(declare-const x Int)` / `(declare-fun x () Int)` → an int var (default bounds
 *    ±[intBound], overridable via `-Dklause.bench.smtlib.intBound`); `Bool` → a bool var.
 *  - linear relations (`<= < >= > = distinct`) over `+ - *`(by constant) terms → [Linear].
 *  - boolean structure (`and or not =>`) over those atoms → Tseitin-encoded with
 *    [ReifiedLinear] (atom ⇔ aux bool) + [Clause]s; a top-level `assert` forces its literal.
 *  - `(minimize e)` / `(maximize e)` → a [LinearObjective] (maximize negates).
 *
 * Out-of-subset constructs (nonlinear terms, arrays, ite over ints, etc.) raise
 * [UnsupportedSmtException] rather than silently mis-encoding.
 */
object SmtLibQfLia {
    private val intBound: Int get() = System.getProperty("klause.bench.smtlib.intBound")?.toIntOrNull() ?: 100_000

    fun parse(text: String): Ingested {
        val b = Builder(intBound)
        for (cmd in SExprReader(text).readAll()) b.command(cmd)
        return b.build()
    }

    private class Builder(val intBound: Int) {
        private val boolNames = HashMap<String, Int>()
        private val intNames = HashMap<String, Int>()
        private var nextBool = 0
        private var nextInt = 0
        private val intDomains = ArrayList<IntDomain>()
        private val factors = ArrayList<Factor>()
        private var objective: LinearObjective? = null
        private var trueLit: Int = -1

        private fun newBool(): Int = nextBool++
        private fun newInt(): Int { intDomains.add(IntDomain(-intBound, intBound)); return nextInt++ }

        fun command(e: SExpr) {
            if (e !is SExpr.SList || e.items.isEmpty()) return
            val head = (e.items[0] as? SExpr.Atom)?.text ?: return
            when (head) {
                "declare-const" -> declare((e.items[1] as SExpr.Atom).text, (e.items[2] as SExpr.Atom).text)
                "declare-fun" -> declare((e.items[1] as SExpr.Atom).text, (e.items[3] as SExpr.Atom).text)
                "assert" -> assert(e.items[1])
                "minimize" -> objective = linearObjective(e.items[1], negate = false)
                "maximize" -> objective = linearObjective(e.items[1], negate = true)
                else -> Unit // set-logic / set-info / check-sat / get-* / exit — ignored
            }
        }

        private fun declare(name: String, sort: String) {
            when (sort) {
                "Int" -> intNames[name] = newInt()
                "Bool" -> boolNames[name] = newBool()
                else -> throw UnsupportedSmtException("unsupported sort '$sort' for '$name'")
            }
        }

        // --- assertions ---

        private fun assert(t: SExpr) {
            // Top-level conjunction and bare relations post hard factors directly; richer
            // boolean structure is Tseitin-encoded and its literal forced true.
            if (t is SExpr.SList && t.items.isNotEmpty()) {
                val h = (t.items[0] as? SExpr.Atom)?.text
                when (h) {
                    "and" -> { t.items.drop(1).forEach { assert(it) }; return }
                    "<=", "<", ">=", ">", "=", "distinct" -> {
                        if (isArithmeticRelation(t)) { factors.add(hardLinear(t)); return }
                    }
                }
            }
            // General case: compile to a literal and force it true.
            forceTrue(compileBool(t))
        }

        private fun forceTrue(lit: Int) { factors.add(Clause(intArrayOf(lit))) }

        // --- boolean term → literal (klause Lit) ---

        private fun compileBool(t: SExpr): Int = when (t) {
            is SExpr.Atom -> when (t.text) {
                "true" -> trueLiteral()
                "false" -> Lit.negate(trueLiteral())
                else -> Lit.make(boolNames[t.text] ?: throw UnsupportedSmtException("unknown bool '${t.text}'"), true)
            }
            is SExpr.SList -> {
                val h = (t.items[0] as? SExpr.Atom)?.text ?: throw UnsupportedSmtException("bad term")
                val args = t.items.drop(1)
                when (h) {
                    "not" -> Lit.negate(compileBool(args[0]))
                    "and" -> tseitinAnd(args.map { compileBool(it) })
                    "or" -> tseitinOr(args.map { compileBool(it) })
                    "=>" -> tseitinOr(listOf(Lit.negate(compileBool(args[0])), compileBool(args[1])))
                    "<=", "<", ">=", ">", "distinct" -> reifyRelation(t)
                    "=" -> if (isArithmeticRelation(t)) reifyRelation(t) else tseitinIff(compileBool(args[0]), compileBool(args[1]))
                    else -> throw UnsupportedSmtException("unsupported boolean op '$h'")
                }
            }
        }

        private fun trueLiteral(): Int {
            if (trueLit < 0) { trueLit = Lit.make(newBool(), true); factors.add(Clause(intArrayOf(trueLit))) }
            return trueLit
        }

        private fun tseitinAnd(lits: List<Int>): Int {
            val a = Lit.make(newBool(), true)
            for (l in lits) factors.add(Clause(intArrayOf(Lit.negate(a), l)))           // a -> li
            factors.add(Clause((lits.map { Lit.negate(it) } + a).toIntArray()))          // (∧li) -> a
            return a
        }

        private fun tseitinOr(lits: List<Int>): Int {
            val a = Lit.make(newBool(), true)
            factors.add(Clause((lits + Lit.negate(a)).toIntArray()))                      // a -> ⋁li
            for (l in lits) factors.add(Clause(intArrayOf(Lit.negate(l), a)))             // li -> a
            return a
        }

        private fun tseitinIff(x: Int, y: Int): Int {
            val a = Lit.make(newBool(), true)
            factors.add(Clause(intArrayOf(Lit.negate(a), Lit.negate(x), y)))
            factors.add(Clause(intArrayOf(Lit.negate(a), x, Lit.negate(y))))
            factors.add(Clause(intArrayOf(a, x, y)))
            factors.add(Clause(intArrayOf(a, Lit.negate(x), Lit.negate(y))))
            return a
        }

        // --- arithmetic relations ---

        private fun isArithmeticRelation(t: SExpr.SList): Boolean {
            // `=` and `distinct` are arithmetic when their operands are int terms (not bools).
            val arg = t.items.getOrNull(1) ?: return false
            return when (arg) {
                is SExpr.Atom -> arg.text.toIntOrNull() != null || intNames.containsKey(arg.text)
                is SExpr.SList -> (arg.items.firstOrNull() as? SExpr.Atom)?.text in setOf("+", "-", "*")
            }
        }

        private fun hardLinear(t: SExpr.SList): Linear {
            val (vars, coeffs, op, bound) = relationToLinear(t)
            return Linear(coeffs, vars, op, bound)
        }

        private fun reifyRelation(t: SExpr.SList): Int {
            val (vars, coeffs, op, bound) = relationToLinear(t)
            val aux = newBool()
            factors.add(ReifiedLinear(auxBoolVar = aux, coeffs = coeffs, vars = vars, op = op, bound = bound))
            return Lit.make(aux, true)
        }

        private data class Rel(val vars: IntArray, val coeffs: IntArray, val op: LinearOp, val bound: Int)

        /** Lower `(op lhs rhs)` to `coeffs·vars OP bound`. `distinct`/`>2`-ary `=` are not
         *  expressible as one Linear and are rejected. */
        private fun relationToLinear(t: SExpr.SList): Rel {
            val op = (t.items[0] as SExpr.Atom).text
            if (t.items.size != 3)
                throw UnsupportedSmtException("$op with ${t.items.size - 1} operands not supported as a single linear relation")
            val lhs = linearTerm(t.items[1])
            val rhs = linearTerm(t.items[2])
            // lhs - rhs <op> 0  →  coeff·x <op> -(constLhs - constRhs)
            val combined = HashMap<Int, Int>(lhs.coeffs)
            for ((v, c) in rhs.coeffs) combined[v] = (combined[v] ?: 0) - c
            combined.entries.removeAll { it.value == 0 }
            val constDiff = lhs.constant - rhs.constant
            var bound = -constDiff
            val linOp = when (op) {
                "<=" -> LinearOp.LE
                ">=" -> LinearOp.GE
                "=" -> LinearOp.EQ
                "distinct" -> LinearOp.NE
                "<" -> { bound -= 1; LinearOp.LE }
                ">" -> { bound += 1; LinearOp.GE }
                else -> throw UnsupportedSmtException("relation '$op'")
            }
            val vars = combined.keys.toIntArray()
            val coeffs = IntArray(vars.size) { combined[vars[it]]!! }
            return Rel(vars, coeffs, linOp, bound)
        }

        // --- linear int term → (coeffs, constant) ---

        private data class LinTerm(val coeffs: Map<Int, Int>, val constant: Int)

        private fun linearTerm(t: SExpr): LinTerm = when (t) {
            is SExpr.Atom -> t.text.toIntOrNull()?.let { LinTerm(emptyMap(), it) }
                ?: LinTerm(mapOf((intNames[t.text] ?: throw UnsupportedSmtException("unknown int var '${t.text}'")) to 1), 0)
            is SExpr.SList -> {
                val h = (t.items[0] as? SExpr.Atom)?.text ?: throw UnsupportedSmtException("bad int term")
                val args = t.items.drop(1)
                when (h) {
                    "+" -> args.map { linearTerm(it) }.reduce(::add)
                    "-" -> if (args.size == 1) scale(linearTerm(args[0]), -1)
                    else args.drop(1).fold(linearTerm(args[0])) { acc, e -> add(acc, scale(linearTerm(e), -1)) }
                    "*" -> {
                        val parts = args.map { linearTerm(it) }
                        val constants = parts.filter { it.coeffs.isEmpty() }
                        val nonConst = parts.filter { it.coeffs.isNotEmpty() }
                        if (nonConst.size > 1) throw UnsupportedSmtException("nonlinear multiplication")
                        val k = constants.fold(1) { a, c -> a * c.constant }
                        if (nonConst.isEmpty()) LinTerm(emptyMap(), k) else scale(nonConst[0], k)
                    }
                    else -> throw UnsupportedSmtException("unsupported int op '$h'")
                }
            }
        }

        private fun add(a: LinTerm, b: LinTerm): LinTerm {
            val m = HashMap(a.coeffs)
            for ((v, c) in b.coeffs) m[v] = (m[v] ?: 0) + c
            return LinTerm(m, a.constant + b.constant)
        }

        private fun scale(a: LinTerm, k: Int): LinTerm =
            LinTerm(a.coeffs.mapValues { it.value * k }, a.constant * k)

        private fun linearObjective(t: SExpr, negate: Boolean): LinearObjective {
            val lt = linearTerm(t)
            val coeffs = DoubleArray(nextInt)
            for ((v, c) in lt.coeffs) coeffs[v] = (if (negate) -c else c).toDouble()
            return LinearObjective(intCoefficients = coeffs, constant = (if (negate) -lt.constant else lt.constant).toDouble())
        }

        fun build(): Ingested = Ingested(
            Problem(
                numBoolVars = nextBool,
                numIntVars = nextInt,
                intDomains = intDomains.toTypedArray(),
                factors = factors.toTypedArray(),
            ),
            objective,
        )
    }
}
