package com.eignex.klause.formats.xcsp3

/** Parsed XCSP3 intension expression tree. */
sealed interface FExpr {
    /** Numeric literal. */
    data class Num(
        /** Literal value. */
        val value: Int,
    ) : FExpr

    /** Variable reference. */
    data class Ref(
        /** Referenced variable name. */
        val name: String,
    ) : FExpr

    /** Function call. */
    data class Call(
        /** Function name. */
        val fn: String,
        /** Argument expressions. */
        val args: List<FExpr>,
    ) : FExpr

    /** Integer set literal `{v1, v2, lo..hi, …}`, used as the right side of `in`. */
    data class SetLit(
        /** Enumerated set members (ranges already expanded). */
        val values: List<Int>,
    ) : FExpr

    /** Parser entry points. */
    companion object {
        /** Parse text into an [FExpr] tree. */
        fun parse(s: String): FExpr = Parser(s).parseFull()
    }

    private class Parser(val s: String) {
        var pos = 0

        fun parseFull(): FExpr {
            val e = parseExpr()
            skipWs()
            require(pos >= s.length) { "trailing input in '$s' at $pos" }
            return e
        }

        fun parseExpr(): FExpr {
            skipWs()
            if (pos < s.length && s[pos] == '{') return parseSet()
            val tok = readToken()
            skipWs()
            return if (pos < s.length && s[pos] == '(') {
                pos++ // consume '('
                val args = ArrayList<FExpr>()
                skipWs()
                if (s[pos] != ')') {
                    while (true) {
                        args.add(parseExpr())
                        skipWs()
                        if (pos < s.length && s[pos] == ',') {
                            pos++
                            continue
                        }
                        break
                    }
                }
                skipWs()
                require(pos < s.length && s[pos] == ')') { "expected ')' in '$s'" }
                pos++
                Call(tok, args)
            } else {
                tok.toIntOrNull()?.let { Num(it) } ?: Ref(tok)
            }
        }

        private fun parseSet(): FExpr {
            pos++ // consume '{'
            val values = ArrayList<Int>()
            val elem = StringBuilder()
            fun flush() {
                val tok = elem.toString().trim()
                elem.clear()
                if (tok.isEmpty()) return
                val range = tok.split("..")
                if (range.size == 2) {
                    for (v in range[0].toInt()..range[1].toInt()) {
                        values.add(
                            v,
                        )
                    }
                } else {
                    values.add(tok.toInt())
                }
            }
            while (pos < s.length && s[pos] != '}') {
                if (s[pos] == ',') flush() else elem.append(s[pos])
                pos++
            }
            flush()
            require(pos < s.length && s[pos] == '}') { "expected '}' in '$s'" }
            pos++
            return SetLit(values)
        }

        private fun readToken(): String {
            val start = pos
            while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] in "_-[]")) pos++
            require(pos > start) { "expected token in '$s' at $pos" }
            return s.substring(start, pos)
        }

        private fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }
    }
}
