package com.eignex.klause.formats.smtlib

import com.eignex.util.io.CharReader
import com.eignex.util.io.CharSource
import com.eignex.util.io.StringCharSource

/** Minimal S-expression model for SMT-LIB scripts. */
sealed interface SExpr {
    /** Atom token. */
    data class Atom(
        /** Raw token text. */
        val text: String,
    ) : SExpr

    /** Parenthesized list node. */
    data class SList(
        /** Child S-expressions. */
        val items: List<SExpr>,
    ) : SExpr
}

/** Streaming parser for S-expressions. Pulls characters from a [CharReader], so the whole script is
 *  never materialized; commands are read one at a time via [readCommandOrNull]. */
class SExprReader(private val reader: CharReader) {
    /** Parses an in-memory script. */
    constructor(src: String) : this(CharReader(StringCharSource(src)))

    /** Wrap a streamed [source] directly. */
    constructor(source: CharSource) : this(CharReader(source))

    /** The next top-level form, or `null` at end of input. */
    fun readCommandOrNull(): SExpr? {
        skipWs()
        if (reader.eof()) return null
        return readExpr()
    }

    /** Parse all top-level forms by draining [readCommandOrNull]. */
    fun readAll(): List<SExpr> {
        val out = ArrayList<SExpr>()
        while (true) out.add(readCommandOrNull() ?: return out)
    }

    // Failures surface through the FormatException supertype every front-end shares.
    private fun parseError(msg: String): Nothing = throw UnsupportedSmtException(msg)

    private fun readExpr(): SExpr {
        skipWs()
        if (reader.eof()) parseError("unexpected end of input")
        // [readToken] would return an empty token without advancing on `)`, spinning [readAll] forever.
        if (reader.peek() == ')'.code) parseError("unexpected ')'")
        if (reader.peek() != '('.code) return SExpr.Atom(readToken())
        // Iterative nesting via an explicit stack: SMT-LIB formulas nest thousands of lists deep,
        // which overflows a recursive-descent reader.
        val stack = ArrayDeque<ArrayList<SExpr>>()
        while (true) {
            skipWs()
            if (reader.eof()) parseError("unterminated list")
            when (reader.peek()) {
                '('.code -> {
                    reader.advance()
                    stack.addLast(ArrayList())
                }

                ')'.code -> {
                    reader.advance()
                    val list = SExpr.SList(stack.removeLast())
                    if (stack.isEmpty()) return list
                    stack.last().add(list)
                }

                else -> stack.last().add(SExpr.Atom(readToken()))
            }
        }
    }

    private fun readToken(): String {
        val sb = StringBuilder()
        when (reader.peek()) {
            // Quoted symbol |...| — may contain whitespace and parentheses; runs to the next '|'.
            '|'.code -> {
                sb.append('|')
                reader.advance()
                while (!reader.eof() && reader.peek() != '|'.code) {
                    if (reader.peek() == '\\'.code) parseError("backslash is not allowed in a quoted symbol")
                    sb.appendCurrent()
                }
                if (reader.eof()) parseError("unterminated |quoted symbol|")
                sb.append('|')
                reader.advance()
            }

            // String literal "..." with "" as an embedded-quote escape.
            '"'.code -> {
                sb.append('"')
                reader.advance()
                while (!reader.eof()) {
                    if (reader.peek() == '"'.code) {
                        if (reader.peek(1) == '"'.code) {
                            sb.append("\"\"")
                            reader.advance()
                            reader.advance()
                        } else {
                            break
                        }
                    } else {
                        sb.appendCurrent()
                    }
                }
                if (reader.eof()) parseError("unterminated string literal")
                sb.append('"')
                reader.advance()
            }

            else -> while (!reader.eof() && !reader.peek().toChar().isWhitespace() &&
                reader.peek() != '('.code && reader.peek() != ')'.code && reader.peek() != ';'.code
                ) {
                    sb.appendCurrent()
                }
        }
        val token = sb.toString()
        if (!validToken(token)) parseError("invalid token '$token'")
        return token
    }

    private fun validToken(token: String): Boolean {
        if (token.length == 1 && token[0] in '0'..'9') return true
        if (token.startsWith('|') || token.startsWith('"')) return true
        if (token.startsWith("#b")) return token.length > 2 && token.drop(2).all { it in "01" }
        if (token.startsWith("#x")) return token.length > 2 && token.drop(2).all {
            it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
        }
        if (token.firstOrNull()?.let { it in '0'..'9' } == true) {
            val pieces = token.split('.')
            return pieces.size in 1..2 && pieces.all { part -> part.isNotEmpty() && part.all { it in '0'..'9' } } &&
                (pieces[0] == "0" || !pieces[0].startsWith('0'))
        }
        val symbol = token.removePrefix(":")
        val punctuation = "~!@$%^&*_-+=<>.?/"
        fun initial(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in punctuation
        return symbol.isNotEmpty() && initial(symbol[0]) && symbol.all { initial(it) || it in '0'..'9' }
    }

    private fun skipWs() {
        while (!reader.eof()) {
            val c = reader.peek()
            when {
                c.toChar().isWhitespace() -> reader.advance()

                c == ';'.code -> {
                    while (!reader.eof() && reader.peek() != '\n'.code) reader.advance()
                }

                else -> return
            }
        }
    }

    private fun StringBuilder.appendCurrent() {
        append(reader.peek().toChar())
        reader.advance()
    }
}
