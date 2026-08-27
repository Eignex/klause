package com.eignex.klause.formats.dimacs

import com.eignex.klause.formats.FormatException
import com.eignex.klause.formats.splitWhitespace
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.dimacs.CnfDocument
import com.eignex.klause.lowering.dimacs.WcnfDocument
import com.eignex.klause.lowering.dimacs.WeightedCnfClause
import com.eignex.klause.util.CharSource
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.StringCharSource
import com.eignex.klause.util.lineSequence
import kotlin.math.abs

/** Raised when a DIMACS CNF/WCNF document is malformed, so a caller can catch it via [FormatException]
 *  like the other input formats. Parsing and lowering remain separate operations. */
class DimacsFormatException(msg: String) : FormatException("DIMACS", msg)

private fun dimacsError(msg: String): Nothing = throw DimacsFormatException(msg)

private inline fun dimacsRequire(cond: Boolean, msg: () -> String) {
    if (!cond) throw DimacsFormatException(msg())
}

// Parse a 32-bit [role] count, distinguishing a value that exceeds the range from a non-integer.
private fun dimacsInt(token: String, role: String): Int = token.toIntOrNull() ?: dimacsError(
    if (token.toLongOrNull() != null) {
        "DIMACS $role exceeds the 32-bit integer range: '$token'"
    } else {
        "DIMACS $role is not an integer: '$token'"
    },
)

/** DIMACS CNF/WCNF parser. */
object Dimacs {

    // Hard-clause sentinel when `top` is absent in `.wcnf`.
    private const val HARD_WEIGHT_SENTINEL: Long = Long.MAX_VALUE

    /** Parse DIMACS CNF [text] into a [CnfDocument]. */
    fun parse(text: String): CnfDocument = parse(StringCharSource(text))

    /** Parse a DIMACS CNF [source] into a [CnfDocument], consuming it line by line. */
    fun parse(source: CharSource): CnfDocument {
        var numVars = -1
        var declaredClauses = -1
        var parsedClauses = 0
        val clauses = mutableListOf<IntArray>()
        // A bare `0` with no accumulated literals is the empty clause (⊥) — an unsatisfiable instance —
        // unless it sits in the legacy trailing `%` block, where a lone `0` is an end-of-file sentinel.
        var triviallyUnsat = false
        var sawTrailer = false

        @Suppress("DoubleMutabilityForCollection") // reset to a new list per clause
        var current: IntArrayList? = null
        for (rawLine in source.lineSequence()) {
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
                dimacsRequire(numVars < 0) { "DIMACS file has more than one `p cnf` header" }
                dimacsRequire(parts.size == 4 && parts[1] == "cnf") {
                    "Expected `p cnf <nvars> <nclauses>` header, got: '$rawLine'"
                }
                numVars = dimacsInt(parts[2], "`p cnf` variable count")
                declaredClauses = dimacsInt(parts[3], "`p cnf` clause count")
                dimacsRequire(numVars >= 0) { "DIMACS `p cnf` variable count must be non-negative" }
                dimacsRequire(declaredClauses >= 0) { "DIMACS `p cnf` clause count must be non-negative" }
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
                        acc != null && !acc.isEmpty() -> {
                            clauses += acc.toIntArray()
                            parsedClauses++
                        }

                        !sawTrailer -> {
                            triviallyUnsat = true
                            parsedClauses++
                        }
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
        dimacsRequire(parsedClauses == declaredClauses) {
            "DIMACS header declares $declaredClauses clauses, found $parsedClauses"
        }
        return CnfDocument(numVars, clauses, triviallyUnsat)
    }

    /** Parse `.wcnf` into a [WcnfDocument]. */
    fun parseWcnf(text: String): WcnfDocument = parseWcnf(StringCharSource(text))

    /** Parse a `.wcnf` [source] into a [WcnfDocument], consuming it line by line. */
    fun parseWcnf(source: CharSource): WcnfDocument {
        var numVars = -1
        var declaredClauses = -1
        var parsedClauses = 0
        var top: Long? = null
        var hasOldHeader = false
        val hardClauses = mutableListOf<IntArray>()
        val softClauses = mutableListOf<WeightedCnfClause>()
        // An empty hard clause is unsatisfiable; an empty soft clause is always falsified, so its
        // weight is a fixed cost every solution pays.
        var triviallyUnsat = false
        var fixedCost = 0L

        for (rawLine in source.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("c") || line.startsWith("%")) continue
            if (line.startsWith("p ") || line.startsWith("p\t")) {
                val parts = line.splitWhitespace()
                dimacsRequire(!hasOldHeader) { "DIMACS file has more than one `p wcnf` header" }
                dimacsRequire((parts.size == 4 || parts.size == 5) && parts[1] == "wcnf") {
                    "Expected `p wcnf <nvars> <nclauses> [<top>]` header, got: '$rawLine'"
                }
                numVars = dimacsInt(parts[2], "`p wcnf` variable count")
                declaredClauses = dimacsInt(parts[3], "`p wcnf` clause count")
                dimacsRequire(numVars >= 0) { "DIMACS `p wcnf` variable count must be non-negative" }
                dimacsRequire(declaredClauses >= 0) { "DIMACS `p wcnf` clause count must be non-negative" }
                if (parts.size >= 5) {
                    top = parts[4].toLongOrNull() ?: dimacsError(
                        "DIMACS `p wcnf` top is not a 64-bit integer: '${parts[4]}'",
                    )
                    dimacsRequire(top > 0) { "DIMACS `p wcnf` top must be positive: $top" }
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
                dimacsRequire(weight >= 0) { "DIMACS wcnf weight must be non-negative: $weight" }
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
            parsedClauses++
            when {
                isHard && clauseLits.isEmpty() -> triviallyUnsat = true

                isHard -> hardClauses.add(clauseLits)

                // A zero-weight soft clause contributes no cost and imposes no constraint.
                weight == 0L -> Unit

                clauseLits.isEmpty() -> fixedCost += weight

                else -> softClauses.add(WeightedCnfClause(weight, clauseLits))
            }
        }
        // New-format instances carry no header, so an instance with no variable-bearing clause
        // (only empty/degenerate clauses) leaves numVars unset — it simply has zero variables.
        if (numVars < 0) numVars = 0
        if (hasOldHeader) {
            dimacsRequire(parsedClauses == declaredClauses) {
                "DIMACS header declares $declaredClauses clauses, found $parsedClauses"
            }
        }

        return WcnfDocument(numVars, hardClauses, softClauses, triviallyUnsat, fixedCost)
    }
}
