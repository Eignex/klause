package com.eignex.klause.bench.format.xcsp3

import com.eignex.klause.bench.format.Ingested
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.Table
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Raised when an XCSP3 construct outside the supported subset is encountered. */
class UnsupportedXcsp3Exception(msg: String) : RuntimeException("klause XCSP3: $msg")

/**
 * Pragmatic XCSP3 ingest → klause [Problem]. Covers the common integer CSP/COP core:
 *
 *  - `<var>` and 1-D `<array>` integer declarations with range / value-list domains;
 *  - `<allDifferent>`, `<sum>` (list + coeffs + condition), `<extension>` (supports),
 *    and `<intension>` binary relations over `var / int / add / sub / mul`(by constant);
 *  - `<objectives>` `minimize` / `maximize` of a (possibly weighted) variable sum.
 *
 * Out-of-subset features (set vars, n-D arrays, global constraints beyond allDifferent,
 * conflict tables, nonlinear intension) raise [UnsupportedXcsp3Exception] rather than
 * silently producing a wrong model.
 */
object Xcsp3 {
    fun parse(text: String): Ingested = Builder().run {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
            .newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray()))
        doc.documentElement.normalize()
        val root = doc.documentElement
        child(root, "variables")?.let { vs -> elements(vs).forEach { declareVar(it) } }
        child(root, "constraints")?.let { cs -> elements(cs).forEach { constraint(it) } }
        child(root, "objectives")?.let { objs -> elements(objs).firstOrNull()?.let { objective(it) } }
        build()
    }

    private class Builder {
        private val varIds = LinkedHashMap<String, Int>()    // resolved name (incl. array cells) -> int var id
        private val domains = ArrayList<IntDomain>()
        private val factors = ArrayList<Factor>()
        private var objective: LinearObjective? = null

        // --- variables ---

        fun declareVar(e: Element) {
            when (e.tagName) {
                "var" -> addVar(e.getAttribute("id"), parseDomain(e.textContent.trim()))
                "array" -> {
                    val id = e.getAttribute("id")
                    val size = Regex("""\[(\d+)]""").find(e.getAttribute("size"))?.groupValues?.get(1)?.toInt()
                        ?: throw UnsupportedXcsp3Exception("only 1-D arrays supported: ${e.getAttribute("size")}")
                    val dom = parseDomain(e.textContent.trim())
                    for (i in 0 until size) addVar("$id[$i]", dom)
                }
                else -> throw UnsupportedXcsp3Exception("variable kind '${e.tagName}'")
            }
        }

        private fun addVar(name: String, dom: IntDomain) { varIds[name] = domains.size; domains.add(dom) }

        private fun parseDomain(text: String): IntDomain {
            // "1..4", "0 2 4", or unions "1..3 5..6" — represent as [min,max] minus holes.
            val values = sortedSetOf<Int>()
            for (tok in text.split(Regex("\\s+")).filter { it.isNotBlank() }) {
                val r = tok.split("..")
                if (r.size == 2) (r[0].toInt()..r[1].toInt()).forEach { values.add(it) } else values.add(tok.toInt())
            }
            if (values.isEmpty()) throw UnsupportedXcsp3Exception("empty domain")
            var dom = IntDomain(values.first(), values.last())
            for (v in values.first()..values.last()) if (v !in values) dom = dom.excludeValue(v)
            return dom
        }

        // --- constraints ---

        fun constraint(e: Element) {
            when (e.tagName) {
                "allDifferent" -> allDifferent(e)
                "sum" -> sum(e)
                "extension" -> extension(e)
                "intension" -> intension(e.textContent.trim())
                "group", "block" -> elements(e).forEach { constraint(it) }
                else -> throw UnsupportedXcsp3Exception("constraint '${e.tagName}'")
            }
        }

        private fun allDifferent(e: Element) {
            val vars = refList(listText(e)).toIntArray()
            val min = vars.minOf { domains[it].min }
            val max = vars.maxOf { domains[it].max }
            factors.add(AllDifferent(vars = vars, domainMin = min, domainSize = max - min + 1))
        }

        private fun sum(e: Element) {
            val vars = refList(child(e, "list")!!.textContent).toIntArray()
            val coeffs = child(e, "coeffs")?.textContent?.trim()?.split(Regex("\\s+"))
                ?.filter { it.isNotBlank() }?.map { it.toInt() }?.toIntArray()
                ?: IntArray(vars.size) { 1 }
            val (op, k) = condition(child(e, "condition")!!.textContent.trim())
            postLinear(vars, coeffs, op, k)
        }

        private fun extension(e: Element) {
            val vars = refList(child(e, "list")!!.textContent).toIntArray()
            if (child(e, "conflicts") != null) throw UnsupportedXcsp3Exception("conflict tables not supported")
            val sup = child(e, "supports")?.textContent?.trim() ?: throw UnsupportedXcsp3Exception("extension without supports")
            val tuples = ArrayList<Int>()
            for (m in Regex("""\(([^)]*)\)""").findAll(sup)) {
                val row = m.groupValues[1].split(",").map { it.trim().toInt() }
                if (row.size != vars.size) throw UnsupportedXcsp3Exception("tuple arity ${row.size} != ${vars.size}")
                tuples.addAll(row)
            }
            factors.add(Table(xs = vars, tuples = tuples.toIntArray()))
        }

        /** Intension over the functional form, supported subset: `rel(lhs, rhs)` with rel in
         *  {eq,ne,le,lt,ge,gt} and lhs/rhs built from var/int/add/sub/mul(const,·). */
        private fun intension(expr: String) {
            val node = FExpr.parse(expr)
            require(node is FExpr.Call && node.fn in REL) { throw UnsupportedXcsp3Exception("intension '$expr'") }
            val op = when (node.fn) {
                "le" -> LinearOp.LE; "ge" -> LinearOp.GE; "eq" -> LinearOp.EQ; "ne" -> LinearOp.NE
                "lt" -> LinearOp.LE; "gt" -> LinearOp.GE; else -> throw UnsupportedXcsp3Exception(node.fn)
            }
            val lhs = linear(node.args[0]); val rhs = linear(node.args[1])
            val combined = HashMap(lhs.coeffs)
            for ((v, c) in rhs.coeffs) combined[v] = (combined[v] ?: 0) - c
            combined.entries.removeAll { it.value == 0 }
            var bound = rhs.constant - lhs.constant
            if (node.fn == "lt") bound -= 1
            if (node.fn == "gt") bound += 1
            val vars = combined.keys.toIntArray()
            factors.add(Linear(IntArray(vars.size) { combined[vars[it]]!! }, vars, op, bound))
        }

        private fun postLinear(vars: IntArray, coeffs: IntArray, op: LinearOp, k: Int) {
            factors.add(Linear(coeffs, vars, op, k))
        }

        private fun condition(text: String): Pair<LinearOp, Int> {
            // "(le,10)" / "(eq,3)"
            val m = Regex("""\(\s*(\w+)\s*,\s*(-?\d+)\s*\)""").find(text)
                ?: throw UnsupportedXcsp3Exception("condition '$text' (only (op,const) supported)")
            val k = m.groupValues[2].toInt()
            return when (m.groupValues[1]) {
                "le" -> LinearOp.LE to k; "ge" -> LinearOp.GE to k; "eq" -> LinearOp.EQ to k
                "ne" -> LinearOp.NE to k; "lt" -> LinearOp.LE to (k - 1); "gt" -> LinearOp.GE to (k + 1)
                else -> throw UnsupportedXcsp3Exception("condition op '${m.groupValues[1]}'")
            }
        }

        // --- objective ---

        fun objective(e: Element) {
            val negate = e.tagName == "maximize"
            val vars = refList(child(e, "list")?.textContent ?: e.textContent)
            val coeffs = child(e, "coeffs")?.textContent?.trim()?.split(Regex("\\s+"))
                ?.filter { it.isNotBlank() }?.map { it.toInt() } ?: List(vars.size) { 1 }
            val arr = DoubleArray(domains.size)
            vars.forEachIndexed { i, v -> arr[v] = (if (negate) -coeffs[i] else coeffs[i]).toDouble() }
            objective = LinearObjective(intCoefficients = arr)
        }

        // --- helpers ---

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
                    val consts = parts.filter { it.coeffs.isEmpty() }
                    val nonConst = parts.filter { it.coeffs.isNotEmpty() }
                    if (nonConst.size > 1) throw UnsupportedXcsp3Exception("nonlinear mul")
                    val k = consts.fold(1) { a, c -> a * c.constant }
                    if (nonConst.isEmpty()) Lin(emptyMap(), k) else scaleLin(nonConst[0], k)
                }
                else -> throw UnsupportedXcsp3Exception("arithmetic fn '${e.fn}'")
            }
        }

        private fun addLin(a: Lin, b: Lin): Lin {
            val m = HashMap(a.coeffs); for ((v, c) in b.coeffs) m[v] = (m[v] ?: 0) + c
            return Lin(m, a.constant + b.constant)
        }
        private fun scaleLin(a: Lin, k: Int) = Lin(a.coeffs.mapValues { it.value * k }, a.constant * k)

        private fun listText(e: Element): String = child(e, "list")?.textContent ?: e.textContent
        private fun refList(text: String): List<Int> =
            text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.flatMap { expandRef(it) }

        /** Resolve a token to var ids, expanding `q[]` to the whole array. */
        private fun expandRef(tok: String): List<Int> {
            if (tok.endsWith("[]")) {
                val base = tok.dropLast(2)
                return varIds.keys.filter { it.startsWith("$base[") }.map { varIds[it]!! }
            }
            return listOf(ref(tok))
        }
        private fun ref(name: String): Int = varIds[name] ?: throw UnsupportedXcsp3Exception("unknown variable '$name'")

        fun build(): Ingested = Ingested(
            Problem(numBoolVars = 0, numIntVars = domains.size, intDomains = domains.toTypedArray(), factors = factors.toTypedArray()),
            objective,
        )

        companion object { private val REL = setOf("eq", "ne", "le", "lt", "ge", "gt") }
    }
}

/** XML DOM helpers shared by the parser. */
private fun child(e: Element, tag: String): Element? =
    elements(e).firstOrNull { it.tagName == tag }

private fun elements(e: Element): List<Element> {
    val out = ArrayList<Element>()
    val kids = e.childNodes
    for (i in 0 until kids.length) (kids.item(i) as? Element)?.let { if (it.nodeType == Node.ELEMENT_NODE) out.add(it) }
    return out
}
