package com.eignex.klause.solver.search

internal class SourceOrderBooleanBranching(private val numBoolVars: Int) : BooleanBranching, SearchUnassignListener {
    private var source: SearchContext? = null
    private var next = 0

    override fun alternatives(context: SearchContext): List<SearchDecision>? {
        if (source !== context) {
            source = context
            next = 0
        }
        while (next < numBoolVars && context.boolValue(next) != null) next++
        if (next >= numBoolVars) return null
        return listOf(SearchDecision.Bool((next shl 1) or 1), SearchDecision.Bool(next shl 1))
    }

    override fun onUnassign(variable: Int) {
        if (variable in 0 until numBoolVars) next = minOf(next, variable)
    }
}
