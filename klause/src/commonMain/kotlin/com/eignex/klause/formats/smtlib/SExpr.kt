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

    private fun readExpr(): SExpr {
        skipWs()
        require(pos < src.length) { "unexpected end of input" }
        if (src[pos] != '(') return SExpr.Atom(readToken())
        // Iterative nesting via an explicit stack: SMT-LIB formulas nest thousands of lists deep,
        // which overflows a recursive-descent reader.
        val stack = ArrayDeque<ArrayList<SExpr>>()
        while (true) {
            skipWs()
            require(pos < src.length) { "unterminated list" }
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
                require(pos < src.length) { "unterminated |quoted symbol|" }
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
                require(pos < src.length) { "unterminated string literal" }
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
