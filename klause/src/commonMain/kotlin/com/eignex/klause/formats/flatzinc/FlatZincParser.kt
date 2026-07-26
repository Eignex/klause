package com.eignex.klause.formats.flatzinc

import com.eignex.klause.util.LongArrayList

/** Recursive-descent parser for FlatZinc 1.6. */
internal class FlatZincParser(private val tokens: List<FznToken>) {
    private var pos: Int = 0

    fun parse(): FznModel {
        while (peek() is FznToken.Kw && (peek() as FznToken.Kw).keyword == "predicate") {
            skipUntilSemicolon()
        }
        while (peek() is FznToken.Kw && (peek() as FznToken.Kw).keyword == "annotation") {
            skipUntilSemicolon()
        }
        val decls = ArrayList<FznVarDecl>()
        while (isStartOfVarDecl()) {
            decls.add(parseVarDecl())
        }
        val constraints = ArrayList<FznConstraint>()
        while (peek() is FznToken.Kw && (peek() as FznToken.Kw).keyword == "constraint") {
            constraints.add(parseConstraint())
        }
        val solve = parseSolve()
        var output: List<FznExpr>? = null
        if (peek() is FznToken.Kw && (peek() as FznToken.Kw).keyword == "output") {
            output = parseOutput()
        }
        return FznModel(decls, constraints, solve, output)
    }

    private fun isStartOfVarDecl(): Boolean {
        val t = peek()
        if (t !is FznToken.Kw) return false
        return when (t.keyword) {
            "var", "par", "array", "bool", "int", "float" -> true
            else -> false
        }
    }

    private fun parseVarDecl(): FznVarDecl {
        val startLine = peek().line
        val startCol = peek().col
        var isVar = false
        if (matchKw("var")) {
            isVar = true
        } else if (matchKw("par")) {
            isVar = false
        }
        val type: FznType = parseType()
        expect(":", "expected `:` in variable declaration")
        val name = expectIdent()
        val anns = parseAnnotations()
        var value: FznExpr? = null
        if (peek() is FznToken.Punct && (peek() as FznToken.Punct).symbol == "=") {
            advance()
            value = parseExpr()
        }
        expect(";", "expected `;` ending declaration")
        val isVarFinal = isVar || (type is FznType.Array && type.elementIsVar)
        return FznVarDecl(name, type, isVarFinal, anns, value, startLine, startCol)
    }

    private fun parseType(): FznType {
        val tok = peek()
        if (tok is FznToken.Kw && tok.keyword == "array") {
            advance()
            expect("[", "expected `[` after `array`")
            val lo = expectIntLit()
            expect("..", "expected `..` in array index range")
            val hi = expectIntLit()
            require(lo == 1L) {
                "FlatZinc arrays are 1-indexed; got [$lo..$hi]"
            }
            require(hi in 0..Int.MAX_VALUE.toLong()) {
                "FlatZinc array length out of range: [$lo..$hi]"
            }
            expect("]", "expected `]` closing index range")
            expectKw("of")
            val elementIsVar = matchKw("var")
            val element = parseScalarType()
            return FznType.Array(hi.toInt(), element, elementIsVar)
        }
        return parseScalarType()
    }

    private fun parseScalarType(): FznType {
        val tok = peek()
        if (tok is FznToken.Kw) {
            return when (tok.keyword) {
                "bool" -> {
                    advance()
                    FznType.Bool
                }

                "int" -> {
                    advance()
                    FznType.IntAny
                }

                "float" -> {
                    advance()
                    FznType.FloatAny
                }

                "set" -> {
                    advance()
                    expectKw("of")
                    val element = parseScalarType()
                    FznType.SetOfInt(element)
                }

                else -> failHere("unexpected type keyword '${tok.keyword}'")
            }
        }
        if (tok is FznToken.IntLit) {
            val lo = tok.value
            advance()
            expect("..", "expected `..` in int range")
            val hi = expectIntLit()
            return FznType.IntRange(lo, hi)
        }
        if (tok is FznToken.FloatLit) {
            val lo = tok.value
            advance()
            expect("..", "expected `..` in float range")
            val hi = expectFloatOrIntAsDouble()
            return FznType.FloatRange(lo, hi)
        }
        if (tok is FznToken.Punct && tok.symbol == "{") {
            advance()
            val values = LongArrayList()
            if (peek() !is FznToken.Punct || (peek() as FznToken.Punct).symbol != "}") {
                values.add(expectIntLit())
                while (matchPunct(",")) values.add(expectIntLit())
            }
            expect("}", "expected `}` closing int set")
            return FznType.IntSet(values.toLongArray())
        }
        failHere("expected a type")
    }

