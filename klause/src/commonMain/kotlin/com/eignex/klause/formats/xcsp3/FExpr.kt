package com.eignex.klause.formats.xcsp3

/**
 * Tiny parser for XCSP3 functional (intension) notation: `fn(arg, arg, ...)`, where args are
 * nested calls, integer literals, or variable references (including array cells like `q[2]`).
 * Only the structure is parsed; semantic interpretation (which functions are supported) is
 * left to [Xcsp3].
 */
sealed interface FExpr {
    data class Num(val value: Int) : FExpr
    data class Ref(val name: String) : FExpr
    data class Call(val fn: String, val args: List<FExpr>) : FExpr

    companion object {
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
                        if (pos < s.length && s[pos] == ',') { pos++; continue }
                        break
                    }
                }
                skipWs(); require(pos < s.length && s[pos] == ')') { "expected ')' in '$s'" }; pos++
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

        private fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }
    }
}
