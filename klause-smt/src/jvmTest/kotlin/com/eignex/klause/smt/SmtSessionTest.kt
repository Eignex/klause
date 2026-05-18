package com.eignex.klause.smt

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.factor.Cardinality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SmtSessionTest {

    private fun exactlyOneOfThree(): Problem {
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true),
        ))
        return Problem(
            numBoolVars = 3, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(factor),
        )
    }

    @Test
    fun `push pop scope a pinned variable then release it`() {
        SmtSolver(exactlyOneOfThree()).session().use { s ->
            s.push(Assumptions(bools = mapOf(1 to true)))
            val pinned = s.solve(SmtParams()) as? SolveResult.Sat
            assertNotNull(pinned)
            assertEquals(true, pinned.assignment.bools[1])
            s.pop()

            // After pop, the pin is gone — the prover is free to set b1=false again.
            // Pinning b0=true forces b1=false (exactly-one).
            s.push(Assumptions(bools = mapOf(0 to true)))
            val other = s.solve(SmtParams()) as? SolveResult.Sat
            assertNotNull(other)
            assertEquals(true, other.assignment.bools[0])
            assertEquals(false, other.assignment.bools[1])
            s.pop()
            assertEquals(0, s.depth)
        }
    }

    @Test
    fun `enumerate yields distinct models without repeats`() {
        SmtSolver(exactlyOneOfThree()).session().use { s ->
            val models = s.enumerate(SmtParams(maxModels = 10)).toList()
            assertEquals(3, models.size, "exactly-one of 3 vars has 3 models")
            val patterns = models.map { it.bools.toList() }.toSet()
            assertEquals(3, patterns.size, "models must be distinct")
        }
    }

    @Test
    fun `samples respects call-site assumptions`() {
        SmtSolver(exactlyOneOfThree()).session().use { s ->
            val samples = s.samples(SmtParams(
                maxModels = 5,
                randomSeed = 42L,
                assumptions = Assumptions(bools = mapOf(2 to true)),
            )).toList()
            assertTrue(samples.isNotEmpty())
            for (sample in samples) {
                assertEquals(true, sample.bools[2])
                assertEquals(false, sample.bools[0])
                assertEquals(false, sample.bools[1])
            }
        }
    }
}
