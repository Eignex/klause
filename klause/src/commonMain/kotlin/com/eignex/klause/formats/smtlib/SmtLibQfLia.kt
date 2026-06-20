package com.eignex.klause.formats.smtlib

import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_HI
import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_LO
import com.eignex.klause.formats.CnfLowering
import com.eignex.klause.formats.reifyLinear
import com.eignex.klause.formats.trueLit
import com.eignex.klause.formats.tseitinAnd
import com.eignex.klause.formats.tseitinIff
import com.eignex.klause.formats.tseitinOr
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.bool.Clause
import com.eignex.klause.solver.factor.global.AllDifferent
import com.eignex.klause.solver.factor.linear.Linear
import com.eignex.klause.solver.factor.linear.LinearOp
import com.eignex.klause.solver.factor.linear.ReifiedLinear
import com.eignex.klause.solver.objective.LinearObjective

/** Raised when an SMT-LIB construct outside the supported QF_LIA subset is encountered. */
class UnsupportedSmtException(msg: String) : RuntimeException("klause SMT-LIB QF_LIA: $msg")

/** A parsed SMT-LIB instance lifted into klause's representation. */
data class SmtLibProblem(
    /** Compiled solver problem. */
    val problem: Problem,
    /** Objective, or null for satisfaction instances. */
    val objective: LinearObjective?,
    /** Declared `Int` variable name to int id. */
    val intVarNames: Map<String, Int> = emptyMap(),
    /** Declared `Bool` variable name to bool id. */
    val boolVarNames: Map<String, Int> = emptyMap(),
    /** True when the parsed objective was a maximize directive. */
    val maximize: Boolean = false,
)

/** Parser/compiler for the supported SMT-LIB QF_LIA subset. */
object SmtLibQfLia {
    /** Parse SMT-LIB QF_LIA [text] into an [SmtLibProblem]. A variable with no provable bound (and a
     *  derived bound past the range) falls back to / is clamped into `[unboundedIntLo, unboundedIntHi]`
     *  — the same default int range as the FlatZinc front-end ([com.eignex.klause.config.KlauseConfig]). */
    fun parse(
        text: String,
        unboundedIntLo: Int = DEFAULT_UNBOUNDED_INT_LO,
        unboundedIntHi: Int = DEFAULT_UNBOUNDED_INT_HI,
        strictBounds: Boolean = false,
    ): SmtLibProblem {
        val b = Builder(unboundedIntLo, unboundedIntHi, strictBounds)
        for (cmd in SExprReader(text).readAll()) b.command(cmd)
        return b.build()
    }

    private class Builder(val unboundedIntLo: Int, val unboundedIntHi: Int, val strictBounds: Boolean) : CnfLowering {
        private val boolNames = HashMap<String, Int>()
        private val intNames = HashMap<String, Int>()
        private var nextBool = 0
        private var nextInt = 0
        private val intDomains = ArrayList<IntDomain>()
        override val factors = ArrayList<Factor>()
        private val asserts = ArrayList<SExpr>()
        private var objectiveSpec: Pair<SExpr, Boolean>? = null // (term, negate)
        override var trueLitCache: Int = -1

        private class Binding(val isBool: Boolean) {
            var lin: LinTerm? = null
            var lit: Int? = null
        }
        private val scopes = ArrayDeque<HashMap<String, Binding>>()
        private fun lookup(name: String): Binding? {
            for (i in scopes.indices.reversed()) scopes[i][name]?.let { return it }
            return null
        }

        override fun newBool(): Int = nextBool++
        private fun newInt(): Int {
            intDomains.add(IntDomain(unboundedIntLo, unboundedIntHi))
            return nextInt++
        }
        private fun newInt(lo: Int, hi: Int): Int {
            intDomains.add(IntDomain(lo, hi))
            return nextInt++
        }

