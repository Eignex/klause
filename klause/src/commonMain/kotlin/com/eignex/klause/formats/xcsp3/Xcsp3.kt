package com.eignex.klause.formats.xcsp3

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.ArrayMinMax
import com.eignex.klause.solver.factor.Circuit
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Cumulative
import com.eignex.klause.solver.factor.Element
import com.eignex.klause.solver.factor.Inverse
import com.eignex.klause.solver.factor.LexLess
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.Regular
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.Table

/** Raised when an XCSP3 construct outside the supported subset is encountered. */
class UnsupportedXcsp3Exception(msg: String) : RuntimeException("klause XCSP3: $msg")

/** A parsed XCSP3 instance lifted into klause's representation. */
data class Xcsp3Problem(
    /** The compiled solver problem. */
    val problem: Problem,
    /** The objective, or null for a pure satisfaction instance. */
    val objective: LinearObjective?,
    /** Declared variable name (including array cells as `id[i]`) → int var id, in
     *  declaration order. Lets a CLI render named solutions (XCSP3 `v <instantiation>`). */
    val intVarNames: Map<String, Int> = emptyMap(),
    /** True when the original `<objectives>` was a `maximize` (the [objective] negates so
     *  the engine minimises). Lets a CLI report the true objective value in the `o` line. */
    val maximize: Boolean = false,
)

/**
 * Pragmatic XCSP3 ingest → klause [Problem]. Covers the common integer CSP/COP core:
 *
 *  - `<var>` and 1-D `<array>` integer declarations with range / value-list domains;
 *  - `<extension>` positive (`<supports>`) and negative (`<conflicts>`, lowered to a
 *    positive [Table] over the domain complement) tables;
 *  - `<intension>` over a tree evaluator: arithmetic (`add sub mul neg`), comparisons
 *    (`eq ne lt le gt ge`) and boolean structure (`and or not imp iff xor`), reified onto
 *    aux bools where nested;
 *  - globals `<allDifferent> <sum> <count> <element> <channel> <regular> <cumulative>
 *    <circuit> <lex>` mapped to the matching native factors;
 *  - `<objectives>` `minimize` / `maximize` of a (weighted) `sum`, or the `minimum` /
 *    `maximum` of a variable list.
 *
 * Out-of-subset features (set/graph/float vars, n-D arrays, unsupported globals, nonlinear
 * intension) raise [UnsupportedXcsp3Exception] rather than silently producing a wrong model.
 *
 * `negTableCap` bounds the domain cartesian product enumerated when lowering a negative table.
 */
object Xcsp3 {
    /** Parse XCSP3 [text] into an [Xcsp3Problem]. */
    fun parse(text: String, negTableCap: Long = 1_000_000L): Xcsp3Problem = Builder(negTableCap).run {
        val root = parseXml(text)
        root.child("variables")?.let { vs -> vs.children.forEach { declareVar(it) } }
        root.child("constraints")?.let { cs -> cs.children.forEach { constraint(it) } }
        root.child("objectives")?.let { objs -> objs.children.firstOrNull()?.let { objective(it) } }
        build()
    }

    private class Builder(val negTableCap: Long) {
        private val varIds = LinkedHashMap<String, Int>() // resolved name (incl. array cells) -> int var id
        private val domains = ArrayList<IntDomain>()
        private val factors = ArrayList<Factor>()
        private var nextBool = 0
        private var objective: LinearObjective? = null
        private var objectiveMaximize = false

        // --- variables ---

