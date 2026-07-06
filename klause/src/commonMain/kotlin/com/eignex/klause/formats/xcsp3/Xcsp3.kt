package com.eignex.klause.formats.xcsp3

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.circuit.Subcircuit
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.factor.global.Inverse
import com.eignex.klause.factor.global.LexLess
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.Regular
import com.eignex.klause.factor.table.Table
import com.eignex.klause.formats.CnfLowering
import com.eignex.klause.formats.constRelationHolds
import com.eignex.klause.formats.reifyLinear
import com.eignex.klause.formats.trueLit
import com.eignex.klause.formats.tseitinAnd
import com.eignex.klause.formats.tseitinIff
import com.eignex.klause.formats.tseitinOr
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective

/** Raised when an XCSP3 construct outside the supported subset is encountered. */
class UnsupportedXcsp3Exception(msg: String) : RuntimeException("klause XCSP3: $msg")

/** A parsed XCSP3 instance lifted into klause's representation. */
data class Xcsp3Problem(
    /** Compiled solver problem. */
    val problem: Problem,
    /** Objective, or null for satisfaction instances. */
    val objective: LinearObjective?,
    /** Declared variable name to int var id. */
    val intVarNames: Map<String, Int> = emptyMap(),
    /** True when the parsed objective was maximize. */
    val maximize: Boolean = false,
)

/** Parser/compiler for the supported XCSP3 integer subset. */
object Xcsp3 {
    /** Parse XCSP3 [text] into an [Xcsp3Problem]. */
    fun parse(text: String, negTableCap: Long = 1_000_000L): Xcsp3Problem = Builder(negTableCap).run {
        val root = parseXml(text)
        root.child("variables")?.let { vs -> vs.children.forEach { declareVar(it) } }
        root.child("constraints")?.let { cs -> cs.children.forEach { constraint(it) } }
        root.child("objectives")?.let { objs -> objs.children.firstOrNull()?.let { objective(it) } }
        build()
    }

    private class Builder(val negTableCap: Long) : CnfLowering {
        private val varIds = LinkedHashMap<String, Int>() // resolved name (incl. array cells) -> int var id
        private val domains = ArrayList<IntDomain>()
        override val factors = ArrayList<Factor>()
        private var nextBool = 0
        private var objective: LinearObjective? = null
        private var objectiveMaximize = false

        fun declareVar(e: XmlElement) {
            when (e.tag) {
                "var" -> addVar(e.attr("id"), parseDomain(e.textContent.trim()))

                "array" -> {
                    val id = e.attr("id")
                    val dims = Regex("""\[(\d+)]""").findAll(e.attr("size")).map { it.groupValues[1].toInt() }.toList()
                    if (dims.isEmpty()) throw UnsupportedXcsp3Exception("array size '${e.attr("size")}'")
                    val dom = parseDomain(e.textContent.trim())

                    // Declare one variable per cell of the (possibly multi-dimensional) array,
                    // naming cells x[i], x[i][j], … so index refs and `x[…][]` wildcards resolve.
                    fun declareCells(prefix: String, d: Int) {
                        if (d == dims.size) return addVar(prefix, dom)
                        for (i in 0 until dims[d]) declareCells("$prefix[$i]", d + 1)
                    }
                    declareCells(id, 0)
                }

                else -> throw UnsupportedXcsp3Exception("variable kind '${e.tag}'")
            }
        }

        private fun addVar(name: String, dom: IntDomain) {
            varIds[name] = domains.size
            domains.add(dom)
        }
        private fun newAuxVar(lo: Int, hi: Int): Int {
            domains.add(IntDomain(lo, hi))
            return domains.size - 1
        }
        override fun newBool(): Int = nextBool++

        private fun parseDomain(text: String): IntDomain {
            val values = HashSet<Int>()
            for (tok in text.split(Regex("\\s+")).filter { it.isNotBlank() }) {
                val r = tok.split("..")
                if (r.size == 2) for (v in r[0].toInt()..r[1].toInt()) values.add(v) else values.add(tok.toInt())
            }
            if (values.isEmpty()) throw UnsupportedXcsp3Exception("empty domain")
            val lo = values.min()
            val hi = values.max()
            var dom = IntDomain(lo, hi)
            for (v in lo..hi) if (v !in values) dom = dom.excludeValue(v)
            return dom
        }

        fun constraint(e: XmlElement) {
            when (e.tag) {
                "allDifferent" -> allDifferent(e)
                "sum" -> sum(e)
                "extension" -> extension(e)
                "intension" -> intension(e.textContent.trim())
                "count" -> count(e)
                "element" -> element(e)
                "channel" -> channel(e)
                "regular" -> regular(e)
                "mdd" -> mdd(e)
                "cumulative" -> cumulative(e)
                "circuit" -> circuit(e)
                "lex", "lexLess", "lexLesseq" -> lex(e)
                "instantiation" -> instantiation(e)
                "ordered" -> ordered(e)
                "allEqual" -> allEqual(e)
                "minimum" -> minMax(e, max = false)
                "maximum" -> minMax(e, max = true)
                "cardinality" -> cardinality(e)
                "noOverlap" -> noOverlap(e)
                "binPacking" -> binPacking(e)
                "nValues" -> nValues(e)
                "precedence" -> precedence(e)
                "knapsack" -> knapsack(e)
                "slide" -> slide(e)
                "group" -> group(e)
                "block" -> e.children.forEach { constraint(it) }
                else -> throw UnsupportedXcsp3Exception("constraint '${e.tag}'")
            }
        }

        private fun allDifferent(e: XmlElement) {
            // <except> weakens the constraint (listed values may repeat); dropping it would be unsound.
            if (e.child("except") != null) throw UnsupportedXcsp3Exception("allDifferent with <except>")
            val vars = refList(listText(e)).toIntArray()
            if (vars.isEmpty()) throw UnsupportedXcsp3Exception("allDifferent: empty list")
            factors.add(AllDifferent(vars = vars, domainMin = domainMin(vars), domainSize = domainSpan(vars)))
        }

        private fun sum(e: XmlElement) {
            // Terms may be plain variables or expressions (e.g. `ne(x,0)` counting occurrences);
            // each expression is reified/linearized into an int var carrying its value.
            val tokens = requireNotNull(e.child("list")).textContent.trim()
                .split(Regex("\\s+")).filter { it.isNotBlank() }
            val termVars = tokens.flatMap { tok -> expandNames(tok).map { termVar(it) } }.toIntArray()
            if (termVars.isEmpty()) throw UnsupportedXcsp3Exception("sum: empty <list>")
            val coeffs = coeffsOrUnit(e.child("coeffs")?.textContent, termVars.size)
            require(coeffs.size == termVars.size) { "sum: <coeffs> length != term count" }
            postCondition(coeffs, termVars, requireNotNull(e.child("condition")).textContent.trim())
        }

        /** Resolve a `<sum>` term to an int var: a declared variable, a constant, a reified
         *  relation (0/1), or an arithmetic expression bound to a fresh auxiliary. */
        private fun termVar(tok: String): Int {
            varIds[tok]?.let { return it }
            val node = FExpr.parse(tok)
            return when {
                node is FExpr.Ref -> ref(node.name)

                node is FExpr.Num -> newAuxVar(node.value, node.value)

                node is FExpr.Call && node.fn in REL -> {
                    val r = relationParts(node)
                    if (r.vars.isEmpty()) {
                        // Constant relation: a fixed 0/1 term.
                        val v = if (constRelationHolds(r.op, r.bound)) 1 else 0
                        newAuxVar(v, v)
                    } else {
                        // Reify the relation onto a fresh bool, then channel it to a 0/1 int var.
                        val aux = newBool()
                        factors.add(ReifiedLinear(aux, r.coeffs, r.vars, r.op, r.bound))
                        val ch = newAuxVar(0, 1)
                        factors.add(ReifiedLinear(aux, intArrayOf(1), intArrayOf(ch), LinearOp.EQ, 1))
                        ch
                    }
                }

                else -> {
                    val lin = linear(node)
                    if (lin.coeffs.isEmpty()) newAuxVar(lin.constant, lin.constant) else materializeVar(lin)
                }
            }
        }

