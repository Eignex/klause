package com.eignex.klause.solver.search

import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.MutableIntIntMap
import com.eignex.klause.util.MutableIntObjectMap

internal class SearchTrail {
    private val decisions = ArrayList<SearchDecision>()
    private val boolValues = MutableIntIntMap()
    private val boolLevels = MutableIntIntMap()
    private val boolReasons = MutableIntObjectMap<SearchExplanation>()
    private val boolPublishers = MutableIntObjectMap<SearchComponent>()
    private val valuesAtLevel = ArrayList<IntArrayList>().apply { add(IntArrayList()) }
    private val intFacts = MutableIntObjectMap<IntFact>()
    private val priorIntFactsAtLevel = ArrayList<MutableIntObjectMap<IntFact?>>().apply { add(MutableIntObjectMap()) }
    val pendingAssertions = ArrayDeque<PendingSearchAssertion>()
    val boolTrail = IntArrayList()
    private val trailStartAtLevel = IntArrayList().apply { add(0) }
    val decisionLevel: Int get() = decisions.size
    val rootFixedCount: Int get() = valuesAtLevel[0].size
    val booleanCount: Int get() = boolValues.size
    fun rawBoolValue(variable: Int): Int = boolValues.getOrDefault(variable, -1)
    fun assignedAtLevel(level: Int): IntArrayList = valuesAtLevel[level]
    fun contributeModel(model: SearchModel) {
        boolValues.forEach { variable, value -> model.contribute(SearchBoolValue(variable), value == 1) }
    }

    fun boolValue(variable: Int): Boolean? = when (boolValues.getOrDefault(variable, -1)) {
        0 -> false
        1 -> true
        else -> null
    }
    fun levelOf(literal: Int): Int = boolLevels.getOrDefault(literal ushr 1, 0)
    fun reasonFor(variable: Int): SearchExplanation? = boolReasons[variable]
    fun publisherOf(variable: Int): SearchComponent? = boolPublishers[variable]
    fun retainReason(variable: Int, reason: SearchExplanation) {
        boolReasons.put(variable, reason)
    }
    fun retainPublisher(variable: Int, publisher: SearchComponent) {
        boolPublishers.put(variable, publisher)
    }
    fun lower(variable: Int): Long? = intFacts[variable]?.lower
    fun upper(variable: Int): Long? = intFacts[variable]?.upper
    fun lowerPremise(variable: Int): SearchAtomPremise = intFacts[variable]?.lowerPremise ?: SearchAtomPremise.Unavailable
    fun upperPremise(variable: Int): SearchAtomPremise = intFacts[variable]?.upperPremise ?: SearchAtomPremise.Unavailable

    fun enter(decision: SearchDecision) {
        decisions.add(decision)
        trailStartAtLevel.add(boolTrail.size)
        valuesAtLevel.add(IntArrayList())
        priorIntFactsAtLevel.add(MutableIntObjectMap())
    }

    fun assignBool(variable: Int, value: Int, source: SearchComponent?, atoms: SearchAtomRegistry?) {
        boolValues.put(variable, value)
        boolLevels.put(variable, decisionLevel)
        valuesAtLevel[decisionLevel].add(variable)
        val literal = (variable shl 1) or if (value == 1) 0 else 1
        boolTrail.add(literal)
        atoms?.assertion(literal)?.let {
            pendingAssertions.addLast(PendingSearchAssertion(SearchDecision.Theory(it), source))
        }
    }

    fun retractTo(level: Int, listener: SearchUnassignListener?) {
        require(level in 0..decisionLevel) { "invalid search decision level $level" }
        if (level == decisionLevel) return
        for (current in decisionLevel downTo level + 1) {
            val assigned = valuesAtLevel[current]
            for (position in assigned.size - 1 downTo 0) {
                val variable = assigned[position]
                boolValues.remove(variable)
                boolLevels.remove(variable)
                boolReasons.remove(variable)
                boolPublishers.remove(variable)
                listener?.onUnassign(variable)
            }
            valuesAtLevel.removeAt(current)
            priorIntFactsAtLevel.removeAt(current).forEach { variable, fact ->
                if (fact == null) intFacts.remove(variable) else intFacts.put(variable, fact)
            }
        }
        decisions.subList(level, decisionLevel).clear()
        boolTrail.truncateTo(trailStartAtLevel[level + 1])
        trailStartAtLevel.truncateTo(level + 1)
        pendingAssertions.clear()
    }