        fun command(e: SExpr) {
            if (e !is SExpr.SList || e.items.isEmpty()) return
            val head = (e.items[0] as? SExpr.Atom)?.text ?: return
            when (head) {
                "declare-const" -> declare((e.items[1] as SExpr.Atom).text, (e.items[2] as SExpr.Atom).text)
                "declare-fun" -> declare((e.items[1] as SExpr.Atom).text, (e.items[3] as SExpr.Atom).text)
                "assert" -> asserts.add(e.items[1])
                "minimize" -> objectiveSpec = e.items[1] to false
                "maximize" -> objectiveSpec = e.items[1] to true
                else -> Unit // set-logic / set-info / check-sat / get-* / exit — ignored
            }
        }

        private fun declare(name: String, sort: String) {
            when (sort) {
                "Int" -> intNames[name] = newInt()
                "Bool" -> boolNames[name] = newBool()
                "Real" -> throw UnsupportedSmtException("Real sort for '$name' (QF_LIA is integer-only)")
                else -> throw UnsupportedSmtException("unsupported sort '$sort' for '$name'")
            }
        }

        fun build(): SmtLibProblem {
            inferBounds()
            for (a in asserts) assert(a)
            val objective = objectiveSpec?.let { (t, neg) -> linearObjective(t, neg) }
            return SmtLibProblem(
                Problem(
                    numBoolVars = nextBool,
                    numIntVars = nextInt,
                    intDomains = intDomains.toTypedArray(),
                    factors = factors.toTypedArray(),
                ),
                objective,
                intVarNames = LinkedHashMap(intNames),
                boolVarNames = LinkedHashMap(boolNames),
                maximize = objectiveSpec?.second ?: false,
            )
        }

        private fun inferBounds() {
            if (intNames.isEmpty()) return
            val lo = LongArray(nextInt) { NEG_INF }
            val hi = LongArray(nextInt) { POS_INF }
            val relations = ArrayList<Rel>()
            for (a in asserts) collectConjunctiveRelations(a, relations)

            var changed = true
            var iter = 0
            while (changed && iter++ < MAX_BOUND_ITERS) {
                changed = false
                for (r in relations) {
                    if (r.op == LinearOp.NE) continue
                    for (ti in r.vars.indices) {
                        val tv = r.vars[ti]
                        val ct = r.coeffs[ti].toLong()
                        if (ct == 0L) continue
                        var sLo = 0L
                        var sHi = 0L
                        var sLoInf = false
                        var sHiInf = false
                        for (oi in r.vars.indices) {
                            if (oi == ti) continue
                            val c = r.coeffs[oi].toLong()
                            val v = r.vars[oi]
                            val vlo = lo[v]
                            val vhi = hi[v]
                            val (clo, chi) = if (c >= 0) {
                                c * safe(vlo) to c * safe(vhi)
                            } else {
                                c * safe(vhi) to c * safe(vlo)
                            }
                            if (c >= 0) {
                                if (vlo <= NEG_INF) sLoInf = true
                                if (vhi >= POS_INF) sHiInf = true
                            } else {
                                if (vhi >= POS_INF) sLoInf = true
                                if (vlo <= NEG_INF) sHiInf = true
                            }
                            sLo += clo
                            sHi += chi
                        }
                        val bnd = r.bound.toLong()
                        if ((r.op == LinearOp.LE || r.op == LinearOp.EQ) && !sLoInf) {
                            changed = applyCtBound(lo, hi, tv, ct, bnd - sLo, upper = true) || changed
                        }
                        if ((r.op == LinearOp.GE || r.op == LinearOp.EQ) && !sHiInf) {
                            changed = applyCtBound(lo, hi, tv, ct, bnd - sHi, upper = false) || changed
                        }
                    }
                }
            }

            for ((name, v) in intNames) {
                var vlo = lo[v]
                var vhi = hi[v]
                if (vlo <= NEG_INF) {
                    if (strictBounds) throw UnsupportedSmtException("no provable lower bound for '$name'")
                    vlo = unboundedIntLo.toLong()
                }
                if (vhi >= POS_INF) {
                    if (strictBounds) throw UnsupportedSmtException("no provable upper bound for '$name'")
                    vhi = unboundedIntHi.toLong()
                }
                val clo = vlo.coerceIn(unboundedIntLo.toLong(), unboundedIntHi.toLong()).toInt()
                val chi = vhi.coerceIn(unboundedIntLo.toLong(), unboundedIntHi.toLong()).toInt()
                intDomains[v] = if (clo <= chi) IntDomain(clo, chi) else IntDomain(clo, clo)
            }
        }

