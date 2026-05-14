package com.eignex.klause.formats.dimacs

import com.eignex.klause.cnf.CnfProblem
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Clause
import kotlin.math.abs

/**
 * DIMACS CNF parser and writer. [parse] reads a `.cnf` file into a [Problem] of all-Boolean
 * variables and one [Clause] factor per clause. [write] serialises a bit-blasted [CnfProblem]
 * back out. Comment lines starting with `c` or `%` are accepted on read; multi-line clauses
 * with a trailing `0` terminator round-trip cleanly.
 */
object Dimacs {

    /** DIMACS CNF serialization. Empty clauses (compile-time false) are emitted as `0`. */
    fun write(cnf: CnfProblem): String {
        val sb = StringBuilder()
        sb.append("p cnf ").append(cnf.numVars).append(' ').append(cnf.clauses.size).append('\n')
        for (clause in cnf.clauses) {
            for (lit in clause) {
                val v = Lit.variable(lit) + 1
                sb.append(if (Lit.isPositive(lit)) v else -v).append(' ')
            }
            sb.append("0\n")
        }
        return sb.toString()
    }

    fun parse(text: String): Problem {
        var numVars = -1
        val clauses = mutableListOf<Clause>()
        var current: MutableList<Int>? = null
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("c") || line.startsWith("%")) continue
            if (line.startsWith("p ") || line.startsWith("p\t")) {
                val parts = line.split(Regex("\\s+"))
                require(parts.size >= 4 && parts[1] == "cnf") {
                    "Expected `p cnf <nvars> <nclauses>` header, got: '$rawLine'"
                }
                numVars = parts[2].toInt()
                continue
            }
            if (numVars < 0) error("DIMACS body before `p cnf` header: '$rawLine'")
            for (token in line.split(Regex("\\s+"))) {
                if (token.isEmpty()) continue
                val lit = token.toIntOrNull()
                    ?: error("Unparseable DIMACS token: '$token'")
                if (lit == 0) {
                    val acc = current
                    if (acc != null && acc.isNotEmpty()) clauses += Clause(acc.toIntArray())
                    current = null
                } else {
                    val v = abs(lit) - 1
                    require(v in 0 until numVars) {
                        "Literal $lit out of range [1, $numVars]"
                    }
                    val accum = current ?: mutableListOf<Int>().also { current = it }
                    accum.add(Lit.make(v, positive = lit > 0))
                }
            }
        }
        require(current == null) { "DIMACS file ends mid-clause (no terminating 0)" }
        require(numVars >= 0) { "DIMACS file has no `p cnf` header" }
        return Problem(numBoolVars = numVars, numIntVars = 0, intDomains = emptyArray(), factors = clauses)
    }
}
