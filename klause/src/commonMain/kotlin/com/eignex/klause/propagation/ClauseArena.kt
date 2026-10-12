package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.isClausal

/**
 * Immutable arena-packed clauses for a pure-Boolean propagation projection whose factors are all
 * [Clause]s. Clause `c` occupies `[start(c), end(c))` in [lits]. Contiguous storage avoids per-fire
 * factor dispatch and payload casts. Literals retain [com.eignex.klause.ir.Lit] encoding; clause
 * indices match `Problem.factors`, so reason indices identify their source factors.
 *
 * Mutable watch positions belong to the session and may drift across backtracking. The arena
 * holds stable clause text and can be shared read-only across portfolio arms.
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
        /** Pack [problem]'s clauses into a flat arena. Requires a native-SAT-eligible problem. */
        fun of(problem: Problem): ClauseArena {
            require(problem.isClausal()) {
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
