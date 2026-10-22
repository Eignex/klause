package com.eignex.klause.solver.search

import com.eignex.klause.ir.Lit

internal class SourceClauseBooleanBranching(
    private val numBoolVars: Int,
    private val clauses: List<IntArray>,
    private val pointHints: SearchCandidateHints = SearchCandidateHints.None,
    private val onPreferred: ((Boolean) -> Unit)? = null,
    private val onAllSatisfied: (() -> Unit)? = null,
) : BooleanBranching, SearchCandidateHints, SearchUnassignListener {
    private val fallback = BooleanBranching.SourceOrder(numBoolVars)
    private var source: SearchContext? = null
    private var nextClause = 0
    private var reportedAllSatisfied = false
    private var selectedLiteral = -1
    private var selectedPointMatched = false

    override fun alternatives(context: SearchContext): List<SearchDecision>? {
        selectedLiteral = -1
        selectedPointMatched = false
        if (source !== context) {
            source = context
            nextClause = 0
            reportedAllSatisfied = false
        }
        var selected: List<SearchDecision>? = null
        var declined = false
        while (nextClause < clauses.size && selected == null && !declined) {
            when (val choice = scan(clauses[nextClause], context)) {
                ClauseChoice.Satisfied -> nextClause++
                ClauseChoice.Declined -> declined = true
                is ClauseChoice.Split -> {
                    selectedLiteral = choice.literal
                    selectedPointMatched = choice.pointMatched
                    selected = listOf(
                        SearchDecision.Bool(choice.literal), SearchDecision.Bool(Lit.negate(choice.literal)),
                    )
                }
            }
        }
        if (nextClause == clauses.size && !reportedAllSatisfied && !stopped(context)) {
            reportedAllSatisfied = true
            onAllSatisfied?.invoke()
        }
        return selected ?: fallback.alternatives(context)
    }

    override fun preferredBool(variable: Int): Boolean? {
        if (selectedLiteral < 0 || Lit.variable(selectedLiteral) != variable) return null
        onPreferred?.invoke(selectedPointMatched)
        return selectedLiteral and 1 == 0
    }

    override fun onUnassign(variable: Int) {
        if (variable in 0 until numBoolVars) {
            nextClause = 0
            selectedLiteral = -1
            selectedPointMatched = false
        }
    }

    private fun scan(clause: IntArray, context: SearchContext): ClauseChoice {
        context.executionControls.cancellation.charge(clause.size.toLong() + 1L)
        if (stopped(context)) return ClauseChoice.Declined
        var candidate = -1
        var satisfied = false
        var declined = false
        for (index in clause.indices) {
            val literal = clause[index]
            val variable = Lit.variable(literal)
            if (variable !in 0 until numBoolVars || (index and 8191 == 0 && stopped(context))) {
                declined = true
                break
            }
            val value = context.boolValue(variable)
            if (value != null && Lit.evaluate(literal, value)) {
                satisfied = true
                break
            }
            if (value == null && candidate < 0) candidate = literal
        }
        if (declined || (candidate < 0 && !satisfied)) return ClauseChoice.Declined
        if (satisfied) return ClauseChoice.Satisfied
        if (pointHints !== SearchCandidateHints.None) {
            for (literal in clause) {
                context.executionControls.cancellation.charge(1L)
                if (stopped(context)) return ClauseChoice.Declined
                val variable = Lit.variable(literal)
                if (context.boolValue(variable) != null) continue
                val preferred = pointHints.preferredBool(variable)
                if (stopped(context)) return ClauseChoice.Declined
                if (preferred != null && Lit.evaluate(literal, preferred)) return ClauseChoice.Split(literal, true)
            }
        }
        return ClauseChoice.Split(candidate)
    }

    private fun stopped(context: SearchContext): Boolean = context.cancelled().also {
        if (it) context.requestCancellationPoll()
    }

    private sealed interface ClauseChoice {
        data object Satisfied : ClauseChoice
        data object Declined : ClauseChoice
        class Split(val literal: Int, val pointMatched: Boolean = false) : ClauseChoice
    }
}
