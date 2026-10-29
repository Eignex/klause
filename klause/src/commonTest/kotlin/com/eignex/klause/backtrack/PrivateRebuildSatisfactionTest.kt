package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.ClauseExchange
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivateRebuildSatisfactionTest {
    @Test
    fun `private consumer should publish a checked canonical witness and release its generation`() {
        for (native in listOf(false, true)) {
            val source = Problem(4, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(0, 2)), Clause(intArrayOf(1, 2)),
                Clause(intArrayOf(3, 4)), Clause(intArrayOf(5, 6)),
            )).bake()
            var opens = 0
            var closes = 0
            val exchange = object : ClauseExchange {
                override fun onSearchStart(session: PropagationSession) { opens++ }
                override fun onSearchClose(session: PropagationSession) { closes++ }
                override fun onRestart(session: PropagationSession) = Unit
            }

            val result = BacktrackSolver(source).solve(BacktrackParams(
                nativeSat = native, privateRebuildWork = 65_536L, clauseExchange = exchange,
            ))

            val accepted = assertIs<SolveResult.Sat>(result)
            assertTrue(source.factors.all { factor ->
                (factor as Clause).literals.any { Lit.evaluate(it, accepted.assignment.boolValue(Lit.variable(it))) }
            })
            val stats = result.stats.search.learning
            assertEquals(1L, stats["privateRebuildConsumers"])
            assertEquals(1L, stats["privateRebuildSourceAccepted"])
            assertEquals(1L, stats["privateRebuildRetired"])
            assertEquals(1, opens)
            assertEquals(1, closes)
        }
    }

    @Test
    fun `a refused generation should resume the retained worker without retrying preparation`() {
        val source = Problem(3, 0, emptyArray(), arrayOf(Clause(intArrayOf(0, 2, 4)))).bake()
        val search = BacktrackSolver(source).resumableSolve(BacktrackParams(
            privateRebuildWork = 65_536L, learnedMemoryScratchBytes = 0L,
        ))
        var result: SolveResult? = null

        search.use {
            assertNull(it.runSlice(Cancellation.Never, Long.MAX_VALUE, 0L))
            repeat(16) {
                if (result == null) result = search.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L)
            }
        }

        val completed = assertIs<SolveResult.Sat>(assertNotNull(result))
        assertEquals(1L, completed.stats.search.learning["privateRebuildAttempts"])
        assertEquals(1L, completed.stats.search.learning["privateRebuildDeclined"])
        assertNull(completed.stats.search.learning["privateRebuildConsumers"])
    }

    @Test
    fun `caller assumptions should keep their original search and verdict authority`() {
        val source = Problem(2, 0, emptyArray(), arrayOf(Clause(intArrayOf(0, 2)))).bake()

        val result = BacktrackSolver(source).solve(BacktrackParams(
            privateRebuildWork = 65_536L, assumptions = Assumptions.None.withBool(0, false),
        ))

        val accepted = assertIs<SolveResult.Sat>(result)
        assertEquals(false, accepted.assignment.boolValue(0))
        assertNull(result.stats.search.learning["privateRebuildAttempts"])
    }

    @Test
    fun `cancellation after private opening should release the generation without publishing a point`() {
        val source = Problem(3, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(0, 2)), Clause(intArrayOf(1, 4)),
        )).bake()
        val search = BacktrackSolver(source).resumableSolve(BacktrackParams(privateRebuildWork = 65_536L))
        val cancellation = Cancellation {
            (search.stats.search.learning["privateRebuildConsumers"] ?: 0L) > 0L
        }

        val result = search.use { it.runSlice(cancellation, Long.MAX_VALUE, -1L) }

        val stopped = assertIs<SolveResult.Unknown>(result)
        assertEquals(1L, stopped.stats.search.learning["privateRebuildConsumers"])
        assertEquals(1L, stopped.stats.search.learning["privateRebuildRetired"])
        assertNull(stopped.stats.search.learning["privateRebuildSourceAccepted"])
        assertEquals(stopped, search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L))
    }
}
