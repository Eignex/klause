package com.eignex.klause.bench.metric

import com.eignex.klause.formats.mps.Mps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MpsWitnessTest {
    // Flow f through a link only a switched-on binary b opens (f <= 1e6 b), demand 0.5.
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

    // Integers x, w and continuous y in [0, 1] on 1e6 x - 1e6 w + y = 0.5.
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
    fun `a witness that holds after rounding integers should be valid with a recomputed objective`() {
        val verdict = MpsWitness.judge(
            link,
            claim(MpsWitness.Status.OPTIMAL, 0.5, 0.5, mapOf("b" to 0.9999999, "f" to 0.5)),
            noRepair,
        )

        assertEquals(listOf(true, 0.5, true), listOf(verdict.feasible, verdict.objective, verdict.proven))
        assertEquals("valid", verdict.stats["validation"])
    }

    @Test
    fun `a near-zero binary carrying flow through a large coefficient should be invalid without a completion`() {
        // Within a solver's integrality tolerance, 8e-7 times 1e6 lets 0.8 units through a link that is off; the
        // closest completion with the link off still misses a row by 0.25.
        val values = mapOf("b" to 8e-7, "f" to 0.8)
        val verdict = MpsWitness.judge(link, claim(MpsWitness.Status.OPTIMAL, -0.8, -0.8, values)) { lp ->
            if (elastic(lp)) solved("c1" to 0.25, "t" to 0.25) else MpsWitness.RepairResult.Infeasible
        }

        assertEquals(listOf(null, null, false), listOf(verdict.feasible, verdict.objective, verdict.proven))
        assertIs<MpsWitness.Outcome.Invalid>(verdict.outcome)
        assertTrue(verdict.stats.getValue("rowViolation").toDouble() > 0.7)
    }

    @Test
    fun `rounded integers should be repaired by re-solving the continuous variables`() {
        val values = mapOf("x" to 2.0000004, "w" to 2.0, "y" to 0.1)
        var lp = ""
        val verdict = MpsWitness.judge(equality, claim(MpsWitness.Status.LIMIT, 2.1, null, values)) { fixed ->
            lp = fixed
            MpsWitness.RepairResult.Solved(mapOf("c2" to 0.5))
        }

        assertEquals(listOf(true, 2.5, false), listOf(verdict.feasible, verdict.objective, verdict.proven))
        assertEquals("repaired", verdict.stats["validation"])
        assertTrue(" FX bnd c0 2.0" in lp && " FX bnd c1 2.0" in lp && " G r0l" in lp && " L r0u" in lp)
    }

    @Test
    fun `a completion within tolerance should count even when the LP solver calls it infeasible`() {
        val values = mapOf("x" to 2.0000004, "w" to 2.0, "y" to 0.1)
        val verdict = MpsWitness.judge(equality, claim(MpsWitness.Status.LIMIT, 2.1, null, values)) { lp ->
            if (elastic(lp)) solved("c2" to 0.5, "t" to 0.0) else MpsWitness.RepairResult.Infeasible
        }

        assertEquals(listOf(true, 2.5), listOf(verdict.feasible, verdict.objective))
        assertEquals("repaired", verdict.stats["validation"])
    }

    @Test
    fun `an unrepairable witness or a bound without a solution should stay unknown`() {
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
    fun `a gap-tolerance optimum or one whose dual bound excludes its witness should not be a proof`() {
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
    fun `a row of large terms should tolerate exactly the rounding of their sum`() {
        // 1e6 x - 1e6 w + y = 0.5 with x = w = 5: terms of 5e6 cancel, so 5e-6 is within their rounding, 1e-4 is not.
        val within = MpsWitness.validate(equality, mapOf("x" to 5.0, "w" to 5.0, "y" to 0.5 + 5e-6), noRepair)
        val beyond = MpsWitness.violations(equality, doubleArrayOf(5.0, 5.0, 0.5 + 1e-4))

        assertIs<MpsWitness.Outcome.Valid>(within)
        assertTrue(beyond.row > 0.0)
    }

    private fun elastic(lp: String) = "    t obj 1" in lp

    private fun solved(vararg values: Pair<String, Double>) = MpsWitness.RepairResult.Solved(mapOf(*values))
}
