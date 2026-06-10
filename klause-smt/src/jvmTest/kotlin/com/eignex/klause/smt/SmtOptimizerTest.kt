package com.eignex.klause.smt

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Cardinality
import kotlin.test.Test
import kotlin.test.assertFailsWith

class SmtOptimizerTest {

    /**
     * SMTInterpol is the default backend and does not support optimization. JavaSMT's
     * `newOptimizationProverEnvironment` throws on that path; we verify the exception
     * surfaces cleanly rather than being swallowed.
     */
    @Test
    fun `smtinterpol throws on minimize (no optimization support)`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
            ),
        )
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(factor),
        )
        val objective = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L))
        assertFailsWith<UnsupportedOperationException> {
            SmtSolver(problem).minimize(objective, SmtParams())
        }
    }

    /**
     * The default `improvements` overload (reached through a generic `Optimizer<SmtParams>`
     * cast) wraps `minimize`, so it must surface the same SMTInterpol no-optimization
     * exception when the sequence is forced — not swallow it lazily.
     */
    @Test
    fun `improvements surfaces the SMTInterpol no-optimization exception when forced`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(),
        )
        val opt: Optimizer<SmtParams> = SmtSolver(problem)
        assertFailsWith<UnsupportedOperationException> {
            opt.improvements(LinearObjective(), SmtParams()).toList()
        }
    }
}
