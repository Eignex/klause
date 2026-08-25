package com.eignex.klause.formats.opb

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.formats.CnfLowering
import com.eignex.klause.formats.FormatException
import com.eignex.klause.formats.channelBoolTo01
import com.eignex.klause.formats.tseitinAnd
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.CharSource
import com.eignex.klause.util.EmptyLongArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableIntLongMap
import com.eignex.klause.util.StringCharSource
import com.eignex.klause.util.lineSequence
import com.ionspin.kotlin.bignum.integer.BigInteger

/** Raised when an OPB/WBO document is malformed, so a caller can catch it via [FormatException] like
 *  the other input formats. */
class OpbFormatException(msg: String) : FormatException("OPB", msg)

private val LONG_MIN_BIG = BigInteger.fromLong(Long.MIN_VALUE)
private val LONG_MAX_BIG = BigInteger.fromLong(Long.MAX_VALUE)
private fun BigInteger.fitsLong(): Boolean = this in LONG_MIN_BIG..LONG_MAX_BIG

private fun opbError(msg: String): Nothing = throw OpbFormatException(msg)

private inline fun opbRequire(cond: Boolean, msg: () -> String) {
    if (!cond) throw OpbFormatException(msg())
}

/**
 * The document's tokens packed into one growable [CharArray] of concatenated characters plus per-token
 * start/end offsets. A `List<String>` costs on the order of 72 bytes per token once the String header,
 * its backing array and the list slot are counted, and every token stays live until the last factor is
 * built; the packed form holds the same tokens at ~9 bytes each, so ingesting a hundreds-of-MB instance
 * does not allocate multiples of the problem it produces.
 *
 * Tokens are addressed by index and read in place: a real [String] is materialized only for an error
 * message or an over-Int64 literal, never per token on the parsing path.
 */
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

    /** The characters of `[from, to)` as a [String] — reserved for error text and the wide-literal path. */
    fun slice(from: Int, to: Int): String = chars.concatToString(from, to)

    fun token(t: Int): String = slice(starts[t], ends[t])

    /** Tokens `[from, to)` rendered space-separated, for the error messages that quote a statement. */
    fun join(from: Int, to: Int): String {
        val sb = StringBuilder()
        for (t in from until to) {
            if (t > from) sb.append(' ')
            sb.appendRange(chars, starts[t], ends[t])
        }
        return sb.toString()
    }

    fun matches(t: Int, text: String): Boolean {
        val s = starts[t]
        if (ends[t] - s != text.length) return false
        for (k in text.indices) if (chars[s + k] != text[k]) return false
        return true
    }

    /** Whether `[from, to)` is `[+-]?\d+`, distinguishing an out-of-range number from a non-number. */
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

    /** The value of `[from, to)` as a [Long], or null when it is not an integer or overflows — the same
     *  contract as `String.toLongOrNull`. Accumulates negatively so `Long.MIN_VALUE` is representable. */
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

    /** The value of `[from, to)` as an [Int], with `String.toIntOrNull`'s contract. */
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

/** Scan [source] into a [TokenArena], dropping blank and `*` comment lines. */
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
            // `;` terminates a statement and need not be whitespace-separated (e.g. `... >= 1;`), so it is
            // split out of the run here rather than by rewriting the line, which would copy every line.
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

/** Parsed OPB instance and optional objective. */
data class OpbProblem(
    /** Compiled solver problem. */
    val problem: Problem,
    /** Objective, or null for satisfaction instances. */
    val objective: LinearObjective?,
    /** AND-indicator definitions for product terms — the local-search bool functional cone. */
    val boolFolds: List<DefinitionalSweep.BoolFoldSpec> = emptyList(),
    /** Count of declared `x1..xN` variables. [Problem.numBoolVars] also counts the Tseitin/soft
     *  indicators appended above them, so a model listing must use this to omit the aux variables. */
    val numDeclaredVars: Int = 0,
)

/**
 * Parser for OPB/WBO pseudo-Boolean instances: linear and non-linear (product terms), with
 * hard constraints and WBO soft constraints. A soft constraint `[c] Σ… ⟨op⟩ k` is reified to an
 * indicator `sat ⇔ (constraint holds)` and its violation charged `c·(1 − sat)` into the objective;
 * a `soft: top` header bounds the total violated cost below `top`.
 */
object Opb {

    /** A parsed term: [coef] times the conjunction of [lits] (a single literal when linear). */
    private class Term(val coef: BigInteger, val lits: IntArrayList)

