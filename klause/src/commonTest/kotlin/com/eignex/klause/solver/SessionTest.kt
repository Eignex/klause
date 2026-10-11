package com.eignex.klause.solver
import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.brute.BruteForceParams
import com.eignex.klause.brute.BruteForceSolver
import com.eignex.klause.count.ApproxCountConfig
import com.eignex.klause.count.Count
import com.eignex.klause.count.CountConfig
import com.eignex.klause.count.SampleQuality
import com.eignex.klause.count.SamplingConfig
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SampleResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SessionTest {

    @Test
    fun `empty session forwards to solver unchanged`() {
        val problem = exactlyOneOver(3)
        val session = BacktrackSolver(problem.bake()).session()
        assertEquals(0, session.depth)
        val r = session.solve(BacktrackParams(randomSeed = 0L))
        assertTrue(r is SolveResult.Sat)
    }

    @Test
    fun `push pins a variable and pop reverts`() {
        val problem = exactlyOneOver(3)
        val session = BacktrackSolver(problem.bake()).session()

        session.push(Assumptions(bools = mapOf(1 to true)))
        assertEquals(1, session.depth)
        val withPin = session.solve(BacktrackParams(randomSeed = 0L))
        assertTrue(withPin is SolveResult.Sat)
        assertTrue(withPin.assignment.bools[1], "pushed pin should force var 1 = true")

        session.pop()
        assertEquals(0, session.depth)
    }

    @Test
    fun `nested pushes merge with last-write semantics`() {
        val problem = exactlyOneOver(3)
        val session = BacktrackSolver(problem.bake()).session()

        session.push(Assumptions(bools = mapOf(0 to true, 1 to false, 2 to false)))
        session.push(Assumptions(bools = mapOf(1 to true))) // overrides 1 = false
        val r = session.solve(BacktrackParams(randomSeed = 0L))
        assertIs<SolveResult.Unsat>(r)

        session.pop()
        val r2 = session.solve(BacktrackParams(randomSeed = 0L))
        assertTrue(r2 is SolveResult.Sat)
        assertTrue(r2.assignment.bools[0])
    }

    @Test
    fun `pop on empty stack throws`() {
        val session = BacktrackSolver(exactlyOneOver(2).bake()).session()
        assertFails { session.pop() }
    }

    @Test
    fun `local search session honors pushed assumptions`() {
        val problem = exactlyOneOver(4)
        val session = LocalSearchSolver(problem.bake()).session()

        session.push(Assumptions(bools = mapOf(2 to true)))
        val samples = session.samples(LocalSearchParams(maxFlips = 5_000, randomSeed = 0L))
            .take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            assertTrue(s.bools[2], "pushed pin should force var 2 = true in every sample")
        }
    }

    @Test
    fun `counting variants restore nested scopes without changing the source`() {
        val counters = listOf<(Session<BacktrackParams>) -> Count>(
            { it.approximateCount(ApproxCountConfig(seed = 1L)) },
            { it.exactCount().last() },
            { it.count(CountConfig(exactBudget = 1L, seed = 1L)) },
        )
        for (count in counters) {
            val solver = BacktrackSolver(exactlyOneOver(3).bake())
            solver.session().use { session ->
                session.push(Assumptions(bools = mapOf(0 to true)))
                assertEquals(1L, count(session).estimate)
                session.push(Assumptions(bools = mapOf(0 to false)))
                assertEquals(2L, count(session).estimate)
                session.pop()
                assertEquals(1L, count(session).estimate)
                session.pop()
                assertEquals(3L, count(session).estimate)
                assertEquals(1, solver.problem.numFactors)
            }
        }
    }

    @Test
    fun `integer pins and deductions constrain counting`() {
        val session = BacktrackSolver(Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray()).bake()).session()
        session.push(Assumptions.None.withIntHole(0, 1L))
        assertEquals(2L, session.exactCount().last().estimate)
        session.push(Assumptions(ints = mapOf(0 to 2L)))
        assertEquals(1L, session.count().estimate)
        session.pop()
        assertEquals(2L, session.count().estimate)
    }

    @Test
    fun `infeasible scopes count zero`() {
        val session = BacktrackSolver(exactlyOneOver(2).bake()).session()
        session.push(Assumptions(bools = mapOf(0 to true, 1 to true)))
        assertEquals(0L, session.exactCount().last().estimate)
        assertEquals(0L, session.approximateCount().estimate)
        assertEquals(0L, session.count().estimate)
    }

    @Test
    fun `accurate sampling honors call pins and nested session scopes`() {
        val session = BacktrackSolver(exactlyOneOver(3).bake()).session()
        val config = SamplingConfig(quality = SampleQuality.ACCURATE, seed = 1L)
        val params = BacktrackParams(assumptions = Assumptions(bools = mapOf(1 to false)))
        session.push(Assumptions(bools = mapOf(0 to true)))
        val captured = session.samples(config, params)
        session.push(Assumptions(bools = mapOf(0 to false)))
        val capturedDraws = captured.take(3).toList()
        val currentDraws = session.samples(config, params).take(3).toList()
        assertEquals(3, capturedDraws.size)
        assertEquals(3, currentDraws.size)
        assertTrue(capturedDraws.all { it.bools[0] && !it.bools[1] })
        assertTrue(currentDraws.all { !it.bools[0] && !it.bools[1] && it.bools[2] })
    }

    @Test
    fun `search operations honor session pins over call pins`() {
        val session = BacktrackSolver(exactlyOneOver(2).bake()).session()
        val params = BacktrackParams(assumptions = Assumptions(bools = mapOf(0 to false)))
        val objective = LinearObjective(boolWeights = longArrayOf(1, 2))
        session.push(Assumptions(bools = mapOf(0 to true)))
        val draws = listOf(
            assertIs<SolveResult.Sat>(session.solve(params)).assignment,
            assertIs<SampleResult.Found>(session.sample(params)).sample,
            session.enumerate(params).single(),
            assertIs<MinimizeResult.Optimal>(session.minimize(objective, params)).sample,
            assertIs<MinimizeResult.Optimal>(session.improvements(objective, params).last()).sample,
        )
        assertTrue(draws.all { it.bools[0] })
        session.pop()
        assertFalse(assertIs<SolveResult.Sat>(session.solve(params)).assignment.bools[0])
    }

    @Test
    fun `backends without assumptions reject scoped searches`() {
        val session = BruteForceSolver(exactlyOneOver(2).bake()).session()
        val params = BruteForceParams()
        val operations = listOf<() -> Any>(
            { session.solve(params) },
            { session.sample(params) },
            { session.enumerate(params) },
            { session.minimize(LinearObjective(), params) },
        )
        session.push(Assumptions(bools = mapOf(0 to true)))
        for (operation in operations) assertFailsWith<UnsupportedOperationException> { operation() }
        session.pop()
        assertIs<SolveResult.Sat>(session.solve(params))
        session.push(Assumptions.None.withTightenedMin(0, 1L))
        assertFailsWith<UnsupportedOperationException> { session.solve(params) }
    }

    @Test
    fun `early close releases enumeration and fresh calls restart it`() {
        val session = BacktrackSolver(exactlyOneOver(3).bake()).session()
        val params = BacktrackParams(randomSeed = 1L)
        val stream = session.openEnumerate(params)
        val first = stream.next()
        assertFailsWith<IllegalStateException> { session.push(Assumptions.None) }
        assertFailsWith<IllegalStateException> { session.solve(params) }
        stream.close()
        stream.close()
        assertFalse(stream.hasNext())
        session.openEnumerate(params).use { fresh ->
            val models = fresh.toList()
            assertEquals(3, models.size)
            assertTrue(models.first().bools.contentEquals(first.bools))
        }
        session.push(Assumptions.None)
        session.pop()
    }

    @Test
    fun `closing a count stream releases its captured scope`() {
        val session = BacktrackSolver(exactlyOneOver(3).bake()).session()
        session.push(Assumptions(bools = mapOf(0 to true)))
        val stream = session.openExactCount()
        assertFailsWith<IllegalStateException> { session.pop() }
        val count = stream.last()
        assertTrue(count.exact)
        assertEquals(1L, count.estimate)
        session.pop()
        val other = session.openExactCount()
        stream.close()
        assertFailsWith<IllegalStateException> { session.push(Assumptions.None) }
        other.close()
        assertEquals(3L, session.exactCount().last().estimate)
    }

    @Test
    fun `failed enumeration releases its session`() {
        val session = BacktrackSolver(exactlyOneOver(2).bake()).session()
        val stream = session.openEnumerate(BacktrackParams(componentFactory = { error("component initialization") }))
        assertFailsWith<IllegalStateException> { stream.hasNext() }
        assertTrue(stream.isDone)
        session.push(Assumptions(bools = mapOf(0 to true)))
        assertTrue(assertIs<SolveResult.Sat>(session.solve(BacktrackParams())).assignment.bools[0])
    }

    @Test
    fun `optimization stream completion releases its session`() {
        val session = BacktrackSolver(exactlyOneOver(2).bake()).session()
        val params = BacktrackParams(randomSeed = 1L)
        session.openImprovements(LinearObjective(boolWeights = longArrayOf(1, 2)), params).use { stream ->
            val result = stream.last()
            assertIs<MinimizeResult.Optimal>(result)
            assertTrue(stream.isDone)
            session.push(Assumptions.None)
            session.pop()
        }
    }

    @Test
    fun `terminal resumable verdicts stay idempotent after scope restoration`() {
        val session = BacktrackSolver(exactlyOneOver(2).bake()).session()
        session.push(Assumptions(bools = mapOf(0 to true)))
        val handle = assertNotNull(session.resumableSolve(BacktrackParams()))
        assertFailsWith<IllegalStateException> { session.pop() }
        val result = assertIs<SolveResult.Sat>(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L))
        session.pop()
        val other = assertNotNull(session.resumableSolve(BacktrackParams()))
        handle.close()
        assertFailsWith<IllegalStateException> { session.push(Assumptions.None) }
        assertSame(result, handle.runSlice(Cancellation.Never, 0L, 0L))
        other.close()
        session.push(Assumptions.None)
    }

    @Test
    fun `session close releases its active handle and rejects further operations`() {
        val session = BacktrackSolver(exactlyOneOver(2).bake()).session()
        val handle = assertNotNull(session.resumableSolve(BacktrackParams()))
        session.close()
        session.close()
        handle.close()
        assertFailsWith<IllegalStateException> { handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) }
        assertFailsWith<IllegalStateException> { session.solve(BacktrackParams()) }
        assertFailsWith<IllegalStateException> { session.exactCount() }
        assertFailsWith<IllegalStateException> { session.push(Assumptions.None) }
    }

    private fun exactlyOneOver(n: Int): Problem {
        val factor = Cardinality.exactlyOne(
            IntArray(n) { Lit.make(it, true) },
        )
        return Problem(n, 0, emptyArray(), listOf(factor))
    }
}
