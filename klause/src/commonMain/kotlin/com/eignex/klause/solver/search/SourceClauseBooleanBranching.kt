package com.eignex.klause.solver.search

import com.eignex.klause.ir.Lit

internal class SourceClauseBooleanBranching(
    private val numBoolVars: Int,
    private val clauses: List<IntArray>,
    private val onAllSatisfied: (() -> Unit)? = null,
) : BooleanBranching, SearchUnassignListener {
    private val fallback = SourceOrderBooleanBranching(numBoolVars)
    private var source: SearchContext? = null
    private var nextClause = 0
    private var reportedAllSatisfied = false

    override fun alternatives(context: SearchContext): List<SearchDecision>? {
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
                is ClauseChoice.Split -> selected = listOf(
                    SearchDecision.Bool(choice.literal), SearchDecision.Bool(Lit.negate(choice.literal)),
                )
            }
        }
        if (nextClause == clauses.size && !reportedAllSatisfied && !stopped(context)) {
            reportedAllSatisfied = true
            onAllSatisfied?.invoke()
        }
        return selected ?: fallback.alternatives(context)
    }

    override fun onUnassign(variable: Int) {
        if (variable in 0 until numBoolVars) nextClause = 0
        fallback.onUnassign(variable)
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
        return when {
            declined -> ClauseChoice.Declined
            satisfied -> ClauseChoice.Satisfied
            candidate >= 0 -> ClauseChoice.Split(candidate)
            else -> ClauseChoice.Declined
        }
    }

    private fun stopped(context: SearchContext): Boolean = context.cancelled().also {
        if (it) context.requestCancellationPoll()
    }

    private sealed interface ClauseChoice {
        data object Satisfied : ClauseChoice
        data object Declined : ClauseChoice
        class Split(val literal: Int) : ClauseChoice
    }
}
