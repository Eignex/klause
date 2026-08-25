package com.eignex.klause.formats.xcsp3

import com.eignex.klause.util.CharReader
import com.eignex.klause.util.CharSource
import com.eignex.klause.util.StringCharSource

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

    /** Whether this element's own [directText] holds a `%` placeholder. Cached: `substituteParams` runs
     *  once per `<args>` row on a shared group template, so a large `%`-free literal (an mdd
     *  `<transitions>` blob) would otherwise be re-scanned for `%` on every instantiation — O(text) per
     *  row. The tree is immutable after parsing, so the scan is done once per node. */
    private val directTextHasPercent: Boolean by lazy(LazyThreadSafetyMode.NONE) { '%' in directText }

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
        // A `%`-free text carries no placeholder, so reuse it verbatim rather than expanding it once per
        // `<args>` row — a group whose template holds a large literal (e.g. an mdd `<transitions>` blob)
        // would otherwise rescan megabytes of text on every instantiation. The `%` test is itself cached
        // ([directTextHasPercent]) so the scan is paid once per node, not once per instantiation.
        if (!directTextHasPercent) directText else expandParams(directText, tokens, usedIndices),
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

// If a `..%<digits>` range tail begins at [at] in [text], the index just past its last digit; else -1.
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

/**
 * Recursive-descent XML reader over a forward-only [CharReader]: it pulls characters through the reader
 * (`peek`/`advance`/`eof`), so a huge document is never held whole. [parseElement] materializes exactly
 * one bounded subtree (a var decl, one constraint, a group template, a single `<args>` row); the cursor
 * ([openRoot], [nextChildTag], [enterPeeked], [materializeChild]) walks the top level element by element
 * so the containers themselves are never materialized.
 */
internal class XmlReader(private val reader: CharReader) {
    private val openElements = ArrayDeque<String>()

    /** Parse an in-memory [String] — the path for tests, the DSL, and an already-decompressed blob. */
    constructor(src: String) : this(CharReader(StringCharSource(src)))

    /** Wrap a streamed [source] directly. */
    constructor(source: CharSource) : this(CharReader(source))

    fun parseDocument(): XmlElement {
        skipMisc()
        require(!reader.eof() && reader.peek() == '<'.code) { "expected root element" }
        return parseElement().also {
            skipMisc()
            require(reader.eof()) { "unexpected content after root element" }
        }
    }

    /** Skip the prolog/comments/doctype and read the root element's start tag, leaving the cursor inside
     *  it. The root's own tag and attributes are irrelevant to the top-level walk, so they are discarded. */
    fun openRoot() {
        skipMisc()
        require(!reader.eof() && reader.peek() == '<'.code) { "expected root element" }
        require(enterPeeked()) { "root element must not be empty" }
    }

    /** Advance to the next child of the currently open element: its tag name (cursor left at the child's
     *  `<`, nothing past it consumed) if a child starts, or `null` — having consumed the closing tag — if
     *  the current element ends. Whitespace, comments and CDATA between children are skipped. */
    fun nextChildTag(): String? {
        while (true) {
            skipWs()
            require(!reader.eof()) { "unterminated element (expected a child or closing tag)" }
            if (reader.peek() != '<'.code) error("unexpected text between child elements")
            when {
                matches("<!--") -> skipUntilAfter("-->")

                matches("<![CDATA[") -> skipUntilAfter("]]>")

                matches("<?") -> skipUntilAfter("?>")

                matches("</") -> {
                    reader.advance()
                    reader.advance()
                    val closeName = readName()
                    val openName = openElements.removeLastOrNull()
                        ?: throw IllegalArgumentException("unexpected closing tag </$closeName>")
                    require(closeName == openName) { "mismatched closing tag </$closeName> for <$openName>" }
                    skipWs()
                    expect('>')
                    return null
                }

                else -> return peekTagName()
            }
        }
    }

    /** Materialize the child [nextChildTag] just peeked (cursor at its `<`) into a bounded subtree. */
    fun materializeChild(): XmlElement = parseElement()

    /** Consume the start tag of the child [nextChildTag] just peeked (cursor at its `<`), leaving the
     *  cursor inside it; the tag name and attributes are discarded. Returns false for a self-closing
     *  (empty) element — already fully consumed — and true for one with a body. */
    fun enterPeeked(): Boolean {
        expect('<')
        val tag = readName()
        while (true) {
            skipWs()
            require(!reader.eof()) { "unterminated start tag" }
            val c = reader.peek()
            if (c == '/'.code && reader.peek(1) == '>'.code) {
                reader.advance()
                reader.advance()
                return false
            }
            if (c == '>'.code) {
                reader.advance()
                openElements.addLast(tag)
                return true
            }
            readName()
            skipWs()
            expect('=')
            skipWs()
            readAttrValue()
        }
    }