    fun resetRoot() {
        boolValues.clear()
        boolLevels.clear()
        boolReasons.clear()
        boolPublishers.clear()
        valuesAtLevel.clear()
        valuesAtLevel.add(IntArrayList())
        intFacts.clear()
        priorIntFactsAtLevel.clear()
        priorIntFactsAtLevel.add(MutableIntObjectMap())
        pendingAssertions.clear()
        boolTrail.clear()
        trailStartAtLevel.clear()
        trailStartAtLevel.add(0)
    }

    fun publishIntFact(
        decision: SearchDecision,
        premise: SearchAtomPremise,
        context: SearchContext,
        source: SearchComponent? = null,
    ): ComponentResult {
        val before = intFacts[decision.intVariable()]
        val result = updateIntFact(decision, premise, context)
        if (result !is ComponentResult.Consistent || intFacts[decision.intVariable()] == before) return result
        pendingAssertions.addLast(PendingSearchAssertion(decision, source))
        return ComponentResult.Consistent
    }

    fun updateIntFact(
        decision: SearchDecision,
        premise: SearchAtomPremise,
        context: SearchContext,
    ): ComponentResult {
        val variable = decision.intVariable()
        val prior = intFacts[variable]
        val values = when (decision) {
            is SearchDecision.IntAtMost -> IntFact(prior?.lower, minOf(prior?.upper ?: Long.MAX_VALUE, decision.upper))

            is SearchDecision.IntAtLeast -> IntFact(maxOf(prior?.lower ?: Long.MIN_VALUE, decision.lower), prior?.upper)

            is SearchDecision.IntEqual -> IntFact(
                maxOf(prior?.lower ?: Long.MIN_VALUE, decision.value),
                minOf(prior?.upper ?: Long.MAX_VALUE, decision.value),
            )

            is SearchDecision.Bool, is SearchDecision.Theory -> error("only integer decisions carry integer facts")
        }
        val candidate = values.copy(
            lowerPremise = if (values.lower == prior?.lower) {
                prior?.lowerPremise ?: SearchAtomPremise.Unavailable
            } else {
                premise
            },
            upperPremise = if (values.upper == prior?.upper) {
                prior?.upperPremise ?: SearchAtomPremise.Unavailable
            } else {
                premise
            },
        )
        if (candidate.lower != null && candidate.upper != null && candidate.lower > candidate.upper) {
            return ComponentResult.Conflict(
                context.explainAtoms(SearchAtomPremise.All(listOf(candidate.lowerPremise, candidate.upperPremise))),
            )
        }
        if (candidate == prior) return ComponentResult.Consistent
        val changedAtLevel = priorIntFactsAtLevel[decisionLevel]
        if (!changedAtLevel.containsKey(variable)) changedAtLevel.put(variable, prior)
        intFacts.put(variable, candidate)
        return ComponentResult.Consistent
    }

    private fun SearchDecision.intVariable(): Int = when (this) {
        is SearchDecision.IntAtMost -> variable
        is SearchDecision.IntAtLeast -> variable
        is SearchDecision.IntEqual -> variable
        is SearchDecision.Bool, is SearchDecision.Theory -> error("only integer decisions carry an integer variable")
    }

    private data class IntFact(
        val lower: Long?,
        val upper: Long?,
        val lowerPremise: SearchAtomPremise = SearchAtomPremise.Unavailable,
        val upperPremise: SearchAtomPremise = SearchAtomPremise.Unavailable,
    )
}

internal data class PendingSearchAssertion(val decision: SearchDecision, val source: SearchComponent?)
