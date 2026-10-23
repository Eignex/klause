package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList

internal class NativeClauseSearchComponent(
    numBoolVars: Int,
    clauses: List<Clause>,
    cancellation: Cancellation,
) : SearchComponent {
    private val native = PropagationSession(
        Problem(numBoolVars = numBoolVars, numIntVars = 0, intDomains = emptyArray(), factors = clauses),
        cancellation, nativeSat = true,
    )
    private val delegate = CpSearchComponent(native)
    private val levels = IntArrayList().apply { add(native.decisionLevel) }

    override fun initialize(context: SearchContext): ComponentResult = delegate.initialize(context)

    override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
        val result = delegate.assert(decision, context)
        // Registered arithmetic atoms have no native column, but their shared levels still need a
        // native checkpoint so retracting to one preserves every earlier Boolean assertion.
        while (levels.size <= context.decisionLevel) levels.add(native.decisionLevel)
        levels[context.decisionLevel] = native.decisionLevel
        return result
    }

    override fun retract(decisionLevel: Int) {
        native.popToLevel(levels[decisionLevel])
        levels.truncateTo(decisionLevel + 1)
    }

    override fun reasonFor(literal: Int): SearchExplanation? = delegate.reasonFor(literal)

    override fun check(context: SearchContext): ComponentCheck = delegate.check(context)
}
