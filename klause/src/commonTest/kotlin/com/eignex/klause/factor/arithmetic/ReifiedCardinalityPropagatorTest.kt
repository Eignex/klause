package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import kotlin.random.Random
import kotlin.test.Test

class ReifiedCardinalityPropagatorTest {

    @Test
    fun `reified cardinality deductions are implied by their reasons with the indicator on variable zero`() {
        // The indicator on Boolean variable 0 makes its true literal the integer 0.
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
}
