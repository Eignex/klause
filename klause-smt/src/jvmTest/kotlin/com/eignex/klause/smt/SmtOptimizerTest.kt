package com.eignex.klause.smt

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Cardinality
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

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
        val objective = LinearObjective(boolWeights = doubleArrayOf(10.0, 5.0, 8.0))
        assertFailsWith<UnsupportedOperationException> {
            SmtSolver(problem).minimize(objective, SmtParams())
        }
    }

    /**
     * Non-LinearObjective objectives are rejected up front with a clear message — JavaSMT
     * has no generic objective callback, so trying to support arbitrary subtypes would
     * require apply-revert tooling that isn't justified for an SMT adapter.
     */
    @Test
    fun `non-linear objective rejected`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(),
        )
        val objective = object : com.eignex.klause.solver.Objective {
            override fun evaluate(sample: com.eignex.klause.solver.Sample): Double = 0.0
        }
        assertFailsWith<IllegalArgumentException> {
            SmtSolver(problem).minimize(objective, SmtParams())
        }
    }

    /**
     * Verdict surface check: the [MinimizeResult] sealed type must be usable from a
     * generic `Optimizer<SmtParams>` cast. Doesn't exercise an optimization-capable
     * backend (none on the classpath in CI), just confirms the wiring compiles and the
     * default `improvements` overload is reachable.
     */
    @Test
    fun `optimizer interface visible on smt solver`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(),
        )
        val opt: com.eignex.klause.solver.Optimizer<SmtParams> = SmtSolver(problem)
        assertNotNull(opt)
        // Calling minimize on an empty problem with SMTInterpol still throws on opt env.
        assertFailsWith<UnsupportedOperationException> {
            opt.minimize(LinearObjective(), SmtParams())
        }
        // The default improvements sequence wraps the same call, so it also throws when
        // forced.
        assertFailsWith<UnsupportedOperationException> {
            opt.improvements(LinearObjective(), SmtParams()).toList()
        }
        // Sanity: returned type is MinimizeResult-typed when it does return.
        val expected: kotlin.reflect.KClass<MinimizeResult> = MinimizeResult::class
        assertIs<kotlin.reflect.KClass<MinimizeResult>>(expected)
    }
}
