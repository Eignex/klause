package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.isClausal

// Arena-packed clauses for a pure-Boolean projection: contiguous storage avoids per-fire factor dispatch and
// payload casts. Clause indices match `Problem.factors`, so reasons identify their source factors. Watch
// positions belong to the session; the arena holds stable clause text shareable read-only across portfolio arms.
internal class ClauseArena private constructor(
    val lits: IntArray,
    // Trailing sentinel `starts[clauseCount] == lits.size`, so `end(c) == starts[c + 1]` needs no special case.
    val starts: IntArray,
    val numBoolVars: Int,
) {
    val clauseCount: Int get() = starts.size - 1

    fun start(c: Int): Int = starts[c]

    fun end(c: Int): Int = starts[c + 1]

    fun length(c: Int): Int = starts[c + 1] - starts[c]

    companion object {
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