        fun declareVar(e: XmlElement) {
            when (e.tag) {
                "var" -> addVar(e.attr("id"), parseDomain(e.textContent.trim()))

                "array" -> {
                    val id = e.attr("id")
                    val size = Regex("""\[(\d+)\]""").find(e.attr("size"))?.groupValues?.get(1)?.toInt()
                        ?: throw UnsupportedXcsp3Exception("only 1-D arrays supported: ${e.attr("size")}")
                    val dom = parseDomain(e.textContent.trim())
                    for (i in 0 until size) addVar("$id[$i]", dom)
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
        private fun newBool(): Int = nextBool++

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

        // --- constraints ---

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
                "cumulative" -> cumulative(e)
                "circuit" -> circuit(e)
                "lex", "lexLess", "lexLesseq" -> lex(e)
                "group" -> group(e)
                "block" -> e.children.forEach { constraint(it) }
                else -> throw UnsupportedXcsp3Exception("constraint '${e.tag}'")
            }
        }

        private fun allDifferent(e: XmlElement) {
            val vars = refList(listText(e)).toIntArray()
            factors.add(AllDifferent(vars = vars, domainMin = domainMin(vars), domainSize = domainSpan(vars)))
        }

        private fun sum(e: XmlElement) {
            val vars = refList(requireNotNull(e.child("list")).textContent).toIntArray()
            val coeffs = parseInts(e.child("coeffs")?.textContent) ?: IntArray(vars.size) { 1 }
            val (op, k) = condition(requireNotNull(e.child("condition")).textContent.trim())
            factors.add(Linear(coeffs, vars, op, k))
        }

        // --- extension (positive supports / negative conflicts) ---

        private fun extension(e: XmlElement) {
            val vars = refList(requireNotNull(e.child("list")).textContent).toIntArray()
            val supports = e.child("supports")?.textContent?.trim()
            val conflicts = e.child("conflicts")?.textContent?.trim()
            when {
                supports != null -> factors.add(Table(xs = vars, tuples = parseTuples(supports, vars.size)))
                conflicts != null -> factors.add(negativeTable(vars, parseTuples(conflicts, vars.size)))
                else -> throw UnsupportedXcsp3Exception("extension without supports or conflicts")
            }
        }

        private fun parseTuples(text: String, arity: Int): IntArray {
            val tuples = ArrayList<Int>()
            for (m in Regex("""\(([^)]*)\)""").findAll(text)) {
                val row = m.groupValues[1].split(",").map { it.trim() }
                if (row.any { it == "*" }) throw UnsupportedXcsp3Exception("wildcard (*) tuples not supported")
                val ints = row.map { it.toInt() }
                if (ints.size != arity) throw UnsupportedXcsp3Exception("tuple arity ${ints.size} != $arity")
                tuples.addAll(ints)
            }
            return tuples.toIntArray()
        }

        /** Lower a negative table to a positive [Table] over the domain cartesian product
         *  minus the forbidden rows. Guarded by `negTableCap` to avoid blowup. */
        private fun negativeTable(vars: IntArray, conflicts: IntArray): Table {
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
            return Table(xs = vars, tuples = allowed.toIntArray())
        }

        // --- intension (arithmetic + comparison + boolean tree) ---

        private fun intension(expr: String) {
            val node = FExpr.parse(expr)
            if (node is FExpr.Call && node.fn in REL && node.args.size == 2) {
                factors.add(relationLinear(node))
            } else {
                factors.add(Clause(intArrayOf(compileBool(node))))
            }
        }

        private fun compileBool(e: FExpr): Int = when (e) {
            is FExpr.Num -> if (e.value != 0) trueLit() else Lit.negate(trueLit())

            is FExpr.Ref -> reifyRel(FExpr.Call("ge", listOf(e, FExpr.Num(1))))

            // 0/1 var truthiness
            is FExpr.Call -> when (e.fn) {
                "not" -> Lit.negate(compileBool(e.args[0]))
                "and" -> tseitinAnd(e.args.map { compileBool(it) })
                "or" -> tseitinOr(e.args.map { compileBool(it) })
                "imp" -> tseitinOr(listOf(Lit.negate(compileBool(e.args[0])), compileBool(e.args[1])))
                "iff" -> e.args.map { compileBool(it) }.reduce { a, b -> tseitinIff(a, b) }
                "xor" -> e.args.map { compileBool(it) }.reduce { a, b -> Lit.negate(tseitinIff(a, b)) }
                in REL -> reifyRel(e)
                else -> throw UnsupportedXcsp3Exception("non-boolean intension op '${e.fn}'")
            }
        }

        private var trueLitCache = -1
        private fun trueLit(): Int {
            if (trueLitCache < 0) {
                trueLitCache = Lit.make(newBool(), true)
                factors.add(
                    Clause(intArrayOf(trueLitCache)),
                )
            }
            return trueLitCache
        }

        private fun tseitinAnd(lits: List<Int>): Int {
            val a = Lit.make(newBool(), true)
            for (l in lits) factors.add(Clause(intArrayOf(Lit.negate(a), l)))
            factors.add(Clause((lits.map { Lit.negate(it) } + a).toIntArray()))
            return a
        }

        private fun tseitinOr(lits: List<Int>): Int {
            val a = Lit.make(newBool(), true)
            factors.add(Clause((lits + Lit.negate(a)).toIntArray()))
            for (l in lits) factors.add(Clause(intArrayOf(Lit.negate(l), a)))
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

        private fun reifyRel(node: FExpr.Call): Int {
            val lin = relationLinear(node)
            val aux = newBool()
            factors.add(
                ReifiedLinear(auxBoolVar = aux, coeffs = lin.coeffs, vars = lin.vars, op = lin.op, bound = lin.bound),
            )
            return Lit.make(aux, true)
        }

        /** `rel(lhs, rhs)` → a [Linear] `coeffs·vars OP bound`. */
        private fun relationLinear(node: FExpr.Call): Linear {
            val op = when (node.fn) {
                "le", "lt" -> LinearOp.LE
                "ge", "gt" -> LinearOp.GE
                "eq" -> LinearOp.EQ
                "ne" -> LinearOp.NE
                else -> throw UnsupportedXcsp3Exception("relation '${node.fn}'")
            }
            val lhs = linear(node.args[0])
            val rhs = linear(node.args[1])
            val combined = HashMap(lhs.coeffs)
            for ((v, c) in rhs.coeffs) combined[v] = (combined[v] ?: 0) - c
            combined.entries.removeAll { it.value == 0 }
            var bound = rhs.constant - lhs.constant
            if (node.fn == "lt") bound -= 1
            if (node.fn == "gt") bound += 1
            val vars = combined.keys.toIntArray()
            return Linear(IntArray(vars.size) { combined.getValue(vars[it]) }, vars, op, bound)
        }

        // --- count / element / channel / regular / cumulative / circuit / lex ---

        private fun count(e: XmlElement) {
            val vars = refList(requireNotNull(e.child("list")).textContent).toIntArray()
            val values = parseInts(e.child("values")?.textContent)
                ?: throw UnsupportedXcsp3Exception("count: only constant <values> supported")
            if (values.size != 1) throw UnsupportedXcsp3Exception("count: only a single value supported")
            val (op, k) = condition(requireNotNull(e.child("condition")).textContent.trim())
            val cnt = newAuxVar(0, vars.size)
            // cnt = #{i : vars[i] = values[0]}: reify each equality, channel to a 0/1 int,
            // then sum the channels into cnt via a Linear EQ. Replaces the dropped Count factor.
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
            factors.add(Linear(intArrayOf(1), intArrayOf(cnt), op, k))
        }

        private fun element(e: XmlElement) {
            val arr = refList(requireNotNull(e.child("list")).textContent).toIntArray()
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

        private fun channel(e: XmlElement) {
            val lists = e.children.filter { it.tag == "list" }
            when (lists.size) {
                1 -> {
                    val f = refList(lists[0].textContent).toIntArray()
                    factors.add(Inverse(f = f, g = f))
                }

                2 -> factors.add(
                    Inverse(
                        f = refList(lists[0].textContent).toIntArray(),
                        g = refList(lists[1].textContent).toIntArray(),
                    ),
                )

                else -> throw UnsupportedXcsp3Exception("channel: only 1- or 2-list forms supported")
            }
        }

        private fun regular(e: XmlElement) {
            val seqVars = refList(requireNotNull(e.child("list")).textContent).toIntArray()
            val start = requireNotNull(e.child("start")).textContent.trim()
            val finals = requireNotNull(
                e.child("final"),
            ).textContent.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            data class Tr(val src: String, val sym: Int, val dst: String)
            val trs = Regex("""\(\s*([\w]+)\s*,\s*(-?\d+)\s*,\s*([\w]+)\s*\)""")
                .findAll(requireNotNull(e.child("transitions")).textContent)
                .map { Tr(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3]) }.toList()
            if (trs.isEmpty()) throw UnsupportedXcsp3Exception("regular: no transitions")

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

        @Suppress("ThrowsCount") // one guarded throw per unsupported cumulative shape
        private fun cumulative(e: XmlElement) {
            val starts = refList(requireNotNull(e.child("origins")).textContent).toIntArray()
            val durations = parseInts(e.child("lengths")?.textContent)
                ?: throw UnsupportedXcsp3Exception("cumulative: only constant <lengths> supported")
            val resources = parseInts(e.child("heights")?.textContent)
                ?: throw UnsupportedXcsp3Exception("cumulative: only constant <heights> supported")
            val (op, cap) = condition(requireNotNull(e.child("condition")).textContent.trim())
            if (op != LinearOp.LE) {
                throw UnsupportedXcsp3Exception(
                    "cumulative: only (le, capacity) conditions supported",
                )
            }
            factors.add(Cumulative(starts = starts, durations = durations, resources = resources, capacity = cap))
        }

        private fun circuit(e: XmlElement) {
            val offset = e.attr("startIndex").ifBlank { "0" }.toInt()
            if (offset != 0) throw UnsupportedXcsp3Exception("circuit: only startIndex=0 supported")
            factors.add(Circuit(succ = refList(listText(e)).toIntArray()))
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

        // --- objective ---

        fun objective(e: XmlElement) {
            val maximize = e.tag == "maximize"
            objectiveMaximize = maximize
            val type = e.attr("type").ifBlank { "sum" }
            val vars = refList(e.child("list")?.textContent ?: e.textContent).toIntArray()
            when (type) {
                "sum" -> {
                    val coeffs = parseInts(e.child("coeffs")?.textContent) ?: IntArray(vars.size) { 1 }
                    val arr = LongArray(domains.size)
                    vars.forEachIndexed { i, v -> arr[v] = (if (maximize) -coeffs[i] else coeffs[i]).toLong() }
                    objective = LinearObjective(intCoefficients = arr)
                }

                "maximum", "minimum" -> {
                    val m = newAuxVar(domainMin(vars), domainMin(vars) + domainSpan(vars) - 1)
                    factors.add(ArrayMinMax(result = m, xs = vars, max = type == "maximum"))
                    val arr = LongArray(domains.size)
                    arr[m] = if (maximize) -1L else 1L
                    objective = LinearObjective(intCoefficients = arr)
                }

                else -> throw UnsupportedXcsp3Exception("objective type '$type'")
            }
        }

        // --- arithmetic helpers ---

        private data class Lin(val coeffs: Map<Int, Int>, val constant: Int)

        private fun linear(e: FExpr): Lin = when (e) {
            is FExpr.Num -> Lin(emptyMap(), e.value)

            is FExpr.Ref -> Lin(mapOf(ref(e.name) to 1), 0)

            is FExpr.Call -> when (e.fn) {
                "add" -> e.args.map { linear(it) }.reduce(::addLin)

                "sub" -> e.args.drop(1).fold(linear(e.args[0])) { a, x -> addLin(a, scaleLin(linear(x), -1)) }

                "neg" -> scaleLin(linear(e.args[0]), -1)

                "mul" -> {
                    val parts = e.args.map { linear(it) }
                    val nonConst = parts.filter { it.coeffs.isNotEmpty() }
                    if (nonConst.size > 1) throw UnsupportedXcsp3Exception("nonlinear mul")
                    val k = parts.filter { it.coeffs.isEmpty() }.fold(1) { a, c -> a * c.constant }
                    if (nonConst.isEmpty()) Lin(emptyMap(), k) else scaleLin(nonConst[0], k)
                }

                else -> throw UnsupportedXcsp3Exception("arithmetic fn '${e.fn}'")
            }
        }

        private fun addLin(a: Lin, b: Lin): Lin {
            val m = HashMap(a.coeffs)
            for ((v, c) in b.coeffs) m[v] = (m[v] ?: 0) + c
            return Lin(m, a.constant + b.constant)
        }
        private fun scaleLin(a: Lin, k: Int) = Lin(a.coeffs.mapValues { it.value * k }, a.constant * k)

        /** A single-variable term (`x`) → its var id; rejects compound expressions. */
        private fun singleTermVar(text: String): Int {
            val t = text.trim()
            varIds[t]?.let { return it }
            return when (val node = FExpr.parse(t)) {
                is FExpr.Ref -> ref(node.name)
                is FExpr.Num -> newAuxVar(node.value, node.value)
                else -> throw UnsupportedXcsp3Exception("expected a single variable/constant, got '$text'")
            }
        }

        // --- ref / domain helpers ---

        private fun listText(e: XmlElement): String = e.child("list")?.textContent ?: e.textContent
        private fun parseInts(text: String?): IntArray? =
            text?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.map { it.toInt() }?.toIntArray()

        private fun refList(text: String): List<Int> =
            text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.flatMap { expandRef(it) }

        private fun expandRef(tok: String): List<Int> = expandNames(tok).map { ref(it) }

        /** Expand a list token to concrete variable names: `x[]` → all cells, `x[lo..hi]` →
         *  the index range, `x[i]` / `x` → itself. */
        private fun expandNames(tok: String): List<String> {
            if (tok.endsWith("[]")) {
                val base = tok.dropLast(2)
                return varIds.keys.filter { it.startsWith("$base[") }
            }
            RANGE_REF.find(tok)?.let { m ->
                val base = m.groupValues[1]
                val lo = m.groupValues[2].toInt()
                val hi = m.groupValues[3].toInt()
                return (lo..hi).map { "$base[$it]" }
            }
            return listOf(tok)
        }
        private fun ref(name: String): Int = varIds[name] ?: throw UnsupportedXcsp3Exception("unknown variable '$name'")

        /** A `<group>`: one template constraint instantiated once per `<args>` row, with the
         *  flattened argument tokens substituted into the template's `%i` / `%...` placeholders. */
        private fun group(e: XmlElement) {
            val template = e.children.firstOrNull { it.tag != "args" }
                ?: throw UnsupportedXcsp3Exception("group without a template constraint")
            for (args in e.children.filter { it.tag == "args" }) {
                val tokens = args.textContent.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                    .flatMap { expandNames(it) }
                constraint(template.substituteParams(tokens))
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
            return when (m.groupValues[1]) {
                "le" -> LinearOp.LE to k
                "ge" -> LinearOp.GE to k
                "eq" -> LinearOp.EQ to k
                "ne" -> LinearOp.NE to k
                "lt" -> LinearOp.LE to (k - 1)
                "gt" -> LinearOp.GE to (k + 1)
                else -> throw UnsupportedXcsp3Exception("condition op '${m.groupValues[1]}'")
            }
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
            private val RANGE_REF = Regex("""^(.+)\[(\d+)\.\.(\d+)\]$""")
        }
    }
}
