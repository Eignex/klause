package com.eignex.klause.solver

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.util.Cancellation
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * JVM-only because we use [Thread] to flip the cancellation flag concurrently. The
 * cancellation primitive itself is KMP-friendly; this test happens to need a thread.
 */
class CancellationTest {

    /** A small problem that's quickly Unsat — the engine should burn through the
     *  search space and never get cancelled. Smoke test that the cancellation hook
     *  doesn't break the fast path. */
    @Test
    fun `cancellation never set lets backtrack finish normally`() {
        val r = BacktrackSolver(unsatThreeBools().bake()).solve(BacktrackParams(randomSeed = 0L))
        assertIs<SolveResult.Unsat>(r)
    }

    /** A long-running LS search on an under-constrained problem; signal cancel from
     *  another thread; verify the call returns within the polling interval. */
    @Test
    fun `local search respects cancellation token`() {
        val problem = unconstrainedBools(numVars = 20)
        val cancel = AtomicBoolean(false)
        val params = LocalSearchParams(
            maxFlips = Long.MAX_VALUE,
            randomSeed = 1L,
            cancellation = { cancel.get() },
        )
        val solver = LocalSearchSolver(problem.bake())

        val flagger = thread(start = true) {
            Thread.sleep(50)
            cancel.set(true)
        }
        // Draining the sequence (no take limit) would loop forever if cancellation
        // didn't work. With cancellation set after 50ms we expect prompt exit.
        val started = System.currentTimeMillis()
        val samples = solver.samples(params).toList()
        val elapsed = System.currentTimeMillis() - started
        flagger.join()

        assertTrue(elapsed < 10_000, "LS samples should stop promptly after cancel; took ${elapsed}ms")
        samples.forEach { assertEquals(problem.numBoolVars, it.bools.size) }
    }

    /** Same but for backtrack — cancel a long enumeration and verify prompt exit. */
    @Test
    fun `backtrack respects cancellation token`() {
        // 12 unpinned bools = 4096 leaves; with `IndomainRandom` value selection the
        // enumeration is fast-ish but takes long enough that we can cancel mid-way.
        val problem = unconstrainedBools(numVars = 12)
        val cancel = AtomicBoolean(false)
        val params = BacktrackParams(
            maxDecisions = Long.MAX_VALUE,
            randomSeed = 1L,
            cancellation = { cancel.get() },
        )
        val solver = BacktrackSolver(problem.bake())

        val flagger = thread(start = true) {
            Thread.sleep(20)
            cancel.set(true)
        }
        val started = System.currentTimeMillis()
        val r = solver.solve(params)
        val elapsed = System.currentTimeMillis() - started
        flagger.join()

        // solve() may find a solution quickly (the first leaf is feasible) or get cancelled
        // mid-search. Either way the call returns promptly.
        assertTrue(elapsed < 10_000, "backtrack solve should respond promptly to cancel; took ${elapsed}ms")
        assertTrue(
            r is SolveResult.Sat || r is SolveResult.Unknown,
            "expected Sat or Unknown (cancelled), got $r",
        )
    }

    @Test
    fun `deadline-backed tokens expose a deadline, plain predicates do not`() {
        assertNotNull(Cancellation.after(1.seconds).deadline(), "after() is deadline-backed")
        assertNull(Cancellation.Never.deadline(), "Never carries no deadline")
        assertNull(Cancellation { false }.deadline(), "a plain predicate carries no deadline")
    }

    @Test
    fun `shorten fires earlier than the full deadline and no-ops without one`() {
        val full = Cancellation.after(100.seconds)
        val short = full.shorten(0.1)
        assertTrue(short.deadline()!! < full.deadline()!!, "the shortened token's deadline is earlier")
        // A deadline-less token can't be shortened — it comes back untouched.
        val bare = Cancellation.Never.shorten(0.1)
        assertNull(bare.deadline())
        assertFalse(bare.isCancelled())
    }

    @Test
    fun `or surfaces the earlier deadline`() {
        val soon = Cancellation.after(1.seconds)
        val late = Cancellation.after(100.seconds)
        assertTrue((late or soon).deadline()!! <= soon.deadline()!!, "the composite reports the earlier bound")
    }

    private fun unsatThreeBools(): Problem {
        // x ∧ ¬x — direct contradiction
        return Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
    }

    private fun unconstrainedBools(numVars: Int): Problem =
        Problem(numBoolVars = numVars, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
}
