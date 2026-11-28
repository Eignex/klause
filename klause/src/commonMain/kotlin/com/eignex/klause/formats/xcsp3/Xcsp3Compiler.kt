package com.eignex.klause.formats.xcsp3

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearObjectiveSpec
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.values
import com.eignex.klause.lowering.CnfLowering
import com.eignex.klause.lowering.IntComb
import com.eignex.klause.lowering.LinComb
import com.eignex.klause.lowering.LinRelation
import com.eignex.klause.lowering.ProblemBuilder
import com.eignex.klause.lowering.absBounds
import com.eignex.klause.lowering.constProduct
import com.eignex.klause.lowering.constRelationHolds
import com.eignex.klause.lowering.intAbs
import com.eignex.klause.lowering.isConstant
import com.eignex.klause.lowering.linearBounds
import com.eignex.klause.lowering.newBooleanInt
import com.eignex.klause.lowering.newIntExpression
import com.eignex.klause.lowering.newIntExtreme
import com.eignex.klause.lowering.newIntProduct
import com.eignex.klause.lowering.productBounds
import com.eignex.klause.lowering.scaleByConst
import com.eignex.klause.lowering.scaleIntComb
import com.eignex.klause.lowering.selectionBounds
import com.eignex.klause.lowering.sumIntCombs
import com.eignex.klause.lowering.trueLit
import com.eignex.klause.lowering.wideConstHolds
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.MutableLongIntMap

// Allocates no matcher state per call.
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
        require(j < n) { "unterminated bracket index in '$s'" }
        require(j > start) { "empty bracket index in '$s'" }
        out.add(s.substring(start, j).toInt())
        i = j + 1
    }
    return out.toIntArray()
}

// XCSP3 run-length shorthand `vxn` as `(v, n)`.
internal fun parseRle(tok: String): Pair<Int, Int>? {
    val xi = tok.indexOf('x')
    if (xi <= 0 || xi == tok.length - 1) return null
    val v = tok.substring(0, xi).toIntOrNull() ?: return null
    val n = tok.substring(xi + 1).toIntOrNull() ?: return null
    return if (n < 0) null else v to n
}

// The operand may itself hold commas, as in `(in, {set})`.
internal fun splitCondition(text: String): Pair<String, String> {
    val t = text.trim()
    require(t.length >= 2 && t.first() == '(' && t.last() == ')') { "condition '$text'" }
    val inner = t.substring(1, t.length - 1)
    val comma = inner.indexOf(',')
    require(comma >= 0) { "condition '$text'" }
    return inner.substring(0, comma).trim() to inner.substring(comma + 1).trim()
}

