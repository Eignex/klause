package com.eignex.klause.formats.flatzinc

/** One lexical token from a FlatZinc source. */
internal sealed interface FznToken {
    val line: Int
    val col: Int

    /** Keywords get their own subtype for cleaner parser pattern matching. */
    data class Kw(val keyword: String, override val line: Int, override val col: Int) : FznToken
    data class Ident(val name: String, override val line: Int, override val col: Int) : FznToken
    data class IntLit(val value: Long, override val line: Int, override val col: Int) : FznToken
    data class FloatLit(val value: Double, override val line: Int, override val col: Int) : FznToken
    data class StringLit(val value: String, override val line: Int, override val col: Int) : FznToken

    /** Punctuation: `,`, `;`, `:`, `=`, `(`, `)`, `[`, `]`, `{`, `}`, `..`, `::`. */
    data class Punct(val symbol: String, override val line: Int, override val col: Int) : FznToken
    data class Eof(override val line: Int, override val col: Int) : FznToken
}

/**
 * Thrown when the lexer or parser hits malformed input. Carries `line:col` for diagnostics.
 */
class FlatZincParseException(
    message: String,
    /** 1-based source line of the error. */
    val sourceLine: Int,
    /** 1-based source column of the error. */
    val sourceCol: Int,
) : RuntimeException("$message (at $sourceLine:$sourceCol)")

/**
 * Hand-rolled tokenizer for FlatZinc. Stateful, single-pass. Skips whitespace and `%`
 * line comments. FlatZinc strings use C-style escapes for `\\`, `\"`, `\n`, `\t`.
 */
internal class FlatZincLexer(private val src: String) {
    private var pos: Int = 0
    private var line: Int = 1
    private var col: Int = 1

    private val keywords: Set<String> = setOf(
        "predicate", "constraint", "solve", "satisfy", "minimize", "maximize",
        "var", "par", "bool", "int", "float", "set", "of", "array", "true", "false",
        "output", "annotation", "any",
    )

    /** Drain the source into a token list. Always ends with [FznToken.Eof]. */
    fun tokenize(): List<FznToken> {
        val out = ArrayList<FznToken>()
        while (true) {
            skipWhitespaceAndComments()
            if (pos >= src.length) {
                out.add(FznToken.Eof(line, col))
                return out
            }
            out.add(nextToken())
        }
    }

    private fun nextToken(): FznToken {
        val startLine = line
        val startCol = col
        val c = src[pos]
        return when {
            c.isLetter() || c == '_' -> readIdentOrKeyword(startLine, startCol)

            c.isDigit() || (c == '-' && pos + 1 < src.length && (src[pos + 1].isDigit() || src[pos + 1] == '.')) ->
                readNumber(startLine, startCol)

            c == '.' && pos + 1 < src.length && src[pos + 1] == '.' -> {
                advance()
                advance()
                FznToken.Punct("..", startLine, startCol)
            }

            c == ':' && pos + 1 < src.length && src[pos + 1] == ':' -> {
                advance()
                advance()
                FznToken.Punct("::", startLine, startCol)
            }

            c == '"' -> readString(startLine, startCol)

            c == ',' || c == ';' || c == ':' || c == '=' ||
                c == '(' || c == ')' || c == '[' || c == ']' || c == '{' || c == '}' -> {
                advance()
                FznToken.Punct(c.toString(), startLine, startCol)
            }

            else -> throw FlatZincParseException("unexpected character '${src[pos]}'", line, col)
        }
    }

    private fun readIdentOrKeyword(startLine: Int, startCol: Int): FznToken {
        val sb = StringBuilder()
        while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) {
            sb.append(src[pos])
            advance()
        }
        val name = sb.toString()
        return if (name in keywords) {
            FznToken.Kw(name, startLine, startCol)
        } else {
            FznToken.Ident(name, startLine, startCol)
        }
    }

    private fun readNumber(startLine: Int, startCol: Int): FznToken {
        val sb = StringBuilder()
        if (src[pos] == '-') {
            sb.append('-')
            advance()
        }
        var sawDot = false
        var sawExp = false
        while (pos < src.length) {
            val ch = src[pos]
            when {
                ch.isDigit() -> {
                    sb.append(ch)
                    advance()
                }

                ch == '.' && !sawDot && !sawExp -> {
                    // FlatZinc range token `..` — don't consume the dot if the next char is also `.`
                    if (pos + 1 < src.length && src[pos + 1] == '.') break
                    sawDot = true
                    sb.append(ch)
                    advance()
                }

                (ch == 'e' || ch == 'E') && !sawExp -> {
                    sawExp = true
                    sb.append(ch)
                    advance()
                    if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) {
                        sb.append(src[pos])
                        advance()
                    }
                }

                else -> break
            }
        }
        val text = sb.toString()
        return if (sawDot || sawExp) {
            val value = text.toDoubleOrNull()
                ?: throw FlatZincParseException("malformed float literal '$text'", startLine, startCol)
            FznToken.FloatLit(value, startLine, startCol)
        } else {
            val value = text.toLongOrNull()
                ?: throw FlatZincParseException("malformed int literal '$text'", startLine, startCol)
            FznToken.IntLit(value, startLine, startCol)
        }
    }

    private fun readString(startLine: Int, startCol: Int): FznToken {
        advance() // opening "
        val sb = StringBuilder()
        while (pos < src.length && src[pos] != '"') {
            if (src[pos] == '\\' && pos + 1 < src.length) {
                advance()
                sb.append(
                    when (src[pos]) {
                        'n' -> '\n'
                        't' -> '\t'
                        'r' -> '\r'
                        '\\' -> '\\'
                        '"' -> '"'
                        else -> src[pos]
                    },
                )
                advance()
            } else {
                if (src[pos] == '\n') throw FlatZincParseException("unterminated string", startLine, startCol)
                sb.append(src[pos])
                advance()
            }
        }
        if (pos >= src.length) throw FlatZincParseException("unterminated string", startLine, startCol)
        advance() // closing "
        return FznToken.StringLit(sb.toString(), startLine, startCol)
    }

    private fun skipWhitespaceAndComments() {
        while (pos < src.length) {
            val ch = src[pos]
            when {
                ch == ' ' || ch == '\t' || ch == '\r' -> advance()

                ch == '\n' -> advance()

                ch == '%' -> {
                    while (pos < src.length && src[pos] != '\n') advance()
                }

                else -> return
            }
        }
    }

    private fun advance() {
        if (src[pos] == '\n') {
            line++
            col = 1
        } else {
            col++
        }
        pos++
    }
}
