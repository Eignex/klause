package com.eignex.klause.solver.search

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BooleanProofTasksTest {
    private val scope = SourceScope(SourceRevision("source-a", "exact-cnf"))
    private val context = BooleanProofContext(scope, "identity:0")

    @Test
    fun `actual scoped finite refutations should close every leased cube without independent proof authority`() {
        val problem = Problem(2, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(0, 2)), Clause(intArrayOf(0, 3)),
            Clause(intArrayOf(1, 2)), Clause(intArrayOf(1, 3)),
        )).bake()
        val tasks = BooleanProofTasks(context, listOf(0))

        repeat(tasks.size) { worker ->
            val lease = assertNotNull(tasks.acquire(worker))
            val assumptions = Assumptions(bools = lease.cube.literals.associate {
                Lit.variable(it) to Lit.isPositive(it)
            })
            val result = BacktrackSolver(problem).solve(BacktrackParams(
                assumptions = assumptions, nativeSat = false, randomSeed = 17,
            ))
            assertTrue(tasks.complete(lease) {
                if (result is SolveResult.Unsat) BooleanTaskCheck.NativeRefutation else BooleanTaskCheck.Unresolved
            })
        }

        assertTrue(tasks.nativeCoverageComplete)
        assertFalse(tasks.independentlyCheckedCoverageComplete)
    }

    @Test
    fun `partition should cover each Boolean assignment exactly once`() {
        val tasks = BooleanProofTasks(context, listOf(0, 2))
        val cubes = List(tasks.size) { assertNotNull(tasks.acquire(it)).cube.literals }

        for (assignment in 0 until 8) {
            val matches = cubes.count { cube -> cube.all {
                Lit.isPositive(it) == (assignment and (1 shl Lit.variable(it)) != 0)
            } }
            assertEquals(1, matches)
        }
        assertNull(tasks.acquire(4))
        assertFalse(tasks.nativeCoverageComplete)
    }

    @Test
    fun `lost cancelled and released attempts should return unclosed coverage`() {
        for (stop in listOf("lost", "cancelled", "released")) {
            val tasks = BooleanProofTasks(context, listOf(0))
            val lease = assertNotNull(tasks.acquire(0))
            val sibling = assertNotNull(tasks.acquire(1))
            when (stop) {
                "lost" -> tasks.loseWorker(0)
                "cancelled" -> tasks.cancel()
                else -> assertTrue(tasks.release(lease))
            }

            val retry = assertNotNull(tasks.acquire(2))

            assertEquals(lease.cube, retry.cube)
            assertFalse(tasks.complete(lease) { error("stale completion must not reach the checker") })
            assertTrue(tasks.complete(retry) { BooleanTaskCheck.NativeRefutation })
            if (stop != "cancelled") assertTrue(tasks.complete(sibling) { BooleanTaskCheck.NativeRefutation })
            assertEquals(if (stop == "cancelled") 1 else 2, tasks.closed)
        }
    }

    @Test
    fun `duplicate and foreign completions should not close another task`() {
        val tasks = BooleanProofTasks(context, listOf(0))
        val lease = assertNotNull(tasks.acquire(0))
        val foreign = assertNotNull(BooleanProofTasks(context, listOf(0)).acquire(0))
        assertTrue(tasks.complete(lease) { BooleanTaskCheck.NativeRefutation })

        assertFalse(tasks.complete(lease) { error("duplicate check") })
        assertFalse(tasks.complete(foreign) { error("foreign check") })
        assertEquals(1, tasks.closed)
        assertFalse(tasks.nativeCoverageComplete)
    }

    @Test
    fun `changed source assumptions mapping objective and cutoff should invalidate all coverage`() {
        val objective = SourceObjective("sum-bools", ObjectiveSense.MINIMIZE)
        val initial = context.copy(scope = scope.copy(objective = objective), cutoff = BigFraction.ONE)
        val replacements = listOf(
            initial.copy(scope = initial.scope.copy(revision = SourceRevision("source-b", "exact-cnf"))),
            initial.copy(scope = initial.scope.copy(condition = SourceCondition(bools = mapOf(1 to true)))),
            initial.copy(mapping = "identity:1"),
            initial.copy(scope = initial.scope.copy(objective = objective.copy(sense = ObjectiveSense.MAXIMIZE))),
            initial.copy(cutoff = BigFraction.ZERO),
        )
        for (replacement in replacements) {
            val tasks = BooleanProofTasks(initial, listOf(0))
            val closed = assertNotNull(tasks.acquire(0))
            assertTrue(tasks.complete(closed) { BooleanTaskCheck.NativeRefutation })
            val running = assertNotNull(tasks.acquire(1))

            tasks.replaceContext(replacement)

            assertEquals(0, tasks.closed)
            assertEquals(2, tasks.pending)
            assertFalse(tasks.complete(running) { error("stale context check") })
            assertEquals(replacement, assertNotNull(tasks.acquire(2)).context)
        }
    }

    @Test
    fun `invalidation during checking should keep coverage open`() {
        val tasks = BooleanProofTasks(context, emptyList())
        val lease = assertNotNull(tasks.acquire(0))

        val accepted = tasks.complete(lease) {
            tasks.cancel()
            BooleanTaskCheck.IndependentlyCheckedRefutation
        }

        assertFalse(accepted)
        assertEquals(1, tasks.pending)
    }

    @Test
    fun `unresolved checks should retry while native closure stays independently unverified`() {
        val tasks = BooleanProofTasks(context, emptyList())
        val first = assertNotNull(tasks.acquire(0))
        assertFalse(tasks.complete(first) { BooleanTaskCheck.Unresolved })
        val retry = assertNotNull(tasks.acquire(0))

        assertTrue(tasks.complete(retry) { BooleanTaskCheck.NativeRefutation })

        assertTrue(tasks.nativeCoverageComplete)
        assertFalse(tasks.independentlyCheckedCoverageComplete)
    }

    @Test
    fun `cancellation should preserve accepted closures when the queue resumes`() {
        val tasks = BooleanProofTasks(context, listOf(0))
        val first = assertNotNull(tasks.acquire(0))
        assertTrue(tasks.complete(first) { BooleanTaskCheck.IndependentlyCheckedRefutation })
        tasks.acquire(1)
        tasks.cancel()

        val pending = assertNotNull(tasks.acquire(2))
        assertTrue(tasks.complete(pending) { BooleanTaskCheck.IndependentlyCheckedRefutation })

        assertTrue(tasks.independentlyCheckedCoverageComplete)
    }
}
