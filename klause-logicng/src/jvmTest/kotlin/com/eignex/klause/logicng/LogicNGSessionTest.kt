package com.eignex.klause.logicng

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.factor.Cardinality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LogicNGSessionTest {

    /** 3-var exactly-one — three satisfying models: {a}, {b}, {c}. */
    private fun threeVarExactlyOne(): Problem {
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
            ),
        )
        return Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(factor),
        )
    }

    @Test
    fun `session factory returns LogicNGSession instead of StatelessSession`() {
        val solver = LogicNGSolver(threeVarExactlyOne())
        val session = solver.session()
        assertTrue(session is LogicNGSession, "expected LogicNGSession, got ${session::class.simpleName}")
    }

    @Test
    fun `session solve respects per-call assumptions`() {
        val problem = threeVarExactlyOne()
        val session = LogicNGSession(LogicNGSolver(problem))
        // Pin bool 0 = true; exactly-one forces 1 and 2 to false.
        val params = LogicNGParams(assumptions = Assumptions(bools = mapOf(0 to true)))
        val result = session.solve(params)
        val sat = result as? SolveResult.Sat
        assertNotNull(sat)
        assertEquals(true, sat!!.assignment.bools[0])
        assertEquals(false, sat.assignment.bools[1])
        assertEquals(false, sat.assignment.bools[2])
    }

    @Test
    fun `session push assumptions stack into solve`() {
        val problem = threeVarExactlyOne()
        val session = LogicNGSession(LogicNGSolver(problem))
        session.push(Assumptions(bools = mapOf(2 to true)))
        try {
            val result = session.solve(LogicNGParams())
            val sat = result as? SolveResult.Sat
            assertNotNull(sat)
            assertEquals(true, sat!!.assignment.bools[2])
        } finally {
            session.pop()
        }
    }

    @Test
    fun `session enumerate yields all distinct models`() {
        val problem = threeVarExactlyOne()
        val session = LogicNGSession(LogicNGSolver(problem))
        val models = session.enumerate(LogicNGParams(maxModels = 10)).toList()
        // 3 distinct exactly-one models.
        assertEquals(3, models.size, "expected 3 models for exactly-one on 3 bools, got ${models.size}")
        val asTuples = models.map { it.bools.toList() }.toSet()
        assertEquals(3, asTuples.size, "models should be distinct, got $asTuples")
    }

    @Test
    fun `assumption-conflicting solve returns unsat`() {
        val problem = threeVarExactlyOne()
        val session = LogicNGSession(LogicNGSolver(problem))
        // Pin both 0 and 1 to true — violates exactly-one.
        val result = session.solve(LogicNGParams(assumptions = Assumptions(bools = mapOf(0 to true, 1 to true))))
        assertIs<SolveResult.Unsat>(result)
    }

    @Test
    fun `session reuses underlying solver across calls`() {
        // Smoke test: two consecutive solves with different assumptions on the same session.
        // Behavior is observed via results; the "incremental" learning happens at the SAT
        // solver level and isn't directly inspectable, but reusing the session shouldn't
        // produce different solve verdicts than the bare solver would.
        val problem = threeVarExactlyOne()
        val session = LogicNGSession(LogicNGSolver(problem))
        val r1 = session.solve(LogicNGParams(assumptions = Assumptions(bools = mapOf(0 to true))))
        val r2 = session.solve(LogicNGParams(assumptions = Assumptions(bools = mapOf(1 to true))))
        assertTrue(
            r1 is SolveResult.Sat && r2 is SolveResult.Sat,
            "both pinned solves should be SAT; got $r1 and $r2",
        )
        assertEquals(true, (r1 as SolveResult.Sat).assignment.bools[0])
        assertEquals(true, (r2 as SolveResult.Sat).assignment.bools[1])
    }
}
