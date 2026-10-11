package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.Reports
import java.io.File
import java.io.StringReader
import java.lang.reflect.Array as ReflectArray
import java.math.BigInteger
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.encodeToString
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xcsp.common.Condition
import org.xcsp.common.domains.Domains.Dom
import org.xcsp.common.predicates.TreeEvaluator
import org.xcsp.common.predicates.XNode
import org.xcsp.common.predicates.XNodeLeaf
import org.xcsp.common.structures.AbstractTuple
import org.xcsp.parser.XParser
import org.xcsp.parser.callbacks.SolutionChecker
import org.xcsp.parser.callbacks.XCallbacks.Implem
import org.xcsp.parser.callbacks.XCallbacks2
import org.xcsp.parser.entries.XConstraints.XCtr
import org.xcsp.parser.entries.XObjectives.OObjectiveExpr
import org.xcsp.parser.entries.XObjectives.OObjectiveSpecial
import org.xcsp.parser.entries.XObjectives.XObj
import org.xcsp.parser.entries.XVariables.XVarInteger
import org.xml.sax.InputSource

internal object Xcsp3SourceChecker {
    private const val CHECKER = "org.xcsp:xcsp3-tools:2.5"

    @JvmStatic
    fun main(args: Array<String>) {
        val validation = inspect(File(args[0]), File(args[1]).readText(), args.getOrNull(2))
        println(Xcsp3SourceValidation.RESULT_PREFIX + Reports.json.encodeToString(validation))
    }

    fun result(status: String, reason: String, witness: String? = null): SourceValidation = SourceValidation(
        status, reason, scope = "xcsp3-source", checkedWitness = witness, checker = CHECKER,
    )

    fun inspect(source: File, witness: String, objective: String?): SourceValidation {
        val solution = runCatching {
            builder().parse(InputSource(StringReader(witness)))
        }.getOrElse { failure ->
            return result("invalid", "malformed instantiation XML: ${failure.message.orEmpty().take(300)}")
        }
        val root = solution.documentElement
        val document = runCatching {
            source.inputStream().use { builder().parse(it) }
        }.getOrElse { failure ->
            return result("unknown", "source XML cannot be read: ${failure.message.orEmpty().take(300)}")
        }
        val parser = runCatching {
            XParser(document)
        }.getOrElse { failure ->
            return result("unknown", "source syntax is unsupported: ${failure.message.orEmpty().take(300)}")
        }
        if (unsupported(document) || parser.oEntries.size > 1) {
            return result("unknown", "reified, soft or multiple-objective source checking is unsupported")
        }
        checkAssignment(parser, root)?.let { return it }
        pinObjective(parser, root, objective)?.let { return it }
        val cost = objective?.let { " cost=\"$it\"" }.orEmpty()
        val names = root.getElementsByTagName("list").item(0).textContent
        val values = root.getElementsByTagName("values").item(0).textContent
        val pinned = "<instantiation$cost><list>${xmlText(names)}</list>" +
            "<values>${xmlText(values)}</values></instantiation>"
        return runCatching {
            val assignment = names.trim().split(Regex("\\s+")).zip(values.trim().split(Regex("\\s+")))
                .associate { (name, value) -> name to value.toInt() }
            CheckerArithmeticGuard(assignment).apply {
                loadConstraints(parser)
                loadObjectives(parser)
            }
            val checker = SolutionChecker(false, source.absolutePath, pinned.byteInputStream())
            when {
                checker.violatedCtrs.isNotEmpty() -> result("invalid", "source constraints reject candidate")

                checker.invalidObjs.isNotEmpty() ->
                    result("invalid", "reported objective differs from source objective")

                else -> result("valid", "source constraints and objective accept candidate", "v $pinned")
            }
        }.getOrElse { failure ->
            result("unknown", "source checking cannot complete: ${failure.message.orEmpty().take(300)}")
        }
    }

