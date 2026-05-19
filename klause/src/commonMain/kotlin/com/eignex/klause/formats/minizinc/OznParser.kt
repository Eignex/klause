package com.eignex.klause.formats.minizinc

/**
 * Recursive-descent parser for the `.ozn` subset of MiniZinc. Produces a list of
 * [OznItem]s from a token stream produced by [OznLexer]. Expression precedence mirrors
 * MiniZinc's grammar (lowest → highest):
 *   if/let → logical (\/, /\, ->) → comparison → additive → multiplicative → unary →
 *   postfix (subscript, call) → atom.
 *
 * Designed to handle the constructs MiniZinc emits into .ozn — comprehensions,
 * conditionals, calls, ranges, and the standard literal / operator set — but not the
 * full MZN expression grammar (no enum types, no opt types, no annotation expressions,
 * no tuples). Errors land as [OznParseException] with line numbers.
 */
internal class OznParser(private val tokens: List<OznToken>) {
    private var pos: Int = 0

    fun parse(): List<OznItem> {
        val items = ArrayList<OznItem>()
        while (peek().kind != OznTokenKind.EOF) {
            items.add(parseItem())
        }
        return items
    }

    private fun parseItem(): OznItem {
        val tk = peek()
        return when {
            tk.kind == OznTokenKind.KEYWORD && tk.text == "output" -> parseOutputItem()
            else -> parseVarDecl()
        }
    }

    private fun parseOutputItem(): OznItem.Output {
        expectKeyword("output")
        // `output` takes a single array-valued expression: a literal `[...]`, a
        // comprehension `[body | gens]`, or any combination joined by `++` (string /
        // array concatenation). Parse a full expression and let the evaluator unwrap
        // the resulting array.
        val expr = parseExpr()
        expectPunct(";")
        return OznItem.Output(listOf(expr))
    }

    private fun parseVarDecl(): OznItem.VarDecl {
        // Optional 'var' / 'par' prefix — .ozn typically omits both since FZN-side
        // bindings are already variables; we accept either for tolerance.
        if (peekKeyword("var") || peekKeyword("par")) advance()
        val type = parseType()
        expectPunct(":")
        val name = expectIdent()
        // Annotations are dropped at parse time — .ozn doesn't carry them but be tolerant.
        while (peekPunct("::")) {
            advance()
            // Skip an annotation: ident, optionally followed by `(...)` or other annotation.
            expectIdent()
            if (peekPunct("(")) {
                advance()
                var depth = 1
                while (depth > 0 && peek().kind != OznTokenKind.EOF) {
                    val t = advance()
                    if (t.kind == OznTokenKind.PUNCT) {
                        when (t.text) { "(" -> depth++; ")" -> depth-- }
                    }
                }
            }
        }
        val init = if (peekPunct("=")) {
            advance()
            parseExpr()
        } else null
        expectPunct(";")
        return OznItem.VarDecl(name, type, init)
    }

    private fun parseType(): OznType {
        if (peekKeyword("array")) {
            advance()
            expectPunct("[")
            val ranges = ArrayList<OznExpr>()
            ranges.add(parseExpr())
            while (peekPunct(",")) {
                advance()
                ranges.add(parseExpr())
            }
            expectPunct("]")
            expectKeyword("of")
            val element = parseType()
            return OznType.ArrayOf(ranges, element)
        }
        if (peekKeyword("set")) {
            advance()
            expectKeyword("of")
            expectKeyword("int")
            return OznType.SetOfInt
        }
        if (peekKeyword("var")) advance()  // tolerate `var int`
        return when {
            peekKeyword("int") -> { advance(); OznType.Int }
            peekKeyword("bool") -> { advance(); OznType.Bool }
            peekKeyword("float") -> { advance(); OznType.Float }
            else -> {
                // Could be a range / domain: parse an expression and treat as int domain.
                // .ozn doesn't really emit domain-typed names; tolerate `1..n` style.
                parseExpr()
                OznType.Int
            }
        }
    }