    private fun parseAnnotations(): List<FznAnnotation> {
        val out = ArrayList<FznAnnotation>()
        while (peek() is FznToken.Punct && (peek() as FznToken.Punct).symbol == "::") {
            advance()
            val ann = parseAnnotation()
            out.add(ann)
        }
        return out
    }

    private fun parseAnnotation(): FznAnnotation {
        val name = expectIdent()
        val args = ArrayList<FznExpr>()
        if (matchPunct("(")) {
            if (peek() !is FznToken.Punct || (peek() as FznToken.Punct).symbol != ")") {
                args.add(parseExpr())
                while (matchPunct(",")) args.add(parseExpr())
            }
            expect(")", "expected `)` closing annotation args")
        }
        return FznAnnotation(name, args)
    }

    private fun parseConstraint(): FznConstraint {
        val startLine = peek().line
        val startCol = peek().col
        expectKw("constraint")
        val name = expectIdent()
        expect("(", "expected `(` after constraint name")
        val args = ArrayList<FznExpr>()
        if (peek() !is FznToken.Punct || (peek() as FznToken.Punct).symbol != ")") {
            args.add(parseExpr())
            while (matchPunct(",")) args.add(parseExpr())
        }
        expect(")", "expected `)` closing constraint args")
        val anns = parseAnnotations()
        expect(";", "expected `;` after constraint")
        return FznConstraint(name, args, anns, startLine, startCol)
    }

    private fun parseSolve(): FznSolve {
        expectKw("solve")
        val anns = parseAnnotations()
        val tok = peek()
        if (tok !is FznToken.Kw) failHere("expected `satisfy` / `minimize` / `maximize`")
        return when (tok.keyword) {
            "satisfy" -> {
                advance()
                expect(";", "expected `;`")
                FznSolve.Satisfy(anns)
            }

            "minimize" -> {
                advance()
                val obj = parseExpr()
                expect(";", "expected `;`")
                FznSolve.Minimize(anns, obj)
            }

            "maximize" -> {
                advance()
                val obj = parseExpr()
                expect(";", "expected `;`")
                FznSolve.Maximize(anns, obj)
            }

            else -> failHere("unexpected solve goal '${tok.keyword}'")
        }
    }

    private fun parseOutput(): List<FznExpr> {
        expectKw("output")
        expect("[", "expected `[` after output")
        val items = ArrayList<FznExpr>()
        if (peek() !is FznToken.Punct || (peek() as FznToken.Punct).symbol != "]") {
            items.add(parseExpr())
            while (matchPunct(",")) items.add(parseExpr())
        }
        expect("]", "expected `]` closing output")
        expect(";", "expected `;` after output")
        return items
    }

