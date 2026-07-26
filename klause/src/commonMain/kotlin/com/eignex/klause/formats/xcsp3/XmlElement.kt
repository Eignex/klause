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
    /** Concatenated text under this element and its descendants. Cached: the tree is immutable after
     *  parsing, and recomputing rebuilt every descendant's text on each access — quadratic on a deep
     *  element read repeatedly. */
    val textContent: String by lazy(LazyThreadSafetyMode.NONE) {
        if (children.isEmpty()) directText else directText + children.joinToString("") { it.textContent }
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
        if ('%' !in directText) directText else expandParams(directText, tokens, usedIndices),
    )

    /** Parameter indices explicitly referenced (`%i` / `%i..%j`) in this element's full text —
     *  the complement of what `%...` expands to. */
    fun explicitParamIndices(): Set<Int> = buildSet { collectExplicitParams(textContent, this) }
}

/** Substitute the group-template placeholders in [text]: `%i` → `tokens[i]`, `%i..%j` → the space-joined
 *  token range, `%...` → the tokens whose index is not in [usedIndices]. A lone `%` (not followed by
 *  digits or `...`) and an out-of-range `%i` are left verbatim. Hand-scanned — no regex per instantiation. */
private fun expandParams(text: String, tokens: List<String>, usedIndices: Set<Int>): String {
    val sb = StringBuilder(text.length)
    var i = 0
    val n = text.length
    while (i < n) {
        val c = text[i]
        if (c != '%') {
            sb.append(c)
            i++
            continue
        }
        if (text.startsWith("...", i + 1)) {
            appendJoined(sb, tokens.indices.filter { it !in usedIndices }, tokens)
            i += 4
            continue
        }
        var j = i + 1
        while (j < n && text[j].isDigit()) j++
        if (j == i + 1) { // a bare '%'
            sb.append('%')
            i++
            continue
        }
        val lo = text.substring(i + 1, j).toInt()
        val rangeEnd = rangeUpperEnd(text, j)
        if (rangeEnd > 0) {
            val hi = text.substring(j + 3, rangeEnd).toInt()
            appendJoined(sb, (lo..hi).toList(), tokens)
            i = rangeEnd
            continue
        }
        val tok = tokens.getOrNull(lo)
        if (tok != null) sb.append(tok) else sb.append(text, i, j)
        i = j
    }
    return sb.toString()
}

/** If a `..%<digits>` range tail begins at [at] in [text], the index just past its last digit; else -1. */
private fun rangeUpperEnd(text: String, at: Int): Int {
    if (!text.startsWith("..%", at)) return -1
    var k = at + 3
    while (k < text.length && text[k].isDigit()) k++
    return if (k > at + 3) k else -1
}

private fun appendJoined(sb: StringBuilder, indices: List<Int>, tokens: List<String>) {
    var first = true
    for (idx in indices) {
        val t = tokens.getOrNull(idx) ?: continue
        if (!first) sb.append(' ')
        sb.append(t)
        first = false
    }
}

/** Collect every explicitly-referenced parameter index (`%i` / `%i..%j`) in [text] into [into]; a
 *  `%...` and a lone `%` reference nothing. */
private fun collectExplicitParams(text: String, into: MutableSet<Int>) {
    var i = 0
    val n = text.length
    while (i < n) {
        if (text[i] != '%') {
            i++
            continue
        }
        var j = i + 1
        while (j < n && text[j].isDigit()) j++
        if (j == i + 1) {
            i++
            continue
        }
        val lo = text.substring(i + 1, j).toInt()
        val rangeEnd = rangeUpperEnd(text, j)
        if (rangeEnd > 0) {
            val hi = text.substring(j + 3, rangeEnd).toInt()
            for (x in lo..hi) into.add(x)
            i = rangeEnd
        } else {
            into.add(lo)
            i = j
        }
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
                        val closeName = readName()
                        require(closeName == tag) { "mismatched closing tag </$closeName> for <$tag>" }
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
