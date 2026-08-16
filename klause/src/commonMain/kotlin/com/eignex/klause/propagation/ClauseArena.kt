package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Problem

/**
 * Flat, arena-packed store of the clauses of a pure-Boolean (native-SAT) problem. Every clause
 * literal lives contiguously in one [lits] array; clause `c` occupies the half-open range
 * `[start(c), end(c))`. This replaces the per-clause [com.eignex.klause.factor.bool.ClausePropagator]
 * heap objects (each with its own literal array and out-of-line watch state) with a single
 * cache-friendly buffer, so the native-SAT BCP loop can walk clauses without a
 * per-fire virtual dispatch, object dereference, or payload cast.
 *
 * Built only for a [Problem.isNativeSatEligible] problem: no integer variables and every factor a
 * [Clause]. The literals keep their original [com.eignex.klause.solver.Lit] encoding, and clause
 * indices line up 1:1 with [Problem.factors], so a learned reason expressed as a clause index maps
 * straight back to the originating factor.
 *
 * Immutable: the two-watched-literal scheme keeps the *watched* literals in the first two slots of
 * each clause and swaps them in place during search, but that mutable watch state lives on the
 * session (rebuilt per solve, allowed to drift across backtrack like all CDCL watches), not here —
 * the arena is the stable clause text and is safe to share read-only across portfolio arms.
 */
internal class ClauseArena private constructor(
    /** All clause literals, concatenated in clause order. */
    val lits: IntArray,
    /** `starts[c]` is the offset of clause `c`; sized `clauseCount + 1` with a trailing sentinel
     *  `starts[clauseCount] == lits.size`, so `end(c) == starts[c + 1]` needs no bounds special-case. */
    val starts: IntArray,
    /** Boolean-variable count of the source problem; every literal's variable is `< numBoolVars`. */
    val numBoolVars: Int,
) {
    /** Number of clauses in the arena. */
    val clauseCount: Int get() = starts.size - 1

    /** Offset of clause [c]'s first literal in [lits]. */
    fun start(c: Int): Int = starts[c]

    /** One past clause [c]'s last literal in [lits]. */
    fun end(c: Int): Int = starts[c + 1]

    /** Number of literals in clause [c]. */
    fun length(c: Int): Int = starts[c + 1] - starts[c]

    companion object {
        /** Pack [problem]'s clauses into a flat arena. Requires [Problem.isNativeSatEligible]. */
        fun of(problem: Problem): ClauseArena {
            require(problem.isNativeSatEligible) {
                "ClauseArena requires a pure-Boolean clause-only problem"
            }
            val factors = problem.factors
            var total = 0
            for (f in factors) total += (f as Clause).literals.size
            val lits = IntArray(total)
            val starts = IntArray(factors.size + 1)
            var off = 0
            for (c in factors.indices) {
                starts[c] = off
                val clauseLits = (factors[c] as Clause).literals
                clauseLits.copyInto(lits, off)
                off += clauseLits.size
            }
            starts[factors.size] = off
            return ClauseArena(lits, starts, problem.numBoolVars)
        }
    }
}