    // --- Expressions (recursive descent over precedence ladder) ---------------------

    fun parseExpr(): OznExpr = parseLogical()

    private fun parseIf(): OznExpr {
        expectKeyword("if")
        val branches = ArrayList<Pair<OznExpr, OznExpr>>()
        val cond = parseExpr()
        expectKeyword("then")
        val thenE = parseExpr()
        branches.add(cond to thenE)
        while (peekKeyword("elseif")) {
            advance()
            val c = parseExpr()
            expectKeyword("then")
            val t = parseExpr()
            branches.add(c to t)
        }
        expectKeyword("else")
        val elseE = parseExpr()
        expectKeyword("endif")
        return OznExpr.If(branches, elseE)
    }

    private fun parseLet(): OznExpr {
        expectKeyword("let")
        expectPunct("{")
        val decls = ArrayList<OznItem.VarDecl>()
        while (!peekPunct("}")) {
            decls.add(parseVarDecl())
        }
        expectPunct("}")
        expectKeyword("in")
        val body = parseExpr()
        return OznExpr.Let(decls, body)
    }

    private fun parseLogical(): OznExpr {
        // \/ /\ -> <- xor — coarse same-precedence chain; correct enough for .ozn output.
        var left = parseComparison()
        while (peek().kind == OznTokenKind.PUNCT && (peek().text == "\\/" || peek().text == "/\\" ||
                peek().text == "->" || peek().text == "<-")) {
            val op = advance().text
            val right = parseComparison()
            left = OznExpr.Binary(op, left, right)
        }
        return left
    }

    private fun parseComparison(): OznExpr {
        var left = parseAdditive()
        while (true) {
            val t = peek()
            val isCmp = t.kind == OznTokenKind.PUNCT && t.text in setOf("<", "<=", ">", ">=", "=", "==", "!=")
            val isIn = t.kind == OznTokenKind.KEYWORD && t.text == "in"
            if (!isCmp && !isIn) break
            val op = advance().text
            val right = parseAdditive()
            left = OznExpr.Binary(if (op == "==") "=" else op, left, right)
        }
        return left
    }

    private fun parseAdditive(): OznExpr {
        var left = parseMultiplicative()
        while (peek().kind == OznTokenKind.PUNCT && peek().text in setOf("+", "-", "++")) {
            val op = advance().text
            val right = parseMultiplicative()
            left = OznExpr.Binary(op, left, right)
        }
        return left
    }

    private fun parseMultiplicative(): OznExpr {
        var left = parseRange()
        while (true) {
            val t = peek()
            val isPunct = t.kind == OznTokenKind.PUNCT && t.text in setOf("*", "/")
            val isKw = t.kind == OznTokenKind.KEYWORD && t.text in setOf("div", "mod")
            if (!isPunct && !isKw) break
            val op = advance().text
            val right = parseRange()
            left = OznExpr.Binary(op, left, right)
        }
        return left
    }

    private fun parseRange(): OznExpr {
        val left = parseUnary()
        if (peek().kind == OznTokenKind.PUNCT && peek().text == "..") {
            advance()
            val right = parseUnary()
            return OznExpr.Range(left, right)
        }
        return left
    }

    private fun parseUnary(): OznExpr {
        val t = peek()
        if (t.kind == OznTokenKind.PUNCT && (t.text == "-" || t.text == "+")) {
            val op = advance().text
            val operand = parseUnary()
            return OznExpr.Unary(op, operand)
        }
        if (t.kind == OznTokenKind.KEYWORD && t.text == "not") {
            advance()
            val operand = parseUnary()
            return OznExpr.Unary("not", operand)
        }
        return parsePostfix()
    }

