package com.eignex.klause.formats.xcsp3

/**
 * A minimal, multiplatform XML element tree — enough for the XCSP3-core subset. Holds the
 * tag, attributes, child elements, and the text directly under this element; [textContent]
 * mirrors the DOM notion (all descendant text concatenated).
 *
 * This is deliberately *not* a general XML implementation: it understands elements,
 * attributes (single- or double-quoted), text, comments, CDATA, the `<?xml…?>` prolog and a
 * `<!DOCTYPE…>` declaration, plus the five predefined entities. XCSP3 instances stay within
 * that subset; anything richer raises during parse.
 */
class XmlElement(
    /** The element's tag name. */
    val tag: String,
    /** Attributes by name. */
    val attributes: Map<String, String>,
    /** Direct child elements. */
    val children: List<XmlElement>,
    private val directText: String,
) {
    val textContent: String
        get() = if (children.isEmpty()) {
            directText
        } else {
            directText + children.joinToString("") { it.textContent }
        }

    /** Value of attribute [name], or empty string if absent. */
    fun attr(name: String): String = attributes[name] ?: ""

    /** First direct child with the given [tag], or null. */
    fun child(tag: String): XmlElement? = children.firstOrNull { it.tag == tag }

    /**
     * Return a copy of this subtree with XCSP3 parameter placeholders substituted: `%i` →
     * `tokens[i]`, and `%...` → all [tokens] space-joined. Used to instantiate a `<group>`
     * template constraint against each `<args>` row.
     */
    fun substituteParams(tokens: List<String>): XmlElement = XmlElement(
        tag,
        attributes,
        children.map { it.substituteParams(tokens) },
        PARAM.replace(directText) { m ->
            val g = m.groupValues[1]
            if (g == "...") tokens.joinToString(" ") else tokens.getOrNull(g.toInt()) ?: m.value
        },
    )

    private companion object {
        val PARAM = Regex("""%(\.\.\.|\d+)""")
    }
}

/** Parse a single XML document, returning its root element. */
fun parseXml(src: String): XmlElement = XmlReader(src).parseDocument()

private class XmlReader(private val s: String) {
    private var pos = 0

    fun parseDocument(): XmlElement {
        skipMisc()
        require(pos < s.length && s[pos] == '<') { "expected root element at $pos" }
        return parseElement()
    }

    /** Skip whitespace, the `<?xml…?>` prolog, comments and a `<!DOCTYPE…>` declaration. */
    private fun skipMisc() {
        while (pos < s.length) {
            when {
                s[pos].isWhitespace() -> pos++

                s.startsWith(
                    "<?",
                    pos,
                ) -> pos = s.indexOf("?>", pos).also { require(it >= 0) { "unterminated <? ?>" } } + 2

                s.startsWith(
                    "<!--",
                    pos,
                ) -> pos = s.indexOf("-->", pos).also { require(it >= 0) { "unterminated comment" } } + 3

                s.startsWith(
                    "<!",
                    pos,
                ) -> pos = s.indexOf('>', pos).also { require(it >= 0) { "unterminated <! >" } } + 1

                else -> return
            }
        }
    }

    private fun parseElement(): XmlElement {
        expect('<')
        val tag = readName()
        val attrs = LinkedHashMap<String, String>()
        while (true) {
            skipWs()
            require(pos < s.length) { "unterminated start tag <$tag" }
            val c = s[pos]
            if (c == '/' && pos + 1 < s.length && s[pos + 1] == '>') {
                pos += 2
                return XmlElement(tag, attrs, emptyList(), "")
            }
            if (c == '>') {
                pos++
                break
            }
            val an = readName()
            skipWs()
            expect('=')
            skipWs()
            attrs[an] = readAttrValue()
        }
        // content
        val text = StringBuilder()
        val children = ArrayList<XmlElement>()
        while (true) {
            require(pos < s.length) { "unterminated element <$tag>" }
            if (s[pos] == '<') {
                when {
                    s.startsWith("<!--", pos) -> pos = s.indexOf("-->", pos).also { require(it >= 0) } + 3

                    s.startsWith("<![CDATA[", pos) -> {
                        val end = s.indexOf("]]>", pos)
                        require(end >= 0) { "unterminated CDATA" }
                        text.append(s, pos + 9, end)
                        pos = end + 3
                    }

                    s.startsWith("</", pos) -> {
                        pos += 2
                        readName()
                        skipWs()
                        expect('>')
                        break
                    }

                    else -> children.add(parseElement())
                }
            } else {
                val start = pos
                while (pos < s.length && s[pos] != '<') pos++
                text.append(decodeEntities(s.substring(start, pos)))
            }
        }
        return XmlElement(tag, attrs, children, text.toString())
    }

    private fun readName(): String {
        skipWs()
        val start = pos
        while (pos < s.length && !s[pos].isWhitespace() && s[pos] !in "=/><") pos++
        require(pos > start) { "expected a name at $pos" }
        return s.substring(start, pos)
    }

    private fun readAttrValue(): String {
        val q = s[pos]
        require(q == '"' || q == '\'') { "expected quoted attribute value at $pos" }
        pos++
        val start = pos
        while (pos < s.length && s[pos] != q) pos++
        require(pos < s.length) { "unterminated attribute value" }
        val raw = s.substring(start, pos)
        pos++
        return decodeEntities(raw)
    }

    private fun expect(c: Char) {
        require(pos < s.length && s[pos] == c) { "expected '$c' at $pos" }
        pos++
    }
    private fun skipWs() {
        while (pos < s.length && s[pos].isWhitespace()) pos++
    }

    private fun decodeEntities(t: String): String {
        if ('&' !in t) return t
        return t.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")
    }
}
