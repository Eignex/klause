package com.eignex.klause.formats.smtlib

import com.eignex.klause.util.CharReader
import com.eignex.klause.util.CharSource
import com.eignex.klause.util.StringCharSource

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
    /** Parse a [String] in one shot — the in-memory path for tests and the DSL. */
    constructor(src: String) : this(CharReader(StringCharSource(src)))

    /** Wrap a streamed [source] directly. */
    constructor(source: CharSource) : this(CharReader(source))

    /** The next top-level form, or `null` at end of input (inter-command whitespace skipped). Streaming
     *  the commands one at a time keeps a large script from ever being held whole. */
    fun readCommandOrNull(): SExpr? {
        skipWs()
        if (reader.eof()) return null
        return readExpr()
    }

    /** Parse all top-level forms. Kept for the in-memory callers; it just drains [readCommandOrNull]. */
    fun readAll(): List<SExpr> {
        val out = ArrayList<SExpr>()
        while (true) out.add(readCommandOrNull() ?: return out)
    }

    // Structural parse failures surface through the catchable FormatException supertype (shared by
    // every klause front-end), not a raw IllegalArgumentException. Funnelled through one helper so the
    // sites stay `if (cond) parseError(...)` guards rather than inline `throw` clutter.
    private fun parseError(msg: String): Nothing = throw UnsupportedSmtException(msg)

    private fun readExpr(): SExpr {
        skipWs()
        if (reader.eof()) parseError("unexpected end of input")
        // A `)` where an expression is expected is unbalanced: [readToken] would return an empty
        // token without advancing, spinning [readAll] forever, so reject it here.
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
                while (!reader.eof() && reader.peek() != '|'.code) sb.appendCurrent()
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
                reader.peek() != '('.code && reader.peek() != ')'.code
                ) {
                    sb.appendCurrent()
                }
        }
        return sb.toString()
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

    // Append the character under the cursor and consume it — the streaming analogue of the old
    // `src.substring(start, pos)` token capture.
    private fun StringBuilder.appendCurrent() {
        append(reader.peek().toChar())
        reader.advance()
    }
}
