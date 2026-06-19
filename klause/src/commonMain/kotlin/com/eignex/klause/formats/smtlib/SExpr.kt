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
        return if (src[pos] == '(') readList() else SExpr.Atom(readToken())
    }

    private fun readList(): SExpr.SList {
        expect('(')
        val items = ArrayList<SExpr>()
        while (true) {
            skipWs()
            require(pos < src.length) { "unterminated list" }
            if (src[pos] == ')') {
                pos++
                break
            }
            items.add(readExpr())
        }
        return SExpr.SList(items)
    }

    private fun readToken(): String {
        val start = pos
        while (pos < src.length && !src[pos].isWhitespace() && src[pos] != '(' && src[pos] != ')') pos++
        return src.substring(start, pos)
    }

    private fun expect(c: Char) {
        require(pos < src.length && src[pos] == c) { "expected '$c' at $pos" }
        pos++
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
