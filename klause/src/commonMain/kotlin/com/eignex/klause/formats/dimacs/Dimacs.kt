package com.eignex.klause.formats.dimacs

import com.eignex.klause.cnf.CnfProblem
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.LinearObjective
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

    /** Parse DIMACS CNF/WCNF [text] into a [Problem]. */
    fun parse(text: String): Problem {
        var numVars = -1
        val clauses = mutableListOf<Clause>()

        @Suppress("DoubleMutabilityForCollection") // reset to a new list per clause
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
        return Problem(
            numBoolVars = numVars,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = Array<Factor>(
                clauses.size,
            ) {
                clauses[it]
            },
        )
    }

    /**
     * Weighted Partial MaxSAT input pair: hard clauses are required; each soft clause `(wᵢ, Cᵢ)`
     * is encoded by allocating one fresh relaxation bool `rᵢ`, posting `rᵢ ∨ Cᵢ`, and adding `wᵢ`
     * as the coefficient on `rᵢ` in the linear objective. Minimising the objective minimises the
     * total weight of violated soft clauses — i.e. the standard Weighted Partial MaxSAT objective.
     *
     * Relaxation bools occupy indices `[numOriginalBoolVars, problem.numBoolVars)`.
     */
    data class WcnfProblem(
        /** The compiled solver problem. */
        val problem: Problem,
        /** The soft-clause objective. */
        val objective: LinearObjective,
        /** Number of original (non-relaxation) Boolean variables. */
        val numOriginalBoolVars: Int,
    )

    /**
     * Parse `.wcnf` (Weighted Partial MaxSAT). Header is `p wcnf <nvars> <nclauses> [<top>]`.
     * Each clause line: `<weight> <lit>* 0`. Clauses with weight equal to `top` (or any clause
     * when `top` is absent and the weight is `Long.MAX_VALUE`-ish) are treated as hard. The 2014+
     * "new" MaxSAT format with leading `h` for hard clauses is also accepted: `h <lit>* 0`.
     */
    fun parseWcnf(text: String): WcnfProblem {
        var numVars = -1
        var top: Long? = null
        var hasOldHeader = false
        val hardClauses = mutableListOf<Clause>()
        // Per soft clause: weight and clause body. Relaxation bools allocated after the parse.
        val softClauses = mutableListOf<Pair<Long, IntArray>>()

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("c") || line.startsWith("%")) continue
            if (line.startsWith("p ") || line.startsWith("p\t")) {
                val parts = line.split(Regex("\\s+"))
                require(parts.size >= 4 && parts[1] == "wcnf") {
                    "Expected `p wcnf <nvars> <nclauses> [<top>]` header, got: '$rawLine'"
                }
                numVars = parts[2].toInt()
                if (parts.size >= 5) top = parts[4].toLong()
                hasOldHeader = true
                continue
            }
            // New MaxSAT 2022+ format may omit the `p` header; derive numVars from observed lits.
            val tokens = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) continue
            val isHard: Boolean
            val weight: Long
            val litStart: Int
            if (tokens[0] == "h") {
                isHard = true
                weight = 0L
                litStart = 1
            } else {
                weight = tokens[0].toLongOrNull()
                    ?: error("Unparseable wcnf weight: '${tokens[0]}'")
                isHard = top != null && weight >= top
                litStart = 1
            }
            val lits = mutableListOf<Int>()
            for (i in litStart until tokens.size) {
                val tok = tokens[i]
                val lit = tok.toIntOrNull() ?: error("Unparseable wcnf literal: '$tok'")
                if (lit == 0) break
                val v = abs(lit) - 1
                if (!hasOldHeader && v + 1 > numVars) numVars = v + 1
                require(v >= 0) { "Literal $lit out of range" }
                lits.add(Lit.make(v, positive = lit > 0))
            }
            require(tokens.subList(litStart, tokens.size).any { it == "0" }) {
                "wcnf clause not terminated by 0: '$rawLine'"
            }
            if (isHard) {
                hardClauses.add(Clause(lits.toIntArray()))
            } else {
                softClauses.add(weight to lits.toIntArray())
            }
        }
        require(numVars >= 0) { "wcnf file has no clauses and no header to fix numVars" }

        // Allocate one relaxation bool per soft clause appended after the original vars.
        val numOriginal = numVars
        val totalVars = numOriginal + softClauses.size
        val factors = mutableListOf<Factor>()
        factors.addAll(hardClauses)
        val weights = DoubleArray(totalVars)
        for ((i, soft) in softClauses.withIndex()) {
            val (w, lits) = soft
            val relax = numOriginal + i
            // Post relax ∨ lits: violating the soft clause forces relax = true.
            val extended = IntArray(lits.size + 1)
            extended[0] = Lit.make(relax, positive = true)
            for (k in lits.indices) extended[k + 1] = lits[k]
            factors.add(Clause(extended))
            weights[relax] = w.toDouble()
        }
        val problem = Problem(
            numBoolVars = totalVars,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = factors.toTypedArray(),
        )
        return WcnfProblem(problem, LinearObjective(boolWeights = weights), numOriginal)
    }
}