    private fun parsePostfix(): OznExpr {
        var atom = parseAtom()
        // Subscript x[i, j, ...]
        while (peek().kind == OznTokenKind.PUNCT && peek().text == "[") {
            advance()
            val idx = ArrayList<OznExpr>()
            idx.add(parseExpr())
            while (peekPunct(",")) {
                advance()
                idx.add(parseExpr())
            }
            expectPunct("]")
            atom = OznExpr.Subscript(atom, idx)
        }
        return atom
    }

    private fun parseAtom(): OznExpr {
        val t = peek()
        // `if-then-else-endif` and `let { ... } in expr` can appear as subexpressions of
        // larger operator expressions (e.g. `show(x) ++ if c then a else b endif`).
        // Recurse to the top precedence level so they're parsed correctly here.
        if (t.kind == OznTokenKind.KEYWORD && t.text == "if") return parseIf()
        if (t.kind == OznTokenKind.KEYWORD && t.text == "let") return parseLet()
        return when (t.kind) {
            OznTokenKind.INT -> { advance(); OznExpr.IntLit(t.text.toLong()) }
            OznTokenKind.FLOAT -> { advance(); OznExpr.FloatLit(t.text.toDouble()) }
            OznTokenKind.BOOL -> { advance(); OznExpr.BoolLit(t.text == "true") }
            OznTokenKind.STRING -> { advance(); OznExpr.StringLit(t.text) }
            OznTokenKind.IDENT, OznTokenKind.KEYWORD -> {
                val name = advance().text
                // Function call?
                if (peekPunct("(")) {
                    advance()
                    val args = if (peekPunct(")")) emptyList() else parseExprList(stopPunct = ")")
                    expectPunct(")")
                    return OznExpr.Call(name, args)
                }
                OznExpr.Ident(name)
            }
            OznTokenKind.PUNCT -> when (t.text) {
                "(" -> {
                    advance()
                    val e = parseExpr()
                    expectPunct(")")
                    e
                }
                "[" -> parseArrayOrComprehension()
                "{" -> parseSetOrComprehension()
                else -> throw OznParseException("unexpected token `${t.text}` at line ${t.line}")
            }
            OznTokenKind.EOF -> throw OznParseException("unexpected EOF")
        }
    }

    private fun parseArrayOrComprehension(): OznExpr {
        expectPunct("[")
        if (peekPunct("]")) {
            advance()
            return OznExpr.ArrayLit(emptyList())
        }
        // 2D-array literal `[| r1c1, r1c2 | r2c1, r2c2 | ... |]`. Detected by the
        // leading `|` immediately after `[`. Rows are pipe-separated; cells within a
        // row are comma-separated. Lowered to `array2d(1..rows, 1..cols, [flat])` so
        // the evaluator's array2d path handles indexing / display.
        if (peekPunct("|")) {
            advance()
            val rows = ArrayList<List<OznExpr>>()
            while (true) {
                val row = ArrayList<OznExpr>()
                if (!peekPunct("|")) {
                    row.add(parseExpr())
                    while (peekPunct(",")) { advance(); row.add(parseExpr()) }
                }
                if (row.isNotEmpty()) rows.add(row)
                if (peekPunct("|")) {
                    advance()
                    if (peekPunct("]")) { advance(); break }
                } else if (peekPunct("]")) { advance(); break }
                else break
            }
            val n = rows.size
            val m = rows.firstOrNull()?.size ?: 0
            val flat = rows.flatten()
            return OznExpr.Call("array2d", listOf(
                OznExpr.Range(OznExpr.IntLit(1), OznExpr.IntLit(n.toLong())),
                OznExpr.Range(OznExpr.IntLit(1), OznExpr.IntLit(m.toLong())),
                OznExpr.ArrayLit(flat),
            ))
        }
        val first = parseExpr()
        // Detect comprehension: `expr | ident in ...`.
        if (peekPunct("|")) {
            advance()
            val gens = parseGenerators()
            expectPunct("]")
            return OznExpr.Comprehension(first, gens, isSet = false)
        }
        val elements = ArrayList<OznExpr>().also { it.add(first) }
        while (peekPunct(",")) {
            advance()
            elements.add(parseExpr())
        }
        expectPunct("]")
        return OznExpr.ArrayLit(elements)
    }