internal object Compiler {
    internal class Builder(
        private val settings: ProblemSettings = KlauseConfig.current.problemSettings(),
        private val semantics: EffectiveSourceSemantics = xcsp3Semantics.resolve(),
        private val recoveryCancellation: ExecutionControl = ExecutionControl.Never,
    ) : CnfLowering {
        internal val varIds = LinkedHashMap<String, Int>() // resolved name (incl. array cells) -> int var id
        internal val arrayDims = HashMap<String, IntArray>() // array id -> declared dimension sizes
        internal val problemBuilder = ProblemBuilder(settings, semantics)
        internal val domains get() = problemBuilder.intDomains
        private val affineDistinctness = Xcsp3AffineDistinctness()
        private val divModOperands = HashMap<FExpr, Int>()
        private val divModRelations = com.eignex.klause.lowering.DivModRelationIndex()
        override val factors get() = problemBuilder.factors
        internal val classes = Xcsp3ConstraintClasses(factors)
        internal var objective: LinearObjectiveSpec? = null
        internal var objectiveMaximize = false

        // Sound `defines_var` hints for local search: the factor IR alone cannot tell an equality's output apart.
        internal val definedVars = IntArrayList()

        // Typed orientations carry the defining relation itself, so local search maintains outputs such as
        // binPacking loads and extremum results whose rows the bare-reference guards decline to orient.
        internal val definitionHints = ArrayList<DefinitionHint>()

        // One 0/1 indicator per `(variable, value)`: binPacking rows and `eq` terms over the same pair read
        // one channel, so a value change moves one literal instead of one per occurrence.
        internal val valueIndicators = HashMap<Int, MutableLongIntMap>()

        internal fun defineLinear(output: Int, coefficients: LongArray, vars: IntArray, bound: Long) {
            definitionHints.add(DefinitionHint.Linear(output, coefficients.copyOf(), operands(vars), bound))
        }

        internal fun defineExtreme(output: Int, vars: IntArray, max: Boolean) {
            definitionHints.add(DefinitionHint.Extreme(output, operands(vars), max))
        }

        private fun operands(vars: IntArray): Array<DefinitionHint.Operand> =
            Array(vars.size) { DefinitionHint.Operand.Variable(vars[it]) }

        // A `<list>` can repeat a constant many times, and a fixed var is never branched, so sharing it is
        // solve-neutral.
        private val constVars = MutableLongIntMap()
        internal fun constVar(value: Long): Int {
            val existing = constVars.getOrDefault(value, -1)
            if (existing != -1) return existing
            return newAuxVar(value, value).also { constVars.put(value, it) }
        }

        // A group's rows share one `<transitions>` String object, so the last automaton is cached by identity.
        internal var cachedAutomatonText: String? = null
        internal var cachedAutomaton: RegularAutomaton? = null

        internal var cachedMddText: String? = null
        internal var cachedMddResult: MddResult? = null

        // Rows share the parsed tuple arrays read-only; an identical high-arity table per row can exhaust the heap.
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
                    val mixed = mixedCellDomains(e, id, dims)
                    val uniform = if (mixed == null) domainFor(e) else null

                    // Cells are named x[i], x[i][j], … so index refs and `x[…][]` wildcards resolve.
                    var flat = 0
                    fun declareCells(prefix: String, d: Int) {
                        if (d == dims.size) {
                            val dom = mixed?.get(flat) ?: uniform
                            flat++
                            // A mixed array cell no `<domain>` lists is undefined, so it is not declared.
                            if (dom != null) addVar(prefix, dom)
                            return
                        }
                        for (i in 0 until dims[d]) declareCells("$prefix[$i]", d + 1)
                    }
                    declareCells(id, 0)
                }