        /** Min/max of a linear expression over its variables' domains. */
        private fun linBounds(lin: Lin): Pair<Int, Int> {
            var lo = lin.constant
            var hi = lin.constant
            for ((v, c) in lin.coeffs) {
                val d = domains[v]
                if (c >= 0) {
                    lo += c * d.min
                    hi += c * d.max
                } else {
                    lo += c * d.max
                    hi += c * d.min
                }
            }
            return lo to hi
        }

        /** Parse a `<condition>` `(op, rhs)` where rhs is a constant or a variable. Returns the
         *  operator, a constant bound, and the rhs var id when the right-hand side is a variable. */
        private fun sumCondition(text: String): Triple<LinearOp, Int, Int?> {
            val m = Regex("""\(\s*(\w+)\s*,\s*(-?\w+(?:\[\d+])*)\s*\)""").find(text)
                ?: throw UnsupportedXcsp3Exception("condition '$text'")
            val (op, delta) = relOp(
                m.groupValues[1],
            ) ?: throw UnsupportedXcsp3Exception("condition op '${m.groupValues[1]}'")
            val rhs = m.groupValues[2]
            val k = rhs.toIntOrNull()
            return if (k != null) Triple(op, k + delta, null) else Triple(op, delta, ref(rhs))
        }

        /** Constrain the linear expression `Σ coeffs·vars` by a `<condition>`: a simple `(op, k|var)`
         *  relation, an `(in, lo..hi)` interval (two bounds), or an `(in, {set})` membership. */
        private fun postCondition(coeffs: IntArray, vars: IntArray, condText: String) {
            val interval = Regex("""\(\s*in\s*,\s*(-?\d+)\.\.(-?\d+)\s*\)""").find(condText)
            if (interval != null) {
                factors.add(Linear(coeffs, vars, LinearOp.GE, interval.groupValues[1].toInt()))
                factors.add(Linear(coeffs, vars, LinearOp.LE, interval.groupValues[2].toInt()))
                return
            }
            val set = Regex("""\(\s*in\s*,\s*\{([^}]*)}\s*\)""").find(condText)
            if (set != null) {
                val m = if (coeffs.size == 1 && coeffs[0] == 1) {
                    vars[0]
                } else {
                    materializeVar(
                        Lin(linMap(coeffs, vars), 0),
                    )
                }
                val members = parseSetMembers(set.groupValues[1])
                factors.add(
                    Clause(members.map { reifyLinear(intArrayOf(1), intArrayOf(m), LinearOp.EQ, it) }.toIntArray()),
                )
                return
            }
            val (op, k, rhsVar) = sumCondition(condText)
            if (rhsVar == null) {
                factors.add(Linear(coeffs, vars, op, k))
            } else {
                factors.add(Linear(coeffs + -1, vars + rhsVar, op, k))
            }
        }

        /** Parse a `{...}` set body into its members, expanding `lo..hi` ranges. */
        private fun parseSetMembers(body: String): List<Int> =
            body.split(",").map { it.trim() }.filter { it.isNotEmpty() }.flatMap { tok ->
                tok.split(
                    "..",
                ).let { if (it.size == 2) (it[0].toInt()..it[1].toInt()).toList() else listOf(tok.toInt()) }
            }

        /** Coalesce parallel `coeffs`/`vars` arrays into a single variable-keyed linear map. */
        private fun linMap(coeffs: IntArray, vars: IntArray): Map<Int, Int> {
            val m = HashMap<Int, Int>()
            for (i in vars.indices) m[vars[i]] = (m[vars[i]] ?: 0) + coeffs[i]
            return m
        }

        private fun extension(e: XmlElement) {
            val vars = listVars(e)
            val supports = e.child("supports")?.textContent?.trim()
            val conflicts = e.child("conflicts")?.textContent?.trim()
            when {
                // No allowed tuple ⇒ the constraint (hence the instance) is unsatisfiable.
                supports != null && supports.isEmpty() -> factors.add(Clause(intArrayOf(Lit.negate(trueLit()))))

                // No forbidden tuple ⇒ trivially satisfied; post nothing.
                conflicts != null && conflicts.isEmpty() -> Unit

                supports != null -> factors.add(Table(xs = vars, tuples = parseTuples(supports, vars)))

                conflicts != null -> {
                    val allowed = negativeTable(vars, parseTuples(conflicts, vars))
                    // Every tuple forbidden ⇒ unsatisfiable; otherwise the allowed-tuple table.
                    if (allowed.isEmpty()) {
                        factors.add(Clause(intArrayOf(Lit.negate(trueLit()))))
                    } else {
                        factors.add(Table(xs = vars, tuples = allowed))
                    }
                }

                else -> throw UnsupportedXcsp3Exception("extension without supports or conflicts")
            }
        }

        @Suppress("ThrowsCount") // arity and expansion-cap guards
        private fun parseTuples(text: String, vars: IntArray): IntArray {
            val arity = vars.size
            val t = text.trim()
            val out = ArrayList<Int>()
            // A unary table is written as bare values `0 1 2` (or ranges `0..2`, or `*`), no parentheses.
            if (arity == 1 && '(' !in t) {
                for (tok in t.split(Regex("\\s+")).filter { it.isNotBlank() }) out.addAll(columnValues(tok, vars[0]))
                return out.toIntArray()
            }
            for (m in Regex("""\(([^)]*)\)""").findAll(t)) {
                val row = m.groupValues[1].split(",").map { it.trim() }
                if (row.size != arity) throw UnsupportedXcsp3Exception("tuple arity ${row.size} != $arity")
                appendTupleExpansion(row, vars, out)
                if (out.size / arity > negTableCap) throw UnsupportedXcsp3Exception("table exceeds cap ($negTableCap)")
            }
            return out.toIntArray()
        }

        /** The concrete values a table-column entry denotes: `*` = the variable's whole domain,
         *  `lo..hi` = the inclusive range, otherwise the single literal value. */
        private fun columnValues(tok: String, v: Int): List<Int> = when {
            tok == "*" -> domainValues(v)
            ".." in tok -> tok.split("..").let { (it[0].toInt()..it[1].toInt()).toList() }
            else -> listOf(tok.toInt())
        }

        /** Append every concrete tuple denoted by [row] to [out], expanding `*`/range columns as a
         *  Cartesian product over the involved variables' domains. */
        private fun appendTupleExpansion(row: List<String>, vars: IntArray, out: MutableList<Int>) {
            val cols = row.mapIndexed { i, tok -> columnValues(tok, vars[i]) }
            val cur = IntArray(cols.size)
            fun rec(p: Int) {
                if (p == cols.size) {
                    for (x in cur) out.add(x)
                    return
                }
                for (value in cols[p]) {
                    cur[p] = value
                    rec(p + 1)
                }
            }
            rec(0)
        }

        /** Complement a negative (conflicts) table to the flat allowed-tuple list; empty when every
         *  tuple is forbidden (the constraint is then unsatisfiable). */
        private fun negativeTable(vars: IntArray, conflicts: IntArray): IntArray {
            val arity = vars.size
            val valuesPer = vars.map { domainValues(it) }
            var product = 1L
            for (vs in valuesPer) {
                product *= vs.size
                if (product > negTableCap) {
                    throw UnsupportedXcsp3Exception(
                        "negative table cartesian product exceeds cap ($negTableCap)",
                    )
                }
            }
            val forbidden = HashSet<List<Int>>()
            var i = 0
            while (i < conflicts.size) {
                forbidden.add((0 until arity).map { conflicts[i + it] })
                i += arity
            }
            val allowed = ArrayList<Int>()
            val cur = IntArray(arity)
            fun rec(p: Int) {
                if (p == arity) {
                    if (cur.toList() !in forbidden) for (v in cur) allowed.add(v)
                    return
                }
                for (v in valuesPer[p]) {
                    cur[p] = v
                    rec(p + 1)
                }
            }
            rec(0)
            return allowed.toIntArray()
        }