        private fun applyCtBound(lo: LongArray, hi: LongArray, tv: Int, ct: Long, rhs: Long, upper: Boolean): Boolean {
            var changed = false
            if (ct > 0) {
                if (upper) {
                    val b = floorDiv(
                        rhs,
                        ct,
                    )
                    if (b < hi[tv]) {
                        hi[tv] = b
                        changed = true
                    }
                } else {
                    val b = ceilDiv(
                        rhs,
                        ct,
                    )
                    if (b > lo[tv]) {
                        lo[tv] = b
                        changed = true
                    }
                }
            } else {
                if (upper) {
                    val b = ceilDiv(
                        rhs,
                        ct,
                    )
                    if (b > lo[tv]) {
                        lo[tv] = b
                        changed = true
                    }
                } else {
                    val b = floorDiv(
                        rhs,
                        ct,
                    )
                    if (b < hi[tv]) {
                        hi[tv] = b
                        changed = true
                    }
                }
            }
            return changed
        }

        private fun safe(x: Long): Long = x.coerceIn(NEG_INF, POS_INF)

        private fun collectConjunctiveRelations(t: SExpr, out: ArrayList<Rel>) {
            if (t !is SExpr.SList || t.items.isEmpty()) return
            val h = (t.items[0] as? SExpr.Atom)?.text ?: return
            when (h) {
                "and" -> t.items.drop(1).forEach { collectConjunctiveRelations(it, out) }

                "<=", "<", ">=", ">", "=" -> if (t.items.size == 3 && isArithmeticRelation(t)) {
                    try {
                        out.add(relationToLinear(t))
                    } catch (_: UnsupportedSmtException) { }
                }
            }
        }

        private fun assert(t: SExpr) {
            if (t is SExpr.SList && t.items.isNotEmpty()) {
                val h = (t.items[0] as? SExpr.Atom)?.text
                val args = t.items.drop(1)
                when (h) {
                    "and" -> {
                        args.forEach { assert(it) }
                        return
                    }

                    "<=", "<", ">=", ">" -> {
                        factors.add(hardLinear(t))
                        return
                    }

                    "=" -> if (isArithmeticRelation(t) && args.size == 2) {
                        factors.add(hardLinear(t))
                        return
                    }

                    "distinct" -> {
                        assertDistinct(args)
                        return
                    }

                    "let" -> {
                        withLet(args[0]) { assert(args[1]) }
                        return
                    }
                }
            }
            forceTrue(compileBool(t))
        }

        private fun forceTrue(lit: Int) {
            factors.add(Clause(intArrayOf(lit)))
        }

        private fun compileBool(t: SExpr): Int = when (t) {
            is SExpr.Atom -> when (t.text) {
                "true" -> trueLit()

                "false" -> Lit.negate(trueLit())

                else -> lookup(t.text)?.let { boolBinding(t.text, it) }
                    ?: Lit.make(boolNames[t.text] ?: throw UnsupportedSmtException("unknown bool '${t.text}'"), true)
            }

            is SExpr.SList -> {
                val h = (t.items[0] as? SExpr.Atom)?.text ?: throw UnsupportedSmtException("bad term")
                val args = t.items.drop(1)
                when (h) {
                    "not" -> Lit.negate(compileBool(args[0]))

                    "and" -> tseitinAnd(args.map { compileBool(it) })

                    "or" -> tseitinOr(args.map { compileBool(it) })

                    "xor" -> args.map { compileBool(it) }.reduce { a, b -> Lit.negate(tseitinIff(a, b)) }

                    "=>" -> args.dropLast(1).foldRight(compileBool(args.last())) { a, acc ->
                        tseitinOr(listOf(Lit.negate(compileBool(a)), acc))
                    }

                    "<=", "<", ">=", ">" -> reifyRelation(t)

                    "distinct" -> compileDistinct(args)

                    "ite" -> tseitinIte(compileBool(args[0]), compileBool(args[1]), compileBool(args[2]))

                    "=" -> if (isArithmeticRelation(t)) {
                        if (args.size == 2) {
                            reifyRelation(t)
                        } else {
                            chainEqToFirst(args.map { linearTerm(it) }, ::reifyEq)
                        }
                    } else {
                        chainEqToFirst(args.map { compileBool(it) }, ::tseitinIff)
                    }

                    "let" -> withLet(args[0]) { compileBool(args[1]) }

                    else -> throw UnsupportedSmtException("unsupported boolean op '$h'")
                }
            }
        }

