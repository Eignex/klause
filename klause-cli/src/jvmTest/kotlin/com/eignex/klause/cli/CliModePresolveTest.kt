package com.eignex.klause.cli

import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.solver.pipeline.ProblemPipeline
import com.eignex.klause.util.Cancellation
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CliModePresolveTest {

    @Test
    fun `an SMT-LIB run should report what presolve did`() {
        // The coefficients share a divisor the bound does not, so `strengthen` divides the row through.
        val smt = File.createTempFile("cli", ".smt2").apply {
            writeText(
                "(set-logic QF_LIA)\n(declare-const x Int)\n(declare-const y Int)\n" +
                    "(assert (>= x 0))\n(assert (>= y 0))\n" +
                    "(assert (<= (+ (* 2 x) (* 4 y)) 7))\n(check-sat)\n",
            )
            deleteOnExit()
        }

        val out = capture { assertEquals(0, runCli(arrayOf("-s", smt.absolutePath))) }

        assertTrue("presolvePasses=" in out, out)
    }

    @Test
    fun `the presolve dry run should report an open model instead of solving it`() {
        val smt = File.createTempFile("cli", ".smt2").apply {
            writeText(
                "(set-logic QF_LIA)\n(declare-const x Int)\n(declare-const y Int)\n" +
                    "(assert (<= (+ (* 2 x) (* 4 y)) 7))\n(check-sat)\n",
            )
            deleteOnExit()
        }

        var out = ""
        val err = captureErr { out = capture { runCli(arrayOf("--param", "dry-run-presolve=true", smt.absolutePath)) } }

        assertTrue("presolve dry-run:" in err, err)
        assertTrue("open columns: 2 of 2" in err, err)
        assertTrue("sat" !in out, out)
    }

    @Test
    fun `dry-run-presolve should print the presolved problem`() {
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }
        val out = captureErr { main(arrayOf("--param", "dry-run-presolve=on", fzn.absolutePath)) }
        assertTrue("presolve dry-run:" in out, out)
        assertTrue("factors:" in out, out)
        assertTrue("elapsed:" in out, out)
    }

    @Test
    fun `finite solve should report its presolve summary`() {
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText("var 1..3: x;\nconstraint int_lt(x, 3);\nsolve satisfy;\n")
            deleteOnExit()
        }

        val out = capture { main(arrayOf("-s", fzn.absolutePath)) }

        assertTrue("%%%mzn-stat: presolvePasses=" in out, out)
    }

    @Test
    fun `the lp-harvest presolve pass should report its contribution when enabled`() {
        // The pairwise rows imply x+y+z <= 4.5, so the sum row is redundant; the `>= 1` row keeps
        // dual fixing from emptying the model before the harvest runs.
        val fzn = File.createTempFile("cli", ".fzn").apply {
            writeText(
                "array[1..3] of var 0..10: x;\n" +
                    "constraint int_lin_le([1,1],[x[1],x[2]],3);\n" +
                    "constraint int_lin_le([1,1],[x[2],x[3]],3);\n" +
                    "constraint int_lin_le([1,1],[x[1],x[3]],3);\n" +
                    "constraint int_lin_le([1,1,1],[x[1],x[2],x[3]],5);\n" +
                    "constraint int_lin_le([-1,-1,-1],[x[1],x[2],x[3]],-1);\n" +
                    "solve satisfy;\n",
            )
            deleteOnExit()
        }
        val off = captureErr { main(arrayOf("--param", "dry-run-presolve=on", fzn.absolutePath)) }
        assertTrue("lp-harvest:" !in off, off)
        val on = captureErr {
            main(arrayOf("--presolve", "default,+lp-harvest", "--param", "dry-run-presolve=on", fzn.absolutePath))
        }
        assertTrue("lp-harvest:" in on, on)
        assertTrue("redundant constraint" in on, on)
    }

    @Test
    fun `the presolve budget should apply the floor cap floor lift plain share and no-limit backstop`() {
        val cases = listOf<Pair<Long?, Long>>(
            // 0.1 of 5s is 500ms, which the 5000ms floor would raise to the whole time limit.
            5_000L to 1250L,
            // 0.1 of 30s is 3s; the floor lifts it to 5s, which is under the 25% ceiling of 7.5s.
            30_000L to 5_000L,
            120_000L to 12_000L,
            null to CliKnobs.DEFAULT_PRESOLVE_BUDGET_MS,
        )
        for ((timeLimitMs, expected) in cases) {
            assertEquals(expected, SolveCore.derivedPresolveBudgetMs(timeLimitMs, 0.1), "timeLimitMs=$timeLimitMs")
        }
    }

    @Test
    fun `routing and source preparation should share one presolve allowance`() {
        val common = CommonOptions().apply {
            timeLimitMs = 60_000L
            deadlineAtMs = nowMillis() + 60_000L
        }
        assertFalse(common.routingCancellation()())
        val shared = requireNotNull(common.presolveBudget)
        assertEquals(presolveWorkFor(6_000L), shared.allowance)

        shared.charge(shared.allowance)
        val (cancel, budget) = SolveCore.presolveAllowance(common, Cancellation.Never)
        assertSame(shared, budget)
        assertTrue(cancel())
    }

    @Test
    fun `elapsed presolve exhaustion should preserve the search cancellation`() {
        val common = CommonOptions().apply { timeLimitMs = 10_000L }
        val shared = requireNotNull(common.sharedPresolveBudget())
        common.presolveTimeCancellation = Cancellation { true }

        val solveCancellation = Cancellation { false }
        val (cancel, budget) = SolveCore.presolveAllowance(common, solveCancellation)

        assertTrue(cancel())
        assertSame(shared, budget)
        assertEquals(shared.allowance, shared.remaining())
        assertFalse(solveCancellation())
    }

    @Test
    fun `routing exhaustion should leave source preparation runnable`() {
        val parent = PresolveBudget(6_000L)
        val route = routingSlice(parent, Cancellation.Never)

        route.charge(3_000L)

        assertTrue(route())
        assertEquals(3_000L, parent.remaining())
        assertFalse(parent.slice(1_000L)())
    }

    @Test
    fun `routing allowance should not restart after local exhaustion`() {
        val common = CommonOptions().apply {
            timeLimitMs = 60_000L
            deadlineAtMs = nowMillis() + 60_000L
        }
        val route = common.routingCancellation()
        val parent = common.presolveBudget

        assertTrue(route === common.routingCancellation())
        assertSame(parent, common.presolveBudget)
    }

    @Test
    fun `global cancellation should also stop a routing slice`() {
        var stopped = false
        val route = routingSlice(PresolveBudget(6_000L), Cancellation { stopped })
        assertFalse(route())

        stopped = true

        assertTrue(route())
    }

    @Test
    fun `routing should apply an explicit presolve budget before the configured fraction`() {
        val oldBudget = System.getProperty(CliKnobs.presolveBudgetMs)
        val oldFraction = System.getProperty(CliKnobs.presolveBudgetFraction)
        try {
            System.setProperty(CliKnobs.presolveBudgetMs, "12000")
            System.setProperty(CliKnobs.presolveBudgetFraction, "0.02")
            val common = CommonOptions().apply {
                timeLimitMs = 60_000L
                deadlineAtMs = nowMillis() + 60_000L
            }
            assertFalse(common.routingCancellation()())
            val shared = requireNotNull(common.presolveBudget)
            assertEquals(presolveWorkFor(12_000L), shared.allowance)

            System.setProperty(CliKnobs.presolveBudgetMs, "1")
            SolveCore.presolveAllowance(common, Cancellation.Never)
            assertSame(shared, common.presolveBudget)
        } finally {
            if (oldBudget == null) {
                System.clearProperty(CliKnobs.presolveBudgetMs)
            } else {
                System.setProperty(CliKnobs.presolveBudgetMs, oldBudget)
            }
            if (oldFraction == null) {
                System.clearProperty(CliKnobs.presolveBudgetFraction)
            } else {
                System.setProperty(CliKnobs.presolveBudgetFraction, oldFraction)
            }
        }
    }

    @Test
    fun `routing should use the configured presolve fraction`() {
        val oldBudget = System.getProperty(CliKnobs.presolveBudgetMs)
        val oldFraction = System.getProperty(CliKnobs.presolveBudgetFraction)
        try {
            System.setProperty(CliKnobs.presolveBudgetMs, "invalid")
            System.setProperty(CliKnobs.presolveBudgetFraction, "0.2")
            val common = CommonOptions().apply {
                timeLimitMs = 60_000L
                deadlineAtMs = nowMillis() + 60_000L
            }
            assertFalse(common.routingCancellation()())
            assertEquals(presolveWorkFor(12_000L), common.presolveBudget?.allowance)
        } finally {
            if (oldBudget == null) {
                System.clearProperty(CliKnobs.presolveBudgetMs)
            } else {
                System.setProperty(CliKnobs.presolveBudgetMs, oldBudget)
            }
            if (oldFraction == null) {
                System.clearProperty(CliKnobs.presolveBudgetFraction)
            } else {
                System.setProperty(CliKnobs.presolveBudgetFraction, oldFraction)
            }
        }
    }

    @Test
    fun `disabled presolve cap should remain disabled after routing`() {
        val oldBudget = System.getProperty(CliKnobs.presolveBudgetMs)
        try {
            System.setProperty(CliKnobs.presolveBudgetMs, "0")
            val common = CommonOptions().apply {
                timeLimitMs = 60_000L
                deadlineAtMs = nowMillis() + 60_000L
            }
            assertFalse(common.routingCancellation()())
            assertTrue(common.presolveAllowanceInitialized)
            assertEquals(null, common.presolveBudget)

            val (cancel, budget) = SolveCore.presolveAllowance(common, Cancellation.Never)
            assertFalse(cancel())
            assertEquals(null, budget)
        } finally {
            if (oldBudget == null) {
                System.clearProperty(CliKnobs.presolveBudgetMs)
            } else {
                System.setProperty(CliKnobs.presolveBudgetMs, oldBudget)
            }
        }
    }

    @Test
    fun `disabled routing proof should leave presolve allowance for preparation`() {
        val common = CommonOptions().apply {
            timeLimitMs = 60_000L
            deadlineAtMs = nowMillis() + 60_000L
            engineParams += "open-bound-proof=false"
        }
        assertTrue(common.routingCancellation()())
        assertTrue(common.routingCancellation()())
        assertFalse(common.presolveAllowanceInitialized)

        val (cancel, budget) = SolveCore.presolveAllowance(common, Cancellation.Never)
        assertFalse(cancel())
        assertEquals(presolveWorkFor(6_000L), budget?.remaining())
    }

    @Test
    fun `exhausted optional routing allowance should retain SMT complete admission`() {
        val source = File.createTempFile("routing-admission", ".smt2").apply {
            writeText("(declare-const x Int) (declare-const y Int) " +
                "(assert (<= (+ (* 2 x) (* 3 y)) 1)) (check-sat)")
            deleteOnExit()
        }
        val common = CommonOptions().apply { engineParams += "presolve-work-limit=1" }

        val loaded = SmtLibMode.newSession().load(source.absolutePath, common)

        val pipeline = assertIs<SolvablePipeline.OpenTheory>(loaded.pipeline)
        assertEquals(ProblemPipeline.EXACT_LIRA, pipeline.request.route)
        assertTrue(common.routingCancellation()())
        assertFalse(common.routingPreparationCancellation()())
    }

    @Test
    fun `required admission should retain routing charges after optional exhaustion`() {
        val common = CommonOptions().apply { engineParams += "presolve-work-limit=1" }
        val optional = common.routingCancellation()
        val required = common.routingPreparationCancellation()
        val budget = checkNotNull(common.presolveBudget)

        required.charge(2)

        assertTrue(optional())
        assertFalse(required())
        assertSame(optional.workMeter(), required.workMeter())
        assertEquals(2L, budget.spent())
    }

    @Test
    fun `required routing admission should retain the containing deadline`() {
        val common = CommonOptions().apply {
            deadlineAtMs = nowMillis() - 1L
            engineParams += "open-bound-proof=false"
        }

        val stop = common.routingPreparationCancellation()

        assertTrue(stop())
        assertFalse(common.presolveAllowanceInitialized)
    }

    @Test
    fun `a presolve allowance too large to count should saturate rather than wrap`() {
        assertEquals(Long.MAX_VALUE, presolveWorkFor(Long.MAX_VALUE / 2))
    }

    private fun capture(block: () -> Unit): String {
        val buf = ByteArrayOutputStream()
        val old = System.out
        System.setOut(PrintStream(buf))
        try {
            block()
        } finally {
            System.setOut(old)
        }
        return buf.toString()
    }

    private fun captureErr(block: () -> Unit): String {
        val buf = ByteArrayOutputStream()
        val old = System.err
        System.setErr(PrintStream(buf))
        try {
            block()
        } finally {
            System.setErr(old)
        }
        return buf.toString()
    }
}