        private fun intension(expr: String) {
            val node = FExpr.parse(expr)
            if (node is FExpr.Call && node.fn in REL && node.args.size == 2) {
                val r = relationParts(node)
                when {
                    r.vars.isNotEmpty() -> factors.add(Linear(r.coeffs, r.vars, r.op, r.bound))
                    !constRelationHolds(r.op, r.bound) -> factors.add(Clause(intArrayOf(Lit.negate(trueLit()))))
                }
            } else {
                factors.add(Clause(intArrayOf(compileBool(node))))
            }
        }

        private fun compileBool(e: FExpr): Int = when (e) {
            is FExpr.Num -> if (e.value != 0) trueLit() else Lit.negate(trueLit())

            is FExpr.Ref -> reifyRel(FExpr.Call("ge", listOf(e, FExpr.Num(1))))

            is FExpr.SetLit -> throw UnsupportedXcsp3Exception("set literal outside 'in'")

            is FExpr.Call -> when (e.fn) {
                "not" -> Lit.negate(compileBool(e.args[0]))
                "and" -> tseitinAnd(e.args.map { compileBool(it) })
                "or" -> tseitinOr(e.args.map { compileBool(it) })
                "imp" -> tseitinOr(listOf(Lit.negate(compileBool(e.args[0])), compileBool(e.args[1])))
                "iff" -> e.args.map { compileBool(it) }.reduce { a, b -> tseitinIff(a, b) }
                "xor" -> e.args.map { compileBool(it) }.reduce { a, b -> Lit.negate(tseitinIff(a, b)) }
                "in" -> memberLit(e)
                in REL -> reifyRel(e)
                else -> throw UnsupportedXcsp3Exception("non-boolean intension op '${e.fn}'")
            }
        }

        /** `in(expr, {set})`: a literal true iff the expression takes one of the set's values. */
        private fun memberLit(e: FExpr.Call): Int {
            val set = e.args[1] as? FExpr.SetLit ?: throw UnsupportedXcsp3Exception("in: right side is not a set")
            val m = materializeVar(linear(e.args[0]))
            return tseitinOr(set.values.map { reifyLinear(intArrayOf(1), intArrayOf(m), LinearOp.EQ, it) })
        }

        override var trueLitCache = -1

        private fun reifyRel(node: FExpr.Call): Int {
            val r = relationParts(node)
            return reifyLinear(r.coeffs, r.vars, r.op, r.bound)
        }

        /** Map XCSP3 relation names to linear operators and strictness deltas. */
        private fun relOp(fn: String): Pair<LinearOp, Int>? = when (fn) {
            "le" -> LinearOp.LE to 0
            "lt" -> LinearOp.LE to -1
            "ge" -> LinearOp.GE to 0
            "gt" -> LinearOp.GE to 1
            "eq" -> LinearOp.EQ to 0
            "ne" -> LinearOp.NE to 0
            else -> null
        }

        private class RelParts(val coeffs: IntArray, val vars: IntArray, val op: LinearOp, val bound: Int)

        /** Lower `rel(lhs, rhs)` to the coalesced linear components. When both sides share the same
         *  variable terms they cancel to an empty var list, leaving the constant relation `0 op bound`. */
        private fun relationParts(node: FExpr.Call): RelParts {
            val (op, delta) = relOp(node.fn) ?: throw UnsupportedXcsp3Exception("relation '${node.fn}'")
            val lhs = linear(node.args[0])
            val rhs = linear(node.args[1])
            val combined = HashMap(lhs.coeffs)
            for ((v, c) in rhs.coeffs) combined[v] = (combined[v] ?: 0) - c
            combined.entries.removeAll { it.value == 0 }
            val bound = rhs.constant - lhs.constant + delta
            val vars = combined.keys.toIntArray()
            return RelParts(IntArray(vars.size) { combined.getValue(vars[it]) }, vars, op, bound)
        }

        private fun count(e: XmlElement) {
            val vars = listVars(e)
            val values = parseInts(e.child("values")?.textContent)
                ?: throw UnsupportedXcsp3Exception("count: only constant <values> supported")
            if (values.size != 1) throw UnsupportedXcsp3Exception("count: only a single value supported")
            val cnt = newAuxVar(0, vars.size)
            // Reify equalities and sum their 0/1 channels into `cnt`.
            val channels = IntArray(vars.size) { i ->
                val aux = newBool()
                factors.add(ReifiedLinear(aux, intArrayOf(1), intArrayOf(vars[i]), LinearOp.EQ, values[0]))
                val ch = newAuxVar(0, 1)
                factors.add(ReifiedLinear(aux, intArrayOf(1), intArrayOf(ch), LinearOp.EQ, 1))
                ch
            }
            val sumCoeffs = IntArray(channels.size + 1) { if (it < channels.size) 1 else -1 }
            val sumVars = IntArray(channels.size + 1) { if (it < channels.size) channels[it] else cnt }
            factors.add(Linear(sumCoeffs, sumVars, LinearOp.EQ, 0))
            postCondition(intArrayOf(1), intArrayOf(cnt), requireNotNull(e.child("condition")).textContent.trim())
        }

        private fun element(e: XmlElement) {
            e.child("matrix")?.let { return elementMatrix(e, it) }
            val arr = listVars(e)
            val offset = e.attr("startIndex").ifBlank { "0" }.toInt()
            val idx = singleTermVar(
                e.child("index")?.textContent
                    ?: throw UnsupportedXcsp3Exception("element: missing <index>"),
            )
            val value = e.child("value")?.textContent?.trim()
                ?: throw UnsupportedXcsp3Exception("element: missing <value>")
            factors.add(
                Element(idx = idx, result = singleTermVar(value), arr = arr, arrIsVars = true, indexOffset = offset),
            )
        }

        /** `element` over a constant matrix `M`: `M[i][j] = v` with `<index> i j </index>`, encoded
         *  as a 3-column [Table] over `(i, j, v)` — one tuple per cell. */
        private fun elementMatrix(e: XmlElement, matrix: XmlElement) {
            // Matrix element uses per-axis start indices (defaulting to 0), not a single startIndex.
            val rowOffset = e.attr("startRowIndex").ifBlank { "0" }.toInt()
            val colOffset = e.attr("startColIndex").ifBlank { "0" }.toInt()
            val rows = Regex("""\(([^)]*)\)""").findAll(matrix.textContent)
                .map { m -> m.groupValues[1].split(",").map { it.trim().toInt() } }.toList()
            if (rows.isEmpty()) throw UnsupportedXcsp3Exception("element: empty <matrix>")
            val idxTokens = requireNotNull(e.child("index")).textContent.trim()
                .split(Regex("\\s+")).filter { it.isNotBlank() }
            if (idxTokens.size != 2) throw UnsupportedXcsp3Exception("element: matrix needs a 2-D <index>")
            val i = singleTermVar(idxTokens[0])
            val j = singleTermVar(idxTokens[1])
            val v = singleTermVar(requireNotNull(e.child("value")).textContent)
            val tuples = ArrayList<Int>(rows.sumOf { it.size } * 3)
            for (r in rows.indices) {
                for (c in rows[r].indices) {
                    tuples.add(r + rowOffset)
                    tuples.add(c + colOffset)
                    tuples.add(rows[r][c])
                }
            }
            factors.add(Table(xs = intArrayOf(i, j, v), tuples = tuples.toIntArray()))
        }

        private fun channel(e: XmlElement) {
            val lists = e.children.filter { it.tag == "list" }
            when (lists.size) {
                1 -> {
                    val f = refList(lists[0].textContent).toIntArray()
                    factors.add(Inverse(f = f, g = f))
                }

                2 -> {
                    val f = refList(lists[0].textContent).toIntArray()
                    val g = refList(lists[1].textContent).toIntArray()
                    // Equal lengths are a bijection (Inverse, Semantics 31: f[i]=j ⟺ g[j]=i). With
                    // |X| < |Y| the spec (Semantics 32) is a one-way implication only.
                    when {
                        f.size == g.size -> factors.add(Inverse(f = f, g = g))
                        f.size < g.size -> channelPartial(f, g)
                        else -> throw UnsupportedXcsp3Exception("channel: first list longer violates |X|<|Y|")
                    }
                }

                else -> throw UnsupportedXcsp3Exception("channel: only 1- or 2-list forms supported")
            }
        }