        private fun boolBinding(name: String, b: Binding): Int {
            if (!b.isBool) throw UnsupportedSmtException("'$name' used as Bool but bound to an Int term")
            return b.lit ?: throw UnsupportedSmtException("'$name' has no compiled Bool value")
        }

        /** Reify boolean ite as `(c and x) or (!c and y)`. */
        private fun tseitinIte(c: Int, x: Int, y: Int): Int =
            tseitinOr(listOf(tseitinAnd(listOf(c, x)), tseitinAnd(listOf(Lit.negate(c), y))))

        /** Compile n-ary equality as pairwise equality to the first operand. */
        private fun <T> chainEqToFirst(items: List<T>, relate: (T, T) -> Int): Int =
            tseitinAnd((1 until items.size).map { relate(items[0], items[it]) })

        private inline fun <T> withLet(bindingList: SExpr, body: () -> T): T {
            val scope = HashMap<String, Binding>()
            require(bindingList is SExpr.SList) { "malformed let bindings" }
            for (pair in bindingList.items) {
                val p = pair as? SExpr.SList ?: throw UnsupportedSmtException("malformed let binding")
                val name = (p.items[0] as SExpr.Atom).text
                val expr = p.items[1]
                val b = Binding(isBool = isBoolExpr(expr))
                if (b.isBool) b.lit = compileBool(expr) else b.lin = linearTerm(expr)
                scope[name] = b
            }
            scopes.addLast(scope)
            try {
                return body()
            } finally {
                scopes.removeLast()
            }
        }

