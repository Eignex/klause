package com.eignex.klause.formats.xcsp3

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.CnfLowering
import com.eignex.klause.formats.FormatException
import com.eignex.klause.formats.LinComb
import com.eignex.klause.formats.ObjectiveSense
import com.eignex.klause.formats.constRelationHolds
import com.eignex.klause.formats.linCombDiff
import com.eignex.klause.formats.reifyLinear
import com.eignex.klause.formats.trueLit
import com.eignex.klause.formats.tseitinAnd
import com.eignex.klause.formats.tseitinIff
import com.eignex.klause.formats.tseitinOr
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.MutableLongIntMap

/** Raised when an XCSP3 construct outside the supported subset is encountered. */
class UnsupportedXcsp3Exception(msg: String) : FormatException("XCSP3", msg)

/** A parsed XCSP3 instance lifted into klause's representation. */
data class Xcsp3Problem(
    /** Compiled solver problem. */
    val problem: Problem,
    /** Objective, or null for satisfaction instances. */
    val objective: LinearObjective?,
    /** Declared variable name to int var id. */
    val intVarNames: Map<String, Int> = emptyMap(),
    /** The objective's optimisation sense (minimise for satisfaction instances, which have none). */
    val sense: ObjectiveSense = ObjectiveSense.MINIMIZE,
    /** Int vars the front-end knows are functionally defined (sound local-search `defines_var` hints). */
    val definedVars: IntArray = IntArray(0),
)

/** Split on runs of whitespace into non-blank tokens. A manual scan replacing an inline
 *  `split(Regex("\\s+")).filter { it.isNotBlank() }`, whose per-call `Regex` compilation dominated
 *  group instantiation over hundreds of thousands of `<args>` rows. */
internal fun String.splitWs(): List<String> {
    val out = ArrayList<String>()
    val n = length
    var i = 0
    while (i < n) {
        while (i < n && this[i].isWhitespace()) i++
        if (i >= n) break
        val start = i
        while (i < n && !this[i].isWhitespace()) i++
        out.add(substring(start, i))
    }
    return out
}

/** Every `[integer]` group's value in [s], left to right — array `size` dimensions and cell indices.
 *  Hand-scanned in place of a `\[(\d+)]` findAll. */
internal fun bracketInts(s: String): IntArray {
    val out = IntArrayList()
    var i = 0
    val n = s.length
    while (i < n) {
        if (s[i] != '[') {
            i++
            continue
        }
        val start = i + 1
        var j = start
        while (j < n && s[j] != ']') j++
        out.add(s.substring(start, j).toInt())
        i = if (j < n) j + 1 else j
    }
    return out.toIntArray()
}

/** XCSP3 run-length shorthand `vxn` (value `v` repeated `n` times, `n ≥ 0`) as `(v, n)`, or null when
 *  [tok] is not exactly that shape. Replaces a `(-?\d+)x(\d+)` match. */
internal fun parseRle(tok: String): Pair<Int, Int>? {
    val xi = tok.indexOf('x')
    if (xi <= 0 || xi == tok.length - 1) return null
    val v = tok.substring(0, xi).toIntOrNull() ?: return null
    val n = tok.substring(xi + 1).toIntOrNull() ?: return null
    return if (n < 0) null else v to n
}

/** Split a `<condition>` `(op, operand)` into operator and operand, trimming whitespace. The first
 *  comma separates them; the operand may itself hold commas (an `(in, {set})`). */
internal fun splitCondition(text: String): Pair<String, String> {
    val t = text.trim()
    require(t.length >= 2 && t.first() == '(' && t.last() == ')') { "condition '$text'" }
    val inner = t.substring(1, t.length - 1)
    val comma = inner.indexOf(',')
    require(comma >= 0) { "condition '$text'" }
    return inner.substring(0, comma).trim() to inner.substring(comma + 1).trim()
}

/** Parser/compiler for the supported XCSP3 integer subset. */
object Xcsp3 {
    /** Parse XCSP3 [text] into an [Xcsp3Problem]. [bakeCancellation] bounds the construction-time root
     *  bake (the propagation fixpoint folded into the problem's domains) — a wall-clock ceiling that only
     *  clips instances whose root bake would otherwise run for seconds, leaving the residual propagation
     *  to the solver; a fast bake completes fully and is unaffected. */
    fun parse(
        text: String,
        negTableCap: Long = 1_000_000L,
        bakeCancellation: Cancellation = Cancellation.Never,
    ): Xcsp3Problem = Builder(negTableCap).run {
        val root = parseXml(text)
        root.child("variables")?.let { vs -> vs.children.forEach { declareVar(it) } }
        root.child("constraints")?.let { cs -> cs.children.forEach { constraint(it) } }
        root.child("objectives")?.let { objs -> objs.children.firstOrNull()?.let { objective(it) } }
        build(bakeCancellation)
    }

    internal class Builder(val negTableCap: Long) : CnfLowering {
        internal val varIds = LinkedHashMap<String, Int>() // resolved name (incl. array cells) -> int var id
        internal val arrayDims = HashMap<String, IntArray>() // array id -> declared dimension sizes
        internal val domains = ArrayList<IntDomain>()
        override val factors = ArrayList<Factor>()
        internal var nextBool = 0
        internal var objective: LinearObjective? = null
        internal var objectiveMaximize = false

        // A fixed var for an integer constant is shared across its occurrences. An Element/sum `<list>`
        // can repeat a constant tens of thousands of times (WordSquare), and a fresh `{c}` var each time
        // bloated the problem to millions of vars. A constant var is single-valued (never branched), so
        // sharing it is solve-neutral.
        // Vars the front-end knows are functionally defined by a constraint it emitted (e.g. a `(eq, v)`
        // sum makes v its value). These are the sound `defines_var` hints local search uses to derive
        // them instead of searching them; the factor IR alone cannot tell an equality's output apart.
        internal val definedVars = IntArrayList()

        private val constVars = MutableLongIntMap()
        internal fun constVar(value: Long): Int {
            val existing = constVars.getOrDefault(value, -1)
            if (existing != -1) return existing
            return newAuxVar(value, value).also { constVars.put(value, it) }
        }