        /** A two-list `channel` with `|X| < |Y|` (XCSP3 Semantics 32): the one-way implication
         *  `∀i,j: x[i]=j ⟹ y[j]=i`. The reverse does NOT hold — entries `y[j]` for `j` never taken
         *  by any `x[i]` are unconstrained — so unlike the equal-length case this is not a bijection. */
        private fun channelPartial(x: IntArray, y: IntArray) {
            if (x.size.toLong() * y.size > negTableCap) {
                throw UnsupportedXcsp3Exception("channel: ${x.size}x${y.size} decomposition exceeds cap")
            }
            for (i in x.indices) {
                for (j in y.indices) {
                    val xij = reifyLinear(intArrayOf(1), intArrayOf(x[i]), LinearOp.EQ, j) // x[i] = j
                    val yji = reifyLinear(intArrayOf(1), intArrayOf(y[j]), LinearOp.EQ, i) // y[j] = i
                    factors.add(Clause(intArrayOf(Lit.negate(xij), yji))) // x[i]=j ⟹ y[j]=i
                }
            }
        }

        private fun regular(e: XmlElement) {
            val finals = requireNotNull(e.child("final")).textContent.trim()
                .split(Regex("\\s+")).filter { it.isNotBlank() }
            buildRegular(
                listVars(e),
                requireNotNull(e.child("transitions")).textContent,
                requireNotNull(e.child("start")).textContent.trim(),
                finals,
            )
        }

        /** A multi-valued decision diagram is a layered automaton: the root is the node that is never a
         *  transition destination; the accepting nodes are the sinks (never a source). */
        private fun mdd(e: XmlElement) {
            val transitions = requireNotNull(e.child("transitions")).textContent
            val srcs = HashSet<String>()
            val dsts = HashSet<String>()
            for (m in TRANSITION.findAll(transitions)) {
                srcs.add(m.groupValues[1])
                dsts.add(m.groupValues[3])
            }
            val start = if ("root" in srcs) {
                "root"
            } else {
                (srcs - dsts).firstOrNull()
                    ?: throw UnsupportedXcsp3Exception("mdd: no root node")
            }
            buildRegular(listVars(e), transitions, start, (dsts - srcs).toList())
        }

        private fun buildRegular(seqVars: IntArray, transitions: String, start: String, finals: List<String>) {
            if (seqVars.isEmpty()) throw UnsupportedXcsp3Exception("regular/mdd: empty sequence list")
            data class Tr(val src: String, val sym: Int, val dst: String)
            val trs = TRANSITION.findAll(transitions)
                .map { Tr(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3]) }.toList()
            if (trs.isEmpty()) throw UnsupportedXcsp3Exception("regular/mdd: no transitions")

            val stateIdx = LinkedHashMap<String, Int>()
            fun stateOf(st: String) = stateIdx.getOrPut(st) { stateIdx.size + 1 }
            stateOf(start)
            trs.forEach {
                stateOf(it.src)
                stateOf(it.dst)
            }
            val q = stateIdx.size

            val minSym = trs.minOf { it.sym }
            val maxSym = trs.maxOf { it.sym }
            val offset = 1 - minSym
            val s = maxSym - minSym + 1
            val table = IntArray(q * s) // 0 = dead state
            for (t in trs) table[(stateOf(t.src) - 1) * s + (t.sym + offset - 1)] = stateOf(t.dst)
            val accepting = finals.map { stateOf(it) }.toIntArray()

            val seq = if (offset == 0) {
                seqVars
            } else {
                IntArray(seqVars.size) { i ->
                    val sv = seqVars[i]
                    val d = domains[sv]
                    val c = newAuxVar(d.min + offset, d.max + offset)
                    factors.add(Linear(intArrayOf(1, -1), intArrayOf(c, sv), LinearOp.EQ, offset)) // c - sv = offset
                    c
                }
            }
            factors.add(
                Regular(
                    seq = seq,
                    numStates = q,
                    alphabetSize = s,
                    transitions = table,
                    q0 = stateOf(start),
                    accepting = accepting,
                ),
            )
        }

        private fun cumulative(e: XmlElement) {
            val starts = refList(requireNotNull(e.child("origins")).textContent).toIntArray()
            val (durations, durationVars) = taskDims(requireNotNull(e.child("lengths")).textContent)
            val (resources, resourceVars) = taskDims(requireNotNull(e.child("heights")).textContent)
            require(durations.size == starts.size && resources.size == starts.size) {
                "cumulative: <origins>/<lengths>/<heights> length mismatch"
            }
            val condEl = e.child("condition")
                ?: throw UnsupportedXcsp3Exception("cumulative: unsupported form (no single <condition>)")
            val (op, cap, capVar) = sumCondition(condEl.textContent.trim())
            if (op != LinearOp.LE) {
                throw UnsupportedXcsp3Exception("cumulative: only (le, capacity) conditions supported")
            }
            factors.add(
                Cumulative(
                    starts = starts,
                    durations = durations,
                    resources = resources,
                    capacity = if (capVar == null) cap else domains[capVar].max,
                    durationVars = durationVars,
                    resourceVars = resourceVars,
                    capacityVar = capVar ?: -1,
                ),
            )
            // <ends> binds each task's end variable to start + duration (constant or variable).
            e.child("ends")?.let { endsEl ->
                val ends = refList(endsEl.textContent).toIntArray()
                require(ends.size == starts.size) { "cumulative: <ends>/<origins> length mismatch" }
                for (i in starts.indices) {
                    if (durationVars.isEmpty()) {
                        // start + duration(const) = end  ⟺  start − end = −duration
                        val vars = intArrayOf(starts[i], ends[i])
                        factors.add(Linear(intArrayOf(1, -1), vars, LinearOp.EQ, -durations[i]))
                    } else {
                        // start + duration(var) − end = 0
                        val vars = intArrayOf(starts[i], durationVars[i], ends[i])
                        factors.add(Linear(intArrayOf(1, 1, -1), vars, LinearOp.EQ, 0))
                    }
                }
            }
        }

        /** Resolve a cumulative dimension list (`<lengths>`/`<heights>`) to (constants-or-upper-bounds,
         *  variable ids). Constant when every entry is an integer; otherwise each entry is a variable and
         *  the constant array holds its domain upper bound (used by [Cumulative] for horizon sizing). */
        private fun taskDims(text: String): Pair<IntArray, IntArray> {
            parseInts(text)?.let { return it to IntArray(0) }
            val vars = refList(text).toIntArray()
            return IntArray(vars.size) { domains[vars[it]].max } to vars
        }

        private fun circuit(e: XmlElement) {
            val offset = e.attr("startIndex").ifBlank { "0" }.toInt()
            if (offset != 0) throw UnsupportedXcsp3Exception("circuit: only startIndex=0 supported")
            val succ = refList(listText(e)).toIntArray()
            // XCSP3 `circuit` (Semantics 46) is subcircuit semantics: nodes with succ(i) = i are
            // excluded (self-looping); the rest form a single cycle. It additionally requires a
            // circuit of size > 1. [Subcircuit] captures the cycle-over-included-nodes part but also
            // admits the empty (all-excluded) assignment, so pin the number of participating nodes
            // (those with succ(i) ≠ i): to <size> when given, else to "at least one" — which, with
            // Subcircuit's rejection of a lone included node, yields size ≥ 2.
            factors.add(Subcircuit(succ = succ))
            val included = IntArray(succ.size) { reifyLinear(intArrayOf(1), intArrayOf(succ[it]), LinearOp.NE, it) }
            val sizeEl = e.child("size")
            if (sizeEl != null) {
                val sizeVar = singleTermVar(sizeEl.textContent.trim())
                val chans = IntArray(included.size) { litTo01(included[it]) }
                val coeffs = IntArray(chans.size + 1) { if (it < chans.size) 1 else -1 }
                factors.add(Linear(coeffs, chans + sizeVar, LinearOp.EQ, 0))
            } else {
                factors.add(Clause(included))
            }
        }