    private fun unsupported(document: Document): Boolean {
        val nodes = document.getElementsByTagName("*")
        val attributes = listOf("reifiedBy", "hreifiedFrom", "hreifiedTo", "violationCost", "violationMeasure")
        return (0 until nodes.length).any { i ->
            val element = nodes.item(i) as Element
            element.getAttribute("type") == "soft" || attributes.any { element.hasAttribute(it) }
        }
    }

    private fun checkAssignment(parser: XParser, root: Element): SourceValidation? {
        val children = (0 until root.childNodes.length).map { root.childNodes.item(it) }.filterIsInstance<Element>()
        if (root.tagName != "instantiation" || children.map { it.tagName } != listOf("list", "values")) {
            return result("invalid", "instantiation requires one list and one values element")
        }
        val names = children[0].textContent.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val values = children[1].textContent.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (names.any { it.contains("[]") || it.contains("..") } ||
            values.any { Regex("-?[0-9]+x[0-9]+").matches(it) }
        ) {
            return result("unknown", "compact assignments require expansion before checking")
        }
        if (names.size != values.size || names.size != names.toSet().size || names.toSet() != parser.mapForVars.keys) {
            return result("invalid", "assignment must cover every source variable exactly once")
        }
        for ((name, text) in names.zip(values)) {
            val variable = parser.mapForVars.getValue(name) as? XVarInteger
                ?: return result("unknown", "only integer source variables are supported")
            val value = text.toLongOrNull() ?: return result("invalid", "noninteger assignment for $name")
            if (!(variable.dom as Dom).contains(value)) {
                return result("invalid", "value outside source domain for $name")
            }
            if (value.toInt().toLong() != value) return result("unknown", "assignment exceeds checker integer range")
        }
        return null
    }

    private fun pinObjective(parser: XParser, root: Element, objective: String?): SourceValidation? {
        if (parser.oEntries.isNotEmpty()) {
            if (objective == null) return result("unknown", "missing reported source objective")
            if (!Regex("-?[0-9]+").matches(objective)) return result("invalid", "malformed reported integer objective")
            if (root.hasAttribute("cost") && root.getAttribute("cost") != objective) {
                return result("invalid", "instantiation cost differs from reported objective")
            }
            root.setAttribute("cost", objective)
        } else if (objective != null || root.hasAttribute("cost")) {
            return result("invalid", "reported objective for a satisfaction source")
        }
        return null
    }

    private fun xmlText(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun builder() = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }.newDocumentBuilder()
}

private class CheckerArithmeticGuard(private val assignment: Map<String, Int>) : XCallbacks2 {
    private val implementation = Implem(this).apply { rawParameters() }

    override fun implem(): Implem = implementation

    override fun loadCtr(c: XCtr) {
        c.childs.forEach { checkTrees(it.value) }
        when (c.type.name) {
            "sum" -> super<XCallbacks2>.loadCtr(c)
            "intension" -> {
                val tree = c.childs[0].value as XNode<*>
                check(tree.canonization(), exact(tree))
            }
            "ordered" -> if (c.childs.size > 2) checkOrdered(c)
            "noOverlap" -> checkEndpoints(c.childs[0].value, c.childs[1].value)
            "cumulative" -> checkCumulative(c)
            "binPacking" -> checkPacking(c)
            "knapsack" -> {
                checkWeighted(c.childs[0].value, c.childs[1].value)
                checkWeighted(c.childs[0].value, c.childs[3].value)
            }
            "extension", "regular", "mdd", "allDifferent", "allEqual", "lex", "precedence",
            "count", "nValues", "cardinality", "maximum", "minimum", "stretch", "instantiation", "clause" -> {
                if (c.childs.any { it.type.name == "index" }) decline("indexed extrema")
            }
            else -> decline("constraint ${c.type}")
        }
    }

    override fun loadObj(o: XObj) {
        when (o) {
            is OObjectiveExpr -> checkTrees(o.rootNode)
            is OObjectiveSpecial -> {
                if (o.type.name !in setOf("SUM", "PRODUCT", "MINIMUM", "MAXIMUM", "NVALUES")) {
                    decline("objective ${o.type}")
                }
                checkTrees(o.terms)
                if (o.coeffs != null && o.coeffs.any { it !is Number }) decline("nonconstant objective coefficients")
            }
            else -> decline("objective")
        }
    }

