package com.eignex.klause.meta.coreguided

import com.eignex.klause.ir.Lit
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.Cancellation

internal enum class CoreRefinementMode { NONE, DELETION, REASONS }

internal object CoreRefinement {
    fun refine(
        problem: BakedProblem,
        literals: IntArray,
        caller: Assumptions,
        cancellation: Cancellation,
        charge: (Long) -> Unit,
    ): IntArray {
        if (literals.size !in 2..32 || problem.numBoolVars.toLong() + problem.numIntVars > 4_096L ||
            problem.numFactors > 4_096 || cancellation()
        ) return literals
        var entries = problem.numBoolVars.toLong() + problem.numIntVars + problem.numFactors
        for (factor in problem.factors) {
            charge(1L)
            if (factor !is Clause && factor !is ReifiedCardinality) return literals
            val size = factor.structuralKeyWeight
            if (size > 32_768L - entries || cancellation()) return literals
            entries += size
        }
        var session: PropagationSession? = null
        var charged = 0L
        fun settle() {
            val work = session?.work ?: 0L
            charge(work - charged)
            charged = work
        }
        val stop = Cancellation {
            settle()
            charged >= 32_768L
        } or cancellation
        return try {
            session = PropagationSession(problem, stop, propagationCancelFloor = 0)
            var current = literals
            for (literal in literals) {
                if (stop()) return literals
                if (current.size == 1) break
                val trial = current.filter { it != literal }.toIntArray()
                charge(current.size.toLong())
                val pins = Assumptions(bools = trial.associate { Lit.variable(it) to !Lit.isPositive(it) })
                // Caller conditions belong to every refutation, including those with private cost selectors.
                val result = session.seed(pins.mergedWith(caller))
                if (session.fixpointCancelled || stop()) return literals
                if (result is PropagationResult.Unsat) current = trial
            }
            current
        } finally {
            settle()
        }
    }
}