        private fun lex(e: XmlElement) {
            val lists = e.children.filter { it.tag == "list" }.map { refList(it.textContent).toIntArray() }
            if (lists.size < 2) throw UnsupportedXcsp3Exception("lex: needs at least two lists")
            val opText = (e.child("operator")?.textContent?.trim() ?: e.attr("operator")).ifBlank { "lt" }
            val (strict, swap) = when (opText) {
                "lt" -> true to false
                "le" -> false to false
                "gt" -> true to true
                "ge" -> false to true
                else -> throw UnsupportedXcsp3Exception("lex operator '$opText'")
            }
            for (i in 0 until lists.size - 1) {
                val a = lists[i]
                val b = lists[i + 1]
                if (swap) factors.add(LexLess(b, a, strict)) else factors.add(LexLess(a, b, strict))
            }
        }

        /** Fix each listed variable to the corresponding value. */
        private fun instantiation(e: XmlElement) {
            val vars = listVars(e)
            val vals = parseInts(e.child("values")?.textContent)
                ?: throw UnsupportedXcsp3Exception("instantiation: non-constant <values>")
            require(vars.size == vals.size) { "instantiation: <list>/<values> length mismatch" }
            vars.forEachIndexed { i, v -> factors.add(Linear(intArrayOf(1), intArrayOf(v), LinearOp.EQ, vals[i])) }
        }

        /** Chain relation over consecutive list entries: `vars[i] ⟨op⟩ vars[i+1]`. */
        private fun ordered(e: XmlElement) {
            if (e.child("lengths") != null) throw UnsupportedXcsp3Exception("ordered with <lengths>")
            val vars = listVars(e)
            val opText = (e.child("operator")?.textContent?.trim() ?: e.attr("operator")).ifBlank { "le" }
            val (op, delta) = relOp(opText) ?: throw UnsupportedXcsp3Exception("ordered operator '$opText'")
            for (i in 0 until vars.size - 1) {
                factors.add(Linear(intArrayOf(1, -1), intArrayOf(vars[i], vars[i + 1]), op, delta))
            }
        }

        /** All listed variables take the same value. */
        private fun allEqual(e: XmlElement) {
            // <except> weakens the constraint (listed values are exempt); dropping it would be unsound.
            if (e.child("except") != null) throw UnsupportedXcsp3Exception("allEqual with <except>")
            val vars = refList(listText(e)).toIntArray()
            for (i in 0 until vars.size - 1) {
                factors.add(Linear(intArrayOf(1, -1), intArrayOf(vars[i], vars[i + 1]), LinearOp.EQ, 0))
            }
        }

        /** `minimum`/`maximum` of a list constrained by a condition, via [ArrayMinMax] + the condition. */
        private fun minMax(e: XmlElement, max: Boolean) {
            // Entries may be plain variables or expressions (e.g. `sub(y,37)`), each resolved to an int var.
            val vars = requireNotNull(e.child("list")).textContent.trim()
                .split(Regex("\\s+")).filter { it.isNotBlank() }
                .flatMap { tok -> expandNames(tok).map { termVar(it) } }.toIntArray()
            val m = newAuxVar(domainMin(vars), domainMin(vars) + domainSpan(vars) - 1)
            factors.add(ArrayMinMax(result = m, xs = vars, max = max))
            postCondition(intArrayOf(1), intArrayOf(m), requireNotNull(e.child("condition")).textContent.trim())
        }

        /** Global cardinality: each value in `<values>` occurs a fixed count, an interval, or a
         *  variable count (`<occurs>`) among the listed variables. */
        private fun cardinality(e: XmlElement) {
            val vars = listVars(e)
            val valuesEl = requireNotNull(e.child("values"))
            val values = parseInts(valuesEl.textContent)
                ?: throw UnsupportedXcsp3Exception("cardinality: non-constant <values>")
            // closed="true" additionally forbids any variable taking a value outside <values>.
            val closed = valuesEl.attr("closed").equals("true", ignoreCase = true)
            val occursText = requireNotNull(e.child("occurs")).textContent.trim()
            val occTokens = occursText.split(Regex("\\s+")).filter { it.isNotBlank() }
            val exact = parseInts(occursText)
            when {
                // Interval occurrences `lo..hi`.
                occTokens.any { ".." in it } -> {
                    require(occTokens.size == values.size) { "cardinality: <values>/<occurs> length mismatch" }
                    val lo = IntArray(occTokens.size) { occTokens[it].substringBefore("..").toInt() }
                    val hi = IntArray(occTokens.size) { occTokens[it].substringAfter("..").toInt() }
                    factors.add(
                        GlobalCardinality(xs = vars, cover = values, countLow = lo, countHigh = hi, closed = closed),
                    )
                }

                // Exact constant occurrences.
                exact != null -> {
                    require(exact.size == values.size) { "cardinality: <values>/<occurs> length mismatch" }
                    factors.add(
                        GlobalCardinality(
                            xs = vars,
                            cover = values,
                            countLow = exact,
                            countHigh = exact,
                            closed = closed,
                        ),
                    )
                }

                // Variable occurrences (a `<list>`/array reference, possibly a wildcard like `g[]`).
                else -> {
                    val occVars = refList(occursText).toIntArray()
                    require(occVars.size == values.size) { "cardinality: <values>/<occurs> length mismatch" }
                    factors.add(GlobalCardinality(xs = vars, cover = values, countVars = occVars, closed = closed))
                }
            }
        }

        /** Reify `x == value` onto a fresh 0/1 int var. */
        private fun eqValue01(x: Int, value: Int): Int {
            val eq = newBool()
            factors.add(ReifiedLinear(eq, intArrayOf(1), intArrayOf(x), LinearOp.EQ, value))
            val ch = newAuxVar(0, 1)
            factors.add(ReifiedLinear(eq, intArrayOf(1), intArrayOf(ch), LinearOp.EQ, 1))
            return ch
        }

        /** `binPacking`: item `i` goes to bin `list[i]`; each bin's total item size meets the condition. */
        @Suppress("ThrowsCount") // one guard per unsupported shape across the loads/limits/condition forms
        private fun binPacking(e: XmlElement) {
            val items = listVars(e)
            val sizes = parseInts(e.child("sizes")?.textContent)
                ?: throw UnsupportedXcsp3Exception("binPacking: non-constant <sizes>")
            require(sizes.size == items.size) { "binPacking: <list>/<sizes> length mismatch" }
            val offset = e.attr("startIndex").ifBlank { "0" }.toInt()
            val loadsEl = e.child("loads")
            val condEl = e.child("condition")
            when {
                // `<loads>`: each bin's total size equals its load variable — `Σ size[i]·[list[i]=b] = loads[b]`.
                loadsEl != null -> {
                    // Load entries may be plain variables or expressions (e.g. `sub(y,37)`).
                    val loadVars = loadsEl.textContent.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                        .flatMap { tok -> expandNames(tok).map { termVar(it) } }.toIntArray()
                    if (loadVars.size.toLong() * items.size > negTableCap) {
                        throw UnsupportedXcsp3Exception("binPacking: decomposition exceeds cap")
                    }
                    for (b in loadVars.indices) {
                        val ind = IntArray(items.size) { i -> eqValue01(items[i], b + offset) }
                        factors.add(Linear(sizes + -1, ind + loadVars[b], LinearOp.EQ, 0))
                    }
                }

                // `<limits>`: each bin `b` has its own constant capacity — `Σ size[i]·[list[i]=b] ≤ limits[b]`.
                e.child("limits") != null -> {
                    val limits = parseInts(e.child("limits")?.textContent)
                        ?: throw UnsupportedXcsp3Exception("binPacking: non-constant <limits>")
                    if (limits.size.toLong() * items.size > negTableCap) {
                        throw UnsupportedXcsp3Exception("binPacking: decomposition exceeds cap")
                    }
                    for (b in limits.indices) {
                        val ind = IntArray(items.size) { i -> eqValue01(items[i], b + offset) }
                        factors.add(Linear(sizes.copyOf(), ind, LinearOp.LE, limits[b]))
                    }
                }

                // `<condition>`: each bin's total size meets a shared capacity condition. The spec
                // quantifies only over used bins; applying the condition to every bin value in range
                // is a strengthening that is sound only for `le`/`lt` (an empty bin's total 0 always
                // satisfies `≤ k` for a non-negative capacity). Other operators would force empty bins
                // to meet a lower bound, so they are rejected rather than mis-encoded.
                condEl != null -> {
                    val condText = condEl.textContent.trim()
                    val (op, _) = condition(condText)
                    if (op != LinearOp.LE) {
                        throw UnsupportedXcsp3Exception("binPacking: only a (le/lt, k) <condition> is supported")
                    }
                    val loBin = items.minOf { domains[it].min }
                    val hiBin = items.maxOf { domains[it].max }
                    if ((hiBin - loBin + 1).toLong() * items.size > negTableCap) {
                        throw UnsupportedXcsp3Exception("binPacking: decomposition exceeds cap")
                    }
                    for (b in loBin..hiBin) {
                        val ind = IntArray(items.size) { i -> eqValue01(items[i], b) }
                        postCondition(sizes.copyOf(), ind, condText)
                    }
                }

                else -> throw UnsupportedXcsp3Exception("binPacking: neither <condition> nor <loads>")
            }
        }