    override fun unimplementedCase(vararg objects: Any?): Any =
        throw UnsupportedOperationException("expression sums or meta-constraints are outside direct-sum range checking")

    override fun buildCtrSum(id: String, list: Array<XVarInteger>, condition: Condition) {
        checkSum(list) { 1 }
    }

    override fun buildCtrSum(id: String, list: Array<XVarInteger>, coeffs: IntArray, condition: Condition) {
        checkSum(list) { coeffs[it] }
    }

    override fun buildCtrSum(id: String, list: Array<XVarInteger>, coeffs: Array<XVarInteger>, condition: Condition) {
        checkSum(list) { assignment.getValue(coeffs[it].id) }
    }

    private fun checkSum(list: Array<XVarInteger>, coefficient: (Int) -> Int) {
        var sum = 0
        try {
            for (i in list.indices) {
                val term = Math.multiplyExact(assignment.getValue(list[i].id), coefficient(i))
                sum = Math.addExact(sum, term)
            }
        } catch (_: ArithmeticException) {
            throw ArithmeticException("direct sum exceeds checker integer range")
        }
    }

    private fun checkOrdered(c: XCtr) {
        val values = integers(c.childs[0].value)
        val lengths = integers(c.childs[1].value)
        require(lengths.size == values.size - 1)
        lengths.indices.forEach { integer(values[it] + lengths[it], "ordered offset") }
    }

    private fun checkEndpoints(origins: Any, lengths: Any) {
        val starts = integers(origins)
        val durations = integers(lengths)
        require(starts.size == durations.size)
        starts.zip(durations).forEach { (start, duration) ->
            if (duration.signum() < 0) decline("negative task length")
            integer(start + duration, "task endpoint")
        }
    }

    private fun checkCumulative(c: XCtr) {
        val origins = integers(c.childs[0].value)
        val lengths = integers(c.childs[1].value)
        val heights = integers(c.childs[c.childs.size - 2].value)
        checkEndpoints(c.childs[0].value, c.childs[1].value)
        require(origins.size == heights.size)
        val ends = origins.zip(lengths).map { (origin, length) -> origin + length }
        // Loads change only at task boundaries, including drops when a task ends.
        for (time in (origins + ends).distinct()) {
            val load = origins.indices.filter { origins[it] <= time && time < ends[it] }
                .fold(BigInteger.ZERO) { total, i -> total + heights[i] }
            integer(load, "cumulative load")
        }
    }

    private fun checkPacking(c: XCtr) {
        val bins = integers(c.childs[0].value)
        val sizes = integers(c.childs[1].value)
        require(bins.size == sizes.size)
        val loads = bins.indices.groupBy { bins[it] }.values.map { items ->
            items.fold(BigInteger.ZERO) { total, i -> total + sizes[i] }
        }
        loads.forEach { integer(it, "bin-packing load") }
        if (c.childs[2].type.name != "condition") {
            val capacities = c.childs[2].value
            val count = ReflectArray.getLength(capacities)
            // The upstream vector callbacks inspect only bins covered by the capacity vector.
            if (bins.any { it < BigInteger.ZERO || it >= BigInteger.valueOf(count.toLong()) }) {
                decline("bin outside capacity vector")
            }
            if (c.childs[2].attributes.any { it.key.name == "startIndex" && it.value.toString() != "0" }) {
                decline("indexed bin conditions")
            }
        }
    }

    private fun checkWeighted(list: Any, coefficients: Any) {
        val values = integers(list)
        val coeffs = integers(coefficients)
        require(values.size == coeffs.size)
        var total = BigInteger.ZERO
        for (i in values.indices) {
            val term = values[i] * coeffs[i]
            integer(term, "weighted term")
            total += term
        }
        integer(total, "weighted total")
    }

