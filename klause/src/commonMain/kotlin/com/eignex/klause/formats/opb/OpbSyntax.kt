package com.eignex.klause.formats.opb

import com.eignex.klause.ir.Lit
import com.eignex.klause.model.PbOp
import com.eignex.klause.util.CharSource
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.lineSequence
import com.ionspin.kotlin.bignum.integer.BigInteger

internal data class OpbDocument(val numDeclaredVars: Int, val statements: List<OpbStatement>)

internal sealed interface OpbStatement {
    data class Objective(val terms: List<OpbTerm>) : OpbStatement
    data class SoftHeader(val top: Long?) : OpbStatement
    data class Constraint(val softCost: Long?, val relation: OpbRelation) : OpbStatement
}

internal data class OpbTerm(val coefficient: BigInteger, val literals: IntArray)

internal data class OpbRelation(val terms: List<OpbTerm>, val op: PbOp, val bound: BigInteger)

/** Syntax parser for OPB/WBO. It only validates and represents the input; lowering is format-neutral. */
internal object OpbSyntax {

    fun parse(source: CharSource): OpbDocument {
        val tokens = tokenize(source)
        var numDeclaredVars = 0
        for (t in 0 until tokens.size) {
            varIndexOrNull(tokens, t)?.let { numDeclaredVars = maxOf(numDeclaredVars, it) }
        }

        val statements = mutableListOf<OpbStatement>()
        var i = 0
        while (i < tokens.size) {
            var end = i
            while (end < tokens.size && !tokens.matches(end, ";")) end++
            if (end == tokens.size) opbError("OPB statement missing ';' terminator near token index $i")
            val from = i
            i = end + 1
            if (from == end) continue

            when {
                tokens.matches(from, "min:") -> statements += OpbStatement.Objective(parseTerms(tokens, from + 1, end))

                tokens.matches(from, "soft:") -> {
                    val top = if (from + 1 < end) parseLong(tokens, from + 1, "soft top") else null
                    opbRequire(from + 1 == end || from + 2 == end) {
                        "OPB soft header has unexpected tokens: ${tokens.join(from, end)}"
                    }
                    statements += OpbStatement.SoftHeader(top)
                }

                else -> {
                    val softCost = parseSoftCost(tokens, from)
                    val bodyFrom = if (softCost != null) from + 1 else from
                    statements += OpbStatement.Constraint(softCost, parseRelation(tokens, bodyFrom, end))
                }
            }
        }
        return OpbDocument(numDeclaredVars, statements)
    }

    private fun parseRelation(tokens: TokenArena, from: Int, to: Int): OpbRelation {
        var opIdx = -1
        for (t in from until to) {
            if (tokens.matches(t, ">=") || tokens.matches(t, "<=") || tokens.matches(t, "=")) {
                opIdx = t
                break
            }
        }
        if (opIdx < 0) opbError("OPB constraint missing relational operator: ${tokens.join(from, to)}")
        opbRequire(opIdx + 1 < to) { "OPB constraint missing right-hand side: ${tokens.join(from, to)}" }
        opbRequire(opIdx + 2 == to) { "OPB constraint has tokens after its right-hand side: ${tokens.join(from, to)}" }
        val op = when {
            tokens.matches(opIdx, ">=") -> PbOp.GE
            tokens.matches(opIdx, "<=") -> PbOp.LE
            else -> PbOp.EQ
        }
        return OpbRelation(parseTerms(tokens, from, opIdx), op, parseBigInteger(tokens, opIdx + 1, "constraint rhs"))
    }

    private fun parseTerms(tokens: TokenArena, from: Int, to: Int): List<OpbTerm> {
        val terms = mutableListOf<OpbTerm>()
        var idx = from
        while (idx < to) {
            val coefficient = parseBigInteger(tokens, idx, "coefficient")
            idx++
            val literals = IntArrayList()
            while (idx < to && isVarToken(tokens, idx)) {
                literals.add(parseLit(tokens, idx))
                idx++
            }
            opbRequire(literals.size > 0) { "OPB term missing variable after coefficient '$coefficient'" }
            terms += OpbTerm(coefficient, literals.toIntArray())
        }
        return terms
    }

    private fun parseSoftCost(tokens: TokenArena, t: Int): Long? {
        val start = tokens.startOf(t)
        val end = tokens.endOf(t)
        if (end - start < 2 || tokens.charAt(start) != '[' || tokens.charAt(end - 1) != ']') return null
        return parseLongIn(tokens, start + 1, end - 1, "soft cost").also {
            opbRequire(it >= 0) { "OPB soft cost must be non-negative: $it" }
        }
    }

    private fun parseLong(tokens: TokenArena, t: Int, role: String): Long =
        parseLongIn(tokens, tokens.startOf(t), tokens.endOf(t), role)

    private fun parseLongIn(tokens: TokenArena, from: Int, to: Int, role: String): Long =
        tokens.longOrNull(from, to) ?: if (tokens.isIntegerText(from, to)) {
            opbError("OPB $role exceeds the supported 64-bit range: '${tokens.slice(from, to)}'")
        } else {
            opbError("OPB $role not an integer: '${tokens.slice(from, to)}'")
        }

