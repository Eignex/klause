package com.eignex.klause.formats.dimacs

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.FormatException
import com.eignex.klause.formats.splitWhitespace
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.IntArrayList
import kotlin.math.abs

/** Raised when a DIMACS CNF/WCNF document is malformed, so a caller can catch it via [FormatException]
 *  like the other input formats. */
class DimacsFormatException(msg: String) : FormatException("DIMACS", msg)

private fun dimacsError(msg: String): Nothing = throw DimacsFormatException(msg)

private inline fun dimacsRequire(cond: Boolean, msg: () -> String) {
    if (!cond) throw DimacsFormatException(msg())
}

/** Parse a 32-bit [role] count, distinguishing a value that exceeds the range from a non-integer. */
private fun dimacsInt(token: String, role: String): Int = token.toIntOrNull() ?: dimacsError(
    if (token.toLongOrNull() != null) {
        "DIMACS $role exceeds the 32-bit integer range: '$token'"
    } else {
        "DIMACS $role is not an integer: '$token'"
    },
)

/** DIMACS CNF/WCNF parser. */
object Dimacs {

    /** Hard-clause sentinel when `top` is absent in `.wcnf`. */
    private const val HARD_WEIGHT_SENTINEL: Long = Long.MAX_VALUE

    /** Parse DIMACS CNF/WCNF [text] into a [Problem]. */
    fun parse(text: String): Problem {
        var numVars = -1
        val clauses = mutableListOf<Clause>()
        // A bare `0` with no accumulated literals is the empty clause (⊥) — an unsatisfiable instance —
        // unless it sits in the legacy trailing `%` block, where a lone `0` is an end-of-file sentinel.
        var triviallyUnsat = false
        var sawTrailer = false

        @Suppress("DoubleMutabilityForCollection") // reset to a new list per clause
        var current: IntArrayList? = null
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("c")) continue
            // `%` begins the SATLIB trailing block; keep reading (some files put a `%` comment before the
            // header) but stop treating a bare `0` as ⊥ from here on — it is the trailer sentinel.
            if (line.startsWith("%")) {
                sawTrailer = true
                continue
            }
            if (line.startsWith("p ") || line.startsWith("p\t")) {
                val parts = line.splitWhitespace()
                dimacsRequire(parts.size >= 4 && parts[1] == "cnf") {
                    "Expected `p cnf <nvars> <nclauses>` header, got: '$rawLine'"
                }
                numVars = dimacsInt(parts[2], "`p cnf` variable count")
                continue
            }
            if (numVars < 0) dimacsError("DIMACS body before `p cnf` header: '$rawLine'")
            for (token in line.splitWhitespace()) {
                if (token.isEmpty()) continue
                val lit = token.toIntOrNull()
                    ?: dimacsError("Unparseable DIMACS token: '$token'")
                if (lit == 0) {
                    val acc = current
                    // A `0` terminates the clause; with no literals it is the empty clause (⊥), except in
                    // the trailing `%` block where a lone `0` is a sentinel to ignore.
                    when {
                        acc != null && !acc.isEmpty() -> clauses += Clause(acc.toIntArray())
                        !sawTrailer -> triviallyUnsat = true
                    }
                    current = null
                } else {
                    val v = abs(lit) - 1
                    dimacsRequire(v in 0 until numVars) {
                        "Literal $lit out of range [1, $numVars]"
                    }
                    val accum = current ?: IntArrayList().also { current = it }
                    accum.add(Lit.make(v, positive = lit > 0))
                }
            }
        }
        dimacsRequire(current == null) { "DIMACS file ends mid-clause (no terminating 0)" }
        dimacsRequire(numVars >= 0) { "DIMACS file has no `p cnf` header" }
        // The empty clause is ⊥; a [Clause] needs a non-empty literal set, so force a contradiction on a
        // fresh marker variable (as the WCNF path does) to reject the instance.
        val totalVars = numVars + if (triviallyUnsat) 1 else 0
        val factors = ArrayList<Factor>(clauses.size + if (triviallyUnsat) 2 else 0)
        factors.addAll(clauses)
        if (triviallyUnsat) {
            val marker = numVars
            factors.add(Clause(intArrayOf(Lit.make(marker, positive = true))))
            factors.add(Clause(intArrayOf(Lit.make(marker, positive = false))))
        }
        return Problem(
            numBoolVars = totalVars,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = factors.toTypedArray(),
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
        // An empty hard clause is unsatisfiable; an empty soft clause is always falsified, so its
        // weight is a fixed cost every solution pays.
        var triviallyUnsat = false
        var fixedCost = 0L

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("c") || line.startsWith("%")) continue
            if (line.startsWith("p ") || line.startsWith("p\t")) {
                val parts = line.splitWhitespace()
                dimacsRequire(parts.size >= 4 && parts[1] == "wcnf") {
                    "Expected `p wcnf <nvars> <nclauses> [<top>]` header, got: '$rawLine'"
                }
                numVars = dimacsInt(parts[2], "`p wcnf` variable count")
                if (parts.size >= 5) {
                    top = parts[4].toLongOrNull() ?: dimacsError(
                        "DIMACS `p wcnf` top is not a 64-bit integer: '${parts[4]}'",
                    )
                }
                hasOldHeader = true
                continue
            }
            val tokens = line.splitWhitespace()
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
                    ?: dimacsError("Unparseable wcnf weight: '${tokens[0]}'")
                // Keep explicit hard clauses hard when `top` is omitted.
                isHard = weight >= (top ?: HARD_WEIGHT_SENTINEL)
                litStart = 1
            }
            val lits = IntArrayList()
            var terminated = false
            var i = litStart
            while (i < tokens.size) {
                val tok = tokens[i]
                val lit = tok.toIntOrNull() ?: dimacsError("Unparseable wcnf literal: '$tok'")
                i++
                if (lit == 0) {
                    terminated = true
                    break
                }
                val v = abs(lit) - 1
                // New-format instances carry no header, so the variable count grows to fit each literal;
                // an old-header instance must stay within the declared `nvars`, as the CNF path enforces —
                // a literal past it would index a nonexistent variable.
                if (hasOldHeader) {
                    dimacsRequire(v in 0 until numVars) { "Literal $lit out of range [1, $numVars]" }
                } else if (v + 1 > numVars) {
                    numVars = v + 1
                }
                lits.add(Lit.make(v, positive = lit > 0))
            }
            dimacsRequire(terminated) { "wcnf clause not terminated by 0: '$rawLine'" }
            dimacsRequire(i == tokens.size) { "wcnf clause has trailing tokens after 0: '$rawLine'" }
            val clauseLits = lits.toIntArray()
            when {
                isHard && clauseLits.isEmpty() -> triviallyUnsat = true

                isHard -> hardClauses.add(Clause(clauseLits))

                // A zero-weight soft clause contributes no cost and imposes no constraint.
                weight == 0L -> Unit

                clauseLits.isEmpty() -> fixedCost += weight

                else -> softClauses.add(weight to clauseLits)
            }
        }
        // New-format instances carry no header, so an instance with no variable-bearing clause
        // (only empty/degenerate clauses) leaves numVars unset — it simply has zero variables.
        if (numVars < 0) numVars = 0

        val numOriginal = numVars
        // Relaxation variables follow the originals; an unsat marker (if any) follows the relaxations.
        val totalVars = numOriginal + softClauses.size + if (triviallyUnsat) 1 else 0
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
        if (triviallyUnsat) {
            // Force a contradiction on a fresh variable to reject the whole instance.
            val marker = numOriginal + softClauses.size
            factors.add(Clause(intArrayOf(Lit.make(marker, positive = true))))
            factors.add(Clause(intArrayOf(Lit.make(marker, positive = false))))
        }
        val problem = Problem(
            numBoolVars = totalVars,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = factors.toTypedArray(),
        )
        return WcnfProblem(problem, LinearObjective(boolWeights = weights, constant = fixedCost), numOriginal)
    }
}