        /** Syntactic bool/int classifier for a term. */
        private fun isBoolExpr(t: SExpr): Boolean = when (t) {
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

        private fun assertDistinct(args: List<SExpr>) {
            if (args.size < 2) return
            if (args.all { !isBoolExpr(it) }) {
                val terms = args.map { linearTerm(it) }
                val simpleVars = terms.mapNotNull { it.asSimpleVar() }
                if (simpleVars.size == terms.size && simpleVars.toSet().size == simpleVars.size) {
                    val vars = simpleVars.toIntArray()
                    val min = vars.minOf { intDomains[it].min }
                    val max = vars.maxOf { intDomains[it].max }
                    factors.add(AllDifferent(vars = vars, domainMin = min, domainSize = max - min + 1))
                } else {
                    assertPairwiseNe(terms)
                }
            } else {
                assertPairwiseNe(args.map { if (isBoolExpr(it)) litToIntTerm(compileBool(it)) else linearTerm(it) })
            }
        }

        /** Post pairwise `!=` as linear NE constraints. */
        private fun assertPairwiseNe(terms: List<LinTerm>) {
            for ((i, j) in pairs(terms.size)) factors.add(neLinear(terms[i], terms[j]))
        }

        private fun compileDistinct(args: List<SExpr>): Int {
            if (args.size < 2) return trueLit()
            val terms = args.map { if (isBoolExpr(it)) litToIntTerm(compileBool(it)) else linearTerm(it) }
            return tseitinAnd(pairs(terms.size).map { (i, j) -> reifyNe(terms[i], terms[j]) })
        }

        /** Channel a bool literal to a fresh 0/1 int term. */
        private fun litToIntTerm(lit: Int): LinTerm {
            val z = newInt(0, 1)
            val w = newBool() // w ⇔ lit
            val wlit = Lit.make(w, true)
            factors.add(Clause(intArrayOf(Lit.negate(wlit), lit)))
            factors.add(Clause(intArrayOf(wlit, Lit.negate(lit))))
            factors.add(
                ReifiedLinear(
                    auxBoolVar = w,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(z),
                    op = LinearOp.EQ,
                    bound = 1,
                ),
            )
            return LinTerm(mapOf(z to 1), 0)
        }

        private fun pairs(n: Int): List<Pair<Int, Int>> =
            buildList { for (i in 0 until n) for (j in i + 1 until n) add(i to j) }

        private fun neLinear(a: LinTerm, b: LinTerm): Linear {
            val (vars, coeffs, bound) = diff(a, b)
            return Linear(coeffs, vars, LinearOp.NE, bound)
        }

        private fun reifyNe(a: LinTerm, b: LinTerm): Int = reifyRelTerms(a, b, LinearOp.NE)
        private fun reifyEq(a: LinTerm, b: LinTerm): Int = reifyRelTerms(a, b, LinearOp.EQ)

        private fun reifyRelTerms(a: LinTerm, b: LinTerm, op: LinearOp): Int {
            val (vars, coeffs, bound) = diff(a, b)
            return reifyLinear(coeffs, vars, op, bound)
        }

        /** Build linear coefficients for `a - b op 0`. */
        private fun diff(a: LinTerm, b: LinTerm): Triple<IntArray, IntArray, Int> {
            val combined = HashMap(a.coeffs)
            for ((v, c) in b.coeffs) combined[v] = (combined[v] ?: 0) - c
            combined.entries.removeAll { it.value == 0 }
            val bound = b.constant - a.constant
            val vars = combined.keys.toIntArray()
            return Triple(vars, IntArray(vars.size) { combined.getValue(vars[it]) }, bound)
        }

        private fun isArithmeticRelation(t: SExpr.SList): Boolean {
            val arg = t.items.getOrNull(1) ?: return false
            return when (arg) {
                is SExpr.Atom -> arg.text.toIntOrNull() != null || intNames.containsKey(arg.text) ||
                    (lookup(arg.text)?.isBool == false)

                is SExpr.SList -> (arg.items.firstOrNull() as? SExpr.Atom)?.text in setOf(
                    "+",
                    "-",
                    "*",
                    "to_real",
                    "to_int",
                )
            }
        }

        private fun hardLinear(t: SExpr.SList): Linear {
            val rel = relationToLinear(t)
            return Linear(rel.coeffs, rel.vars, rel.op, rel.bound)
        }

        private fun reifyRelation(t: SExpr.SList): Int {
            val rel = relationToLinear(t)
            return reifyLinear(rel.coeffs, rel.vars, rel.op, rel.bound)
        }

        private data class Rel(val vars: IntArray, val coeffs: IntArray, val op: LinearOp, val bound: Int)

        /** Lower `(op lhs rhs)` to one linear relation. */
        private fun relationToLinear(t: SExpr.SList): Rel {
            val op = (t.items[0] as SExpr.Atom).text
            if (t.items.size != 3) {
                throw UnsupportedSmtException(
                    "$op with ${t.items.size - 1} operands not supported as a single linear relation",
                )
            }
            val (vars, coeffs, baseBound) = diff(linearTerm(t.items[1]), linearTerm(t.items[2]))
            val (linOp, delta) = when (op) {
                "<=" -> LinearOp.LE to 0
                ">=" -> LinearOp.GE to 0
                "=" -> LinearOp.EQ to 0
                "distinct" -> LinearOp.NE to 0
                "<" -> LinearOp.LE to -1
                ">" -> LinearOp.GE to 1
                else -> throw UnsupportedSmtException("relation '$op'")
            }
            return Rel(vars, coeffs, linOp, baseBound + delta)
        }

        private data class LinTerm(val coeffs: Map<Int, Int>, val constant: Int) {
            fun asSimpleVar(): Int? =
                if (constant == 0 && coeffs.size == 1 && coeffs.values.first() == 1) coeffs.keys.first() else null
        }

        private fun linearTerm(t: SExpr): LinTerm = when (t) {
            is SExpr.Atom -> {
                val n = t.text.toIntOrNull()
                when {
                    n != null -> LinTerm(emptyMap(), n)

                    isRealLiteral(
                        t.text,
                    ) -> throw UnsupportedSmtException("real literal '${t.text}' (QF_LIA is integer-only)")

                    else -> lookup(t.text)?.let { intBinding(t.text, it) }
                        ?: LinTerm(
                            mapOf(
                                (intNames[t.text] ?: throw UnsupportedSmtException("unknown int var '${t.text}'")) to 1,
                            ),
                            0,
                        )
                }
            }

            is SExpr.SList -> {
                val h = (t.items[0] as? SExpr.Atom)?.text ?: throw UnsupportedSmtException("bad int term")
                val args = t.items.drop(1)
                when (h) {
                    "+" -> args.map { linearTerm(it) }.reduce(::add)

                    "-" -> if (args.size == 1) {
                        scale(linearTerm(args[0]), -1)
                    } else {
                        args.drop(1).fold(linearTerm(args[0])) { acc, e -> add(acc, scale(linearTerm(e), -1)) }
                    }

                    "*" -> {
                        val parts = args.map { linearTerm(it) }
                        val nonConst = parts.filter { it.coeffs.isNotEmpty() }
                        if (nonConst.size > 1) throw UnsupportedSmtException("nonlinear multiplication")
                        val k = parts.filter { it.coeffs.isEmpty() }.fold(1) { a, c -> a * c.constant }
                        if (nonConst.isEmpty()) LinTerm(emptyMap(), k) else scale(nonConst[0], k)
                    }

                    "to_real", "to_int" -> linearTerm(args[0])

                    "/", "div", "mod", "abs" -> throw UnsupportedSmtException("nonlinear/real operator '$h'")

                    "let" -> withLet(args[0]) { linearTerm(args[1]) }

                    else -> throw UnsupportedSmtException("unsupported int op '$h'")
                }
            }
        }

        private fun intBinding(name: String, b: Binding): LinTerm {
            if (b.isBool) throw UnsupportedSmtException("'$name' used as Int but bound to a Bool term")
            return b.lin ?: throw UnsupportedSmtException("'$name' has no compiled Int value")
        }

        private fun isRealLiteral(s: String): Boolean =
            s.isNotEmpty() && s.toDoubleOrNull() != null && s.toIntOrNull() == null

        private fun add(a: LinTerm, b: LinTerm): LinTerm {
            val m = HashMap(a.coeffs)
            for ((v, c) in b.coeffs) m[v] = (m[v] ?: 0) + c
            return LinTerm(m, a.constant + b.constant)
        }

        private fun scale(a: LinTerm, k: Int): LinTerm = LinTerm(a.coeffs.mapValues { it.value * k }, a.constant * k)

        private fun linearObjective(t: SExpr, negate: Boolean): LinearObjective {
            val lt = linearTerm(t)
            val coeffs = LongArray(nextInt)
            for ((v, c) in lt.coeffs) coeffs[v] = (if (negate) -c else c).toLong()
            return LinearObjective(
                intCoefficients = coeffs,
                constant = (if (negate) -lt.constant else lt.constant).toLong(),
            )
        }

        companion object {
            private const val NEG_INF = Long.MIN_VALUE / 4
            private const val POS_INF = Long.MAX_VALUE / 4
            private const val MAX_BOUND_ITERS = 64

            /** Pure-Kotlin floor/ceil division for multiplatform builds. */
            private fun floorDiv(a: Long, b: Long): Long {
                val q = a / b
                return (if ((a xor b) < 0 && q * b != a) q - 1 else q).coerceIn(NEG_INF, POS_INF)
            }
            private fun ceilDiv(a: Long, b: Long): Long {
                val q = a / b
                return (if ((a xor b) > 0 && q * b != a) q + 1 else q).coerceIn(NEG_INF, POS_INF)
            }
        }
    }
}
