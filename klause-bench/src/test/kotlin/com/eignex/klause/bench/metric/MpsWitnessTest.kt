package com.eignex.klause.bench.metric

import com.eignex.klause.formats.mps.Mps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MpsWitnessTest {
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

    /** Integers `x` and `w` and a continuous `y` on one equality, `1e6 x - 1e6 w + y = 0.5`, `y` in [0, 1]. */
    private val equality = Mps.parse(
        """
        NAME eq
        ROWS
         N obj
         E bal
        COLUMNS
            M1 'MARKER' 'INTORG'
            x obj 1 bal 1000000
            w obj 0 bal -1000000
            M2 'MARKER' 'INTEND'
            y obj 1 bal 1
        RHS
            rhs bal 0.5
        BOUNDS
         UP bnd x 5
         UP bnd w 5
         UP bnd y 1
        ENDATA
        """.trimIndent(),
    )

    private val noRepair: MpsWitness.Repair? = null

    private fun claim(status: MpsWitness.Status, primal: Double?, dual: Double?, values: Map<String, Double>?) =
        MpsWitness.Claim(status, primal, dual, gap = null, assignment = values)

    @Test
    fun `a witness that holds once its integers are rounded is valid, its objective recomputed`() {
        val verdict = MpsWitness.judge(
            link,
            claim(MpsWitness.Status.OPTIMAL, 0.5, 0.5, mapOf("b" to 0.9999999, "f" to 0.5)),
            noRepair,
        )

        assertEquals(listOf(true, 0.5, true), listOf(verdict.feasible, verdict.objective, verdict.proven))
        assertEquals("valid", verdict.stats["validation"])
    }

    @Test
    fun `a binary near zero carrying flow through a large coefficient is invalid when no completion exists`() {
        // Within a solver's integrality tolerance, 8e-7 times 1e6 lets 0.8 units through a link that is off.
        val values = mapOf("b" to 8e-7, "f" to 0.8)
        val verdict = MpsWitness.judge(link, claim(MpsWitness.Status.OPTIMAL, -0.8, -0.8, values)) {
            MpsWitness.RepairResult.Infeasible
        }

        assertEquals(listOf(null, null, false), listOf(verdict.feasible, verdict.objective, verdict.proven))
        assertIs<MpsWitness.Outcome.Invalid>(verdict.outcome)
        assertTrue(verdict.stats.getValue("rowViolation").toDouble() > 0.7)
    }

    @Test
    fun `rounded integers are repaired by re-solving the continuous variables, then checked again`() {
        val values = mapOf("x" to 2.0000004, "w" to 2.0, "y" to 0.1)
        var lp = ""
        val verdict = MpsWitness.judge(equality, claim(MpsWitness.Status.LIMIT, 2.1, null, values)) { fixed ->
            lp = fixed
            MpsWitness.RepairResult.Solved(mapOf("c2" to 0.5))
        }

        assertEquals(listOf(true, 2.5, false), listOf(verdict.feasible, verdict.objective, verdict.proven))
        assertEquals("repaired", verdict.stats["validation"])
        assertTrue(" FX bnd c0 2.0" in lp && " FX bnd c1 2.0" in lp && " E r0" in lp)
    }

    @Test
    fun `a witness that cannot be repaired here, or a bound without a solution, stays unknown`() {
        val unrepaired = MpsWitness.judge(
            equality,
            claim(MpsWitness.Status.LIMIT, 2.1, null, mapOf("x" to 2.0000004, "w" to 2.0, "y" to 0.1)),
            noRepair,
        )
        val undecided = MpsWitness.judge(equality, claim(MpsWitness.Status.LIMIT, 2.5, null, null), noRepair)
        val failed = MpsWitness.judge(
            equality,
            claim(MpsWitness.Status.LIMIT, 2.1, null, mapOf("x" to 2.0000004, "w" to 2.0, "y" to 0.1)),
        ) {
            MpsWitness.RepairResult.Failed("time limit")
        }

        assertEquals(listOf(null, null, null), listOf(unrepaired.feasible, undecided.feasible, failed.feasible))
        assertIs<MpsWitness.Outcome.Unresolved>(unrepaired.outcome)
        assertIs<MpsWitness.Outcome.Unresolved>(failed.outcome)
        assertTrue(undecided.stats.getValue("validation").startsWith("unresolved"))
    }

    @Test
    fun `an optimum within the gap tolerance, or whose dual bound excludes its own witness, is not a proof`() {
        val values = mapOf("b" to 1.0, "f" to 0.5)
        val gapLimited = MpsWitness.judge(link, claim(MpsWitness.Status.OPTIMAL, 0.5, 0.49996, values), noRepair)
        val selfExcluding = MpsWitness.judge(link, claim(MpsWitness.Status.OPTIMAL, 0.5, 0.6, values), noRepair)

        assertEquals(
            listOf(true to false, true to false),
            listOf(gapLimited, selfExcluding).map { it.feasible to it.proven },
        )
        assertTrue(gapLimited.stats.getValue("proof").startsWith("gap-limited"))
        assertTrue(selfExcluding.stats.getValue("proof").startsWith("rejected"))
    }

    @Test
    fun `a row of large terms allows for the rounding of their sum, and no more`() {
        // 1e6 x - 1e6 w + y = 0.5 with x = w = 5: terms of 5e6 cancel, so 5e-6 is within their rounding, 1e-4 is not.
        val within = MpsWitness.validate(equality, mapOf("x" to 5.0, "w" to 5.0, "y" to 0.5 + 5e-6), noRepair)
        val beyond = MpsWitness.violations(equality, doubleArrayOf(5.0, 5.0, 0.5 + 1e-4))

        assertIs<MpsWitness.Outcome.Valid>(within)
        assertTrue(beyond.row > 0.0)
    }
}
