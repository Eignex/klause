package com.eignex.klause.formats.flatzinc

import com.eignex.klause.formats.FormatException
import com.eignex.klause.util.CharReader
import com.eignex.klause.util.StringCharSource

/** One lexical token from FlatZinc source. */
internal sealed interface FznToken {
    val line: Int
    val col: Int

    data class Kw(val keyword: String, override val line: Int, override val col: Int) : FznToken
    data class Ident(val name: String, override val line: Int, override val col: Int) : FznToken
    data class IntLit(val value: Long, override val line: Int, override val col: Int) : FznToken
    data class FloatLit(val value: Double, override val line: Int, override val col: Int) : FznToken
    data class StringLit(val value: String, override val line: Int, override val col: Int) : FznToken

    data class Punct(val symbol: String, override val line: Int, override val col: Int) : FznToken
    data class Eof(override val line: Int, override val col: Int) : FznToken
}

/** Thrown on malformed FlatZinc with source location. */
class FlatZincParseException(message: String, sourceLine: Int, sourceCol: Int) :
    FormatException("FlatZinc", "$message (at $sourceLine:$sourceCol)")

/**
 * Single-pass FlatZinc tokenizer. Pulls characters from a [CharReader], so the whole source is never
 * held as one [String]; tokens are produced one at a time via [next] (the parser streams them), and
 * [tokenize] just drains that pull for the in-memory callers (tests).
 */
internal class FlatZincLexer(private val reader: CharReader) {
    /** Tokenize a [String] in one shot — the in-memory path for tests. */
    constructor(src: String) : this(CharReader(StringCharSource(src)))

    private var line: Int = 1
    private var col: Int = 1

    private val keywords: Set<String> = setOf(
        "predicate", "constraint", "solve", "satisfy", "minimize", "maximize",
        "var", "par", "bool", "int", "float", "set", "of", "array", "true", "false",
        "output", "annotation", "any",
    )

    /** The next token, or a terminal [FznToken.Eof] once the input is exhausted. Calling again after
     *  end of input keeps returning [FznToken.Eof] (with the final position), which is the sentinel the
     *  parser's lookahead expects. */
    fun next(): FznToken {
        skipWhitespaceAndComments()
        if (reader.eof()) return FznToken.Eof(line, col)
        return nextToken()
    }

    fun tokenize(): List<FznToken> {
        val out = ArrayList<FznToken>()
        while (true) {
            val t = next()
            out.add(t)
            if (t is FznToken.Eof) return out
        }
    }

    private fun nextToken(): FznToken {
        val startLine = line
        val startCol = col
        val c = reader.peek().toChar()
        return when {
            c.isLetter() || c == '_' -> readIdentOrKeyword(startLine, startCol)

            c.isDigit() || (
                c == '-' && reader.peek(1) >= 0 &&
                    (reader.peek(1).toChar().isDigit() || reader.peek(1) == '.'.code)
                ) ->
                readNumber(startLine, startCol)

            c == '.' && reader.peek(1) == '.'.code -> {
                advance()
                advance()
                FznToken.Punct("..", startLine, startCol)
            }

            c == ':' && reader.peek(1) == ':'.code -> {
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

            else -> throw FlatZincParseException("unexpected character '$c'", line, col)
        }
    }

    private fun readIdentOrKeyword(startLine: Int, startCol: Int): FznToken {
        val sb = StringBuilder()
        while (!reader.eof() && (reader.peek().toChar().isLetterOrDigit() || reader.peek() == '_'.code)) {
            sb.append(reader.peek().toChar())
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
        if (reader.peek() == '-'.code) {
            sb.append('-')
            advance()
        }
        var sawDot = false
        var sawExp = false
        while (!reader.eof()) {
            val ch = reader.peek().toChar()
            when {
                ch.isDigit() -> {
                    sb.append(ch)
                    advance()
                }

                ch == '.' && !sawDot && !sawExp -> {
                    // FlatZinc range token `..` — don't consume the dot if the next char is also `.`
                    if (reader.peek(1) == '.'.code) break
                    sawDot = true
                    sb.append(ch)
                    advance()
                }

                (ch == 'e' || ch == 'E') && !sawExp -> {
                    sawExp = true
                    sb.append(ch)
                    advance()
                    if (reader.peek() == '+'.code || reader.peek() == '-'.code) {
                        sb.append(reader.peek().toChar())
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
        while (!reader.eof() && reader.peek() != '"'.code) {
            if (reader.peek() == '\\'.code && reader.peek(1) >= 0) {
                advance()
                sb.append(
                    when (reader.peek().toChar()) {
                        'n' -> '\n'
                        't' -> '\t'
                        'r' -> '\r'
                        '\\' -> '\\'
                        '"' -> '"'
                        else -> reader.peek().toChar()
                    },
                )
                advance()
            } else {
                if (reader.peek() == '\n'.code) throw FlatZincParseException("unterminated string", startLine, startCol)
                sb.append(reader.peek().toChar())
                advance()
            }
        }
        if (reader.eof()) throw FlatZincParseException("unterminated string", startLine, startCol)
        advance() // closing "
        return FznToken.StringLit(sb.toString(), startLine, startCol)
    }

    private fun skipWhitespaceAndComments() {
        while (!reader.eof()) {
            when (reader.peek().toChar()) {
                ' ', '\t', '\r' -> advance()

                '\n' -> advance()

                '%' -> {
                    while (!reader.eof() && reader.peek() != '\n'.code) advance()
                }

                else -> return
            }
        }
    }

    private fun advance() {
        if (reader.peek() == '\n'.code) {
            line++
            col = 1
        } else {
            col++
        }
        reader.advance()
    }
}
