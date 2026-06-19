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
