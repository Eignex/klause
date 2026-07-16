package com.eignex.klause.formats.smtlib

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

/** Streaming parser for S-expressions. */
class SExprReader(private val src: String) {
    private var pos = 0

    /** Parse all top-level forms. */
    fun readAll(): List<SExpr> {
        val out = ArrayList<SExpr>()
        while (true) {
            skipWs()
            if (pos >= src.length) break
            out.add(readExpr())
        }
        return out
    }

    // Structural parse failures surface through the catchable FormatException supertype (shared by
    // every klause front-end), not a raw IllegalArgumentException. Funnelled through one helper so the
    // sites stay `if (cond) parseError(...)` guards rather than inline `throw` clutter.
    private fun parseError(msg: String): Nothing = throw UnsupportedSmtException(msg)

    private fun readExpr(): SExpr {
        skipWs()
        if (pos >= src.length) parseError("unexpected end of input")
        // A `)` where an expression is expected is unbalanced: [readToken] would return an empty
        // token without advancing, spinning [readAll] forever, so reject it here.
        if (src[pos] == ')') parseError("unexpected ')'")
        if (src[pos] != '(') return SExpr.Atom(readToken())
        // Iterative nesting via an explicit stack: SMT-LIB formulas nest thousands of lists deep,
        // which overflows a recursive-descent reader.
        val stack = ArrayDeque<ArrayList<SExpr>>()
        while (true) {
            skipWs()
            if (pos >= src.length) parseError("unterminated list")
            when (src[pos]) {
                '(' -> {
                    pos++
                    stack.addLast(ArrayList())
                }

                ')' -> {
                    pos++
                    val list = SExpr.SList(stack.removeLast())
                    if (stack.isEmpty()) return list
                    stack.last().add(list)
                }

                else -> stack.last().add(SExpr.Atom(readToken()))
            }
        }
    }

    private fun readToken(): String {
        val start = pos
        when (src[pos]) {
            // Quoted symbol |...| — may contain whitespace and parentheses; runs to the next '|'.
            '|' -> {
                pos++
                while (pos < src.length && src[pos] != '|') pos++
                if (pos >= src.length) parseError("unterminated |quoted symbol|")
                pos++
            }

            // String literal "..." with "" as an embedded-quote escape.
            '"' -> {
                pos++
                while (pos < src.length) {
                    if (src[pos] == '"') {
                        if (pos + 1 < src.length && src[pos + 1] == '"') pos += 2 else break
                    } else {
                        pos++
                    }
                }
                if (pos >= src.length) parseError("unterminated string literal")
                pos++
            }

            else -> while (pos < src.length && !src[pos].isWhitespace() && src[pos] != '(' && src[pos] != ')') pos++
        }
        return src.substring(start, pos)
    }

    private fun skipWs() {
        while (pos < src.length) {
            val c = src[pos]
            when {
                c.isWhitespace() -> pos++

                c == ';' -> {
                    while (pos < src.length && src[pos] != '\n') pos++
                }

                else -> return
            }
        }
    }
}
