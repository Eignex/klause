package com.eignex.klause.solver.search

import com.eignex.klause.solver.pipeline.OpenTheoryExecutionAccounting
import com.eignex.klause.solver.result.OpenTheoryWorkSink
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds

class SearchExecutionControlsTest {
    @Test
    fun `invocation controls should preserve the caller meter and deadline`() {
        var charged = 0L
        val deadline = Cancellation.after(1000.milliseconds)
        val meter = WorkMeter { charged += it }
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = deadline()
            override fun deadline() = deadline.deadline()
            override fun workMeter() = meter
        }
        val session = SearchSession(emptyList(), executionControls = SearchExecutionControlAdapter(stop))

        val operation = session.executionControls.operationAllowance(stop)
        operation.charge(7)

        assertSame(stop, session.executionControls.cancellation)
        assertSame(meter, operation.workMeter())
        assertEquals(7L, charged)
    }

    @Test
    fun `expired deadline should have a timeout cause`() {
        val controls = SearchExecutionControlAdapter(Cancellation.after((-1).milliseconds))
        val session = SearchSession(emptyList(), executionControls = controls)

        assertIs<SearchResult.Indeterminate>(session.solve(1))

        assertEquals(SearchStopCause.Timeout, controls.stopCause)
    }

    @Test
    fun `external cancellation should have a cancellation cause`() {
        val controls = SearchExecutionControlAdapter(Cancellation { true })
        val session = SearchSession(emptyList(), executionControls = controls)

        assertIs<SearchResult.Indeterminate>(session.solve(1))

        assertEquals(SearchStopCause.Cancelled, controls.stopCause)
    }

    @Test
    fun `native work exhaustion should keep its own cause and cumulative currency`() {
        val work = OpenTheoryWorkSink(1)
        val controls = SearchExecutionControlAdapter(accounting = OpenTheoryExecutionAccounting(work))
        val session = SearchSession(emptyList(), executionControls = controls)

        assertIs<SearchResult.Indeterminate>(session.solve(2))
        session.restart()
        assertIs<SearchResult.Indeterminate>(session.solve(2))

        assertEquals(SearchStopCause.WorkAllowance, controls.stopCause)
        assertEquals(1L, work.snapshot().openBoolDecisions)
        assertEquals(1L, work.spent)
    }

    @Test
    fun `check exhaustion should be distinct from work exhaustion`() {
        val work = OpenTheoryWorkSink()
        val component = object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck =
                if (context.consumeCheck()) ComponentCheck.Feasible else ComponentCheck.Indeterminate
        }
        val controls = SearchExecutionControlAdapter(accounting = OpenTheoryExecutionAccounting(work))
        val session = SearchSession(listOf(component), maxChecks = 0, executionControls = controls)

        assertIs<SearchResult.Indeterminate>(session.solve(0))

        assertEquals(SearchStopCause.CheckAllowance, controls.stopCause)
        assertEquals(0L, work.spent)
    }

    @Test
    fun `decision exhaustion should be distinct from work exhaustion`() {
        val controls = SearchExecutionControlAdapter()
        val session = SearchSession(emptyList(), executionControls = controls)

        assertIs<SearchResult.Indeterminate>(session.solve(1, SearchSolveParams(maxDecisions = 0)))

        assertEquals(SearchStopCause.DecisionAllowance, controls.stopCause)
    }

    @Test
    fun `representation decline should not imply resource exhaustion`() {
        val controls = SearchExecutionControlAdapter()
        val session = SearchSession(
            listOf(object : SearchComponent {
                override fun check(context: SearchContext): ComponentCheck = ComponentCheck.Indeterminate
            }),
            executionControls = controls,
        )

        assertIs<SearchResult.Indeterminate>(session.solve(0))

        assertEquals(SearchStopCause.RepresentationDecline, controls.stopCause)
    }

    @Test
    fun `injected native limits should retain their typed refusal causes`() {
        for (cause in listOf(SearchStopCause.CheckAllowance, SearchStopCause.DecisionAllowance)) {
            val controls = SearchExecutionControlAdapter(
                accounting = object : SearchExecutionAccounting {
                    override val stopCause = cause
                    override fun consumeCheck(): Boolean = false
                    override fun canCommitDecision(): Boolean = false
                },
            )

            val accepted = if (cause == SearchStopCause.CheckAllowance) {
                controls.consumeCheck()
            } else {
                controls.canCommitDecision()
            }

            assertFalse(accepted)

            assertEquals(cause, controls.stopCause)
        }
    }

    @Test
    fun `an unresolved branch should retain its cause after a later complete check`() {
        val controls = SearchExecutionControlAdapter()
        val session = SearchSession(
            listOf(object : SearchComponent {
                override fun check(context: SearchContext): ComponentCheck = if (context.boolValue(0) == true) {
                    controls.reportStop(SearchStopCause.OperationAllowance)
                    ComponentCheck.Indeterminate
                } else {
                    controls.beginOperation()
                    ComponentCheck.Infeasible()
                }
            }),
            executionControls = controls,
        )

        assertIs<SearchResult.Indeterminate>(session.solve(1))

        assertEquals(SearchStopCause.OperationAllowance, controls.stopCause)
    }

    @Test
    fun `an expired slice should not become lifetime cancellation after resumption`() {
        var cancelled = false
        var firstCheck = true
        val controls = SearchExecutionControlAdapter(Cancellation { cancelled })
        val component = object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck = if (firstCheck) {
                firstCheck = false
                cancelled = true
                ComponentCheck.Indeterminate
            } else {
                ComponentCheck.Infeasible()
            }
        }
        val session = SearchSession(listOf(component), executionControls = controls)
        val run = session.openRun(
            1,
            lifecycle = object : SearchRunLifecycle {
                override fun onCancellation(context: SearchContext): SearchRunDisposition = SearchRunDisposition.Pause
            },
        )
        assertIs<SearchRunEvent.Paused>(run.next())
        cancelled = false

        assertIs<SearchRunEvent.Indeterminate.Component>(run.next())

        assertEquals(SearchStopCause.RepresentationDecline, controls.stopCause)
    }

    @Test
    fun `a fresh traversal should clear only its own decision diagnostic`() {
        val controls = SearchExecutionControlAdapter()
        val session = SearchSession(
            listOf(object : SearchComponent {
                override fun check(context: SearchContext): ComponentCheck = ComponentCheck.Indeterminate
            }),
            executionControls = controls,
        )
        assertIs<SearchResult.Indeterminate>(session.solve(1, SearchSolveParams(maxDecisions = 0)))
        assertEquals(SearchStopCause.DecisionAllowance, controls.stopCause)

        assertIs<SearchResult.Indeterminate>(session.solve(1))

        assertEquals(SearchStopCause.RepresentationDecline, controls.stopCause)
    }

    @Test
    fun `native allowance exhaustion should precede concurrent external cancellation`() {
        val work = OpenTheoryWorkSink(0)
        work.hasAllowance()
        val controls = SearchExecutionControlAdapter(Cancellation { true }, OpenTheoryExecutionAccounting(work))

        assertEquals(SearchStopCause.WorkAllowance, controls.stopCause)
    }

    @Test
    fun `controls should not be replaceable after component initialization`() {
        val session = SearchSession(emptyList())
        session.initialize()

        assertFailsWith<IllegalStateException> {
            session.attachExecutionControls(SearchExecutionControlAdapter())
        }
    }

    @Test
    fun `a local operation stop should preserve the containing allowance cause`() {
        val controls = SearchExecutionControlAdapter()
        controls.reportStop(SearchStopCause.CheckAllowance)

        controls.reportStop(SearchStopCause.OperationAllowance)

        assertEquals(SearchStopCause.CheckAllowance, controls.stopCause)
    }

    @Test
    fun `a new component operation should clear only a local decline`() {
        val controls = SearchExecutionControlAdapter()
        controls.reportStop(SearchStopCause.OperationAllowance)

        controls.beginOperation()

        assertNull(controls.stopCause)
        controls.reportStop(SearchStopCause.CheckAllowance)
        controls.beginOperation()
        assertEquals(SearchStopCause.CheckAllowance, controls.stopCause)
    }
}