    private fun parseBigInteger(tokens: TokenArena, t: Int, role: String): BigInteger {
        val from = tokens.startOf(t)
        val to = tokens.endOf(t)
        tokens.longOrNull(from, to)?.let { return BigInteger.fromLong(it) }
        opbRequire(tokens.isIntegerText(from, to)) { "OPB $role not an integer: '${tokens.slice(from, to)}'" }
        return BigInteger.parseString(tokens.slice(from, to).removePrefix("+"))
    }

    private fun isVarToken(tokens: TokenArena, t: Int): Boolean {
        val c = tokens.charAt(tokens.startOf(t))
        return c == 'x' || c == '~'
    }

    private fun varIndexOrNull(tokens: TokenArena, t: Int): Int? {
        if (!isVarToken(tokens, t)) return null
        var p = tokens.startOf(t)
        val end = tokens.endOf(t)
        if (tokens.charAt(p) == '~') p++
        if (p >= end || tokens.charAt(p) != 'x') return null
        return tokens.intOrNull(p + 1, end)?.takeIf { it >= 1 }
    }

    private fun parseLit(tokens: TokenArena, t: Int): Int {
        var p = tokens.startOf(t)
        val end = tokens.endOf(t)
        val negated = tokens.charAt(p) == '~'
        if (negated) p++
        opbRequire(p < end && tokens.charAt(p) == 'x') { "OPB variable must start with 'x', got '${tokens.token(t)}'" }
        val v = tokens.intOrNull(p + 1, end)?.minus(1)
            ?: opbError("OPB variable index not parseable: '${tokens.token(t)}'")
        opbRequire(v >= 0) { "OPB variable index out of range: '${tokens.token(t)}'" }
        return Lit.make(v, positive = !negated)
    }
}

private fun opbError(msg: String): Nothing = throw OpbFormatException(msg)

private inline fun opbRequire(condition: Boolean, msg: () -> String) {
    if (!condition) throw OpbFormatException(msg())
}

private class TokenArena {
    private var chars = CharArray(INITIAL_CHARS)
    private var charCount = 0
    private var starts = IntArray(INITIAL_TOKENS)
    private var ends = IntArray(INITIAL_TOKENS)

    var size: Int = 0
        private set

    fun startOf(t: Int): Int = starts[t]
    fun endOf(t: Int): Int = ends[t]
    fun charAt(i: Int): Char = chars[i]

    fun add(line: String, from: Int, to: Int) {
        if (size == starts.size) {
            starts = starts.copyOf(size * 2)
            ends = ends.copyOf(size * 2)
        }
        val len = to - from
        if (charCount + len > chars.size) {
            var cap = chars.size * 2
            while (cap < charCount + len) cap *= 2
            chars = chars.copyOf(cap)
        }
        starts[size] = charCount
        for (k in 0 until len) chars[charCount + k] = line[from + k]
        charCount += len
        ends[size] = charCount
        size++
    }

    fun slice(from: Int, to: Int): String = chars.concatToString(from, to)
    fun token(t: Int): String = slice(starts[t], ends[t])

    fun join(from: Int, to: Int): String = buildString {
        for (t in from until to) {
            if (t > from) append(' ')
            appendRange(chars, starts[t], ends[t])
        }
    }

    fun matches(t: Int, text: String): Boolean {
        val start = starts[t]
        if (ends[t] - start != text.length) return false
        for (k in text.indices) if (chars[start + k] != text[k]) return false
        return true
    }

    fun isIntegerText(from: Int, to: Int): Boolean {
        var p = from
        if (p < to && (chars[p] == '+' || chars[p] == '-')) p++
        if (p >= to) return false
        while (p < to) {
            if (chars[p] !in '0'..'9') return false
            p++
        }
        return true
    }

    fun longOrNull(from: Int, to: Int): Long? {
        var p = from
        if (p >= to) return null
        val negative = chars[p] == '-'
        if (negative || chars[p] == '+') p++
        if (p >= to) return null
        var acc = 0L
        while (p < to) {
            val c = chars[p]
            if (c !in '0'..'9') return null
            val digit = c - '0'
            if (acc < OVERFLOW_LIMIT) return null
            acc *= 10
            if (acc < Long.MIN_VALUE + digit) return null
            acc -= digit
            p++
        }
        if (negative) return acc
        return if (acc == Long.MIN_VALUE) null else -acc
    }

    fun intOrNull(from: Int, to: Int): Int? {
        val v = longOrNull(from, to) ?: return null
        return if (v in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) v.toInt() else null
    }

    private companion object {
        const val INITIAL_CHARS = 1 shl 12
        const val INITIAL_TOKENS = 1 shl 10
        const val OVERFLOW_LIMIT = Long.MIN_VALUE / 10
    }
}

private fun tokenize(source: CharSource): TokenArena {
    val arena = TokenArena()
    for (line in source.lineSequence()) {
        val n = line.length
        var i = 0
        while (i < n && line[i].isWhitespace()) i++
        if (i >= n || line[i] == '*') continue
        while (i < n) {
            while (i < n && line[i].isWhitespace()) i++
            if (i >= n) break
            if (line[i] == ';') {
                arena.add(line, i, i + 1)
                i++
                continue
            }
            val start = i
            while (i < n && !line[i].isWhitespace() && line[i] != ';') i++
            arena.add(line, start, i)
        }
    }
    return arena
}
