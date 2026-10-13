package com.eignex.klause.solver.search

import com.eignex.klause.solver.pipeline.OpenTheoryExecutionAccounting
import com.eignex.klause.solver.result.OpenTheoryWorkSink
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds

class SearchExecutionControlsTest {
    @Test
    fun `invocation controls preserve the caller meter and deadline`() {
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
    fun `expired deadline has a timeout cause`() {
        val controls = SearchExecutionControlAdapter(Cancellation.after((-1).milliseconds))
        val session = SearchSession(emptyList(), executionControls = controls)

        assertIs<SearchResult.Indeterminate>(session.solve(1))

        assertEquals(SearchStopCause.Timeout, controls.stopCause)
    }

    @Test
    fun `external cancellation has a cancellation cause`() {
        val controls = SearchExecutionControlAdapter(Cancellation { true })
        val session = SearchSession(emptyList(), executionControls = controls)

        assertIs<SearchResult.Indeterminate>(session.solve(1))

        assertEquals(SearchStopCause.Cancelled, controls.stopCause)
    }

    @Test
    fun `native work exhaustion keeps its own cause and cumulative currency`() {
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
    fun `check exhaustion is distinct from work exhaustion`() {
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
    fun `decision exhaustion is distinct from work exhaustion`() {
        val controls = SearchExecutionControlAdapter()
        val session = SearchSession(emptyList(), executionControls = controls)

        assertIs<SearchResult.Indeterminate>(session.solve(1, SearchSolveParams(maxDecisions = 0)))

        assertEquals(SearchStopCause.DecisionAllowance, controls.stopCause)
    }

    @Test
    fun `representation decline does not imply resource exhaustion`() {
        val controls = SearchExecutionControlAdapter()
        val session = SearchSession(listOf(object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck = ComponentCheck.Indeterminate
        }), executionControls = controls)

        assertIs<SearchResult.Indeterminate>(session.solve(0))

        assertEquals(SearchStopCause.RepresentationDecline, controls.stopCause)
    }

    @Test
    fun `a new component operation clears only a local decline`() {
        val controls = SearchExecutionControlAdapter()
        controls.reportStop(SearchStopCause.OperationAllowance)

        controls.beginOperation()

        assertNull(controls.stopCause)
        controls.reportStop(SearchStopCause.CheckAllowance)
        controls.beginOperation()
        assertEquals(SearchStopCause.CheckAllowance, controls.stopCause)
    }
}