        // A `<group>` instantiates its regular/mdd template once per `<args>` row over the same shared
        // `<transitions>` text object; the built automaton depends only on that text, so cache the last
        // one by reference identity and reuse it, rebuilding only each row's own sequence variables.
        internal var cachedAutomatonText: String? = null
        internal var cachedAutomaton: RegularAutomaton? = null

        // Same reuse for the layered-Mdd lowering of an <mdd> (result.layered == null means the diagram
        // was not cleanly layered and the Regular fallback — cached above — is used instead).
        internal var cachedMddText: String? = null
        internal var cachedMddResult: MddResult? = null

        // Same reuse for a `<group>`'s positive `<extension>` template: the parsed short-support tuple
        // arrays depend only on the shared `<supports>` text, so cache them by reference identity and
        // share them across every row's [Table] (which treats its tuples as read-only) instead of
        // re-parsing an identical high-arity table per row — that accumulation exhausted the heap.
        internal var cachedSupportsText: String? = null
        internal var cachedSupportTemplate: SupportTemplate? = null

        fun declareVar(e: XmlElement) {
            when (e.tag) {
                "var" -> addVar(e.attr("id"), domainFor(e))

                "array" -> {
                    val id = e.attr("id")
                    val dims = bracketInts(e.attr("size"))
                    if (dims.isEmpty()) throw UnsupportedXcsp3Exception("array size '${e.attr("size")}'")
                    arrayDims[id] = dims // retained so `<matrix>` refs reshape from the shape
                    val dom = domainFor(e)

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

        internal fun addVar(name: String, dom: IntDomain) {
            varIds[name] = domains.size
            domains.add(dom)
        }
        internal fun newAuxVar(lo: Long, hi: Long): Int {
            domains.add(IntDomain(lo, hi))
            return domains.size - 1
        }
        override fun newBool(): Int = nextBool++

        /** The domain of a `<var>`/`<array>`: its inline domain text, or — when that is empty and an
         *  `as="ref"` attribute is present — the domain reused from a previously declared variable or
         *  array (XCSP3 domain aliasing). */
        internal fun domainFor(e: XmlElement): IntDomain {
            val text = e.textContent.trim()
            if (text.isNotEmpty()) return parseDomain(text)
            val alias = e.attr("as")
            if (alias.isBlank()) throw UnsupportedXcsp3Exception("empty domain")
            varIds[alias]?.let { return domains[it] }
            // An array alias reuses the referenced array's (uniform) cell domain.
            val cell = varIds.keys.firstOrNull { it.startsWith("$alias[") }
                ?: throw UnsupportedXcsp3Exception("unknown domain alias '$alias'")
            return domains[varIds.getValue(cell)]
        }

        internal fun parseDomain(text: String): IntDomain {
            // Parse the range/singleton tokens into intervals, then build `[lo,hi]` minus only the interior
            // holes (the gaps between consecutive intervals). Enumerating a range's interior would be
            // O(span) — a wide domain like `0..1000000000` would exhaust the heap — while a compact
            // [IntDomain] needs just the holes, which a contiguous range has none of.
            val intervals = ArrayList<LongArray>()
            for (tok in text.splitWs()) {
                val r = tok.split("..")
                val a = if (r.size == 2) r[0].toLong() else tok.toLong()
                val b = if (r.size == 2) r[1].toLong() else a
                require(a <= b) { "invalid domain range '$tok'" }
                intervals.add(longArrayOf(a, b))
            }
            if (intervals.isEmpty()) throw UnsupportedXcsp3Exception("empty domain")
            // XCSP3 lists domain tokens ascending, but sort defensively so the gap walk is correct.
            intervals.sortBy { it[0] }
            val lo = intervals.first()[0]
            var hi = intervals.first()[1]
            var prevEnd = hi
            val holes = ArrayList<Long>()
            for (k in 1 until intervals.size) {
                val a = intervals[k][0]
                val b = intervals[k][1]
                if (a > prevEnd + 1) for (v in prevEnd + 1 until a) holes.add(v)
                if (b > prevEnd) prevEnd = b
                if (b > hi) hi = b
            }
            val dom = IntDomain(lo, hi)
            return if (holes.isEmpty()) {
                dom
            } else {
                dom.excludeValues(
                    holes.toLongArray(),
                ) ?: throw UnsupportedXcsp3Exception("empty domain")
            }
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

        internal fun sum(e: XmlElement) {
            // Terms may be plain variables or expressions (e.g. `ne(x,0)` counting occurrences);
            // each expression is reified/linearized into an int var carrying its value.
            val tokens = requireNotNull(e.child("list")).textContent.splitWs()
            val termVars = tokens.flatMap { tok -> expandNames(tok).map { termVar(it) } }.toIntArray()
            if (termVars.isEmpty()) throw UnsupportedXcsp3Exception("sum: empty <list>")
            val condText = requireNotNull(e.child("condition")).textContent.trim()
            val coeffsText = e.child("coeffs")?.textContent
            val constCoeffs = if (coeffsText == null) IntArray(termVars.size) { 1 } else parseInts(coeffsText)
            if (constCoeffs != null) {
                require(constCoeffs.size == termVars.size) { "sum: <coeffs> length != term count" }
                postCondition(constCoeffs, termVars, condText)
                return
            }
            // Variable coefficients (constCoeffs is null only when <coeffs> is present but not constant):
            // Σ coeff_i·term_i where each product is materialized via [Product].
            val coeffVars = requireNotNull(coeffsText).splitWs()
                .flatMap { tok -> expandNames(tok).map { termVar(it) } }.toIntArray()
            require(coeffVars.size == termVars.size) { "sum: <coeffs> length != term count" }
            val products = IntArray(termVars.size) { i ->
                val (lo, hi) = productBounds(coeffVars[i], termVars[i])
                val p = newAuxVar(lo, hi)
                factors.add(Product(coeffVars[i], termVars[i], p))
                p
            }
            postCondition(IntArray(products.size) { 1 }, products, condText)
        }

        /** Resolve a `<sum>` term to an int var: a declared variable, a constant, a reified
         *  relation (0/1), or an arithmetic expression bound to a fresh auxiliary. */
        internal fun termVar(tok: String): Int {
            varIds[tok]?.let { return it }
            val node = FExpr.parse(tok)
            return when {
                node is FExpr.Ref -> ref(node.name)

                node is FExpr.Num -> constVar(node.value.toLong())

                node is FExpr.Call && node.fn == "eq" && node.args.size > 2 ->
                    // n-ary eq as a sum term: reify the all-equal conjunction to a 0/1 int.
                    litTo01(reifyRel(node))

                node is FExpr.Call && node.fn in REL -> {
                    val r = relationParts(node)
                    if (r.vars.isEmpty()) {
                        // Constant relation: a fixed 0/1 term.
                        val v = if (constRelationHolds(r.op, r.bound)) 1L else 0L
                        newAuxVar(v, v)
                    } else {
                        // Reify the relation onto a fresh bool, then channel it to a 0/1 int var.
                        val aux = newBool()
                        factors.add(ReifiedLinear(aux, r.coeffs, r.vars, r.op, r.bound))
                        val ch = newAuxVar(0L, 1L)
                        factors.add(ReifiedLinear(aux, intArrayOf(1), intArrayOf(ch), LinearOp.EQ, 1))
                        ch
                    }
                }

                else -> {
                    val lin = linear(node)
                    if (lin.coeffs.isEmpty()) {
                        newAuxVar(lin.constant, lin.constant)
                    } else {
                        materializeVar(lin)
                    }
                }
            }
        }

        /** Min/max of a linear expression over its variables' domains. */
        internal fun linBounds(lin: LinComb): Pair<Long, Long> {
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
        internal fun sumCondition(text: String): Triple<LinearOp, Int, Int?> {
            val (opTok, rhs) = splitCondition(text)
            val (op, delta) = relOp(opTok) ?: throw UnsupportedXcsp3Exception("condition op '$opTok'")
            val k = rhs.toIntOrNull()
            return if (k != null) Triple(op, k + delta, null) else Triple(op, delta, ref(rhs))
        }

        /** Constrain the linear expression `Σ coeffs·vars` by a `<condition>`: a simple `(op, k|var)`
         *  relation, an `(in, lo..hi)` interval (two bounds), or an `(in, {set})` membership. */
        internal fun postCondition(coeffs: IntArray, vars: IntArray, condText: String) {
            val (opTok, operand) = splitCondition(condText)
            if (opTok == "in") {
                if (operand.startsWith("{")) {
                    val m = if (coeffs.size == 1 && coeffs[0] == 1) {
                        vars[0]
                    } else {
                        materializeVar(LinComb(linMap(coeffs, vars), 0L))
                    }
                    val members = parseSetMembers(operand.removeSurrounding("{", "}"))
                    factors.add(
                        Clause(members.map { reifyLinear(intArrayOf(1), intArrayOf(m), LinearOp.EQ, it) }.toIntArray()),
                    )
                    return
                }
                val dd = operand.indexOf("..")
                if (dd >= 0) {
                    factors.add(Linear(coeffs, vars, LinearOp.GE, operand.substring(0, dd).trim().toInt()))
                    factors.add(Linear(coeffs, vars, LinearOp.LE, operand.substring(dd + 2).trim().toInt()))
                    return
                }
                throw UnsupportedXcsp3Exception("condition '$condText'")
            }
            val (op, k, rhsVar) = sumCondition(condText)
            if (rhsVar == null) {
                factors.add(Linear(coeffs, vars, op, k))
            } else {
                factors.add(Linear(coeffs + -1, vars + rhsVar, op, k))
                // `(eq, v)` makes v the sum's value: v = Σ coeffs·vars. Record it as a functional
                // definition so local search derives v from the sum rather than searching it (the sound
                // `defines_var` hint the factor IR alone can't recover — orienting a bare equality could
                // pick a decision var as the output).
                if (op == LinearOp.EQ) definedVars.add(rhsVar)
            }
        }

        /** Parse a `{...}` set body into its members, expanding `lo..hi` ranges. */
        internal fun parseSetMembers(body: String): List<Int> =
            body.split(",").map { it.trim() }.filter { it.isNotEmpty() }.flatMap { tok ->
                tok.split(
                    "..",
                ).let { if (it.size == 2) (it[0].toInt()..it[1].toInt()).toList() else listOf(tok.toInt()) }
            }

        /** Coalesce parallel `coeffs`/`vars` arrays into a single variable-keyed linear map. */
        internal fun linMap(coeffs: IntArray, vars: IntArray): Map<Int, Long> {
            val m = HashMap<Int, Long>()
            for (i in vars.indices) m[vars[i]] = (m[vars[i]] ?: 0L) + coeffs[i]
            return m
        }

        internal fun intension(expr: String) {
            val node = FExpr.parse(expr)
            if (node is FExpr.Call && node.fn == "eq" && node.args.size == 2) {
                // `eq(v, mul(a,b))` with v, a, b plain variables is one Product (v = a·b), skipping the aux
                // var and equality the generic term path emits — the bulk of an O(n²) product model.
                (directProduct(node.args[0], node.args[1]) ?: directProduct(node.args[1], node.args[0]))
                    ?.let {
                        factors.add(it)
                        return
                    }
            }
            // n-ary eq (x = y = z = …) is the conjunction of its consecutive pairwise equalities, posted
            // directly as top-level factors (cf. allEqual) rather than reified onto an aux literal.
            if (node is FExpr.Call && node.fn == "eq" && node.args.size > 2) {
                for (i in 0 until node.args.size - 1) {
                    postRel(relationParts(node.args[i], node.args[i + 1], LinearOp.EQ, 0))
                }
                return
            }
            if (node is FExpr.Call && node.fn in REL && node.args.size == 2) {
                postRel(relationParts(node))
            } else {
                // A Boolean combination that is a plain disjunction/implication of single-variable
                // comparisons is the constraint itself (a [ComparisonClause]) — lowering it directly
                // avoids a reifying indicator + [ReifiedLinear] per comparison. Anything else keeps the
                // general Tseitin path.
                val cc = tryComparisonClause(node)
                if (cc != null) factors.add(cc) else factors.add(Clause(intArrayOf(compileBool(node))))
            }
        }

        /** Post a lowered relation as a top-level factor: a [Linear] when it carries variable terms, else a
         *  contradiction clause when the constant relation `0 op bound` is false (a true one is dropped). */
        private fun postRel(r: RelParts) {
            when {
                r.vars.isNotEmpty() -> factors.add(Linear(r.coeffs, r.vars, r.op, r.bound))
                !constRelationHolds(r.op, r.bound) -> factors.add(Clause(intArrayOf(Lit.negate(trueLit()))))
            }
        }

        /**
         * [node] as a [ComparisonClause] when it is a disjunction (directly, or via `imp`/`not`) of at
         * least two single-variable comparisons against constants; `null` otherwise (the caller keeps the
         * Tseitin path). A single comparison stays on the cheaper [Linear] path, and a negated disjunction
         * (`¬(a ∨ b)` is a conjunction) is declined since it is not a clause.
         */
        private fun tryComparisonClause(node: FExpr): ComparisonClause? {
            val vars = IntArrayList()
            val ops = ArrayList<LinearOp>()
            val consts = ArrayList<Long>()
            if (!collectClauseLiterals(node, negated = false, vars, ops, consts)) return null
            if (vars.size < 2) return null
            return ComparisonClause(vars.toIntArray(), ops.toTypedArray(), consts.toLongArray())
        }

        private fun collectClauseLiterals(
            node: FExpr,
            negated: Boolean,
            vars: IntArrayList,
            ops: MutableList<LinearOp>,
            consts: MutableList<Long>,
        ): Boolean {
            if (node !is FExpr.Call) return false
            when (node.fn) {
                "not" -> return node.args.size == 1 && collectClauseLiterals(node.args[0], !negated, vars, ops, consts)

                // ¬(a ∨ b) is a conjunction, not a clause.
                "or" -> return !negated && node.args.all { collectClauseLiterals(it, false, vars, ops, consts) }

                // a ⇒ b ≡ ¬a ∨ b; ¬(a ⇒ b) is a conjunction.
                "imp" ->
                    return !negated && node.args.size == 2 &&
                        collectClauseLiterals(node.args[0], true, vars, ops, consts) &&
                        collectClauseLiterals(node.args[1], false, vars, ops, consts)

                in REL -> {
                    if (node.args.size != 2) return false
                    val lit = singleVarComparison(node) ?: return false
                    val (v, op, c) = if (negated) negateComparison(lit) else lit
                    vars.add(v)
                    ops.add(op)
                    consts.add(c)
                    return true
                }

                else -> return false
            }
        }

        /** `node` as a single-variable comparison `(var, op, const)` with unit coefficient (a `-1`
         *  coefficient is folded by flipping the operator and negating the bound), or `null`. */
        private fun singleVarComparison(node: FExpr.Call): Triple<Int, LinearOp, Long>? {
            val r = relationParts(node)
            if (r.vars.size != 1) return null
            val bound = r.bound.toLong()
            return when (r.coeffs[0]) {
                1 -> Triple(r.vars[0], r.op, bound)
                -1 -> Triple(r.vars[0], r.op.flipSign(), -bound)
                else -> null
            }
        }

        private fun LinearOp.flipSign(): LinearOp = when (this) {
            LinearOp.LE -> LinearOp.GE
            LinearOp.GE -> LinearOp.LE
            LinearOp.EQ -> LinearOp.EQ
            LinearOp.NE -> LinearOp.NE
        }

        /** The complement of a single-variable comparison: `¬(x ≤ c) = x ≥ c+1`, `¬(x ≥ c) = x ≤ c−1`,
         *  `¬(x = c) = x ≠ c`, `¬(x ≠ c) = x = c`. */
        private fun negateComparison(lit: Triple<Int, LinearOp, Long>): Triple<Int, LinearOp, Long> {
            val (v, op, c) = lit
            return when (op) {
                LinearOp.LE -> Triple(v, LinearOp.GE, c + 1)
                LinearOp.GE -> Triple(v, LinearOp.LE, c - 1)
                LinearOp.EQ -> Triple(v, LinearOp.NE, c)
                LinearOp.NE -> Triple(v, LinearOp.EQ, c)
            }
        }

        /** The var id when [e] is a plain variable reference, else null. Emits no factors either way. */
        private fun plainVar(e: FExpr): Int? = if (e is FExpr.Ref) ref(e.name) else null

        /** `Product(a, b, v)` (`v = a·b`) when [vSide] is a plain var and [mulSide] is `mul` of two plain
         *  vars; null otherwise (the caller then takes the generic aux-var + equality path). */
        private fun directProduct(vSide: FExpr, mulSide: FExpr): Product? {
            if (mulSide !is FExpr.Call || mulSide.fn != "mul" || mulSide.args.size != 2) return null
            val v = plainVar(vSide) ?: return null
            val a = plainVar(mulSide.args[0]) ?: return null
            val b = plainVar(mulSide.args[1]) ?: return null
            return Product(a, b, v)
        }

        internal fun compileBool(e: FExpr): Int = when (e) {
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
                "notin" -> Lit.negate(memberLit(e))
                in REL -> reifyRel(e)
                else -> throw UnsupportedXcsp3Exception("non-boolean intension op '${e.fn}'")
            }
        }

        /** `in(expr, {set})`: a literal true iff the expression takes one of the set's values. */
        internal fun memberLit(e: FExpr.Call): Int {
            val values = setValues(e.args[1]) ?: throw UnsupportedXcsp3Exception("in: right side is not a set")
            val m = materializeVar(linear(e.args[0]))
            return tseitinOr(values.map { reifyLinear(intArrayOf(1), intArrayOf(m), LinearOp.EQ, it) })
        }

        /** The integer members of a set operand: a `{v, …}` literal or a `set(v, …)` call. Null when
         *  the operand is not a constant set (e.g. a set variable), which the caller rejects. */
        internal fun setValues(node: FExpr): List<Int>? = when {
            node is FExpr.SetLit -> node.values
            node is FExpr.Call && node.fn == "set" -> node.args.map { (it as? FExpr.Num)?.value ?: return null }
            else -> null
        }

        override var trueLitCache = -1

        internal fun reifyRel(node: FExpr.Call): Int {
            // n-ary eq reifies to the conjunction of its consecutive pairwise equalities.
            if (node.fn == "eq" && node.args.size > 2) {
                return tseitinAnd(
                    (0 until node.args.size - 1).map {
                        val r = relationParts(node.args[it], node.args[it + 1], LinearOp.EQ, 0)
                        reifyLinear(r.coeffs, r.vars, r.op, r.bound)
                    },
                )
            }
            val r = relationParts(node)
            return reifyLinear(r.coeffs, r.vars, r.op, r.bound)
        }

        /** Map XCSP3 relation names to linear operators and strictness deltas. */
        internal fun relOp(fn: String): Pair<LinearOp, Int>? = when (fn) {
            "le" -> LinearOp.LE to 0
            "lt" -> LinearOp.LE to -1
            "ge" -> LinearOp.GE to 0
            "gt" -> LinearOp.GE to 1
            "eq" -> LinearOp.EQ to 0
            "ne" -> LinearOp.NE to 0
            else -> null
        }

        internal class RelParts(val coeffs: IntArray, val vars: IntArray, val op: LinearOp, val bound: Int)

        /** Lower `rel(lhs, rhs)` to the coalesced linear components. When both sides share the same
         *  variable terms they cancel to an empty var list, leaving the constant relation `0 op bound`. */
        internal fun relationParts(node: FExpr.Call): RelParts {
            val (op, delta) = relOp(node.fn) ?: throw UnsupportedXcsp3Exception("relation '${node.fn}'")
            // Only eq is n-ary in XCSP3, and its callers fold it pairwise before reaching here.
            require(node.args.size == 2) { "relation '${node.fn}' expects 2 operands, got ${node.args.size}" }
            return relationParts(node.args[0], node.args[1], op, delta)
        }

        /** Lower `lhs op rhs` (with strictness [delta]) to coalesced linear components. When both sides
         *  share the same variable terms they cancel to an empty var list, leaving the constant `0 op bound`. */
        internal fun relationParts(lhs: FExpr, rhs: FExpr, op: LinearOp, delta: Int): RelParts {
            val (vars, coeffs, bound) = linCombDiff(linear(lhs), linear(rhs), delta.toLong())
            // XCSP3 coefficients/bounds originate from Int-valued FExpr numerals, so they fit Int.
            return RelParts(IntArray(coeffs.size) { coeffs[it].toInt() }, vars, op, bound.toInt())
        }

        fun objective(e: XmlElement) {
            val maximize = e.tag == "maximize"
            objectiveMaximize = maximize
            val type = e.attr("type").ifBlank { "sum" }
            // Terms may be plain variables or expressions (e.g. `gt(x,0)`), each resolved to an int var.
            val listText = e.child("list")?.textContent ?: e.textContent
            val termVars = listText.splitWs()
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
        internal fun singleVarObjective(v: Int, maximize: Boolean): LinearObjective {
            val arr = LongArray(domains.size)
            arr[v] = if (maximize) -1L else 1L
            return LinearObjective(intCoefficients = arr)
        }

        internal fun linear(e: FExpr): LinComb = when (e) {
            is FExpr.Num -> LinComb(emptyMap(), e.value.toLong())

            is FExpr.Ref -> LinComb(mapOf(ref(e.name) to 1L), 0L)

            is FExpr.SetLit -> throw UnsupportedXcsp3Exception("set literal used arithmetically")

            is FExpr.Call -> when (e.fn) {
                "add" -> e.args.map { linear(it) }.reduce { a, b -> a.plus(b) }

                "sub" -> e.args.drop(1).fold(linear(e.args[0])) { a, x -> a.plus(linear(x).scaled(-1)) }

                "neg" -> linear(e.args[0]).scaled(-1)

                "abs" -> absOf(linear(e.args[0]))

                "dist" -> absOf(linear(e.args[0]).plus(linear(e.args[1]).scaled(-1)))

                "min" -> minMaxTerm(e.args, max = false)

                "max" -> minMaxTerm(e.args, max = true)

                "if" -> ifTerm(e.args)

                "div" -> divModTerm(e.args, mod = false)

                "mod" -> divModTerm(e.args, mod = true)

                "mul" -> {
                    val parts = e.args.map { linear(it) }
                    val nonConst = parts.filter { it.coeffs.isNotEmpty() }
                    val k = parts.filter { it.coeffs.isEmpty() }.fold(1L) { a, c -> a * c.constant }
                    when {
                        k == 0L -> LinComb(emptyMap(), 0L)

                        nonConst.isEmpty() -> LinComb(emptyMap(), k)

                        nonConst.size == 1 -> nonConst[0].scaled(k)

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
                            LinComb(mapOf(acc to 1L), 0L).scaled(k)
                        }
                    }
                }

                // A boolean-valued subexpression used arithmetically is its 0/1 truth value.
                "in", "notin", in REL, in BOOL_FNS -> LinComb(mapOf(litTo01(compileBool(e)) to 1L), 0L)

                else -> throw UnsupportedXcsp3Exception("arithmetic fn '${e.fn}'")
            }
        }

        /** Channel a literal to a fresh 0/1 int var equal to its truth value. */
        internal fun litTo01(lit: Int): Int {
            val ch = newAuxVar(0L, 1L)
            val b = reifyLinear(intArrayOf(1), intArrayOf(ch), LinearOp.GE, 1) // b ⟺ ch = 1
            factors.add(Clause(intArrayOf(Lit.negate(b), lit))) // b → lit
            factors.add(Clause(intArrayOf(b, Lit.negate(lit)))) // lit → b
            return ch
        }

        /** `min`/`max` of expressions as a linear term, via [ArrayMinMax] over the materialised args. */
        internal fun minMaxTerm(args: List<FExpr>, max: Boolean): LinComb {
            val vs = args.map { materializeVar(linear(it)) }.toIntArray()
            val m = newAuxVar(vs.minOf { domains[it].min }, vs.maxOf { domains[it].max })
            factors.add(ArrayMinMax(result = m, xs = vs, max = max))
            return LinComb(mapOf(m to 1L), 0L)
        }

        /** `if(cond, a, b)` as a linear term: a fresh int pinned to `a` or `b` by the condition. */
        internal fun ifTerm(args: List<FExpr>): LinComb {
            val cond = compileBool(args[0])
            val a = materializeVar(linear(args[1]))
            val b = materializeVar(linear(args[2]))
            val v = newAuxVar(minOf(domains[a].min, domains[b].min), maxOf(domains[a].max, domains[b].max))
            val ea = reifyLinear(intArrayOf(1, -1), intArrayOf(v, a), LinearOp.EQ, 0)
            val eb = reifyLinear(intArrayOf(1, -1), intArrayOf(v, b), LinearOp.EQ, 0)
            factors.add(Clause(intArrayOf(Lit.negate(cond), ea))) // cond ⇒ v = a
            factors.add(Clause(intArrayOf(cond, eb))) // ¬cond ⇒ v = b
            return LinComb(mapOf(v to 1L), 0L)
        }

        /** Integer `div`/`mod` by a nonzero constant, matching XCSP3's truncated-toward-zero semantics:
         *  the reference evaluator computes `a / k` and `a % k` with Java's operators, so the quotient
         *  truncates toward zero and the remainder takes the dividend's sign. Encoded as `a = k·q + r`
         *  with `|r| < |k|` and `r` sharing `a`'s sign. A variable or zero divisor stays unsupported —
         *  a nonlinear/division-by-zero shape we cannot soundly linearize. */
        internal fun divModTerm(args: List<FExpr>, mod: Boolean): LinComb {
            val a = materializeVar(linear(args[0]))
            val bLin = linear(args[1])
            if (bLin.coeffs.isNotEmpty()) return divModVar(a, materializeVar(bLin), mod)
            val k = bLin.constant
            if (k == 0L) throw UnsupportedXcsp3Exception("div/mod by zero divisor")
            val da = domains[a]
            val absK = if (k < 0) -k else k
            // trunc(a / k) is monotonic in `a` for a fixed-sign `k`, so the domain endpoints bound `q`
            // (Kotlin's Long `/` already truncates toward zero, matching the required convention).
            val qa = da.min / k
            val qb = da.max / k
            val q = newAuxVar(minOf(qa, qb), maxOf(qa, qb))
            val r = newAuxVar(-(absK - 1), (absK - 1))
            factors.add(Linear(longArrayOf(1, -k, -1), intArrayOf(a, q, r), LinearOp.EQ, 0L)) // a = k·q + r
            // Truncation ⇒ r shares the dividend's sign (or is 0). Pin it: trivially when `a` is
            // single-signed, else gate on the reified sign of `a`.
            when {
                da.min >= 0L -> factors.add(Linear(intArrayOf(1), intArrayOf(r), LinearOp.GE, 0))

                da.max <= 0L -> factors.add(Linear(intArrayOf(1), intArrayOf(r), LinearOp.LE, 0))

                else -> {
                    val aNonNeg = reifyLinear(intArrayOf(1), intArrayOf(a), LinearOp.GE, 0)
                    val rNonNeg = reifyLinear(intArrayOf(1), intArrayOf(r), LinearOp.GE, 0)
                    val rNonPos = reifyLinear(intArrayOf(1), intArrayOf(r), LinearOp.LE, 0)
                    factors.add(Clause(intArrayOf(Lit.negate(aNonNeg), rNonNeg))) // a ≥ 0 ⟹ r ≥ 0
                    factors.add(Clause(intArrayOf(aNonNeg, rNonPos))) // a < 0 ⟹ r ≤ 0
                }
            }
            return LinComb(mapOf((if (mod) r else q) to 1L), 0L)
        }

        /** Integer `div`/`mod` by a *variable* divisor, supported only when the divisor is provably
         *  positive (`b ≥ 1`, so no division by zero) and the dividend provably non-negative
         *  (`a ≥ 0`) — the range where truncated and floored division coincide: `a = b·q + r`
         *  with `0 ≤ r < b`. Other shapes stay unsupported (sign-dependent truncation / zero divisor). */
        internal fun divModVar(a: Int, b: Int, mod: Boolean): LinComb {
            val da = domains[a]
            val db = domains[b]
            if (da.min < 0L || db.min < 1L) {
                throw UnsupportedXcsp3Exception("div/mod by variable divisor requires b >= 1 and a >= 0")
            }
            val q = newAuxVar(0L, da.max / db.min)
            val r = newAuxVar(0L, db.max - 1)
            val (plo, phi) = productBounds(b, q)
            val p = newAuxVar(plo, phi)
            factors.add(Product(b, q, p)) // p = b·q
            factors.add(Linear(intArrayOf(1, -1, -1), intArrayOf(a, p, r), LinearOp.EQ, 0)) // a = b·q + r
            factors.add(Linear(intArrayOf(1, -1), intArrayOf(r, b), LinearOp.LE, -1)) // r < b
            return LinComb(mapOf((if (mod) r else q) to 1L), 0L)
        }

        /** `|expr|` as a linear term: `|v| = max(v, -v)` via [ArrayMinMax] over `v` and its negation. */
        internal fun absOf(lin: LinComb): LinComb {
            val v = materializeVar(lin)
            val d = domains[v]
            val neg = newAuxVar(-d.max, -d.min)
            factors.add(Linear(intArrayOf(1, 1), intArrayOf(v, neg), LinearOp.EQ, 0)) // neg = −v
            val hi = maxOf(-d.min, d.max)
            val lo = when {
                d.min <= 0L && d.max >= 0L -> 0L
                d.min > 0L -> d.min
                else -> -d.max
            }
            val a = newAuxVar(lo, hi)
            factors.add(ArrayMinMax(result = a, xs = intArrayOf(v, neg), max = true))
            return LinComb(mapOf(a to 1L), 0L)
        }

        /** Materialise a linear expression as a single int var (returning it directly when it already
         *  is one), posting `v = expr` otherwise. */
        internal fun materializeVar(lin: LinComb): Int {
            if (lin.constant == 0L && lin.coeffs.size == 1 && lin.coeffs.values.first() == 1L) {
                return lin.coeffs.keys.first()
            }
            val (lo, hi) = linBounds(lin)
            val v = newAuxVar(lo, hi)
            val vars = lin.coeffs.keys.toList()
            val cs = LongArray(vars.size + 1) { if (it < vars.size) -lin.coeffs.getValue(vars[it]) else 1L }
            val ids = IntArray(vars.size + 1) { if (it < vars.size) vars[it] else v }
            factors.add(Linear(cs, ids, LinearOp.EQ, lin.constant)) // v − expr = 0
            return v
        }

        /** Integer bounds of the product `a * b` over the two variables' domains. */
        internal fun productBounds(a: Int, b: Int): Pair<Long, Long> {
            val da = domains[a]
            val db = domains[b]
            val corners = listOf(
                da.min * db.min,
                da.min * db.max,
                da.max * db.min,
                da.max * db.max,
            )
            return corners.min() to corners.max()
        }

        /** Resolve one variable/constant term to a var id. */
        internal fun singleTermVar(text: String): Int {
            val t = text.trim()
            varIds[t]?.let { return it }
            return when (val node = FExpr.parse(t)) {
                is FExpr.Ref -> ref(node.name)
                is FExpr.Num -> constVar(node.value.toLong())
                else -> throw UnsupportedXcsp3Exception("expected a single variable/constant, got '$text'")
            }
        }

        internal fun listText(e: XmlElement): String = e.child("list")?.textContent ?: e.textContent

        /** Resolve vars from a constraint `<list>` child. */
        internal fun listVars(e: XmlElement): IntArray = refList(requireNotNull(e.child("list")).textContent)

        internal fun IntArray.widenToLong(): LongArray = LongArray(size) { this[it].toLong() }

        /** Parse whitespace-separated integer constants, or null if [text] is null or any token
         *  is not an integer (e.g. a variable reference) — callers treat null as "not constant".
         *  Supports XCSP3 run-length shorthand `vxn` (value `v` repeated `n` times). */
        internal fun parseInts(text: String?): IntArray? {
            val toks = text?.splitWs() ?: return null
            val out = ArrayList<Int>(toks.size)
            for (tok in toks) {
                val rle = parseRle(tok)
                if (rle != null) {
                    repeat(rle.second) { out.add(rle.first) }
                } else {
                    out.add(tok.toIntOrNull() ?: return null)
                }
            }
            return out.toIntArray()
        }

        /** Coefficients for a `<sum>`/objective: unit weights when absent, the parsed constants
         *  when present, else unsupported (a present-but-non-constant `<coeffs>` must not default). */
        internal fun coeffsOrUnit(text: String?, n: Int): IntArray = when {
            text == null -> IntArray(n) { 1 }
            else -> parseInts(text) ?: throw UnsupportedXcsp3Exception("non-constant <coeffs>")
        }

        // A `<list>` entry may be a declared variable (possibly a wildcard/range over cells), a constant,
        // a reified relation, or an arithmetic expression; [termVar] resolves each to an int var
        // (fast-pathing declared variables). Builds an [IntArray] directly — a large `<list>` (WordSquare's
        // thousands-entry element lists) would otherwise box every resolved id into a `List<Int>`.
        internal fun refList(text: String): IntArray {
            val out = IntArrayList()
            for (tok in text.splitWs()) for (name in expandNames(tok)) out.add(termVar(name))
            return out.toIntArray()
        }

        /** Expand an array reference token into the declared cell names it denotes. Each `[...]`
         *  group is a fixed index `[i]`, a range `[lo..hi]`, or a wildcard `[]` (any index), in
         *  any position and dimensionality — e.g. `x[]`, `x[][]`, `x[2][]`, `x[0..1][0]`. A plain
         *  reference with no wildcard or range is returned as-is (resolved later by [ref]). */
        // Declared cell names grouped by array base (the prefix before the first `[`), built lazily on
        // the first wildcard/range reference — by which point every variable is declared. A wildcard
        // pattern `^base\[…]$` can only match cells of that base, so expansion filters this bucket
        // instead of every declared variable, turning an O(references × variables) scan (pathological
        // with a large array) into O(cells of the base).
        // Each cell name paired with its bracket indices, parsed once at index-build time so reference
        // expansion never re-parses a cell's `[i][j]` per token — the array-heavy hot path (BusScheduling).
        private class BaseCells {
            val names = ArrayList<String>()
            val indices = ArrayList<IntArray>()
        }

        private val cellsByBase: HashMap<String, BaseCells> by lazy(LazyThreadSafetyMode.NONE) {
            val index = HashMap<String, BaseCells>()
            for (name in varIds.keys) {
                val br = name.indexOf('[')
                if (br <= 0) continue
                val bucket = index.getOrPut(name.substring(0, br)) { BaseCells() }
                bucket.names.add(name)
                bucket.indices.add(bracketInts(name))
            }
            index
        }

        internal fun expandNames(tok: String): List<String> {
            if ("[]" !in tok && ".." !in tok) return listOf(tok)
            val br = tok.indexOf('[')
            if (br <= 0) return listOf(tok)
            val base = tok.substring(0, br)
            val specs = parseBracketSpecs(tok, br) ?: return listOf(tok)
            // When the base is a declared array of matching rank, generate exactly the referenced cell
            // names from the per-dimension index ranges (a wildcard spanning `0 until size`) — O(selected)
            // — instead of scanning every declared cell of the array. Row-major generation matches the
            // declaration order [declareCells] used, so positional list semantics are preserved.
            val dims = arrayDims[base]
            if (dims != null && dims.size == specs.size) {
                val out = ArrayList<String>()
                generateCells(base, specs, dims, 0, StringBuilder(base), out)
                return out
            }
            // Fallback (non-array base, or a rank the array's shape doesn't match): scan the base's cells.
            val cells = cellsByBase[base] ?: return emptyList()
            val out = ArrayList<String>()
            for (i in cells.names.indices) if (cellMatches(cells.indices[i], specs)) out.add(cells.names[i])
            return out
        }

        /** Emit `base[i0][i1]…` for every index tuple in the Cartesian product of the per-dimension ranges
         *  [specs] gives (a wildcard `[Int.MIN, Int.MAX]` covering `0 until dims(d)`), keeping only names
         *  that were actually declared. Row-major to match [declareVar]'s cell order. */
        private fun generateCells(
            base: String,
            specs: List<IntArray>,
            dims: IntArray,
            dim: Int,
            sb: StringBuilder,
            out: ArrayList<String>,
        ) {
            if (dim == specs.size) {
                val name = sb.toString()
                if (varIds.containsKey(name)) out.add(name)
                return
            }
            val spec = specs[dim]
            val lo = if (spec[0] == Int.MIN_VALUE) 0 else spec[0]
            val hi = if (spec[1] == Int.MAX_VALUE) dims[dim] - 1 else spec[1]
            val mark = sb.length
            var i = lo
            while (i <= hi) {
                sb.append('[').append(i).append(']')
                generateCells(base, specs, dims, dim + 1, sb, out)
                sb.setLength(mark)
                i++
            }
        }

        /** The bracket groups of a reference token from [from] as per-dimension `[lo, hi]` bounds: a fixed
         *  `[i]` → `[i, i]`, a range `[lo..hi]` → `[lo, hi]`, a wildcard `[]` → the full integer range. Null
         *  when the tail is not a clean run of `[...]` groups (the caller then keeps the token verbatim). */
        private fun parseBracketSpecs(tok: String, from: Int): List<IntArray>? {
            val specs = ArrayList<IntArray>()
            var i = from
            val n = tok.length
            while (i < n) {
                if (tok[i] != '[') return null
                val close = tok.indexOf(']', i + 1)
                if (close < 0) return null
                val inner = tok.substring(i + 1, close)
                val dd = inner.indexOf("..")
                specs.add(
                    when {
                        inner.isEmpty() -> intArrayOf(Int.MIN_VALUE, Int.MAX_VALUE)

                        dd >= 0 -> intArrayOf(
                            inner.substring(0, dd).toIntOrNull() ?: return null,
                            inner.substring(dd + 2).toIntOrNull() ?: return null,
                        )

                        else -> (inner.toIntOrNull() ?: return null).let { intArrayOf(it, it) }
                    },
                )
                i = close + 1
            }
            return specs.ifEmpty { null }
        }

        /** Whether a cell's pre-parsed bracket indices [idx] (same array base as the reference, so only
         *  the indices matter) satisfy every per-dimension bound in [specs]. */
        private fun cellMatches(idx: IntArray, specs: List<IntArray>): Boolean {
            if (idx.size != specs.size) return false
            for (k in specs.indices) if (idx[k] < specs[k][0] || idx[k] > specs[k][1]) return false
            return true
        }
        internal fun ref(name: String): Int = varIds[name] ?: throw UnsupportedXcsp3Exception(
            "unknown variable '$name'",
        )

        /** Instantiate a `<slide>` template over each sliding window of the list. `collect` (default:
         *  the template's parameter count) is the window size; the window steps by one. */
        internal fun slide(e: XmlElement) {
            val lists = e.children.filter { it.tag == "list" }
            if (lists.size != 1) throw UnsupportedXcsp3Exception("slide: only the single-list form is supported")
            val listElem = lists[0]
            val template = e.children.firstOrNull { it.tag != "list" }
                ?: throw UnsupportedXcsp3Exception("slide: missing template constraint")
            val names = listElem.textContent.splitWs().flatMap { expandNames(it) }
            val used = template.explicitParamIndices()
            val collect = listElem.attr("collect").toIntOrNull() ?: ((used.maxOrNull() ?: 0) + 1)
            require(collect >= 1) { "slide: collect must be >= 1" }
            for (i in 0..names.size - collect) {
                constraint(template.substituteParams(names.subList(i, i + collect), used))
            }
        }

        /** Instantiate a `<group>` template for each `<args>` row. */
        internal fun group(e: XmlElement) {
            val template = e.children.firstOrNull { it.tag != "args" }
                ?: throw UnsupportedXcsp3Exception("group without a template constraint")
            val used = template.explicitParamIndices()
            for (args in e.children.filter { it.tag == "args" }) {
                val tokens = args.textContent.splitWs()
                    .flatMap { expandNames(it) }
                constraint(template.substituteParams(tokens, used))
            }
        }

        internal fun domainMin(vars: IntArray) = vars.minOf { domains[it].min }
        internal fun domainSpan(vars: IntArray) = vars.maxOf { domains[it].max } - domainMin(vars) + 1
        internal fun domainValues(v: Int): List<Int> {
            val d = domains[v]
            val out = ArrayList<Int>(d.size)
            for (k in 0 until d.size) out.add(d.valueAt(k).toInt())
            return out
        }

        internal fun condition(text: String): Pair<LinearOp, Int> {
            val (opTok, rhs) = splitCondition(text)
            val (op, delta) = relOp(opTok) ?: throw UnsupportedXcsp3Exception("condition op '$opTok'")
            val k = rhs.toIntOrNull()
                ?: throw UnsupportedXcsp3Exception("condition '$text' (only (op,const) supported)")
            return op to (k + delta)
        }

        fun build(bakeCancellation: Cancellation = Cancellation.Never): Xcsp3Problem = Xcsp3Problem(
            Problem(
                numBoolVars = nextBool,
                numIntVars = domains.size,
                intDomains = domains.toTypedArray(),
                factors = factors.toTypedArray(),
                cancellation = bakeCancellation,
            ),
            objective,
            intVarNames = LinkedHashMap(varIds),
            sense = if (objectiveMaximize) ObjectiveSense.MAXIMIZE else ObjectiveSense.MINIMIZE,
            definedVars = definedVars.toIntArray(),
        )

        companion object {
            internal val REL = setOf("eq", "ne", "le", "lt", "ge", "gt")
            internal val BOOL_FNS = setOf("and", "or", "not", "imp", "iff", "xor")
        }
    }
}