    /** A parsed relation `Σ weights(i)·literals(i) op bound` shared by hard and soft constraints. An
     *  over-Int64 weight or bound makes it [wide] — lowered to a wide [Linear] over channeled {0,1} int
     *  vars; a narrow relation keeps the fast [Long] pseudo-Boolean path. */
    private class Relation(
        val weights: Array<BigInteger>,
        val literals: IntArray,
        val op: PbOp,
        val bound: BigInteger,
    ) {
        val wide: Boolean get() = !bound.fitsLong() || weights.any { !it.fitsLong() }
        fun longWeights(): LongArray = LongArray(weights.size) { weights[it].longValue() }
        fun longBound(): Long = bound.longValue()
    }

    /**
     * Accumulates the compiled problem. A product term `c l1 l2 ...` is a coefficient times an
     * AND of literals; it is Tseitin-reified to a fresh 0/1 indicator so the constraint stays a
     * linear [PseudoBoolean] over indicators. Indicator ids are handed out by [newBool] *above*
     * the declared `x1..xN`, so [numVars] must be seeded with the declared count first.
     */
    private class Builder : CnfLowering {
        override val factors = mutableListOf<Factor>()
        override var trueLitCache = -1
        var numVars = 0

        override fun newBool(): Int = numVars++

        /** {0,1} int vars minted to carry a wide constraint's literals (via [channelBoolTo01]); empty
         *  unless the model has an over-Int64 coefficient. */
        val intDomains = ArrayList<IntDomain>()

        fun newBinaryIntVar(): Int {
            intDomains.add(IntDomain(0, 1))
            return intDomains.size - 1
        }

        private val productCache = HashMap<List<Int>, Int>()

        /** AND-indicator definitions `b ↔ ⋀ lits` recovered as they are minted, so local search can
         *  derive the indicators from the literals and descend the objective through them. */
        val boolFolds = ArrayList<DefinitionalSweep.BoolFoldSpec>()

        /** The literal standing for a term's value: the literal itself when linear, else an AND indicator. */
        fun literalFor(lits: IntArrayList): Int {
            if (lits.size == 1) return lits[0]
            val key = lits.toIntArray().sorted()
            return productCache.getOrPut(key) {
                val indicator = tseitinAnd(key)
                boolFolds.add(DefinitionalSweep.BoolFoldSpec(Lit.variable(indicator), key.toIntArray(), isAnd = true))
                indicator
            }
        }
    }

    /** Parse OPB [text] into an [OpbProblem]. */
    fun parse(text: String): OpbProblem = parse(StringCharSource(text))

    /** Parse an OPB [source] into an [OpbProblem], consuming it line by line. */
    fun parse(source: CharSource): OpbProblem {
        val tokens = tokenize(source)

        val builder = Builder()
        // Declared variables occupy ids 0..maxIndex-1; seed the counter so indicators land above them.
        for (t in 0 until tokens.size) {
            varIndexOrNull(tokens, t)?.let { if (it > builder.numVars) builder.numVars = it }
        }
        // Captured before any indicator is minted, so it counts only the declared `x1..xN`.
        val numDeclaredVars = builder.numVars

        val objWeights = MutableIntLongMap()
        var objConstant = 0L
        var hasObjective = false
        // WBO soft constraints: a cost bound `soft: top` and the (cost, violation-literal) pairs.
        var softTop: Long? = null
        val softCosts = LongArrayList()
        val softViolations = IntArrayList()

        var i = 0
        while (i < tokens.size) {
            var end = i
            while (end < tokens.size && !tokens.matches(end, ";")) end++
            if (end == tokens.size) opbError("OPB statement missing ';' terminator near token index $i")
            val from = i
            i = end + 1
            if (from == end) continue

            if (tokens.matches(from, "min:")) {
                hasObjective = true
                for (term in parseTerms(tokens, from + 1, end)) {
                    addObjectiveTerm(
                        objWeights,
                        requireLong(term.coef, "objective coefficient"),
                        builder.literalFor(term.lits),
                    ) { objConstant += it }
                }
                continue
            }

            if (tokens.matches(from, "soft:")) {
                // `soft: top` bounds total violated cost strictly below top; the top is optional.
                softTop = if (from + 1 < end) parseLong(tokens, from + 1, "soft top") else null
                opbRequire(from + 1 == end || from + 2 == end) {
                    "OPB soft header has unexpected tokens: ${tokens.join(from, end)}"
                }
                continue
            }

            val softCost = parseSoftCost(tokens, from)
            val bodyFrom = if (softCost != null) from + 1 else from
            val relation = parseRelation(builder, tokens, bodyFrom, end)
            if (relation.wide) {
                if (softCost != null) opbError("OPB wide coefficients in a soft constraint are not supported")
                // Channel each literal to a {0,1} int var (1 iff the literal holds) and post the constraint
                // as a wide Linear `Σ weights·ivs op bound` — the exact pseudo-Boolean relation.
                val ivs = IntArray(relation.literals.size) { k ->
                    val lit = relation.literals[k]
                    val iv = builder.newBinaryIntVar()
                    channelBoolTo01(builder.factors, Lit.variable(lit), iv, whenTrue = Lit.isPositive(lit))
                    iv
                }
                builder.factors.add(Linear(ivs, relation.weights, toLinearOp(relation.op), relation.bound))
            } else if (softCost == null) {
                builder.factors.add(
                    PseudoBoolean(relation.longWeights(), relation.literals, relation.op, relation.longBound()),
                )
            } else {
                // Reify the soft relation to `sat`; a violation (¬sat) costs `softCost`.
                val sat = builder.newBool()
                builder.factors.add(
                    ReifiedPseudoBoolean(
                        sat,
                        relation.longWeights(),
                        relation.literals,
                        relation.op,
                        relation.longBound(),
                    ),
                )
                hasObjective = true
                objWeights.addTo(sat, -softCost)
                objConstant += softCost
                softCosts.add(softCost)
                softViolations.add(Lit.make(sat, positive = false))
            }
        }

        // A `soft: top` header rejects any assignment whose total violated cost reaches top.
        softTop?.let { top ->
            if (softViolations.size > 0) {
                opbRequire(top != Long.MIN_VALUE) { "OPB soft top is too small: $top" }
                builder.factors.add(
                    PseudoBoolean(softCosts.toLongArray(), softViolations.toIntArray(), PbOp.LE, top - 1),
                )
            }
        }

        val objective: LinearObjective? = if (!hasObjective) {
            null
        } else {
            val weights = LongArray(builder.numVars)
            objWeights.forEach { v, w -> weights[v] = w }
            LinearObjective(boolWeights = weights, intCoefficients = EmptyLongArray, constant = objConstant)
        }
        val problem = Problem(
            numBoolVars = builder.numVars,
            numIntVars = builder.intDomains.size,
            intDomains = builder.intDomains.toTypedArray(),
            factors = builder.factors.toTypedArray(),
            // Defer the base bake (root PB/unit propagation) to presolve step 0, so parsing only reads.
        )
        return OpbProblem(problem, objective, builder.boolFolds, numDeclaredVars)
    }

