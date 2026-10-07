package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.model.PbOp
import kotlin.random.Random
import kotlin.test.Test

class ReifiedPseudoBooleanPropagatorTest {

    @Test
    fun `reified pseudo-boolean deductions are implied by their reasons with the indicator on variable zero`() {
        // The indicator on Boolean variable 0 makes its true literal the integer 0.
        val rng = Random(0x9B0)
        repeat(300) { iter ->
            val n = 4
            val problem = Problem(
                numBoolVars = n + 1,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf<Factor>(
                    ReifiedPseudoBoolean(
                        auxBoolVar = 0,
                        weights = LongArray(n) { 1L + rng.nextInt(3) },
                        literals = IntArray(n) { Lit.make(it + 1, rng.nextBoolean()) },
                        op = PbOp.entries[rng.nextInt(PbOp.entries.size)],
                        bound = 1L + rng.nextInt(5),
                    ),
                ),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "reified-pb#$iter") { state ->
                (0..n).all { b -> rng.nextInt(2) == 0 || state.pinBool(b, rng.nextBoolean()) }
            }
        }
    }
}