                else -> throw UnsupportedXcsp3Exception("variable kind '${e.tag}'")
            }
        }

        // Null for a uniform array. `others` covers every cell no other child lists; an uncovered cell is null.
        // The children are never read through the array's textContent, which would concatenate them into one union.
        private fun mixedCellDomains(e: XmlElement, id: String, dims: IntArray): Array<IntDomain?>? {
            if (e.children.none { it.tag == "domain" }) return null
            val size = dims.fold(1L) { acc, d -> acc * d }
            if (size > Int.MAX_VALUE) throw UnsupportedXcsp3Exception("array '$id' of $size cells")
            val cells = arrayOfNulls<IntDomain>(size.toInt())
            var others: IntDomain? = null
            for (part in e.children) {
                if (part.tag != "domain") throw UnsupportedXcsp3Exception("array child '${part.tag}'")
                val dom = domainFor(part)
                for (tok in part.attr("for").splitWs()) {
                    if (tok == "others") {
                        others = dom
                        continue
                    }
                    forEachCell(domainTargetSpecs(tok, id, dims), dims, 0, 0) { cells[it] = dom }
                }
            }
            others?.let { dom -> for (i in cells.indices) if (cells[i] == null) cells[i] = dom }
            return cells
        }

        private fun domainTargetSpecs(tok: String, id: String, dims: IntArray): List<IntArray> {
            val specs = if (tok.startsWith("$id[")) parseBracketSpecs(tok, id.length) else null
            if (specs == null || specs.size != dims.size) {
                throw UnsupportedXcsp3Exception("domain target '$tok' of array '$id'")
            }
            return specs
        }

        private fun forEachCell(specs: List<IntArray>, dims: IntArray, dim: Int, flat: Int, action: (Int) -> Unit) {
            if (dim == dims.size) return action(flat)
            val spec = specs[dim]
            val lo = if (spec[0] == Int.MIN_VALUE) 0 else spec[0]
            val hi = if (spec[1] == Int.MAX_VALUE) dims[dim] - 1 else spec[1]
            if (lo < 0 || hi >= dims[dim]) throw UnsupportedXcsp3Exception("domain target index out of range")
            for (i in lo..hi) forEachCell(specs, dims, dim + 1, flat * dims[dim] + i, action)
        }

        internal fun addVar(name: String, dom: IntDomain) {
            varIds[name] = problemBuilder.newInt(dom)
        }
        internal fun newAuxVar(lo: Long, hi: Long): Int = problemBuilder.newInt(IntDomain(lo, hi, settings.storage))
        override fun newBool(): Int = problemBuilder.newBool()

        // An empty domain with `as="ref"` reuses a previously declared variable's or array's domain.
        internal fun domainFor(e: XmlElement): IntDomain {
            val text = e.textContent.trim()
            if (text.isNotEmpty()) return parseDomain(text)
            val alias = e.attr("as")
            if (alias.isBlank()) throw UnsupportedXcsp3Exception("empty domain")
            varIds[alias]?.let { return domains[it] }
            // A declared array's first cell is `alias[0]…[0]`, indexed directly; the scan below is a fallback.
            arrayDims[alias]?.let { dims ->
                varIds[alias + "[0]".repeat(dims.size)]?.let { return domains[it] }
            }
            val cell = varIds.keys.firstOrNull { it.startsWith("$alias[") }
                ?: throw UnsupportedXcsp3Exception("unknown domain alias '$alias'")
            return domains[varIds.getValue(cell)]
        }

        internal fun parseDomain(text: String): IntDomain {
            // Built from the gaps between intervals: enumerating a range's interior is O(span) and a wide domain
            // would exhaust the heap.
            val intervals = ArrayList<LongArray>()
            for (tok in text.splitWs()) {
                val separator = tok.indexOf("..")
                val a: Long
                val b: Long
                if (separator < 0) {
                    a = tok.toLong()
                    b = a
                } else {
                    require(tok.indexOf("..", separator + 2) < 0) { "invalid domain range '$tok'" }
                    a = tok.substring(0, separator).toLong()
                    b = tok.substring(separator + 2).toLong()
                }
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
                if (prevEnd != Long.MAX_VALUE && a > prevEnd + 1) {
                    for (v in prevEnd + 1 until a) holes.add(v)
                }
                if (b > prevEnd) prevEnd = b
                if (b > hi) hi = b
            }
            val dom = IntDomain(lo, hi, settings.storage)
            return if (holes.isEmpty()) {
                dom
            } else {
                dom.excludeValues(
                    holes.toLongArray(),
                ) ?: throw UnsupportedXcsp3Exception("empty domain")
            }
        }

        fun constraint(e: XmlElement) {
            requireHardConstraint(e)
            classes.within(e.attr("class")) { dispatch(e) }
        }

        private fun dispatch(e: XmlElement) {
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

        // A constant relation posts a contradiction clause when false and nothing when true.
        internal fun postRel(r: RelParts) {
            affineDistinctness.record(r.rel, r.op, factors.size)
            when (val rel = r.rel) {
                is LinRelation.LongRel ->
                    if (rel.vars.isNotEmpty()) {
                        factors.add(Linear(rel.coeffs, rel.vars, r.op, rel.bound))
                    } else if (!constRelationHolds(r.op, rel.bound)) {
                        factors.add(Clause(intArrayOf(Lit.negate(trueLit()))))
                    }

                is LinRelation.WideRel ->
                    if (rel.vars.isNotEmpty()) {
                        factors.add(Linear(rel.vars, rel.coeffs, r.op, rel.bound))
                    } else if (!wideConstHolds(r.op, rel.bound)) {
                        factors.add(Clause(intArrayOf(Lit.negate(trueLit()))))
                    }
            }
        }

        override var trueLitCache: Int
            get() = problemBuilder.trueLitCache
            set(value) {
                problemBuilder.trueLitCache = value
            }

        internal class RelParts(val rel: LinRelation, val op: LinearOp)

        fun objective(e: XmlElement) {
            val maximize = e.tag == "maximize"
            objectiveMaximize = maximize
            val type = e.attr("type").ifBlank { "sum" }
            val listText = e.child("list")?.textContent ?: e.textContent
            val termVars = listText.splitWs()
                .flatMap { tok -> expandNames(tok).map { termVar(it, shareIndicators = type == "sum") } }.toIntArray()
            if (termVars.isEmpty()) throw UnsupportedXcsp3Exception("objective: empty <list>")
            when (type) {
                "sum" -> {
                    val coeffs = coeffsOrUnit(e.child("coeffs")?.textContent, termVars.size)
                    require(coeffs.size == termVars.size) { "objective: <coeffs> length != term count" }
                    val arr = LongArray(domains.size)
                    termVars.forEachIndexed { i, v ->
                        val coefficient = coeffs[i].toLong()
                        arr[v] += if (maximize) -coefficient else coefficient
                    }
                    objective = LinearObjectiveSpec(intCoefficients = arr)
                }

                "maximum", "minimum" -> {
                    val m = problemBuilder.newIntExtreme(termVars, max = type == "maximum",
                        domain = selectionBounds(termVars, domains))
                    definedVars.add(m)
                    defineExtreme(m, termVars, type == "maximum")
                    objective = singleVarObjective(m, maximize)
                }

                "nValues" -> objective = singleVarObjective(distinctCountVar(termVars), maximize)

                else -> throw UnsupportedXcsp3Exception("objective type '$type'")
            }
        }

        internal fun singleVarObjective(v: Int, maximize: Boolean): LinearObjectiveSpec {
            val arr = LongArray(domains.size)
            arr[v] = if (maximize) -1L else 1L
            return LinearObjectiveSpec(intCoefficients = arr)
        }

        internal fun linear(e: FExpr): IntComb = linearFold(e)

        // Folds promote to IntComb.Wide on Long overflow, and an over-Int64 comparison lowers to a wide Linear. A
        // wide value can only be a relation coefficient or bound: a Long-domain variable cannot hold it.
        private fun linearFold(e: FExpr): IntComb = when (e) {
            is FExpr.Num -> IntComb.Narrow(LinComb(emptyMap(), e.value.toLong()))

            is FExpr.Ref -> IntComb.Narrow(LinComb(mapOf(ref(e.name) to 1L), 0L))

            is FExpr.SetLit -> throw UnsupportedXcsp3Exception("set literal used arithmetically")

            is FExpr.Call -> when (e.fn) {
                "add" -> sumIntCombs(e.args.map { linear(it) })

                "sub" -> sumIntCombs(e.args.map { linear(it) }, negateTail = true)

                "neg" -> scaleIntComb(linear(e.args[0]), -1L)

                "abs" -> absOf(linear(e.args[0]))

                "dist" -> absOf(sumIntCombs(listOf(linear(e.args[0]), linear(e.args[1])), negateTail = true))

                "min" -> minMaxTerm(e.args, max = false)

                "max" -> minMaxTerm(e.args, max = true)

                "if" -> ifTerm(e.args)

                "div" -> divModTerm(e.args, mod = false)

                "mod" -> divModTerm(e.args, mod = true)

                "pow" -> powerTerm(e.args)

                "mul" -> {
                    val parts = e.args.map { linear(it) }
                    val nonConst = parts.filter { !it.isConstant() }
                    val k = constProduct(parts.filter { it.isConstant() })
                    when {
                        k is IntComb.Narrow && k.lin.constant == 0L -> IntComb.Narrow(LinComb(emptyMap(), 0L))

                        nonConst.isEmpty() -> k

                        nonConst.size == 1 -> scaleByConst(nonConst[0], k)

                        else -> {
                            var acc = materializeVar(nonConst[0])
                            for (idx in 1 until nonConst.size) {
                                val next = materializeVar(nonConst[idx])
                                val p = problemBuilder.newIntProduct(acc, next, productBounds(acc, next))
                                acc = p
                            }
                            scaleByConst(IntComb.Narrow(LinComb(mapOf(acc to 1L), 0L)), k)
                        }
                    }
                }

                // A boolean-valued subexpression used arithmetically is its 0/1 truth value.
                "in", "notin", in REL, in BOOL_FNS ->
                    IntComb.Narrow(LinComb(mapOf(litTo01(compileBool(e)) to 1L), 0L))

                else -> throw UnsupportedXcsp3Exception("arithmetic fn '${e.fn}'")
            }
        }

        internal fun litTo01(lit: Int): Int {
            val ch = problemBuilder.newBooleanInt(lit)
            definedVars.add(ch)
            return ch
        }

        /** `min`/`max` of expressions as a linear term, via [ArrayMinMax] over the materialised args. */
        internal fun minMaxTerm(args: List<FExpr>, max: Boolean): IntComb {
            val vs = args.map { materializeVar(linear(it)) }.toIntArray()
            val m = problemBuilder.newIntExtreme(vs, max, selectionBounds(vs, domains))
            definedVars.add(m)
            return IntComb.Narrow(LinComb(mapOf(m to 1L), 0L))
        }

        internal fun ifTerm(args: List<FExpr>): IntComb = selectionTerm(compileBool(args[0]),
            materializeVar(linear(args[1])),
            materializeVar(linear(args[2])))

        internal fun divModTerm(args: List<FExpr>, mod: Boolean): IntComb {
            val a = divModOperand(args[0]) { linear(args[0]) }
            val bLin = linear(args[1])
            if (!bLin.isConstant()) return divModVar(a, divModOperand(args[1]) { bLin }, mod)
            val k = when (bLin) {
                is IntComb.Narrow -> bLin.lin.constant
                is IntComb.Wide -> throw UnsupportedXcsp3Exception("div/mod by an over-64-bit constant divisor")
            }
            if (k == 0L) throw UnsupportedXcsp3Exception("div/mod by zero divisor")
            return retainedDivMod(a, constVar(k), mod)
        }

        private fun divModOperand(operand: FExpr, lin: () -> IntComb): Int {
            val start = factors.size
            val v = divModOperands.getOrPut(operand) { materializeVar(lin()) }
            classes.define(v, start)
            return v
        }

        internal fun divModVar(a: Int, b: Int, mod: Boolean): IntComb {
            val da = domains[a]
            val db = domains[b]
            if (da.min < 0L || db.min < 1L) {
                throw UnsupportedXcsp3Exception("div/mod by variable divisor requires b >= 1 and a >= 0")
            }
            return retainedDivMod(a, b, mod)
        }

        private fun retainedDivMod(a: Int, b: Int, mod: Boolean): IntComb {
            val relation = divModRelations.retain(a, b, domains[a], domains[b],
                quotient = null, remainder = null,
                freshInt = { domain -> newAuxVar(domain.min, domain.max) })
            val start = factors.size
            factors.addAll(relation.factors)
            classes.define(relation.quotient, start)
            return IntComb.Narrow(LinComb(mapOf((if (mod) relation.remainder else relation.quotient) to 1L), 0L))
        }

        internal fun absOf(lin: IntComb): IntComb {
            val v = materializeVar(lin)
            val domain = absBounds(domains[v])
            val a = problemBuilder.newInt(domain)
            problemBuilder.intAbs(v, a)
            return IntComb.Narrow(LinComb(mapOf(a to 1L), 0L))
        }

        // A wide expression is rejected: a Long-domain variable cannot hold its value.
        internal fun materializeVar(lin: IntComb): Int {
            val narrow = (lin as? IntComb.Narrow)?.lin
                ?: throw UnsupportedXcsp3Exception("cannot materialise an over-64-bit expression into a variable")
            narrow.asSimpleVar()?.let { return it }
            return problemBuilder.newIntExpression(narrow, linearBounds(narrow, domains),
                define = { output, coefficients, variables, bound ->
                    definedVars.add(output)
                    defineLinear(output, coefficients, variables, bound)
                })
        }

        internal fun productBounds(a: Int, b: Int): IntDomain = productBounds(domains[a], domains[b]) { x, y -> x * y }

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

        internal fun listVars(e: XmlElement): IntArray = refList(requireNotNull(e.child("list")).textContent)

        internal fun IntArray.widenToLong(): LongArray = LongArray(size) { this[it].toLong() }

        // Null when any token is not an integer, which callers treat as "not constant".
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

        // A present but non-constant `<coeffs>` must not default to unit weights.
        internal fun coeffsOrUnit(text: String?, n: Int): IntArray = when {
            text == null -> IntArray(n) { 1 }
            else -> parseInts(text) ?: throw UnsupportedXcsp3Exception("non-constant <coeffs>")
        }

        // Builds an IntArray directly: a large `<list>` would otherwise box every resolved id.
        internal fun refList(text: String): IntArray {
            val out = IntArrayList()
            for (tok in text.splitWs()) for (name in expandNames(tok)) out.add(termVar(name))
            return out.toIntArray()
        }

        // Each cell name paired with its bracket indices, parsed once at index-build time so reference
        // expansion never re-parses a cell's `[i][j]` per token.
        private class BaseCells {
            val names = ArrayList<String>()
            val indices = ArrayList<IntArray>()
        }

        // Built on the first wildcard reference, when every variable is declared. A wildcard only matches cells of
        // its base, so expansion costs O(cells of the base) rather than O(references × variables).
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

        // Each `[...]` group is a fixed index, a `[lo..hi]` range or a `[]` wildcard, in any position, e.g.
        // `x[2][]`, `x[0..1][0]`. A plain reference is returned as-is.
        internal fun expandNames(tok: String): List<String> {
            if ("[]" !in tok && ".." !in tok) return listOf(tok)
            val br = tok.indexOf('[')
            if (br <= 0) return listOf(tok)
            val base = tok.substring(0, br)
            val specs = parseBracketSpecs(tok, br) ?: return listOf(tok)
            // A declared array of matching rank generates exactly the selected names, row-major as declared, so
            // positional list semantics are preserved.
            val dims = arrayDims[base]
            if (dims != null && dims.size == specs.size) {
                val out = ArrayList<String>()
                generateCells(base, specs, dims, 0, StringBuilder(base), out)
                return out
            }
            val cells = cellsByBase[base] ?: return emptyList()
            val out = ArrayList<String>()
            for (i in cells.names.indices) if (cellMatches(cells.indices[i], specs)) out.add(cells.names[i])
            return out
        }

        // Keeps only declared names, row-major to match declareVar's cell order.
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

        // A wildcard is the full integer range. Null when the tail is not a clean run of `[...]` groups.
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

        private fun cellMatches(idx: IntArray, specs: List<IntArray>): Boolean {
            if (idx.size != specs.size) return false
            for (k in specs.indices) if (idx[k] < specs[k][0] || idx[k] > specs[k][1]) return false
            return true
        }
        internal fun ref(name: String): Int = varIds[name] ?: throw UnsupportedXcsp3Exception(
            "unknown variable '$name'",
        )

        // `collect` (default: the template's parameter count) is the window size; the window steps by one.
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

        // For a `<group>` that arrives already materialized, e.g. one nested as a template.
        internal fun group(e: XmlElement) {
            val template = e.children.firstOrNull { it.tag != "args" }
                ?: throw UnsupportedXcsp3Exception("group without a template constraint")
            val used = template.explicitParamIndices()
            val intensionTemplate = intensionTemplateOf(template)
            for (args in e.children.filter { it.tag == "args" }) {
                applyGroupRow(template, used, intensionTemplate, argsTokens(args))
            }
        }

        // Neither the container nor a group's rows are ever all resident.
        internal fun streamConstraints(reader: XmlReader) {
            while (true) {
                val tag = reader.nextChildTag() ?: return
                when (tag) {
                    "group" -> {
                        val group = requireNotNull(enterStreamed(reader)) { "group without a template constraint" }
                        classes.within(group) { streamGroup(reader) }
                    }
                    "block" -> enterStreamed(reader)?.let { classes.within(it) { streamConstraints(reader) } }
                    else -> constraint(reader.materializeChild())
                }
            }
        }

        // The `class` attribute, or null when the container has no body.
        private fun enterStreamed(reader: XmlReader): String? {
            var containerClasses = ""
            val entered = reader.enterPeeked { name, value ->
                requireHardAttribute(name, value)
                if (name == "class") containerClasses = value
            }
            return if (entered) containerClasses else null
        }

        private fun streamGroup(reader: XmlReader) {
            val firstTag = reader.nextChildTag()
                ?: throw UnsupportedXcsp3Exception("group without a template constraint")
            require(firstTag != "args") { "group template must precede its <args> rows" }
            val template = reader.materializeChild()
            val used = template.explicitParamIndices()
            val intensionTemplate = intensionTemplateOf(template)
            while (true) {
                val tag = reader.nextChildTag() ?: break
                require(tag == "args") { "group: expected <args>, found <$tag>" }
                applyGroupRow(template, used, intensionTemplate, argsTokens(reader.materializeChild()))
            }
        }

        // Re-parsing the expanded string per row would dominate the parse of group-heavy models.
        private fun intensionTemplateOf(template: XmlElement): FExpr? {
            requireHardConstraint(template)
            return if (template.tag == "intension" && FExpr.isScalarTemplate(template.textContent)) {
                FExpr.parse(template.textContent.trim())
            } else {
                null
            }
        }

        private fun requireHardConstraint(element: XmlElement) {
            for ((name, value) in element.attributes) requireHardAttribute(name, value)
        }

        private fun requireHardAttribute(name: String, value: String) {
            if (name in GUARDED_ATTRIBUTES || (name == "type" && value == "soft")) {
                throw UnsupportedXcsp3Exception("reified or soft constraint attribute '$name'")
            }
        }

        private fun argsTokens(args: XmlElement): List<String> = args.textContent.splitWs().flatMap { expandNames(it) }

        private fun applyGroupRow(
            template: XmlElement,
            used: Set<Int>,
            intensionTemplate: FExpr?,
            tokens: List<String>,
        ) {
            if (intensionTemplate != null) {
                classes.within(template.attr("class")) { intension(FExpr.substitute(intensionTemplate, tokens)) }
            } else {
                constraint(template.substituteParams(tokens, used))
            }
        }

        internal fun domainMin(vars: IntArray) = vars.minOf { domains[it].min }
        internal fun domainSpan(vars: IntArray) = vars.maxOf { domains[it].max } - domainMin(vars) + 1
        internal fun domainValues(v: Int): List<Long> {
            val d = domains[v]
            val out = ArrayList<Long>(d.values.size)
            for (k in 0 until d.values.size) out.add(d.values.valueAt(k))
            return out
        }

        internal fun condition(text: String): Pair<LinearOp, Int> {
            val (opTok, rhs) = splitCondition(text)
            val (op, delta) = relOp(opTok) ?: throw UnsupportedXcsp3Exception("condition op '$opTok'")
            val k = rhs.toIntOrNull()
                ?: throw UnsupportedXcsp3Exception("condition '$text' (only (op,const) supported)")
            return op to (k + delta)
        }

        fun build(): Xcsp3Problem {
            recoverAffineDistinctness(affineDistinctness, recoveryCancellation)
            objective = objective?.let { spec ->
                if (spec.intCoefficientValues.size >= domains.size) spec
                else spec.copy(intCoefficients = spec.intCoefficientValues.copyOf(domains.size))
            }
            val implied = classes.mask(factors.size)?.also { mask ->
                // The forced-true literal is a constant every constraint shares, so no class owns its unit clause.
                for (i in mask.indices) {
                    val unit = factors[i]
                    if (unit is Clause && unit.literals.size == 1 && unit.literals[0] == trueLitCache) mask[i] = false
                }
            }?.takeIf { mask -> mask.any { it } }
            return Xcsp3Problem(
                problemBuilder.build(impliedFactorMask = implied, hasSymmetryBreaking = classes.hasSymmetryBreaking),
                objective,
                intVarNames = LinkedHashMap(varIds),
                sense = if (objectiveMaximize) ObjectiveSense.MAXIMIZE else ObjectiveSense.MINIMIZE,
                definedVars = definedVars.toIntArray(),
                definitionHints = definitionHints.toList(),
            )
        }

        companion object {
            private val GUARDED_ATTRIBUTES = setOf("reifiedBy", "hreifiedFrom", "hreifiedTo", "violationCost")
            internal val REL = setOf("eq", "ne", "le", "lt", "ge", "gt")
            internal val BOOL_FNS = setOf("and", "or", "not", "imp", "iff", "xor")
        }
    }
}
