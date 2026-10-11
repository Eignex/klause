package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.Reports
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.encodeToString
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xcsp.common.domains.Domains.Dom
import org.xcsp.parser.XParser
import org.xcsp.parser.callbacks.SolutionChecker
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
        val names = xmlText(root.getElementsByTagName("list").item(0).textContent)
        val values = xmlText(root.getElementsByTagName("values").item(0).textContent)
        val pinned = "<instantiation$cost><list>$names</list><values>$values</values></instantiation>"
        return runCatching {
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
