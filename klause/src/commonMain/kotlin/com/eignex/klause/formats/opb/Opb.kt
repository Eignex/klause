package com.eignex.klause.formats.opb

import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.formats.CnfLowering
import com.eignex.klause.formats.tseitinAnd
import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.EmptyLongArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableIntDoubleMap

/** Parsed OPB instance and optional objective. */
data class OpbProblem(
    /** Compiled solver problem. */
    val problem: Problem,
    /** Objective, or null for satisfaction instances. */
    val objective: LinearObjective?,
)

/** Parser for OPB pseudo-Boolean instances, linear and non-linear (product terms). */
object Opb {

    /** A parsed term: [coef] times the conjunction of [lits] (a single literal when linear). */
    private class Term(val coef: Long, val lits: IntArrayList)

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

        /** The literal standing for a term's value: the literal itself when linear, else an AND indicator. */
        fun literalFor(lits: IntArrayList): Int {
            if (lits.size == 1) return lits[0]
            val key = lits.toIntArray().sorted()
            return productCache.getOrPut(key) { tseitinAnd(key) }
        }
    }

    /** Parse OPB [text] into an [OpbProblem]. */
    fun parse(text: String): OpbProblem {
        val tokens = mutableListOf<String>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("*")) continue
            tokens.addAll(line.split(Regex("\\s+")).filter { it.isNotEmpty() })
        }

        val builder = Builder()
        // Declared variables occupy ids 0..maxIndex-1; seed the counter so indicators land above them.
        for (t in tokens) varIndexOrNull(t)?.let { if (it > builder.numVars) builder.numVars = it }

        val objWeights = MutableIntDoubleMap()
        var objConstant = 0.0
        var hasObjective = false

        var i = 0
        while (i < tokens.size) {
            var end = i
            while (end < tokens.size && tokens[end] != ";") end++
            if (end == tokens.size) error("OPB statement missing ';' terminator near token index $i")
            val stmt = tokens.subList(i, end)
            i = end + 1
            if (stmt.isEmpty()) continue

            if (stmt[0] == "min:") {
                hasObjective = true
                for (term in parseTerms(stmt.subList(1, stmt.size))) {
                    val w = term.coef.toDouble()
                    val lit = builder.literalFor(term.lits)
                    val v = Lit.variable(lit)
                    if (Lit.isPositive(lit)) {
                        objWeights.addTo(v, w)
                    } else {
                        // c*(~x) in OPB objective is c*(1-x).
                        objWeights.addTo(v, -w)
                        objConstant += w
                    }
                }
                continue
            }

            val opIdx = stmt.indexOfFirst { it == ">=" || it == "<=" || it == "=" }
            if (opIdx < 0) error("OPB constraint missing relational operator: ${stmt.joinToString(" ")}")
            require(opIdx + 1 < stmt.size) {
                "OPB constraint missing right-hand side: ${stmt.joinToString(" ")}"
            }
            val rhs = stmt[opIdx + 1].toLongOrNull()
                ?: error("OPB constraint rhs not an integer: '${stmt[opIdx + 1]}'")
            val pbOp = when (stmt[opIdx]) {
                ">=" -> PbOp.GE
                "<=" -> PbOp.LE
                "=" -> PbOp.EQ
                else -> error("unknown OPB operator '${stmt[opIdx]}'")
            }
            val weights = LongArrayList()
            val literals = IntArrayList()
            for (term in parseTerms(stmt.subList(0, opIdx))) {
                weights.add(term.coef)
                literals.add(builder.literalFor(term.lits))
            }
            builder.factors.add(
                PseudoBoolean(
                    weights = weights.toLongArray(),
                    literals = literals.toIntArray(),
                    op = pbOp,
                    bound = rhs,
                ),
            )
        }

        val objective: LinearObjective? = if (!hasObjective) {
            null
        } else {
            val weights = LongArray(builder.numVars)
            objWeights.forEach { v, w -> weights[v] = w.toLong() }
            LinearObjective(boolWeights = weights, intCoefficients = EmptyLongArray, constant = objConstant.toLong())
        }
        val problem = Problem(
            numBoolVars = builder.numVars,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = builder.factors.toTypedArray(),
        )
        return OpbProblem(problem, objective)
    }

    /** Parse a term sequence: each term is a coefficient followed by one or more literals. */
    private fun parseTerms(tokens: List<String>): List<Term> {
        val terms = mutableListOf<Term>()
        var idx = 0
        while (idx < tokens.size) {
            val coef = tokens[idx].toLongOrNull()
                ?: error("OPB coefficient not an integer: '${tokens[idx]}'")
            idx++
            val lits = IntArrayList()
            while (idx < tokens.size && isVarToken(tokens[idx])) {
                lits.add(parseLit(tokens[idx]))
                idx++
            }
            require(lits.size > 0) { "OPB term missing variable after coefficient '$coef'" }
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
        require(rawVar.startsWith("x")) { "OPB variable must start with 'x', got '$token'" }
        val v = rawVar.substring(1).toIntOrNull()?.minus(1)
            ?: error("OPB variable index not parseable: '$token'")
        require(v >= 0) { "OPB variable index out of range: '$token'" }
        return Lit.make(v, positive = !negated)
    }
}
