package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertIs

class ReifiedCardinalityPropagatorTest {

    @Test
    fun `reified cardinality deductions should be implied by their reasons with the indicator on variable zero`() {
        val rng = Random(0xCA2)
        repeat(300) { iter ->
            val n = 4
            val min = rng.nextInt(n)
            val problem = Problem(
                numBoolVars = n + 1,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf<Factor>(
                    ReifiedCardinality(
                        auxBoolVar = 0,
                        literals = IntArray(n) { Lit.make(it + 1, rng.nextBoolean()) },
                        min = min,
                        max = min + rng.nextInt(n - min + 1),
                    ),
                ),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "reified-card#$iter") { state ->
                (0..n).all { b -> rng.nextInt(2) == 0 || state.pinBool(b, rng.nextBoolean()) }
            }
        }
    }

    @Test
    fun `false aux should conflict with a body that must hold`() {
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(1, true))),
                ReifiedCardinality(
                    auxBoolVar = 2,
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true)),
                    min = 1,
                    max = 2,
                ),
            ),
        )
        val session = PropagationSession(problem)
        val r = session.pinBool(2, false)
        assertIs<PropagationResult.Unsat>(r)
    }
}
