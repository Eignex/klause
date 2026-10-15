package com.eignex.klause.localsearch

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LocalSearchEngineHandleTest {
    @Test
    fun `early root and unsupported verdicts should release handle ownership`() {
        val refuted = Problem(
            1,
            0,
            emptyArray(),
            arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        val unsupported = Problem(
            0,
            1,
            arrayOf(IntDomain(Long.MIN_VALUE, Long.MAX_VALUE)),
            emptyArray(),
        )
        for (problem in listOf(refuted, unsupported)) {
            val solver = LocalSearchSolver(problem.bake())
            val params = LocalSearchParams(maxFlips = 1)
            val satisfaction = solver.resumableSolve(params)
            assertTrue(satisfaction.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) != null)
            val optimization = solver.resumable(LinearObjective(), params)
            assertTrue(optimization.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {} != null)
            solver.resumableSolve(params).close()
            satisfaction.close()
            optimization.close()
        }
    }

    @Test
    fun `live handles should reject overlapping solver and session searches`() {
        for (optimizing in listOf(false, true)) {
            val solver = LocalSearchSolver(Problem(0, 0, emptyArray(), emptyArray()).bake())
            val objective = LinearObjective()
            val params = LocalSearchParams()
            val session = solver.session()
            val owner = if (optimizing) {
                session.resumable(objective, params).also {
                    assertNull(it.runSlice(Cancellation { true }, Long.MAX_VALUE, -1) {})
                }
            } else {
                session.resumableSolve(params).also {
                    assertNull(it.runSlice(Cancellation { true }, Long.MAX_VALUE, -1))
                }
            }

            assertFailsWith<IllegalStateException> { solver.resumableSolve(params) }
            assertFailsWith<IllegalStateException> { solver.resumable(objective, params) }
            assertFailsWith<IllegalStateException> { session.solve(params) }
            assertFailsWith<IllegalStateException> { session.samples(params).iterator().hasNext() }
            assertFailsWith<IllegalStateException> { session.minimize(objective, params) }

            owner.close()
            val next = solver.resumableSolve(params)
            owner.close()
            assertFailsWith<IllegalStateException> { solver.resumable(objective, params) }
            next.close()
            assertIs<SolveResult.Sat>(solver.solve(params))
        }
    }

    @Test
    fun `terminal handles should release the solver before close`() {
        val solver = LocalSearchSolver(Problem(0, 0, emptyArray(), emptyArray()).bake())
        val params = LocalSearchParams(maxFlips = 1)
        val objective = LinearObjective()
        val satisfaction = solver.resumableSolve(params)
        val verdict = satisfaction.runSlice(Cancellation.Never, Long.MAX_VALUE, -1)
        assertIs<SolveResult.Sat>(verdict)
        val optimization = solver.resumable(objective, params)
        assertEquals(verdict, satisfaction.runSlice(Cancellation.Never, Long.MAX_VALUE, -1))
        val optimum = optimization.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {}
        assertTrue(optimum != null)
        assertIs<SolveResult.Sat>(solver.solve(params))
        satisfaction.close()
        optimization.close()
        val other = solver.resumableSolve(params)
        assertSame(verdict, satisfaction.runSlice(Cancellation.Never, 0L, 0L))
        assertSame(optimum, optimization.runSlice(Cancellation.Never, 0L, 0L) {})
        satisfaction.close()
        optimization.close()
        assertFailsWith<IllegalStateException> { solver.resumable(objective, params) }
        other.close()
    }

    @Test
    fun `failed handles should release the solver and refuse to resume`() {
        for (optimizing in listOf(false, true)) {
            var fail = true
            val policy = object : RestartPolicy {
                override fun shouldRestart(stepsSinceLastRestart: Int): Boolean = false
                override fun restart(state: LocalSearchState, bestSoFar: Sample?) {
                    if (fail) error("restart failure")
                    state.recompute()
                }
            }
            val solver = LocalSearchSolver(
                Problem(0, 0, emptyArray(), emptyArray()).bake(),
                restartPolicy = policy,
            )
            val params = LocalSearchParams(maxFlips = 1)
            val objective = LinearObjective()
            if (optimizing) {
                val owner = solver.resumable(objective, params)
                assertFailsWith<IllegalStateException> { owner.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {} }
                fail = false
                assertIs<SolveResult.Sat>(solver.solve(params))
                assertFailsWith<IllegalStateException> { owner.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {} }
            } else {
                val owner = solver.resumableSolve(params)
                assertFailsWith<IllegalStateException> { owner.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) }
                fail = false
                assertIs<SolveResult.Sat>(solver.solve(params))
                assertFailsWith<IllegalStateException> { owner.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) }
            }
        }
    }

    @Test
    fun `incumbent callback failures should release optimization ownership`() {
        val solver = LocalSearchSolver(Problem(0, 0, emptyArray(), emptyArray()).bake())
        val owner = solver.resumable(LinearObjective(), LocalSearchParams(maxFlips = 1))

        val failure = assertFailsWith<IllegalArgumentException> {
            owner.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {
                throw IllegalArgumentException("callback failure")
            }
        }

        assertEquals("callback failure", failure.message)
        assertIs<SolveResult.Sat>(solver.solve(LocalSearchParams()))
    }
}
