package com.eignex.klause.formats.dimacs

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.math.abs

/** DIMACS CNF/WCNF parser. */
object Dimacs {

    /** Hard-clause sentinel when `top` is absent in `.wcnf`. */
    private const val HARD_WEIGHT_SENTINEL: Long = Long.MAX_VALUE

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

    /** Parsed WCNF problem and its soft-clause objective. */
    data class WcnfProblem(
        /** Compiled solver problem. */
        val problem: Problem,
        /** Soft-clause objective. */
        val objective: LinearObjective,
        /** Number of original, non-relaxation variables. */
        val numOriginalBoolVars: Int,
    )

    /** Parse `.wcnf` into hard clauses plus weighted soft clauses. */
    fun parseWcnf(text: String): WcnfProblem {
        var numVars = -1
        var top: Long? = null
        var hasOldHeader = false
        val hardClauses = mutableListOf<Clause>()
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
                // Keep explicit hard clauses hard when `top` is omitted.
                isHard = weight >= (top ?: HARD_WEIGHT_SENTINEL)
                litStart = 1
            }
            val lits = mutableListOf<Int>()
            var terminated = false
            var i = litStart
            while (i < tokens.size) {
                val tok = tokens[i]
                val lit = tok.toIntOrNull() ?: error("Unparseable wcnf literal: '$tok'")
                i++
                if (lit == 0) {
                    terminated = true
                    break
                }
                val v = abs(lit) - 1
                if (!hasOldHeader && v + 1 > numVars) numVars = v + 1
                require(v >= 0) { "Literal $lit out of range" }
                lits.add(Lit.make(v, positive = lit > 0))
            }
            require(terminated) { "wcnf clause not terminated by 0: '$rawLine'" }
            require(i == tokens.size) { "wcnf clause has trailing tokens after 0: '$rawLine'" }
            if (isHard) {
                hardClauses.add(Clause(lits.toIntArray()))
            } else {
                softClauses.add(weight to lits.toIntArray())
            }
        }
        require(numVars >= 0) { "wcnf file has no clauses and no header to fix numVars" }

        val numOriginal = numVars
        val totalVars = numOriginal + softClauses.size
        val factors = mutableListOf<Factor>()
        factors.addAll(hardClauses)
        val weights = LongArray(totalVars)
        for ((i, soft) in softClauses.withIndex()) {
            val (w, lits) = soft
            val relax = numOriginal + i
            val extended = IntArray(lits.size + 1)
            extended[0] = Lit.make(relax, positive = true)
            for (k in lits.indices) extended[k + 1] = lits[k]
            factors.add(Clause(extended))
            weights[relax] = w
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