    private fun parseSetOrComprehension(): OznExpr {
        expectPunct("{")
        if (peekPunct("}")) {
            advance()
            return OznExpr.SetLit(emptyList())
        }
        val first = parseExpr()
        if (peekPunct("|")) {
            advance()
            val gens = parseGenerators()
            expectPunct("}")
            return OznExpr.Comprehension(first, gens, isSet = true)
        }
        val elements = ArrayList<OznExpr>().also { it.add(first) }
        while (peekPunct(",")) {
            advance()
            elements.add(parseExpr())
        }
        expectPunct("}")
        return OznExpr.SetLit(elements)
    }

    private fun parseGenerators(): List<OznExpr.Generator> {
        val gens = ArrayList<OznExpr.Generator>()
        gens.add(parseGenerator())
        while (peekPunct(",")) {
            advance()
            gens.add(parseGenerator())
        }
        return gens
    }

    private fun parseGenerator(): OznExpr.Generator {
        val names = ArrayList<String>()
        names.add(expectIdent())
        while (peekPunct(",")) {
            // Multi-binding requires we look ahead — if next-after-comma is `ident in`,
            // it's a new generator, not a same-generator multi-binding. MZN syntax for
            // multi-bound generator: `i, j in 1..n`. We disambiguate by checking whether
            // the second ident is followed by `in` or `,` (then in).
            val save = pos
            advance()  // consume comma
            if (peek().kind == OznTokenKind.IDENT) {
                val nxt = peek().text
                advance()
                // Lookahead: if followed by `in` (same generator) or `,<ident> in` we're in
                // multi-binding; if followed by `]`/`|`/etc., we backtrack to caller.
                if (peekKeyword("in") || peekPunct(",")) {
                    names.add(nxt)
                    continue
                } else {
                    pos = save  // rewind
                    break
                }
            } else {
                pos = save
                break
            }
        }
        expectKeyword("in")
        val source = parseExpr()
        val where: OznExpr? = if (peekKeyword("where")) {
            advance()
            parseExpr()
        } else null
        return OznExpr.Generator(names, source, where)
    }

    private fun parseExprList(stopPunct: String): List<OznExpr> {
        val out = ArrayList<OznExpr>()
        if (peek().kind == OznTokenKind.PUNCT && peek().text == stopPunct) return out
        out.add(parseExpr())
        while (peekPunct(",")) {
            advance()
            out.add(parseExpr())
        }
        return out
    }

    // --- Token helpers ----------------------------------------------------------

    private fun peek(): OznToken = tokens[pos]
    private fun advance(): OznToken = tokens[pos++]

    private fun peekKeyword(kw: String): Boolean {
        val t = peek()
        return t.kind == OznTokenKind.KEYWORD && t.text == kw
    }

    private fun peekPunct(p: String): Boolean {
        val t = peek()
        return t.kind == OznTokenKind.PUNCT && t.text == p
    }

    private fun expectKeyword(kw: String) {
        val t = peek()
        if (t.kind != OznTokenKind.KEYWORD || t.text != kw) {
            throw OznParseException("expected keyword `$kw`, got `${t.text}` at line ${t.line}")
        }
        advance()
    }

    private fun expectPunct(p: String) {
        val t = peek()
        if (t.kind != OznTokenKind.PUNCT || t.text != p) {
            throw OznParseException("expected `$p`, got `${t.text}` at line ${t.line}")
        }
        advance()
    }

    private fun expectIdent(): String {
        val t = peek()
        if (t.kind != OznTokenKind.IDENT) {
            throw OznParseException("expected identifier, got `${t.text}` at line ${t.line}")
        }
        return advance().text
    }
}

class OznParseException(message: String) : RuntimeException(message)
