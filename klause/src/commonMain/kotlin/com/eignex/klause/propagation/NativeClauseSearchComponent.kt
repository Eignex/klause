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
    private val numBoolVars: Int,
    clauses: List<Clause>,
    cancellation: Cancellation,
) : SearchComponent {
    private val native = PropagationSession(
        Problem(numBoolVars = numBoolVars, numIntVars = 0, intDomains = emptyArray(), factors = clauses),
        cancellation, nativeSat = true,
    )
    private val delegate = CpSearchComponent(native, sourceIntIds = IntArray(0))
    private val levels = IntArrayList().apply { add(native.decisionLevel) }
    private var interruptedLevel: Int? = null

    private fun unavailable(context: SearchContext): Boolean =
        interruptedLevel != null || native.fixpointCancelled || context.cancelled()

    override fun initialize(context: SearchContext): ComponentResult =
        if (unavailable(context)) ComponentResult.Indeterminate else delegate.initialize(context)

    override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
        val result = when {
            unavailable(context) -> {
                if (interruptedLevel == null) interruptedLevel = context.decisionLevel
                ComponentResult.Indeterminate
            }
            decision is SearchDecision.Bool && decision.literal ushr 1 in 0 until numBoolVars -> {
                val complete = native.decisionLevel
                val asserted = delegate.assert(decision, context)
                if (native.restoreInterruptedFixpoint(complete)) {
                    interruptedLevel = context.decisionLevel
                    ComponentResult.Indeterminate
                } else asserted
            }
            else -> ComponentResult.Consistent
        }
        // Arithmetic assertions do not enter the native projection, but their shared levels must
        // retain checkpoints so a sibling keeps every earlier Boolean pin.
        while (levels.size <= context.decisionLevel) levels.add(native.decisionLevel)
        levels[context.decisionLevel] = native.decisionLevel
        return result
    }

    override fun propagate(context: SearchContext): ComponentResult =
        if (unavailable(context)) ComponentResult.Indeterminate else ComponentResult.Consistent

    override fun retract(decisionLevel: Int) {
        native.popToLevel(levels[decisionLevel])
        levels.truncateTo(decisionLevel + 1)
        // Shared publications survive private rollback until their shared suffix is also removed.
        if (interruptedLevel?.let { decisionLevel < it } == true) interruptedLevel = null
    }

    override fun reasonFor(literal: Int): SearchExplanation? =
        if (interruptedLevel == null && literal ushr 1 in 0 until numBoolVars) delegate.reasonFor(literal) else null

    override fun check(context: SearchContext): ComponentCheck =
        if (unavailable(context)) ComponentCheck.Indeterminate else delegate.check(context)
}