    /** Fold `weight·lit` into the objective, rewriting a negated literal `c·(~x)` as `c·(1 − x)`. */
    private inline fun addObjectiveTerm(
        objWeights: MutableIntLongMap,
        weight: Long,
        lit: Int,
        addConstant: (Long) -> Unit,
    ) {
        val v = Lit.variable(lit)
        if (Lit.isPositive(lit)) {
            objWeights.addTo(v, weight)
        } else {
            objWeights.addTo(v, -weight)
            addConstant(weight)
        }
    }

    /** Parse a `Σ terms ⟨op⟩ rhs` relation over tokens `[from, to)`, reifying any product term to an
     *  indicator literal. */
    private fun parseRelation(builder: Builder, tokens: TokenArena, from: Int, to: Int): Relation {
        var opIdx = -1
        for (t in from until to) {
            if (tokens.matches(t, ">=") || tokens.matches(t, "<=") || tokens.matches(t, "=")) {
                opIdx = t
                break
            }
        }
        if (opIdx < 0) opbError("OPB constraint missing relational operator: ${tokens.join(from, to)}")
        opbRequire(opIdx + 1 < to) {
            "OPB constraint missing right-hand side: ${tokens.join(from, to)}"
        }
        opbRequire(opIdx + 2 == to) {
            "OPB constraint has tokens after its right-hand side: ${tokens.join(from, to)}"
        }
        val rhs = parseBigInteger(tokens, opIdx + 1, "constraint rhs")
        val op = when {
            tokens.matches(opIdx, ">=") -> PbOp.GE
            tokens.matches(opIdx, "<=") -> PbOp.LE
            else -> PbOp.EQ
        }
        val weights = ArrayList<BigInteger>()
        val literals = IntArrayList()
        for (term in parseTerms(tokens, from, opIdx)) {
            weights.add(term.coef)
            literals.add(builder.literalFor(term.lits))
        }
        return Relation(weights.toTypedArray(), literals.toIntArray(), op, rhs)
    }

    /** The cost of a WBO soft constraint whose statement opens with a `[cost]` token, else null (hard). */
    private fun parseSoftCost(tokens: TokenArena, t: Int): Long? {
        val start = tokens.startOf(t)
        val end = tokens.endOf(t)
        if (end - start < 2 || tokens.charAt(start) != '[' || tokens.charAt(end - 1) != ']') return null
        return parseLongIn(tokens, start + 1, end - 1, "soft cost").also {
            opbRequire(it >= 0) { "OPB soft cost must be non-negative: $it" }
        }
    }

