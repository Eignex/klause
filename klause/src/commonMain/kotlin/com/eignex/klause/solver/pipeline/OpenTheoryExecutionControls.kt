package com.eignex.klause.solver.pipeline

import com.eignex.klause.solver.result.OpenTheoryWorkSink
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExecutionAccounting
import com.eignex.klause.solver.search.SearchExecutionControlAdapter
import com.eignex.klause.solver.search.SearchSession

internal class OpenTheoryExecutionAccounting(private val work: OpenTheoryWorkSink) : SearchExecutionAccounting {
    override val exhausted: Boolean get() = work.exhausted

    override fun canCommitDecision(): Boolean = work.hasAllowance()

    override fun recordDecision(decision: SearchDecision): Boolean = when (decision) {
        is SearchDecision.Bool -> work.boolDecision()
        is SearchDecision.IntAtMost, is SearchDecision.IntAtLeast, is SearchDecision.IntEqual -> work.intDecision()
        is SearchDecision.Theory -> work.theoryDecision()
    }

    override fun consumeCheck(): Boolean = work.theoryCheck()
}

internal fun SearchSession.bindOpenTheoryWork(work: OpenTheoryWorkSink) {
    attachExecutionControls(SearchExecutionControlAdapter(executionControls.cancellation, OpenTheoryExecutionAccounting(work)))
}
