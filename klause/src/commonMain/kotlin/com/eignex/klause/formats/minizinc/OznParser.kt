package com.eignex.klause.formats.minizinc

import com.eignex.klause.formats.FormatException

/** Recursive-descent parser for the `.ozn` subset of MiniZinc. */
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
        val expr = parseExpr()
        expectPunct(";")
        return OznItem.Output(listOf(expr))
    }

    private fun parseVarDecl(): OznItem.VarDecl {
        if (peekKeyword("var") || peekKeyword("par")) advance()
        val type = parseType()
        expectPunct(":")
        val name = expectIdent()
        while (peekPunct("::")) {
            advance()
            expectIdent()
            if (peekPunct("(")) {
                advance()
                var depth = 1
                while (depth > 0 && peek().kind != OznTokenKind.EOF) {
                    val t = advance()
                    if (t.kind == OznTokenKind.PUNCT) {
                        when (t.text) {
                            "(" -> depth++
                            ")" -> depth--
                        }
                    }
                }
            }
        }
        val init = if (peekPunct("=")) {
            advance()
            parseExpr()
        } else {
            null
        }
        expectPunct(";")
        return OznItem.VarDecl(name, type, init)
    }

    private fun parseType(): OznType {
        if (peekKeyword("array")) {
            advance()
            expectPunct("[")
            val ranges = parseCommaSeparated(parseExpr()) { parseExpr() }
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
        if (peekKeyword("var")) advance() // tolerate `var int`
        return when {
            peekKeyword("int") -> {
                advance()
                OznType.Int
            }

            peekKeyword("bool") -> {
                advance()
                OznType.Bool
            }

            peekKeyword("float") -> {
                advance()
                OznType.Float
            }

            else -> {
                parseExpr()
                OznType.Int
            }
        }
    }

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
        var left = parseComparison()
        while (peek().kind == OznTokenKind.PUNCT && (
                peek().text == "\\/" || peek().text == "/\\" ||
                    peek().text == "->" || peek().text == "<-"
                )
        ) {
            val op = advance().text
            val right = parseComparison()
            left = OznExpr.Binary(op, left, right)
        }
        return left
    }

    private fun parseComparison(): OznExpr {
        var left = parseRange()
        while (true) {
            val t = peek()
            val isCmp = t.kind == OznTokenKind.PUNCT && t.text in setOf("<", "<=", ">", ">=", "=", "==", "!=")
            val isIn = t.kind == OznTokenKind.KEYWORD && t.text == "in"
            if (!isCmp && !isIn) break
            val op = advance().text
            val right = parseRange()
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
        var left = parseUnary()
        while (true) {
            val t = peek()
            val isPunct = t.kind == OznTokenKind.PUNCT && t.text in setOf("*", "/")
            val isKw = t.kind == OznTokenKind.KEYWORD && t.text in setOf("div", "mod")
            if (!isPunct && !isKw) break
            val op = advance().text
            val right = parseUnary()
            left = OznExpr.Binary(op, left, right)
        }
        return left
    }

    // `..` binds looser than the arithmetic operators but tighter than comparison, so `1..n-1` is
    // `1..(n-1)` — the ubiquitous MiniZinc index-set idiom — not `(1..n)-1`.
    private fun parseRange(): OznExpr {
        val left = parseAdditive()
        if (peek().kind == OznTokenKind.PUNCT && peek().text == "..") {
            advance()
            val right = parseAdditive()
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
        if (t.kind == OznTokenKind.KEYWORD && t.text == "if") return parseIf()
        if (t.kind == OznTokenKind.KEYWORD && t.text == "let") return parseLet()
        return when (t.kind) {
            OznTokenKind.INT -> {
                advance()
                OznExpr.IntLit(parseLongLiteral(t))
            }

            OznTokenKind.FLOAT -> {
                advance()
                OznExpr.FloatLit(t.text.toDouble())
            }

            OznTokenKind.BOOL -> {
                advance()
                OznExpr.BoolLit(t.text == "true")
            }

            OznTokenKind.STRING -> {
                advance()
                OznExpr.StringLit(t.text)
            }

            OznTokenKind.IDENT, OznTokenKind.KEYWORD -> {
                val name = advance().text
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
        // Lower row-table `[| ... |]` syntax to `array2d(...)`.
        if (peekPunct("|")) {
            advance()
            val rows = ArrayList<List<OznExpr>>()
            while (true) {
                val row = ArrayList<OznExpr>()
                if (!peekPunct("|")) {
                    row.add(parseExpr())
                    while (peekPunct(",")) {
                        advance()
                        row.add(parseExpr())
                    }
                }
                if (row.isNotEmpty()) rows.add(row)
                if (peekPunct("|")) {
                    advance()
                    if (peekPunct("]")) {
                        advance()
                        break
                    }
                } else if (peekPunct("]")) {
                    advance()
                    break
                } else {
                    break
                }
            }
            val n = rows.size
            val m = rows.firstOrNull()?.size ?: 0
            val flat = rows.flatten()
            return OznExpr.Call(
                "array2d",
                listOf(
                    OznExpr.Range(OznExpr.IntLit(1), OznExpr.IntLit(n.toLong())),
                    OznExpr.Range(OznExpr.IntLit(1), OznExpr.IntLit(m.toLong())),
                    OznExpr.ArrayLit(flat),
                ),
            )
        }
        val first = parseExpr()
        if (peekPunct("|")) {
            advance()
            val gens = parseGenerators()
            expectPunct("]")
            return OznExpr.Comprehension(first, gens, isSet = false)
        }
        val elements = parseCommaSeparated(first) { parseExpr() }
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
        val elements = parseCommaSeparated(first) { parseExpr() }
        expectPunct("}")
        return OznExpr.SetLit(elements)
    }

    private fun parseGenerators(): List<OznExpr.Generator> = parseCommaSeparated(parseGenerator()) { parseGenerator() }

    private fun parseGenerator(): OznExpr.Generator {
        val names = ArrayList<String>()
        names.add(expectIdent())
        while (peekPunct(",")) {
            // Disambiguate `i, j in ...` (same generator) from `i in ..., j in ...`.
            val save = pos
            advance() // consume comma
            if (peek().kind == OznTokenKind.IDENT) {
                val nxt = peek().text
                advance()
                if (peekKeyword("in") || peekPunct(",")) {
                    names.add(nxt)
                    continue
                } else {
                    pos = save // rewind
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
        } else {
            null
        }
        return OznExpr.Generator(names, source, where)
    }

    private fun parseExprList(stopPunct: String): List<OznExpr> {
        if (peek().kind == OznTokenKind.PUNCT && peek().text == stopPunct) return emptyList()
        return parseCommaSeparated(parseExpr()) { parseExpr() }
    }

    /** Parse a non-empty comma-separated list. */
    private fun <T> parseCommaSeparated(first: T, parseItem: () -> T): MutableList<T> {
        val list = ArrayList<T>()
        list.add(first)
        while (peekPunct(",")) {
            advance()
            list.add(parseItem())
        }
        return list
    }

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

    private fun parseLongLiteral(t: OznToken): Long = t.text.toLongOrNull()
        ?: throw OznParseException("integer literal `${t.text}` out of 64-bit range at line ${t.line}")
}

/** Raised when MiniZinc/Ozn parsing fails. */
class OznParseException(message: String) : FormatException("MiniZinc output", message)
