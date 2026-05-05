package com.eignex.klause.export

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause

/**
 * Writes a [Problem] as DIMACS CNF. Cardinality factors are lowered to clauses:
 * AtMost-1 to pairwise binary clauses, AtLeast-1 to a single big clause, ExactlyOne to both.
 *
 * Problems containing integer variables or non-clausal factors are not supported and raise
 * [UnsupportedOperationException]. The intent is to ship a Boolean projection through to
 * external SAT solvers; the wider integer/float fragment exports through other paths.
 */
object DimacsWriter {

    fun write(problem: Problem): String {
        require(problem.numIntVars == 0) {
            "DIMACS export does not support integer variables (${problem.numIntVars} declared)"
        }
        val clauses = mutableListOf<IntArray>()
        for (factor in problem.factors) {
            when (factor) {
                is Clause -> clauses += factor.literals
                is Cardinality -> clauses += lowerCardinality(factor)
                else -> throw UnsupportedOperationException(
                    "DIMACS export cannot encode factor type ${factor::class.simpleName}"
                )
            }
        }
        val sb = StringBuilder()
        sb.append("p cnf ").append(problem.numBoolVars).append(' ').append(clauses.size).append('\n')
        for (clause in clauses) {
            for (lit in clause) sb.append(toDimacs(lit)).append(' ')
            sb.append("0\n")
        }
        return sb.toString()
    }

    private fun lowerCardinality(c: Cardinality): List<IntArray> {
        val out = mutableListOf<IntArray>()
        if (c.max < c.literals.size) {
            require(c.max == 1) { "AtMost-${c.max} encoding is not yet supported" }
            for (i in c.literals.indices) {
                for (j in i + 1 until c.literals.size) {
                    out += intArrayOf(Lit.negate(c.literals[i]), Lit.negate(c.literals[j]))
                }
            }
        }
        if (c.min > 0) {
            require(c.min == 1) { "AtLeast-${c.min} encoding is not yet supported" }
            out += c.literals.copyOf()
        }
        return out
    }

    private fun toDimacs(lit: Int): Int {
        val v = Lit.variable(lit) + 1
        return if (Lit.isPositive(lit)) v else -v
    }
}
