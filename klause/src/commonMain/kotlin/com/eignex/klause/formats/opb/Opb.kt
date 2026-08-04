package com.eignex.klause.formats.opb

import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.formats.CnfLowering
import com.eignex.klause.formats.FormatException
import com.eignex.klause.formats.splitWhitespace
import com.eignex.klause.formats.tseitinAnd
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
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

/** Raised when an OPB/WBO document is malformed, so a caller can catch it via [FormatException] like
 *  the other input formats. */
class OpbFormatException(msg: String) : FormatException("OPB", msg)

private fun opbError(msg: String): Nothing = throw OpbFormatException(msg)

private inline fun opbRequire(cond: Boolean, msg: () -> String) {
    if (!cond) throw OpbFormatException(msg())
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

    /** A signed integer literal, used to tell an out-of-64-bit-range value from a non-numeric token. */
    private val INTEGER = Regex("[+-]?\\d+")

    /** A parsed term: [coef] times the conjunction of [lits] (a single literal when linear). */
    private class Term(val coef: Long, val lits: IntArrayList)

    /** A parsed relation `Σ weights(i)·literals(i) op bound` shared by hard and soft constraints. */
    private class Relation(val weights: LongArray, val literals: IntArray, val op: PbOp, val bound: Long)

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
        val tokens = mutableListOf<String>()
        for (rawLine in source.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("*")) continue
            // `;` terminates a statement and need not be whitespace-separated (e.g. `... >= 1;`), so
            // isolate it into its own token before splitting.
            tokens.addAll(line.replace(";", " ; ").splitWhitespace())
        }

        val builder = Builder()
        // Declared variables occupy ids 0..maxIndex-1; seed the counter so indicators land above them.
        for (t in tokens) varIndexOrNull(t)?.let { if (it > builder.numVars) builder.numVars = it }
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
            while (end < tokens.size && tokens[end] != ";") end++
            if (end == tokens.size) opbError("OPB statement missing ';' terminator near token index $i")
            val stmt = tokens.subList(i, end)
            i = end + 1
            if (stmt.isEmpty()) continue

            if (stmt[0] == "min:") {
                hasObjective = true
                for (term in parseTerms(stmt.subList(1, stmt.size))) {
                    addObjectiveTerm(
                        objWeights,
                        term.coef,
                        builder.literalFor(term.lits),
                    ) { objConstant += it }
                }
                continue
            }

            if (stmt[0] == "soft:") {
                // `soft: top` bounds total violated cost strictly below top; the top is optional.
                softTop = stmt.getOrNull(1)?.let { parseLong(it, "soft top") }
                continue
            }

            val softCost = parseSoftCost(stmt[0])
            val body = if (softCost != null) stmt.subList(1, stmt.size) else stmt
            val relation = parseRelation(builder, body)
            if (softCost == null) {
                builder.factors.add(PseudoBoolean(relation.weights, relation.literals, relation.op, relation.bound))
            } else {
                // Reify the soft relation to `sat`; a violation (¬sat) costs `softCost`.
                val sat = builder.newBool()
                builder.factors.add(
                    ReifiedPseudoBoolean(sat, relation.weights, relation.literals, relation.op, relation.bound),
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
            numIntVars = 0,
            intDomains = emptyArray(),
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

    /** Parse a `Σ terms ⟨op⟩ rhs` relation, reifying any product term to an indicator literal. */
    private fun parseRelation(builder: Builder, tokens: List<String>): Relation {
        val opIdx = tokens.indexOfFirst { it == ">=" || it == "<=" || it == "=" }
        if (opIdx < 0) opbError("OPB constraint missing relational operator: ${tokens.joinToString(" ")}")
        opbRequire(opIdx + 1 < tokens.size) {
            "OPB constraint missing right-hand side: ${tokens.joinToString(" ")}"
        }
        val rhs = parseLong(tokens[opIdx + 1], "constraint rhs")
        val op = when (tokens[opIdx]) {
            ">=" -> PbOp.GE
            "<=" -> PbOp.LE
            "=" -> PbOp.EQ
            else -> opbError("unknown OPB operator '${tokens[opIdx]}'")
        }
        val weights = LongArrayList()
        val literals = IntArrayList()
        for (term in parseTerms(tokens.subList(0, opIdx))) {
            weights.add(term.coef)
            literals.add(builder.literalFor(term.lits))
        }
        return Relation(weights.toLongArray(), literals.toIntArray(), op, rhs)
    }

    /** The cost of a WBO soft constraint whose statement opens with a `[cost]` token, else null (hard). */
    private fun parseSoftCost(token: String): Long? {
        if (!(token.startsWith("[") && token.endsWith("]"))) return null
        return parseLong(token.substring(1, token.length - 1), "soft cost")
    }

    /**
     * Parse an OPB integer [token] naming a [role], rejecting a value that overflows the 64-bit range
     * with a distinct message: klause weights and domains are [Long], so a coefficient beyond that can
     * neither be represented nor solved, and treating it as "not an integer" would be misleading.
     */
    private fun parseLong(token: String, role: String): Long = token.toLongOrNull() ?: if (INTEGER.matches(token)) {
        opbError("OPB $role exceeds the supported 64-bit range: '$token'")
    } else {
        opbError("OPB $role not an integer: '$token'")
    }

    /** Parse a term sequence: each term is a coefficient followed by one or more literals. */
    private fun parseTerms(tokens: List<String>): List<Term> {
        val terms = mutableListOf<Term>()
        var idx = 0
        while (idx < tokens.size) {
            val coef = parseLong(tokens[idx], "coefficient")
            idx++
            val lits = IntArrayList()
            while (idx < tokens.size && isVarToken(tokens[idx])) {
                lits.add(parseLit(tokens[idx]))
                idx++
            }
            opbRequire(lits.size > 0) { "OPB term missing variable after coefficient '$coef'" }
            terms.add(Term(coef, lits))
        }
        return terms
    }

    /** Whether [token] is a (possibly negated) variable reference rather than a coefficient. */
    private fun isVarToken(token: String): Boolean = token.startsWith("x") || token.startsWith("~")

    /** The 1-based variable index of [token] (`x7` / `~x7` -> 7), or null when it is not a variable. */
    private fun varIndexOrNull(token: String): Int? {
        if (!isVarToken(token)) return null
        val raw = if (token.startsWith("~")) token.substring(1) else token
        if (!raw.startsWith("x")) return null
        return raw.substring(1).toIntOrNull()?.takeIf { it >= 1 }
    }

    /** Parse a variable [token] into a literal. */
    private fun parseLit(token: String): Int {
        val negated = token.startsWith("~")
        val rawVar = if (negated) token.substring(1) else token
        opbRequire(rawVar.startsWith("x")) { "OPB variable must start with 'x', got '$token'" }
        val v = rawVar.substring(1).toIntOrNull()?.minus(1)
            ?: opbError("OPB variable index not parseable: '$token'")
        opbRequire(v >= 0) { "OPB variable index out of range: '$token'" }
        return Lit.make(v, positive = !negated)
    }
}
