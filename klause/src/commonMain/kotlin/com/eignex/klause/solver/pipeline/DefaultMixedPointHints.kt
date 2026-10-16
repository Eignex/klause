package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.result.HintIndexOutcome
import com.eignex.klause.solver.result.OpenHintUsageSink
import com.eignex.klause.solver.search.SearchCandidateHints
import com.eignex.klause.theory.qflra.ExactLiraSearchComponent
import com.eignex.klause.util.Cancellation

internal class DefaultMixedPointHints(
    private val source: Problem,
    private val component: ExactLiraSearchComponent,
    private val variables: IntArray,
    private val primitive: SearchCandidateHints,
    private val stop: Cancellation,
    private val usage: OpenHintUsageSink?,
) : SearchCandidateHints, AutoCloseable {
    private var attempted = false
    private var closed = false
    var indexedHints: SourceConjunctionHints? = null
        private set

    override fun preferredBool(variable: Int): Boolean? {
        if (closed) return null
        if (!attempted && component.completePointGuidanceEnabled && component.partialPointHintAvailable()) {
            attempted = true
            indexedHints = compile()
        }
        return (indexedHints ?: primitive).preferredBool(variable)
    }

    private fun compile(): SourceConjunctionHints? {
        if (source.factors.size > SourceConjunctionHints.MAX_INDEX_ENTRIES) {
            usage?.index(HintIndexOutcome.SOURCE_LIMIT)
            return null
        }
        if (stop()) {
            usage?.index(HintIndexOutcome.STOPPED)
            return null
        }
        val clauses = ArrayList<Clause>()
        for (factor in source.factors) {
            stop.charge(1L)
            if (stop()) {
                usage?.index(HintIndexOutcome.STOPPED)
                return null
            }
            if (factor is Clause) clauses += factor
        }
        return SourceConjunctionHints.create(clauses, source.numBoolVars, variables, primitive, stop,
            usage = usage, available = component::partialPointHintAvailable,
            knownBool = component::partialPointHintBool)
    }

    override fun close() {
        closed = true
        indexedHints?.close()
        indexedHints = null
    }
}
