package com.eignex.klause.formats.minizinc

/**
 * Tokenizer for the subset of MiniZinc syntax that appears in `.ozn` files. MiniZinc
 * emits a restricted form into `.ozn`: top-level variable / parameter declarations and
 * a single `output [ ... ];` item. Inside `output`, expressions can include string
 * literals, calls (`show`, `show2d`, `array1d`, …), array literals, ranges, identifiers,
 * arithmetic, comparisons, conditionals, and comprehensions — broadly the same
 * expression sublanguage [com.eignex.klause.formats.flatzinc.FlatZincLexer] handles, but
 * with strings, comprehensions, and `if-then-else` added back.
 *
 * Whitespace and `%`-prefixed line comments are skipped. String literals support the
 * standard MZN escapes (`\n`, `\t`, `\\`, `\"`).
 */
internal class OznLexer(private val source: String) {
    private var pos: Int = 0
    private val line: Int get() = source.substring(0, pos).count { it == '\n' } + 1

    fun tokenize(): List<OznToken> {
        val out = ArrayList<OznToken>()
        while (pos < source.length) {
            skipWhitespaceAndComments()
            if (pos >= source.length) break
            out.add(nextToken())
        }
        out.add(OznToken(OznTokenKind.EOF, "", line))
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
                    pos += 2
                    while (pos + 1 < source.length && !(source[pos] == '*' && source[pos + 1] == '/')) pos++
                    if (pos + 1 < source.length) pos += 2
                }
                else -> return
            }
        }
    }

    private fun nextToken(): OznToken {
        val ln = line
        val c = source[pos]
        return when {
            c == '"' -> stringLit(ln)
            c.isDigit() || (c == '-' && pos + 1 < source.length && source[pos + 1].isDigit()) -> numberOrRange(ln)
            c.isLetter() || c == '_' -> identOrKeyword(ln)
            else -> punct(ln)
        }
    }

    private fun stringLit(ln: Int): OznToken {
        require(source[pos] == '"')
        pos++  // skip opening quote
        val sb = StringBuilder()
        while (pos < source.length && source[pos] != '"') {
            val c = source[pos]
            if (c == '\\' && pos + 1 < source.length) {
                sb.append(when (val n = source[pos + 1]) {
                    'n' -> '\n'
                    't' -> '\t'
                    'r' -> '\r'
                    '\\' -> '\\'
                    '"' -> '"'
                    '\'' -> '\''
                    else -> n
                })
                pos += 2
            } else {
                sb.append(c)
                pos++
            }
        }
        if (pos < source.length) pos++  // skip closing quote
        return OznToken(OznTokenKind.STRING, sb.toString(), ln)
    }

    private fun numberOrRange(ln: Int): OznToken {
        val start = pos
        if (source[pos] == '-') pos++
        while (pos < source.length && source[pos].isDigit()) pos++
        // Float?
        if (pos < source.length && source[pos] == '.' &&
            pos + 1 < source.length && source[pos + 1].isDigit()) {
            pos++  // dot
            while (pos < source.length && source[pos].isDigit()) pos++
            return OznToken(OznTokenKind.FLOAT, source.substring(start, pos), ln)
        }
        return OznToken(OznTokenKind.INT, source.substring(start, pos), ln)
    }

    private fun identOrKeyword(ln: Int): OznToken {
        val start = pos
        while (pos < source.length && (source[pos].isLetterOrDigit() || source[pos] == '_')) pos++
        val text = source.substring(start, pos)
        val kind = when (text) {
            "true", "false" -> OznTokenKind.BOOL
            "output", "array", "set", "of", "var", "int", "bool", "float", "string",
            "if", "then", "elseif", "else", "endif", "in", "where", "let",
            "show", "show2d", "show3d", "show_int", "show_float", "fix",
            "array1d", "array2d", "array3d", "array4d", "array5d", "array6d",
            "min", "max", "abs", "sum", "product",
            "bool2int", "int2float" ->
                OznTokenKind.KEYWORD
            else -> OznTokenKind.IDENT
        }
        return OznToken(kind, text, ln)
    }

    private fun punct(ln: Int): OznToken {
        val c = source[pos]
        // Two-char operators first.
        if (pos + 1 < source.length) {
            val pair = source.substring(pos, pos + 2)
            val two = when (pair) {
                "..", "==", "!=", "<=", ">=", "->", "<-", "++", "/\\", "\\/", "::" -> pair
                else -> null
            }
            if (two != null) {
                pos += 2
                return OznToken(OznTokenKind.PUNCT, two, ln)
            }
        }
        val text = c.toString()
        pos++
        return OznToken(OznTokenKind.PUNCT, text, ln)
    }
}

internal enum class OznTokenKind {
    IDENT, KEYWORD, INT, FLOAT, STRING, BOOL, PUNCT, EOF,
}

internal data class OznToken(val kind: OznTokenKind, val text: String, val line: Int)