    // Skip prolog, comments, doctype, and whitespace.
    private fun skipMisc() {
        while (!reader.eof()) {
            when {
                reader.peek().toChar().isWhitespace() -> reader.advance()
                matches("<?") -> skipUntilAfter("?>")
                matches("<!--") -> skipUntilAfter("-->")
                matches("<!") -> skipUntilAfter(">")
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
            require(!reader.eof()) { "unterminated start tag <$tag" }
            val c = reader.peek()
            if (c == '/'.code && reader.peek(1) == '>'.code) {
                reader.advance()
                reader.advance()
                return XmlElement(tag, attrs, emptyList(), "")
            }
            if (c == '>'.code) {
                reader.advance()
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
            require(!reader.eof()) { "unterminated element <$tag>" }
            if (reader.peek() == '<'.code) {
                when {
                    matches("<!--") -> skipUntilAfter("-->")

                    matches("<?") -> skipUntilAfter("?>")

                    matches("<![CDATA[") -> {
                        repeat(CDATA_OPEN.length) { reader.advance() }
                        while (!reader.eof() && !matches("]]>")) appendCurrent(text)
                        require(!reader.eof()) { "unterminated CDATA" }
                        repeat(3) { reader.advance() }
                    }

                    matches("</") -> {
                        reader.advance()
                        reader.advance()
                        val closeName = readName()
                        require(closeName == tag) { "mismatched closing tag </$closeName> for <$tag>" }
                        skipWs()
                        expect('>')
                        break
                    }

                    else -> children.add(parseElement())
                }
            } else {
                val raw = StringBuilder()
                while (!reader.eof() && reader.peek() != '<'.code) appendCurrent(raw)
                text.append(decodeEntities(raw.toString()))
            }
        }
        return XmlElement(tag, attrs, children, text.toString())
    }

    private fun readName(): String {
        skipWs()
        val sb = StringBuilder()
        while (!reader.eof()) {
            val ch = reader.peek().toChar()
            if (ch.isWhitespace() || ch in NAME_STOP) break
            sb.append(ch)
            reader.advance()
        }
        require(sb.isNotEmpty()) { "expected a name" }
        return sb.toString()
    }

    // The tag name of the child at the cursor (which sits on `<`), read via lookahead without consuming —
    // so the subsequent [materializeChild]/[enterPeeked] still starts cleanly from the `<`.
    private fun peekTagName(): String {
        val sb = StringBuilder()
        var i = 1
        while (true) {
            val c = reader.peek(i)
            if (c < 0) break
            val ch = c.toChar()
            if (ch.isWhitespace() || ch in NAME_STOP) break
            sb.append(ch)
            i++
        }
        require(sb.isNotEmpty()) { "expected a tag name" }
        return sb.toString()
    }

    private fun readAttrValue(): String {
        val q = reader.peek()
        require(q == '"'.code || q == '\''.code) { "expected quoted attribute value" }
        reader.advance()
        val sb = StringBuilder()
        while (!reader.eof() && reader.peek() != q) appendCurrent(sb)
        require(!reader.eof()) { "unterminated attribute value" }
        reader.advance()
        return decodeEntities(sb.toString())
    }

    private fun expect(c: Char) {
        require(!reader.eof() && reader.peek() == c.code) { "expected '$c'" }
        reader.advance()
    }

    private fun skipWs() {
        while (!reader.eof() && reader.peek().toChar().isWhitespace()) reader.advance()
    }

    // True if the next characters spell [lit] (lookahead only, cursor unmoved).
    private fun matches(lit: String): Boolean {
        for (i in lit.indices) if (reader.peek(i) != lit[i].code) return false
        return true
    }

    // Advance until the cursor spells [lit], then consume it; used to skip over comments/PIs/doctype.
    private fun skipUntilAfter(lit: String) {
        while (!reader.eof()) {
            if (matches(lit)) {
                repeat(lit.length) { reader.advance() }
                return
            }
            reader.advance()
        }
        throw IllegalArgumentException("unterminated '$lit'")
    }

    // Append the character under the cursor and consume it — the streaming analogue of a substring capture.
    private fun appendCurrent(sb: StringBuilder) {
        sb.append(reader.peek().toChar())
        reader.advance()
    }

    private fun decodeEntities(text: String): String {
        val first = text.indexOf('&')
        if (first < 0) return text
        val out = StringBuilder(text.length)
        var from = 0
        var amp = first
        while (amp >= 0) {
            out.append(text, from, amp)
            val semi = text.indexOf(';', amp + 1)
            require(semi >= 0) { "unterminated entity reference" }
            val entity = text.substring(amp + 1, semi)
            out.append(
                when (entity) {
                    "lt" -> '<'
                    "gt" -> '>'
                    "quot" -> '"'
                    "apos" -> '\''
                    "amp" -> '&'
                    else -> decodeNumericEntity(entity)
                },
            )
            from = semi + 1
            amp = text.indexOf('&', from)
        }
        out.append(text, from, text.length)
        return out.toString()
    }

    private fun decodeNumericEntity(entity: String): Char {
        val value = when {
            entity.startsWith("#x") -> entity.substring(2).toIntOrNull(16)
            entity.startsWith('#') -> entity.substring(1).toIntOrNull()
            else -> null
        } ?: throw IllegalArgumentException("unknown entity '&$entity;'")
        require(value in 0..0xffff && value !in 0xd800..0xdfff) { "invalid character entity '&$entity;'" }
        return value.toChar()
    }

    private companion object {
        private const val CDATA_OPEN = "<![CDATA["
        private const val NAME_STOP = "=/><"
    }
}