        /** `knapsack`: a weighted-sum condition on item weights and one on item profits. */
        private fun knapsack(e: XmlElement) {
            val items = listVars(e)
            val weights = parseInts(e.child("weights")?.textContent)
                ?: throw UnsupportedXcsp3Exception("knapsack: non-constant <weights>")
            val profits = parseInts(e.child("profits")?.textContent)
                ?: throw UnsupportedXcsp3Exception("knapsack: non-constant <profits>")
            require(weights.size == items.size && profits.size == items.size) { "knapsack: length mismatch" }
            val conditions = e.children.filter { it.tag == "condition" }
            require(conditions.size == 2) { "knapsack: expected weight and profit conditions" }
            postCondition(weights, items, conditions[0].textContent.trim())
            postCondition(profits, items, conditions[1].textContent.trim())
        }

        /** `nValues`: the number of distinct values taken across the list — excluding any `<except>`
         *  values — meets the condition. */
        private fun nValues(e: XmlElement) {
            val except = (parseInts(e.child("except")?.textContent)?.toSet()).orEmpty()
            val cnt = distinctCountVar(listVars(e), except)
            postCondition(intArrayOf(1), intArrayOf(cnt), requireNotNull(e.child("condition")).textContent.trim())
        }

        /** A fresh int var equal to the count of distinct values taken across [vars], decomposed as
         *  `Σ used[v]` where `used[v] = 1` iff some variable equals `v`. Values in [except] are not
         *  counted (XCSP3 `nValues` with `<except>`). */
        private fun distinctCountVar(vars: IntArray, except: Set<Int> = emptySet()): Int {
            val loV = vars.minOf { domains[it].min }
            val hiV = vars.maxOf { domains[it].max }
            if ((hiV - loV + 1).toLong() * vars.size > negTableCap) {
                throw UnsupportedXcsp3Exception("nValues: value range too large to decompose")
            }
            val used = ArrayList<Int>()
            for (v in loV..hiV) {
                if (v in except) continue
                val eqLits = vars.map { reifyLinear(intArrayOf(1), intArrayOf(it), LinearOp.EQ, v) }
                used.add(litTo01(tseitinOr(eqLits)))
            }
            val cnt = newAuxVar(0, used.size)
            val coeffs = IntArray(used.size + 1) { if (it < used.size) 1 else -1 }
            factors.add(Linear(coeffs, (used + cnt).toIntArray(), LinearOp.EQ, 0))
            return cnt
        }

        /** Value precedence: for each consecutive pair `(s, t)` in `<values>`, value `s` must first
         *  occur before `t` — `t` at position `j` requires some `s` at an earlier position, and `t`
         *  cannot occupy position 0. With no `<values>`, the chain runs over the sorted union of the
         *  variables' domain values. */
        private fun precedence(e: XmlElement) {
            if (e.attr("covered").equals("true", ignoreCase = true)) {
                throw UnsupportedXcsp3Exception("precedence: covered form")
            }
            val vars = listVars(e)
            if (vars.isEmpty()) return
            val values = parseInts(e.child("values")?.textContent)
                ?: vars.flatMap { domainValues(it) }.distinct().sorted().toIntArray()
            if (values.size < 2) return
            if (values.size.toLong() * vars.size * vars.size > negTableCap) {
                throw UnsupportedXcsp3Exception("precedence: too large to decompose")
            }
            val eqLits = HashMap<Long, Int>()
            fun eqLit(j: Int, v: Int) = eqLits.getOrPut(j.toLong() shl 32 or (v.toLong() and 0xffffffffL)) {
                reifyLinear(intArrayOf(1), intArrayOf(vars[j]), LinearOp.EQ, v)
            }
            for (i in 0 until values.size - 1) {
                val s = values[i]
                val t = values[i + 1]
                factors.add(Clause(intArrayOf(Lit.negate(eqLit(0, t))))) // t may not occupy position 0
                for (j in 1 until vars.size) {
                    // x[j] = t ⇒ some earlier variable equals s
                    val clause = IntArray(j + 1) { if (it == 0) Lit.negate(eqLit(j, t)) else eqLit(it - 1, s) }
                    factors.add(Clause(clause))
                }
            }
        }

        /** 1-D no-overlap (disjunctive): tasks with constant durations share a unit resource, so at
         *  most one runs at a time — a [Cumulative] with unit heights and capacity 1. */
        @Suppress("ThrowsCount") // one guard per unsupported noOverlap shape
        private fun noOverlap(e: XmlElement) {
            val originsText = requireNotNull(e.child("origins")).textContent
            if ('(' in originsText) throw UnsupportedXcsp3Exception("noOverlap: multi-dimensional form")
            val starts = refList(originsText).toIntArray()
            val durations = parseInts(e.child("lengths")?.textContent)
                ?: throw UnsupportedXcsp3Exception("noOverlap: non-constant <lengths>")
            require(durations.size == starts.size) { "noOverlap: <origins>/<lengths> length mismatch" }
            // The Cumulative encoding lets a zero-length task sit anywhere (it consumes no resource),
            // which is exactly zeroIgnored="true" (the default). zeroIgnored="false" forbids placing a
            // zero-length task overlapping others, which this encoding cannot express — reject it.
            if (e.attr("zeroIgnored").equals("false", ignoreCase = true) && durations.any { it == 0 }) {
                throw UnsupportedXcsp3Exception("noOverlap: zeroIgnored=false with a zero-length task")
            }
            factors.add(
                Cumulative(
                    starts = starts,
                    durations = durations,
                    resources = IntArray(starts.size) { 1 },
                    capacity = 1,
                ),
            )
        }

        fun objective(e: XmlElement) {
            val maximize = e.tag == "maximize"
            objectiveMaximize = maximize
            val type = e.attr("type").ifBlank { "sum" }
            // Terms may be plain variables or expressions (e.g. `gt(x,0)`), each resolved to an int var.
            val listText = e.child("list")?.textContent ?: e.textContent
            val termVars = listText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                .flatMap { tok -> expandNames(tok).map { termVar(it) } }.toIntArray()
            if (termVars.isEmpty()) throw UnsupportedXcsp3Exception("objective: empty <list>")
            when (type) {
                "sum" -> {
                    val coeffs = coeffsOrUnit(e.child("coeffs")?.textContent, termVars.size)
                    require(coeffs.size == termVars.size) { "objective: <coeffs> length != term count" }
                    val arr = LongArray(domains.size)
                    termVars.forEachIndexed { i, v -> arr[v] += (if (maximize) -coeffs[i] else coeffs[i]).toLong() }
                    objective = LinearObjective(intCoefficients = arr)
                }

                "maximum", "minimum" -> {
                    val m = newAuxVar(domainMin(termVars), domainMin(termVars) + domainSpan(termVars) - 1)
                    factors.add(ArrayMinMax(result = m, xs = termVars, max = type == "maximum"))
                    objective = singleVarObjective(m, maximize)
                }

                "nValues" -> objective = singleVarObjective(distinctCountVar(termVars), maximize)

                else -> throw UnsupportedXcsp3Exception("objective type '$type'")
            }
        }