    private fun integers(value: Any): List<BigInteger> = when (value) {
        is XVarInteger -> listOf(BigInteger.valueOf(assignment.getValue(value.id).toLong()))
        is Number -> listOf(BigInteger.valueOf(value.toLong()))
        else -> if (value.javaClass.isArray) {
            (0 until ReflectArray.getLength(value)).flatMap {
                integers(ReflectArray.get(value, it))
            }
        } else {
            decline("integer list ${value.javaClass.simpleName}")
        }
    }

    private fun integer(value: BigInteger, operation: String) {
        try {
            value.intValueExact()
        } catch (_: ArithmeticException) {
            throw ArithmeticException("$operation exceeds checker integer range")
        }
    }

    private fun checkTrees(value: Any?) {
        when (value) {
            is XNode<*> -> check(value, exact(value))
            is AbstractTuple -> decline("hybrid extension tuples")
            is Array<*> -> value.forEach { checkTrees(it) }
        }
    }

    private fun check(tree: XNode<*>, expected: BigInteger) {
        val scope = tree.vars().orEmpty()
        val values = scope.map { assignment.getValue(it.id()) }.toIntArray()
        val actual = BigInteger.valueOf(TreeEvaluator(tree).evaluate(values))
        if (actual != expected) throw ArithmeticException("expression differs from exact source arithmetic")
    }

    private fun exact(tree: XNode<*>): BigInteger {
        if (tree is XNodeLeaf<*>) {
            return when (tree.type.name) {
                "VAR" -> BigInteger.valueOf(assignment.getValue((tree.value as XVarInteger).id).toLong())
                "LONG" -> BigInteger.valueOf((tree.value as Number).toLong())
                else -> decline("expression ${tree.type}")
            }
        }
        val values = tree.sons.map { exact(it) }
        val value = when (tree.type.name) {
            "NEG" -> -values[0]
            "ABS" -> values[0].abs()
            "ADD" -> values.fold(BigInteger.ZERO, BigInteger::add)
            "SUB" -> values[0] - values[1]
            "MUL" -> values.fold(BigInteger.ONE, BigInteger::multiply)
            "SQR" -> values[0] * values[0]
            "DIV" -> values[0] / values[1]
            "MOD" -> values[0].remainder(values[1])
            "DIST" -> (values[0] - values[1]).abs()
            "MIN" -> values.min()
            "MAX" -> values.max()
            "LT" -> truth(values.zipWithNext().all { (a, b) -> a < b })
            "LE" -> truth(values.zipWithNext().all { (a, b) -> a <= b })
            "GT" -> truth(values.zipWithNext().all { (a, b) -> a > b })
            "GE" -> truth(values.zipWithNext().all { (a, b) -> a >= b })
            "EQ" -> truth(values.distinct().size == 1)
            "NE" -> truth(values.distinct().size == values.size)
            "NOT", "AND", "OR", "XOR", "IFF", "IMP", "IF" -> logical(tree.type.name, values)
            else -> decline("expression ${tree.type}")
        }
        if (value.bitLength() > 4096) decline("expression integer size")
        return value
    }

    private fun logical(operator: String, values: List<BigInteger>): BigInteger {
        val booleans = if (operator == "IF") values.take(1) else values
        if (booleans.any { it != BigInteger.ZERO && it != BigInteger.ONE }) decline("nonboolean logical operand")
        return when (operator) {
            "NOT" -> truth(values[0] == BigInteger.ZERO)
            "AND" -> truth(values.all { it == BigInteger.ONE })
            "OR" -> truth(values.any { it == BigInteger.ONE })
            "XOR" -> truth(values.count { it == BigInteger.ONE } % 2 == 1)
            "IFF" -> truth(values.distinct().size == 1)
            "IMP" -> truth(values[0] == BigInteger.ZERO || values[1] == BigInteger.ONE)
            else -> if (values[0] == BigInteger.ONE) values[1] else values[2]
        }
    }

    private fun truth(value: Boolean): BigInteger = if (value) BigInteger.ONE else BigInteger.ZERO

    private fun decline(surface: String): Nothing =
        throw UnsupportedOperationException("$surface is outside checker arithmetic auditing")
}