    private fun parseExpr(): FznExpr = when (val tok = peek()) {
        is FznToken.Kw -> when (tok.keyword) {
            "true" -> {
                advance()
                FznExpr.BoolLit(true)
            }

            "false" -> {
                advance()
                FznExpr.BoolLit(false)
            }

            else -> failHere("unexpected keyword '${tok.keyword}' in expression")
        }

        is FznToken.IntLit -> {
            val lo = tok.value
            advance()
            if (peek() is FznToken.Punct && (peek() as FznToken.Punct).symbol == "..") {
                advance()
                val hi = expectIntLit()
                FznExpr.IntRangeLit(lo, hi)
            } else {
                FznExpr.IntLit(lo)
            }
        }

        is FznToken.FloatLit -> {
            advance()
            FznExpr.FloatLit(tok.value)
        }

        is FznToken.StringLit -> {
            advance()
            FznExpr.StringLit(tok.value)
        }

        is FznToken.Ident -> {
            val name = tok.name
            advance()
            if (peek() is FznToken.Punct && (peek() as FznToken.Punct).symbol == "[") {
                advance()
                val idx = expectIntLit()
                expect("]", "expected `]` closing array access")
                FznExpr.ArrayAccess(name, idx.toInt())
            } else if (peek() is FznToken.Punct && (peek() as FznToken.Punct).symbol == "(") {
                advance()
                val args = ArrayList<FznExpr>()
                if (peek() !is FznToken.Punct || (peek() as FznToken.Punct).symbol != ")") {
                    args.add(parseExpr())
                    while (matchPunct(",")) args.add(parseExpr())
                }
                expect(")", "expected `)` closing call")
                FznExpr.AnnCall(name, args)
            } else {
                FznExpr.Ident(name)
            }
        }

        is FznToken.Punct -> when (tok.symbol) {
            "[" -> parseArrayLit()
            "{" -> parseIntSetLit()
            else -> failHere("unexpected '${tok.symbol}' in expression")
        }

        is FznToken.Eof -> failHere("unexpected end of file in expression")
    }

    private fun parseArrayLit(): FznExpr {
        expect("[", "expected `[`")
        val items = ArrayList<FznExpr>()
        if (peek() !is FznToken.Punct || (peek() as FznToken.Punct).symbol != "]") {
            items.add(parseExpr())
            while (matchPunct(",")) items.add(parseExpr())
        }
        expect("]", "expected `]` closing array literal")
        return FznExpr.ArrayLit(items)
    }

    private fun parseIntSetLit(): FznExpr {
        expect("{", "expected `{`")
        val values = LongArrayList()
        if (peek() !is FznToken.Punct || (peek() as FznToken.Punct).symbol != "}") {
            values.add(expectIntLit())
            while (matchPunct(",")) values.add(expectIntLit())
        }
        expect("}", "expected `}` closing set literal")
        return FznExpr.IntSetLit(values.toLongArray())
    }

    private fun peek(): FznToken = tokens[pos]
    private fun advance(): FznToken = tokens[pos++]

    private fun matchKw(kw: String): Boolean {
        val t = peek()
        if (t is FznToken.Kw && t.keyword == kw) {
            advance()
            return true
        }
        return false
    }
    private fun matchPunct(s: String): Boolean {
        val t = peek()
        if (t is FznToken.Punct && t.symbol == s) {
            advance()
            return true
        }
        return false
    }
    private fun expectKw(kw: String) {
        if (!matchKw(kw)) failHere("expected keyword `$kw`")
    }
    private fun expect(symbol: String, why: String) {
        if (!matchPunct(symbol)) failHere(why)
    }
    private fun expectIdent(): String {
        val t = peek()
        if (t !is FznToken.Ident) failHere("expected identifier")
        advance()
        return t.name
    }
    private fun expectIntLit(): Long {
        val t = peek()
        if (t !is FznToken.IntLit) failHere("expected int literal")
        advance()
        return t.value
    }
    private fun expectFloatOrIntAsDouble(): Double = when (val t = peek()) {
        is FznToken.FloatLit -> {
            advance()
            t.value
        }

        is FznToken.IntLit -> {
            advance()
            t.value.toDouble()
        }

        else -> failHere("expected float literal")
    }
    private fun failHere(msg: String): Nothing {
        val t = peek()
        throw FlatZincParseException(msg, t.line, t.col)
    }
    private fun skipUntilSemicolon() {
        while (peek() !is FznToken.Eof) {
            val t = advance()
            if (t is FznToken.Punct && t.symbol == ";") return
        }
    }
}