        /** An objective that minimizes (or, when [maximize], maximizes) a single variable. */
        private fun singleVarObjective(v: Int, maximize: Boolean): LinearObjective {
            val arr = LongArray(domains.size)
            arr[v] = if (maximize) -1L else 1L
            return LinearObjective(intCoefficients = arr)
        }

        private data class Lin(val coeffs: Map<Int, Int>, val constant: Int)

        private fun linear(e: FExpr): Lin = when (e) {
            is FExpr.Num -> Lin(emptyMap(), e.value)

            is FExpr.Ref -> Lin(mapOf(ref(e.name) to 1), 0)

            is FExpr.SetLit -> throw UnsupportedXcsp3Exception("set literal used arithmetically")

            is FExpr.Call -> when (e.fn) {
                "add" -> e.args.map { linear(it) }.reduce(::addLin)

                "sub" -> e.args.drop(1).fold(linear(e.args[0])) { a, x -> addLin(a, scaleLin(linear(x), -1)) }

                "neg" -> scaleLin(linear(e.args[0]), -1)

                "abs" -> absOf(linear(e.args[0]))

                "dist" -> absOf(addLin(linear(e.args[0]), scaleLin(linear(e.args[1]), -1)))

                "min" -> minMaxTerm(e.args, max = false)

                "max" -> minMaxTerm(e.args, max = true)

                "if" -> ifTerm(e.args)

                "div" -> divModTerm(e.args, mod = false)

                "mod" -> divModTerm(e.args, mod = true)

                "mul" -> {
                    val parts = e.args.map { linear(it) }
                    val nonConst = parts.filter { it.coeffs.isNotEmpty() }
                    val k = parts.filter { it.coeffs.isEmpty() }.fold(1) { a, c -> a * c.constant }
                    when {
                        k == 0 -> Lin(emptyMap(), 0)

                        nonConst.isEmpty() -> Lin(emptyMap(), k)

                        nonConst.size == 1 -> scaleLin(nonConst[0], k)

                        // A genuine variable product: materialise each factor and chain `Product`s.
                        else -> {
                            var acc = materializeVar(nonConst[0])
                            for (idx in 1 until nonConst.size) {
                                val next = materializeVar(nonConst[idx])
                                val (lo, hi) = productBounds(acc, next)
                                val p = newAuxVar(lo, hi)
                                factors.add(Product(acc, next, p))
                                acc = p
                            }
                            scaleLin(Lin(mapOf(acc to 1), 0), k)
                        }
                    }
                }

                // A boolean-valued subexpression used arithmetically is its 0/1 truth value.
                "in", in REL, in BOOL_FNS -> Lin(mapOf(litTo01(compileBool(e)) to 1), 0)

                else -> throw UnsupportedXcsp3Exception("arithmetic fn '${e.fn}'")
            }
        }

        /** Channel a literal to a fresh 0/1 int var equal to its truth value. */
        private fun litTo01(lit: Int): Int {
            val ch = newAuxVar(0, 1)
            val b = reifyLinear(intArrayOf(1), intArrayOf(ch), LinearOp.GE, 1) // b ⟺ ch = 1
            factors.add(Clause(intArrayOf(Lit.negate(b), lit))) // b → lit
            factors.add(Clause(intArrayOf(b, Lit.negate(lit)))) // lit → b
            return ch
        }

        /** `min`/`max` of expressions as a linear term, via [ArrayMinMax] over the materialised args. */
        private fun minMaxTerm(args: List<FExpr>, max: Boolean): Lin {
            val vs = args.map { materializeVar(linear(it)) }.toIntArray()
            val m = newAuxVar(vs.minOf { domains[it].min }, vs.maxOf { domains[it].max })
            factors.add(ArrayMinMax(result = m, xs = vs, max = max))
            return Lin(mapOf(m to 1), 0)
        }

        /** `if(cond, a, b)` as a linear term: a fresh int pinned to `a` or `b` by the condition. */
        private fun ifTerm(args: List<FExpr>): Lin {
            val cond = compileBool(args[0])
            val a = materializeVar(linear(args[1]))
            val b = materializeVar(linear(args[2]))
            val v = newAuxVar(minOf(domains[a].min, domains[b].min), maxOf(domains[a].max, domains[b].max))
            val ea = reifyLinear(intArrayOf(1, -1), intArrayOf(v, a), LinearOp.EQ, 0)
            val eb = reifyLinear(intArrayOf(1, -1), intArrayOf(v, b), LinearOp.EQ, 0)
            factors.add(Clause(intArrayOf(Lit.negate(cond), ea))) // cond ⇒ v = a
            factors.add(Clause(intArrayOf(cond, eb))) // ¬cond ⇒ v = b
            return Lin(mapOf(v to 1), 0)
        }

        /** Integer `div`/`mod` by a positive constant with a non-negative dividend (where floored and
         *  truncated division agree): `a = k·q + r, 0 ≤ r < k`. Other shapes stay unsupported to avoid
         *  a division-semantics mismatch. */
        private fun divModTerm(args: List<FExpr>, mod: Boolean): Lin {
            val a = materializeVar(linear(args[0]))
            val bLin = linear(args[1])
            val k = bLin.constant
            if (bLin.coeffs.isNotEmpty() || k <= 0) {
                throw UnsupportedXcsp3Exception(
                    "div/mod by non-constant/non-positive",
                )
            }
            val da = domains[a]
            if (da.min < 0) throw UnsupportedXcsp3Exception("div/mod with a possibly-negative dividend")
            val q = newAuxVar(da.min / k, da.max / k)
            val r = newAuxVar(0, k - 1)
            factors.add(Linear(intArrayOf(1, -k, -1), intArrayOf(a, q, r), LinearOp.EQ, 0)) // a = k·q + r
            return Lin(mapOf((if (mod) r else q) to 1), 0)
        }

        /** `|expr|` as a linear term: `|v| = max(v, -v)` via [ArrayMinMax] over `v` and its negation. */
        private fun absOf(lin: Lin): Lin {
            val v = materializeVar(lin)
            val d = domains[v]
            val neg = newAuxVar(-d.max, -d.min)
            factors.add(Linear(intArrayOf(1, 1), intArrayOf(v, neg), LinearOp.EQ, 0)) // neg = −v
            val hi = maxOf(-d.min, d.max)
            val lo = when {
                d.min <= 0 && d.max >= 0 -> 0
                d.min > 0 -> d.min
                else -> -d.max
            }
            val a = newAuxVar(lo, hi)
            factors.add(ArrayMinMax(result = a, xs = intArrayOf(v, neg), max = true))
            return Lin(mapOf(a to 1), 0)
        }

        /** Materialise a linear expression as a single int var (returning it directly when it already
         *  is one), posting `v = expr` otherwise. */
        private fun materializeVar(lin: Lin): Int {
            if (lin.constant == 0 && lin.coeffs.size == 1 && lin.coeffs.values.first() == 1) {
                return lin.coeffs.keys.first()
            }
            val (lo, hi) = linBounds(lin)
            val v = newAuxVar(lo, hi)
            val vars = lin.coeffs.keys.toList()
            val cs = IntArray(vars.size + 1) { if (it < vars.size) -lin.coeffs.getValue(vars[it]) else 1 }
            val ids = IntArray(vars.size + 1) { if (it < vars.size) vars[it] else v }
            factors.add(Linear(cs, ids, LinearOp.EQ, lin.constant)) // v − expr = 0
            return v
        }

