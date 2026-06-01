package com.eignex.klause.formats.smtlib

/**
 * Minimal S-expression model + reader for SMT-LIB scripts. An [SExpr] is either an [Atom]
 * (symbol or numeral token) or a [SList] (parenthesised sequence). Comments (`;` to
 * end-of-line) and whitespace are skipped. Sufficient for the QF_LIA subset this front-end
 * ingests; not a full SMT-LIB 2 reader (no quoted symbols, string literals, or `|...|`).
 */
sealed interface SExpr {
    /** An atom (symbol, keyword, or literal token). */
    data class Atom(
        /** The raw token text. */
        val text: String,
    ) : SExpr

    /** A parenthesised list of S-expressions. */
    data class SList(
        /** The contained S-expressions. */
        val items: List<SExpr>,
    ) : SExpr
}

/** Streaming reader that tokenises and parses SMT-LIB S-expressions from [src]. */
class SExprReader(private val src: String) {
    private var pos = 0

    /** Parse all top-level S-expressions in the source. */
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
