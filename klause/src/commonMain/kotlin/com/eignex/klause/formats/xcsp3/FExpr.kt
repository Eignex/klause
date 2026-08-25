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

        /** True when [text] uses only scalar `%<digits>` group placeholders — no `%...` splice, `%i..%j`
         *  range, or bare `%`. Such a template can be parsed once and reused across a group's instances by
         *  substituting tokens into the [FExpr] tree ([substitute]) instead of re-expanding and re-parsing
         *  the string per row; the range/splice forms produce space-joined token lists that only a
         *  string expansion can place, so they keep the general path. */
        fun isScalarTemplate(text: String): Boolean {
            var i = 0
            while (i < text.length) {
                if (text[i] != '%') {
                    i++
                    continue
                }
                if (text.startsWith("...", i + 1)) return false
                var j = i + 1
                while (j < text.length && text[j].isDigit()) j++
                if (j == i + 1) return false // a bare '%'
                if (text.startsWith("..%", j)) return false // a `%i..%j` range
                i = j
            }
            return true
        }

        /** Substitute the scalar `%<digits>` placeholder [Ref]s in [template] with [tokens] — each token
         *  parsed as a [Num] when numeric, else a [Ref] — reproducing the tree of an expanded-then-parsed
         *  instance. An out-of-range index keeps its placeholder verbatim, exactly as string expansion
         *  leaves it. [template] must satisfy [isScalarTemplate]. */
        fun substitute(template: FExpr, tokens: List<String>): FExpr = when (template) {
            is Ref -> {
                val idx = placeholderIndex(template.name)
                if (idx < 0) {
                    template
                } else {
                    tokens.getOrNull(idx)?.let { t -> t.toIntOrNull()?.let { Num(it) } ?: Ref(t) } ?: template
                }
            }

            is Call -> Call(template.fn, template.args.map { substitute(it, tokens) })

            is Num, is SetLit -> template
        }

        // The index `i` of a `%i` placeholder [Ref] name, or `-1` for an ordinary variable reference.
        private fun placeholderIndex(name: String): Int {
            if (name.length < 2 || name[0] != '%') return -1
            for (k in 1 until name.length) if (!name[k].isDigit()) return -1
            return name.substring(1).toInt()
        }
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
                require(pos < s.length) { "expected ')' in '$s'" }
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
                val separator = tok.indexOf("..")
                if (separator >= 0) {
                    require(tok.indexOf("..", separator + 2) < 0) { "invalid set range '$tok'" }
                    val lo = tok.substring(0, separator).toInt()
                    val hi = tok.substring(separator + 2).toInt()
                    require(lo <= hi) { "invalid set range '$tok'" }
                    for (v in lo..hi) {
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
            // `%` lets a group's scalar template parse its `%i` placeholders as [Ref]s ([substitute] then
            // replaces them per instance); a real expression never contains `%` (modulo is `mod`).
            while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] in "_-[]%")) pos++
            require(pos > start) { "expected token in '$s' at $pos" }
            return s.substring(start, pos)
        }

        private fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }
    }
}