    /** Parse the OPB integer token [t] naming a [role]; see [parseLongIn]. */
    private fun parseLong(tokens: TokenArena, t: Int, role: String): Long =
        parseLongIn(tokens, tokens.startOf(t), tokens.endOf(t), role)

    /**
     * Parse the OPB integer text `[from, to)` naming a [role], rejecting a value that overflows the
     * 64-bit range with a distinct message: klause weights and domains are [Long], so a coefficient beyond
     * that can neither be represented nor solved, and treating it as "not an integer" would be misleading.
     */
    private fun parseLongIn(tokens: TokenArena, from: Int, to: Int, role: String): Long =
        tokens.longOrNull(from, to) ?: if (tokens.isIntegerText(from, to)) {
            opbError("OPB $role exceeds the supported 64-bit range: '${tokens.slice(from, to)}'")
        } else {
            opbError("OPB $role not an integer: '${tokens.slice(from, to)}'")
        }

    /** Parse the OPB integer token [t] naming a [role] at arbitrary precision: a value beyond 64 bits is
     *  kept (routed to the wide lane) rather than rejected; only a non-integer token is an error. */
    private fun parseBigInteger(tokens: TokenArena, t: Int, role: String): BigInteger {
        val from = tokens.startOf(t)
        val to = tokens.endOf(t)
        // A coefficient that fits 64 bits — every one on a normal instance — is converted without ever
        // materializing its text, so the hot path allocates no String per term.
        tokens.longOrNull(from, to)?.let { return BigInteger.fromLong(it) }
        opbRequire(tokens.isIntegerText(from, to)) { "OPB $role not an integer: '${tokens.slice(from, to)}'" }
        return BigInteger.parseString(tokens.slice(from, to).removePrefix("+"))
    }

    /** Narrow [v] to [Long], rejecting an over-Int64 value where the target cannot be wide (the objective). */
    private fun requireLong(v: BigInteger, role: String): Long =
        if (v.fitsLong()) v.longValue() else opbError("OPB $role exceeds the supported 64-bit range: '$v'")

    private fun toLinearOp(op: PbOp): LinearOp = when (op) {
        PbOp.LE -> LinearOp.LE
        PbOp.GE -> LinearOp.GE
        PbOp.EQ -> LinearOp.EQ
    }

    /** Parse the term sequence in tokens `[from, to)`: each term is a coefficient followed by one or
     *  more literals. */
    private fun parseTerms(tokens: TokenArena, from: Int, to: Int): List<Term> {
        val terms = mutableListOf<Term>()
        var idx = from
        while (idx < to) {
            val coef = parseBigInteger(tokens, idx, "coefficient")
            idx++
            val lits = IntArrayList()
            while (idx < to && isVarToken(tokens, idx)) {
                lits.add(parseLit(tokens, idx))
                idx++
            }
            opbRequire(lits.size > 0) { "OPB term missing variable after coefficient '$coef'" }
            terms.add(Term(coef, lits))
        }
        return terms
    }

    /** Whether token [t] is a (possibly negated) variable reference rather than a coefficient. */
    private fun isVarToken(tokens: TokenArena, t: Int): Boolean {
        val c = tokens.charAt(tokens.startOf(t))
        return c == 'x' || c == '~'
    }

    /** The 1-based variable index of token [t] (`x7` / `~x7` -> 7), or null when it is not a variable. */
    private fun varIndexOrNull(tokens: TokenArena, t: Int): Int? {
        if (!isVarToken(tokens, t)) return null
        var p = tokens.startOf(t)
        val end = tokens.endOf(t)
        if (tokens.charAt(p) == '~') p++
        if (p >= end || tokens.charAt(p) != 'x') return null
        return tokens.intOrNull(p + 1, end)?.takeIf { it >= 1 }
    }

    /** Parse the variable token [t] into a literal. */
    private fun parseLit(tokens: TokenArena, t: Int): Int {
        var p = tokens.startOf(t)
        val end = tokens.endOf(t)
        val negated = tokens.charAt(p) == '~'
        if (negated) p++
        opbRequire(p < end && tokens.charAt(p) == 'x') {
            "OPB variable must start with 'x', got '${tokens.token(t)}'"
        }
        val v = tokens.intOrNull(p + 1, end)?.minus(1)
            ?: opbError("OPB variable index not parseable: '${tokens.token(t)}'")
        opbRequire(v >= 0) { "OPB variable index out of range: '${tokens.token(t)}'" }
        return Lit.make(v, positive = !negated)
    }
}
