package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class InprocessingTest {

    private class CountingPass(override val preservesVariables: Boolean = true) : InprocessingPass {
        var runs = 0
        var resets = 0

        override fun run(session: PropagationSession, params: BacktrackParams) {
            runs++
        }

        override fun reset() {
            resets++
        }
    }

    private fun problem(): Problem = Problem(
        numBoolVars = 2,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
    )

    private fun session(): PropagationSession = PropagationSession(problem().bake())

    @Test
    fun `a variable-eliminating pass should be rejected at construction`() {
        assertFailsWith<IllegalArgumentException> {
            Inprocessing(listOf(CountingPass(preservesVariables = false)), cadence = 1)
        }
    }

    @Test
    fun `passes should run only every cadence-th restart`() {
        val pass = CountingPass()
        val loop = Inprocessing(listOf(pass), cadence = 3)
        val session = session()
        val params = BacktrackParams()
        repeat(7) { loop.onRestart(session, params) }
        assertEquals(2, pass.runs)
    }

    @Test
    fun `reset should restore the cadence countdown and reset every pass`() {
        val pass = CountingPass()
        val loop = Inprocessing(listOf(pass), cadence = 2)
        val session = session()
        val params = BacktrackParams()
        loop.onRestart(session, params)
        loop.reset()
        loop.onRestart(session, params)
        assertEquals(0, pass.runs)
        assertEquals(1, pass.resets)
        loop.onRestart(session, params)
        assertEquals(1, pass.runs)
    }

    @Test
    fun `from should build a loop only for an unseeded search with a pass enabled`() {
        val solver = BacktrackSolver(problem().bake())
        assertNull(Inprocessing.from(solver, BacktrackParams()))
        assertNotNull(Inprocessing.from(solver, BacktrackParams(vivification = true)))
        assertNull(
            Inprocessing.from(
                solver,
                BacktrackParams(vivification = true, assumptions = Assumptions(bools = mapOf(0 to true))),
            ),
        )
    }
}
