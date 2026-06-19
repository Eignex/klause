package com.eignex.klause.formats.opb

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.objective.LinearObjective

/** Parsed OPB instance and optional objective. */
data class OpbProblem(
    /** Compiled solver problem. */
    val problem: Problem,
    /** Objective, or null for satisfaction instances. */
    val objective: LinearObjective?,
)

/** Parser for OPB pseudo-Boolean instances. */
object Opb {

    /** Parse OPB [text] into an [OpbProblem]. */
    fun parse(text: String): OpbProblem {
        val tokens = mutableListOf<String>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("*")) continue
            tokens.addAll(line.split(Regex("\\s+")).filter { it.isNotEmpty() })
        }

        val factors = mutableListOf<Factor>()
        val objWeights = mutableMapOf<Int, Double>()
        var objConstant = 0.0
        var hasObjective = false
        var numVars = 0

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
                val (weights, literals) = parseTerms(stmt.subList(1, stmt.size))
                for (j in weights.indices) {
                    val w = weights[j].toDouble()
                    val lit = literals[j]
                    val v = Lit.variable(lit)
                    if (Lit.isPositive(lit)) {
                        objWeights[v] = (objWeights[v] ?: 0.0) + w
                    } else {
                        // c*(~x) in OPB objective is c*(1-x).
                        objWeights[v] = (objWeights[v] ?: 0.0) - w
                        objConstant += w
                    }
                    if (v + 1 > numVars) numVars = v + 1
                }
                continue
            }

            val opIdx = stmt.indexOfFirst { it == ">=" || it == "<=" || it == "=" }
            if (opIdx < 0) error("OPB constraint missing relational operator: ${stmt.joinToString(" ")}")
            require(opIdx + 1 < stmt.size) {
                "OPB constraint missing right-hand side: ${stmt.joinToString(" ")}"
            }
            val (weights, literals) = parseTerms(stmt.subList(0, opIdx))
            val rhs = stmt[opIdx + 1].toIntOrNull()
                ?: error("OPB constraint rhs not an integer: '${stmt[opIdx + 1]}'")
            val pbOp = when (stmt[opIdx]) {
                ">=" -> PbOp.GE
                "<=" -> PbOp.LE
                "=" -> PbOp.EQ
                else -> error("unknown OPB operator '${stmt[opIdx]}'")
            }
            for (lit in literals) {
                val v = Lit.variable(lit)
                if (v + 1 > numVars) numVars = v + 1
            }
            factors.add(
                PseudoBoolean(
                    weights = weights.toIntArray(),
                    literals = literals.toIntArray(),
                    op = pbOp,
                    bound = rhs,
                ),
            )
        }

        val objective: LinearObjective? = if (!hasObjective) {
            null
        } else {
            val weights = LongArray(numVars)
            for ((v, w) in objWeights) weights[v] = w.toLong()
            LinearObjective(boolWeights = weights, intCoefficients = LongArray(0), constant = objConstant.toLong())
        }
        val problem = Problem(
            numBoolVars = numVars,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = factors.toTypedArray(),
        )
        return OpbProblem(problem, objective)
    }

    /** Parse `coef var` pairs into aligned weights and literals. */
    private fun parseTerms(tokens: List<String>): Pair<List<Int>, List<Int>> {
        require(tokens.size % 2 == 0) {
            "OPB term sequence must alternate coefficient/variable, got: ${tokens.joinToString(" ")}"
        }
        val weights = mutableListOf<Int>()
        val literals = mutableListOf<Int>()
        var idx = 0
        while (idx < tokens.size) {
            val coef = tokens[idx].toIntOrNull()
                ?: error("OPB coefficient not an integer: '${tokens[idx]}'")
            val varToken = tokens[idx + 1]
            val negated = varToken.startsWith("~")
            val rawVar = if (negated) varToken.substring(1) else varToken
            require(rawVar.startsWith("x")) {
                "OPB variable must start with 'x', got '$varToken'"
            }
            val v = rawVar.substring(1).toIntOrNull()?.minus(1)
                ?: error("OPB variable index not parseable: '$varToken'")
            require(v >= 0) { "OPB variable index out of range: '$varToken'" }
            weights.add(coef)
            literals.add(Lit.make(v, positive = !negated))
            idx += 2
        }
        return weights to literals
    }
}
