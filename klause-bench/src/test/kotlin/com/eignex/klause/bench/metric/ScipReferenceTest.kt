package com.eignex.klause.bench.metric

import kotlin.test.Test
import kotlin.test.assertEquals

class ScipReferenceTest {
    @Test
    fun `an optimum's summary and displayed solution give its claim`() {
        val out = "SCIP Status        : problem is solved [optimal solution found]\n" +
            "Primal Bound       : +9.24000000000000e+02 (3 solutions)\n" +
            "Dual Bound         : +9.24000000000000e+02\nGap                : 0.00 %\n" +
            "objective value:                                  924\n" +
            "x243                                                1 \t(obj:9)\n" +
            "x282                                          1.5e-07 \t(obj:10)\n\nSCIP> quit\n"

        val claim = ScipReference.parseClaim(out)

        assertEquals(
            MpsWitness.Claim(MpsWitness.Status.OPTIMAL, 924.0, 924.0, 0.0, mapOf("x243" to 1.0, "x282" to 1.5e-7)),
            claim,
        )
    }

    @Test
    fun `infeasibility, a time limit and a missing incumbent read as such`() {
        val infeasible = ScipReference.parseClaim("SCIP Status        : problem is solved [infeasible]\n")
        val limited = ScipReference.parseClaim(
            "SCIP Status        : solving was interrupted [time limit reached]\n" +
                "Primal Bound       : +1.00000000000000e+20\nDual Bound         : +4.00000000000000e+00\n" +
                "Gap                : infinite\nno solution available\n",
        )

        assertEquals(MpsWitness.Claim(MpsWitness.Status.INFEASIBLE, null, null, null, null), infeasible)
        assertEquals(MpsWitness.Claim(MpsWitness.Status.LIMIT, null, 4.0, null, null), limited)
    }

    @Test
    fun `scip's cache identity names its image, its options and the validation rules`() {
        val identity = ScipReference.identity("sha256:abc")

        assertEquals("${ScipReference.IMAGE}@sha256:abc|${ScipReference.OPTIONS}|${MpsWitness.VERSION}", identity)
    }
}
