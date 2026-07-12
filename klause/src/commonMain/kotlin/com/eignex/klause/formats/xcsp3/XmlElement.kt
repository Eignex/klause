package com.eignex.klause.formats.xcsp3

/** Minimal XML element tree for XCSP3 parsing. */
class XmlElement(
    /** Element tag name. */
    val tag: String,
    /** Attributes by name. */
    val attributes: Map<String, String>,
    /** Direct child elements. */
    val children: List<XmlElement>,
    private val directText: String,
) {
    /** Concatenated text under this element and its descendants. */
    val textContent: String
        get() = if (children.isEmpty()) {
            directText
        } else {
            directText + children.joinToString("") { it.textContent }
        }

    /** Attribute [name], or empty string if absent. */
    fun attr(name: String): String = attributes[name].orEmpty()

    /** First direct child with [tag], or null. */
    fun child(tag: String): XmlElement? = children.firstOrNull { it.tag == tag }

    /** Substitute `%i`, `%i..%j` (parameter range) and `%...` placeholders for group-template
     *  instantiation. Per the XCSP3 convention, `%...` denotes the parameters **not** explicitly
     *  referenced elsewhere in the template ([usedIndices]); with none referenced it is all of them. */
    fun substituteParams(tokens: List<String>, usedIndices: Set<Int> = emptySet()): XmlElement = XmlElement(
        tag,
        attributes,
        children.map { it.substituteParams(tokens, usedIndices) },
        // A `%`-free text carries no placeholder, so reuse it verbatim rather than scanning it with the
        // parameter regex once per `<args>` row — a group whose template holds a large literal (e.g. an
        // mdd `<transitions>` blob) would otherwise re-match megabytes of text on every instantiation.
        if ('%' !in directText) {
            directText
        } else {
            PARAM.replace(directText) { m ->
                when {
                    m.groupValues[1].isNotEmpty() -> { // %i..%j
                        val lo = m.groupValues[1].toInt()
                        val hi = m.groupValues[2].toInt()
                        (lo..hi).mapNotNull { tokens.getOrNull(it) }.joinToString(" ")
                    }

                    m.groupValues[3] == "..." ->
                        tokens.filterIndexed { i, _ -> i !in usedIndices }.joinToString(" ")

                    else -> tokens.getOrNull(m.groupValues[3].toInt()) ?: m.value
                }
            }
        },
    )

    /** Parameter indices explicitly referenced (`%i` / `%i..%j`) in this element's full text —
     *  the complement of what `%...` expands to. */
    fun explicitParamIndices(): Set<Int> = buildSet {
        for (m in EXPLICIT_PARAM.findAll(textContent)) {
            val lo = m.groupValues[1].toInt()
            val hi = m.groupValues[2].toIntOrNull() ?: lo
            for (i in lo..hi) add(i)
        }
    }

    private companion object {
        val PARAM = Regex("""%(\d+)\.\.%(\d+)|%(\.\.\.|\d+)""")
        val EXPLICIT_PARAM = Regex("""%(\d+)(?:\.\.%(\d+))?""")
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

    /** Skip prolog, comments, doctype, and whitespace. */
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
