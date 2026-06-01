package com.eignex.klause.bench.format.xcsp3

import com.eignex.klause.choco.ChocoParams
import com.eignex.klause.choco.ChocoSolver
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Linear
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Validates the XCSP3 parser via the Choco reference (independent of klause solver bugs). */
class Xcsp3Test {

    @Test
    fun `parses 4-queens CSP (array + allDifferent + intension) and is SAT`() {
        val xml = """
            <instance format="XCSP3" type="CSP">
              <variables><array id="q" size="[4]"> 1..4 </array></variables>
              <constraints>
                <allDifferent> q[] </allDifferent>
                <intension> ne(add(q[0],0), add(q[1],1)) </intension>
                <intension> ne(sub(q[0],0), sub(q[1],1)) </intension>
              </constraints>
            </instance>
        """.trimIndent()
        val ing = Xcsp3.parse(xml)
        assertEquals(4, ing.problem.numIntVars)
        assertTrue(ing.problem.factors.any { it is AllDifferent })
        assertTrue(ing.problem.factors.count { it is Linear } >= 2)
        assertTrue(ChocoSolver(ing.problem).solve(ChocoParams()) is SolveResult.Sat)
    }

    @Test
    fun `parses COP (sum constraint + maximize objective) and optimizes`() {
        val xml = """
            <instance format="XCSP3" type="COP">
              <variables>
                <var id="a"> 0..2 </var><var id="b"> 0..2 </var><var id="c"> 0..2 </var>
              </variables>
              <constraints>
                <sum><list> a b c </list><coeffs> 1 1 1 </coeffs><condition> (le,4) </condition></sum>
              </constraints>
              <objectives><maximize type="sum"><list> a b c </list><coeffs> 3 2 1 </coeffs></maximize></objectives>
            </instance>
        """.trimIndent()
        val ing = Xcsp3.parse(xml)
        val obj = requireNotNull(ing.objective)
        val r = ChocoSolver(ing.problem).minimize(obj, ChocoParams())
        // internal objective is the negated (minimized) maximize -> -10 for the max of 10.
        assertTrue(r is MinimizeResult.Optimal, "expected Optimal, got $r")
        assertEquals(-10.0, r.objective)
    }
}
