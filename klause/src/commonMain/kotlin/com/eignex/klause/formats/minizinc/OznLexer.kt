package com.eignex.klause.formats.minizinc

/** Tokenizer for the MiniZinc syntax subset used in `.ozn` files. */
internal class OznLexer(private val source: String) {
    private var pos: Int = 0

    // Line tracking. `pos` only advances, so counting newlines forward from the last scanned offset
    // makes [lineAt] O(1) amortized; recounting from offset 0 per token would be O(n²) over a large
    // `.ozn`.
    private var lineNo: Int = 1
    private var lineScanned: Int = 0
    private fun lineAt(): Int {
        while (lineScanned < pos) {
            if (source[lineScanned] == '\n') lineNo++
            lineScanned++
        }
        return lineNo
    }

    fun tokenize(): List<OznToken> {
        val out = ArrayList<OznToken>()
        while (pos < source.length) {
            skipWhitespaceAndComments()
            if (pos >= source.length) break
            out.add(nextToken())
        }
        out.add(OznToken(OznTokenKind.EOF, "", lineAt()))
        return out
    }

    private fun skipWhitespaceAndComments() {
        while (pos < source.length) {
            val c = source[pos]
            when {
                c.isWhitespace() -> pos++

                c == '%' -> {
                    while (pos < source.length && source[pos] != '\n') pos++
                }

                c == '/' && pos + 1 < source.length && source[pos + 1] == '*' -> {
                    val commentLine = lineAt()
                    pos += 2
                    while (pos + 1 < source.length && !(source[pos] == '*' && source[pos + 1] == '/')) pos++
                    if (pos + 1 >= source.length) {
                        throw OznParseException("unterminated block comment at line $commentLine")
                    }
                    pos += 2
                }

                else -> return
            }
        }
    }

    private fun nextToken(): OznToken {
        val ln = lineAt()
        val c = source[pos]
        return when {
            c == '"' -> stringLit(ln)
            c.isDigit() -> numberOrRange(ln)
            c.isLetter() || c == '_' -> identOrKeyword(ln)
            else -> punct(ln)
        }
    }

    private fun stringLit(ln: Int): OznToken {
        require(source[pos] == '"')
        pos++
        val sb = StringBuilder()
        while (pos < source.length && source[pos] != '"') {
            val c = source[pos]
            if (c == '\n') throw OznParseException("unterminated string literal at line $ln")
            if (c == '\\' && pos + 1 < source.length) {
                sb.append(
                    when (val n = source[pos + 1]) {
                        'n' -> '\n'
                        't' -> '\t'
                        'r' -> '\r'
                        '\\' -> '\\'
                        '"' -> '"'
                        '\'' -> '\''
                        else -> n
                    },
                )
                pos += 2
            } else {
                sb.append(c)
                pos++
            }
        }
        if (pos >= source.length) throw OznParseException("unterminated string literal at line $ln")
        pos++ // consume the closing quote
        return OznToken(OznTokenKind.STRING, sb.toString(), ln)
    }

    private fun numberOrRange(ln: Int): OznToken {
        val start = pos
        while (pos < source.length && source[pos].isDigit()) pos++
        var isFloat = false
        if (pos < source.length && source[pos] == '.' &&
            pos + 1 < source.length && source[pos + 1].isDigit()
        ) {
            isFloat = true
            pos++ // dot
            while (pos < source.length && source[pos].isDigit()) pos++
        }
        // Optional exponent `e`/`E` with an optional sign and at least one digit; a trailing `e` with no
        // following digit is left for the next token (a `..` range endpoint never carries an exponent).
        if (pos < source.length && (source[pos] == 'e' || source[pos] == 'E')) {
            var k = pos + 1
            if (k < source.length && (source[k] == '+' || source[k] == '-')) k++
            if (k < source.length && source[k].isDigit()) {
                isFloat = true
                pos = k
                while (pos < source.length && source[pos].isDigit()) pos++
            }
        }
        val kind = if (isFloat) OznTokenKind.FLOAT else OznTokenKind.INT
        return OznToken(kind, source.substring(start, pos), ln)
    }

    private fun identOrKeyword(ln: Int): OznToken {
        val start = pos
        while (pos < source.length && (source[pos].isLetterOrDigit() || source[pos] == '_')) pos++
        val text = source.substring(start, pos)
        val kind = when (text) {
            "true", "false" -> OznTokenKind.BOOL

            "output", "array", "set", "of", "var", "int", "bool", "float", "string",
            "if", "then", "elseif", "else", "endif", "in", "where", "let", "not", "xor",
            "div", "mod",
            "show", "show2d", "show3d", "show_int", "show_float", "fix",
            "array1d", "array2d", "array3d", "array4d", "array5d", "array6d",
            "min", "max", "abs", "sum", "product",
            "bool2int", "int2float",
            ->
                OznTokenKind.KEYWORD

            else -> OznTokenKind.IDENT
        }
        return OznToken(kind, text, ln)
    }

    private fun punct(ln: Int): OznToken {
        val c = source[pos]
        if (pos + 1 < source.length) {
            val two = when (val pair = source.substring(pos, pos + 2)) {
                "..", "==", "!=", "<=", ">=", "->", "<-", "++", "/\\", "\\/", "::" -> pair
                else -> null
            }
            if (two != null) {
                pos += 2
                return OznToken(OznTokenKind.PUNCT, two, ln)
            }
        }
        if (c !in SINGLE_PUNCT) throw OznParseException("unexpected character `$c` at line $ln")
        pos++
        return OznToken(OznTokenKind.PUNCT, c.toString(), ln)
    }
}

/** Single punctuation characters valid in the `.ozn` subset (two-character operators are matched
 *  before this fallthrough). Anything else is rejected rather than emitted as a stray token. */
private val SINGLE_PUNCT: Set<Char> = "()[]{},:;=+-*/<>!.|".toSet()

internal enum class OznTokenKind {
    IDENT,
    KEYWORD,
    INT,
    FLOAT,
    STRING,
    BOOL,
    PUNCT,
    EOF,
}

internal data class OznToken(val kind: OznTokenKind, val text: String, val line: Int)