        /** Integer bounds of the product `a * b` over the two variables' domains. */
        private fun productBounds(a: Int, b: Int): Pair<Int, Int> {
            val da = domains[a]
            val db = domains[b]
            val corners = listOf(
                da.min.toLong() * db.min,
                da.min.toLong() * db.max,
                da.max.toLong() * db.min,
                da.max.toLong() * db.max,
            )
            val lo = corners.min()
            val hi = corners.max()
            if (lo < Int.MIN_VALUE || hi > Int.MAX_VALUE) throw UnsupportedXcsp3Exception("product domain overflow")
            return lo.toInt() to hi.toInt()
        }

        private fun addLin(a: Lin, b: Lin): Lin {
            val m = HashMap(a.coeffs)
            for ((v, c) in b.coeffs) m[v] = (m[v] ?: 0) + c
            return Lin(m, a.constant + b.constant)
        }
        private fun scaleLin(a: Lin, k: Int) = Lin(a.coeffs.mapValues { it.value * k }, a.constant * k)

        /** Resolve one variable/constant term to a var id. */
        private fun singleTermVar(text: String): Int {
            val t = text.trim()
            varIds[t]?.let { return it }
            return when (val node = FExpr.parse(t)) {
                is FExpr.Ref -> ref(node.name)
                is FExpr.Num -> newAuxVar(node.value, node.value)
                else -> throw UnsupportedXcsp3Exception("expected a single variable/constant, got '$text'")
            }
        }

        private fun listText(e: XmlElement): String = e.child("list")?.textContent ?: e.textContent

        /** Resolve vars from a constraint `<list>` child. */
        private fun listVars(e: XmlElement): IntArray = refList(
            requireNotNull(e.child("list")).textContent,
        ).toIntArray()

        /** Parse whitespace-separated integer constants, or null if [text] is null or any token
         *  is not an integer (e.g. a variable reference) — callers treat null as "not constant".
         *  Supports XCSP3 run-length shorthand `vxn` (value `v` repeated `n` times). */
        private fun parseInts(text: String?): IntArray? {
            val toks = text?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: return null
            val out = ArrayList<Int>(toks.size)
            for (tok in toks) {
                val rle = RLE.matchEntire(tok)
                if (rle != null) {
                    val v = rle.groupValues[1].toInt()
                    repeat(rle.groupValues[2].toInt()) { out.add(v) }
                } else {
                    out.add(tok.toIntOrNull() ?: return null)
                }
            }
            return out.toIntArray()
        }

        /** Coefficients for a `<sum>`/objective: unit weights when absent, the parsed constants
         *  when present, else unsupported (a present-but-non-constant `<coeffs>` must not default). */
        private fun coeffsOrUnit(text: String?, n: Int): IntArray = when {
            text == null -> IntArray(n) { 1 }
            else -> parseInts(text) ?: throw UnsupportedXcsp3Exception("non-constant <coeffs>")
        }

        private fun refList(text: String): List<Int> =
            text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.flatMap { expandRef(it) }

        // A `<list>` entry may be a declared variable (possibly a wildcard/range over cells), a
        // constant, a reified relation, or an arithmetic expression; [termVar] resolves each to an
        // int var (fast-pathing declared variables) so lists of expressions/constants are supported.
        private fun expandRef(tok: String): List<Int> = expandNames(tok).map { termVar(it) }

        /** Expand an array reference token into the declared cell names it denotes. Each `[...]`
         *  group is a fixed index `[i]`, a range `[lo..hi]`, or a wildcard `[]` (any index), in
         *  any position and dimensionality — e.g. `x[]`, `x[][]`, `x[2][]`, `x[0..1][0]`. A plain
         *  reference with no wildcard or range is returned as-is (resolved later by [ref]). */
        private fun expandNames(tok: String): List<String> {
            if ("[]" !in tok && ".." !in tok) return listOf(tok)
            val m = ARRAY_REF.find(tok) ?: return listOf(tok)
            val pattern = StringBuilder("^").append(Regex.escape(m.groupValues[1]))
            for (spec in BRACKET.findAll(m.groupValues[2]).map { it.groupValues[1] }) {
                val range = spec.split("..")
                val frag = when {
                    spec.isBlank() -> """\d+"""
                    range.size == 2 -> "(?:" + (range[0].toInt()..range[1].toInt()).joinToString("|") + ")"
                    else -> Regex.escape(spec)
                }
                pattern.append("""\[""").append(frag).append(']')
            }
            val rx = Regex(pattern.append('$').toString())
            return varIds.keys.filter { rx.matches(it) }
        }
        private fun ref(name: String): Int = varIds[name] ?: throw UnsupportedXcsp3Exception("unknown variable '$name'")

        /** Instantiate a `<slide>` template over each sliding window of the list. `collect` (default:
         *  the template's parameter count) is the window size; the window steps by one. */
        private fun slide(e: XmlElement) {
            val lists = e.children.filter { it.tag == "list" }
            if (lists.size != 1) throw UnsupportedXcsp3Exception("slide: only the single-list form is supported")
            val listElem = lists[0]
            val template = e.children.firstOrNull { it.tag != "list" }
                ?: throw UnsupportedXcsp3Exception("slide: missing template constraint")
            val names = listElem.textContent.trim().split(Regex("\\s+"))
                .filter { it.isNotBlank() }.flatMap { expandNames(it) }
            val used = template.explicitParamIndices()
            val collect = listElem.attr("collect").toIntOrNull() ?: ((used.maxOrNull() ?: 0) + 1)
            require(collect >= 1) { "slide: collect must be >= 1" }
            for (i in 0..names.size - collect) {
                constraint(template.substituteParams(names.subList(i, i + collect), used))
            }
        }

        /** Instantiate a `<group>` template for each `<args>` row. */
        private fun group(e: XmlElement) {
            val template = e.children.firstOrNull { it.tag != "args" }
                ?: throw UnsupportedXcsp3Exception("group without a template constraint")
            val used = template.explicitParamIndices()
            for (args in e.children.filter { it.tag == "args" }) {
                val tokens = args.textContent.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                    .flatMap { expandNames(it) }
                constraint(template.substituteParams(tokens, used))
            }
        }

        private fun domainMin(vars: IntArray) = vars.minOf { domains[it].min }
        private fun domainSpan(vars: IntArray) = vars.maxOf { domains[it].max } - domainMin(vars) + 1
        private fun domainValues(v: Int): List<Int> {
            val d = domains[v]
            val out = ArrayList<Int>(d.size)
            for (k in 0 until d.size) out.add(d.valueAt(k))
            return out
        }

        private fun condition(text: String): Pair<LinearOp, Int> {
            val m = Regex("""\(\s*(\w+)\s*,\s*(-?\d+)\s*\)""").find(text)
                ?: throw UnsupportedXcsp3Exception("condition '$text' (only (op,const) supported)")
            val k = m.groupValues[2].toInt()
            val (op, delta) = relOp(m.groupValues[1])
                ?: throw UnsupportedXcsp3Exception("condition op '${m.groupValues[1]}'")
            return op to (k + delta)
        }

        fun build(): Xcsp3Problem = Xcsp3Problem(
            Problem(
                numBoolVars = nextBool,
                numIntVars = domains.size,
                intDomains = domains.toTypedArray(),
                factors = factors.toTypedArray(),
            ),
            objective,
            intVarNames = LinkedHashMap(varIds),
            maximize = objectiveMaximize,
        )

        companion object {
            private val REL = setOf("eq", "ne", "le", "lt", "ge", "gt")
            private val BOOL_FNS = setOf("and", "or", "not", "imp", "iff", "xor")
            private val TRANSITION = Regex("""\(\s*(\w+)\s*,\s*(-?\d+)\s*,\s*(\w+)\s*\)""")
            private val ARRAY_REF = Regex("""^([^\[]+)((?:\[[^\]]*])+)$""")
            private val BRACKET = Regex("""\[([^\]]*)]""")
            private val RLE = Regex("""(-?\d+)x(\d+)""")
        }
    }
}
