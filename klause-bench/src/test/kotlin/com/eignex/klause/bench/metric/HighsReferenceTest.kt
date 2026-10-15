package com.eignex.klause.bench.metric

import kotlin.test.Test
import kotlin.test.assertEquals

class HighsReferenceTest {
    @Test
    fun `highs summary and solution file should give its claim`() {
        val stdout = "  Status            Optimal\n  Primal bound      924\n  Dual bound        924\n" +
            "  Gap               0% (tolerance: 0.01%)\n  Solution status   feasible\n"
        val solution = "Model status\nOptimal\n\n# Primal solution values\nFeasible\nObjective 924\n" +
            "# Columns 2\nx1 1\nx2 0\n# Rows 1\nr 1\n"
        val limited = HighsReference.parseClaim(
            "  Status            Time limit reached\n  Primal bound      inf\n",
            "# Primal solution values\nNone\n",
        )
        val lp = HighsReference.parseClaim(
            "Model status        : Optimal\nObjective value     :  -4.6475314286e+02\n",
            null,
        )

        val claim = HighsReference.parseClaim(stdout, solution)

        assertEquals(
            MpsWitness.Claim(MpsWitness.Status.OPTIMAL, 924.0, 924.0, 0.0, mapOf("x1" to 1.0, "x2" to 0.0)),
            claim,
        )
        assertEquals(MpsWitness.Claim(MpsWitness.Status.LIMIT, null, null, null, null), limited)
        assertEquals(listOf(-464.75314286, -464.75314286), listOf(lp.primal, lp.dual))
    }
}
