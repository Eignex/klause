package com.eignex.klause.bench.metric

import com.eignex.klause.formats.mps.Mps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MpsReferenceTest {
    /** Flow `f` through a link that only a switched-on binary `b` opens (`f <= 1e6 b`), with a demand of 0.5. */
    private val link = Mps.parse(
        """
        NAME link
        ROWS
         N obj
         L cap
         G dem
        COLUMNS
            M1 'MARKER' 'INTORG'
            b obj 1 cap -1000000
            M2 'MARKER' 'INTEND'
            f obj -1 cap 1
            f dem 1
        RHS
            rhs dem 0.5
        BOUNDS
         UP bnd b 1
         UP bnd f 10
        ENDATA
        """.trimIndent(),
    )

    private fun attempt(
        label: String,
        status: MpsWitness.Status,
        primal: Double?,
        values: Map<String, Double>?,
    ): MpsAttempt {
        val claim = MpsWitness.Claim(status, primal, primal, gap = null, assignment = values)
        return MpsAttempt(label, "", 1_000, label, claim, MpsWitness.judge(link, claim, repair = null), emptyMap())
    }

    @Test
    fun `a retry that finds a checked solution refutes the first run's infeasibility`() {
        val infeasible = attempt("default", MpsWitness.Status.INFEASIBLE, null, null)
        val found = attempt("presolve off", MpsWitness.Status.LIMIT, 0.5, mapOf("b" to 1.0, "f" to 0.5))

        val result = MpsReference.result(listOf(infeasible, found), link, maximize = false, "highs|test", "")
        val alone = MpsReference.result(listOf(infeasible), link, maximize = false, "highs|test", "")

        assertEquals(listOf(true, 0.5, false), listOf(result.feasible, result.objective, result.proven))
        assertTrue(result.stats.getValue("refuted").startsWith("default claimed infeasible"))
        assertEquals("b 1.0\nf 0.5\n", result.assignment)
        assertEquals(listOf(false, true), listOf(alone.feasible, alone.proven))
    }

    @Test
    fun `an infeasibility claim the same solver contradicts with a solution, even a rejected one, is not a proof`() {
        val infeasible = attempt("default", MpsWitness.Status.INFEASIBLE, null, null)
        val rejected = attempt("presolve off", MpsWitness.Status.OPTIMAL, -0.8, mapOf("b" to 8e-7, "f" to 0.8))

        val result = MpsReference.result(listOf(infeasible, rejected), link, maximize = false, "highs|test", "")

        assertEquals(listOf(null, false), listOf(result.feasible, result.proven))
        assertTrue(result.stats.getValue("contradicted").startsWith("default claimed infeasible"))
    }
}
