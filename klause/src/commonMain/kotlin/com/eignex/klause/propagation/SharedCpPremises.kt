package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.MutableIntObjectMap

internal fun PropagationSession.sharedBoundPremise(
    variable: Int,
    upper: Boolean,
    context: SearchContext,
    sourceIntId: (Int) -> Int,
    rootLevel: Int,
): SearchAtomPremise = explanationState.sharedBoundPremise(variable, upper, context, sourceIntId, rootLevel)

internal fun PropagationSession.sharedBooleanPremise(
    variable: Int,
    context: SearchContext,
    sourceIntId: (Int) -> Int,
    rootLevel: Int,
): SearchAtomPremise? = explanationState.reasonOf(explanationState.boolAntecedents[variable])?.let { antecedents ->
    explanationState.sharedPremise(
        IntArray(antecedents.size) { antecedents[it] xor 1 },
        context,
        sourceIntId,
        rootLevel,
    )
}

private fun PropagationState.sharedBoundPremise(
    variable: Int,
    upper: Boolean,
    context: SearchContext,
    sourceIntId: (Int) -> Int,
    rootLevel: Int,
): SearchAtomPremise {
    if (endpointLevel(variable, upper) <= rootLevel) return SearchAtomPremise.All(emptyList())
    endpointReason(variable, upper)?.let { antecedents ->
        return sharedPremise(IntArray(antecedents.size) { antecedents[it] xor 1 }, context, sourceIntId, rootLevel)
    }
    val domain = intDomains[variable]
    val source = sourceIntId(variable)
    val decision = if (upper) {
        SearchDecision.IntAtMost(source, domain.max)
    } else {
        SearchDecision.IntAtLeast(source, domain.min)
    }
    return context.sharedIntegerPremise(decision)
}

// Freeze the native graph while the producing fixpoint is live. Later endpoint moves must not replace its proof.
private fun PropagationState.sharedPremise(
    asserted: IntArray,
    context: SearchContext,
    sourceIntId: (Int) -> Int,
    rootLevel: Int,
): SearchAtomPremise {
    val proofs = MutableIntObjectMap<SearchAtomPremise>()
    val visiting = IntHashSet()
    val pending = ArrayDeque<NativePremiseFrame>()
    for (literal in asserted) pending.addLast(NativePremiseFrame(literal))
    var remaining = 4096
    while (pending.isNotEmpty()) {
        if (remaining-- <= 0) return SearchAtomPremise.Unavailable
        val frame = pending.removeLast()
        val literal = frame.literal
        if (proofs.containsKey(literal)) continue
        val children = frame.children
        if (children != null) {
            val leaves = children.map { proofs[it] ?: return SearchAtomPremise.Unavailable }
            proofs.put(literal, SearchAtomPremise.All(leaves))
            visiting.remove(literal)
            continue
        }
        if (litTruth(literal) != true) return SearchAtomPremise.Unavailable
        val variable = Lit.variable(literal)
        if (variable < problem.numBoolVars) {
            if (context.boolValue(variable) != Lit.isPositive(literal)) return SearchAtomPremise.Unavailable
            proofs.put(literal, SearchAtomPremise.Asserted(SearchDecision.Bool(literal)))
            continue
        }
        val atom = atomIdOf(variable)
        if (atomLevelForConflict(atom) <= rootLevel) {
            proofs.put(literal, SearchAtomPremise.All(emptyList()))
            continue
        }
        val antecedents = atomAntecedentsDerived(atom)
        if (antecedents == null) {
            val premise = sharedIntegerLeaf(atom, Lit.isPositive(literal), context, sourceIntId)
            if (premise === SearchAtomPremise.Unavailable) return premise
            proofs.put(literal, premise)
            continue
        }
        if (!visiting.add(literal)) return SearchAtomPremise.Unavailable
        val next = IntArray(antecedents.size) { antecedents[it] xor 1 }
        pending.addLast(NativePremiseFrame(literal, next))
        for (child in next) pending.addLast(NativePremiseFrame(child))
    }
    return SearchAtomPremise.All(asserted.map { proofs[it] ?: return SearchAtomPremise.Unavailable })
}

private fun PropagationState.sharedIntegerLeaf(
    atom: Int,
    positive: Boolean,
    context: SearchContext,
    sourceIntId: (Int) -> Int,
): SearchAtomPremise {
    val variable = sourceIntId(atoms.intVar[atom])
    val threshold = atoms.threshold[atom]
    val decisions = when (atoms.kind[atom]) {
        AtomKind.GE -> if (positive) {
            listOf(SearchDecision.IntAtLeast(variable, threshold))
        } else if (threshold > Long.MIN_VALUE) {
            listOf(SearchDecision.IntAtMost(variable, threshold - 1L))
        } else {
            return SearchAtomPremise.Unavailable
        }

        AtomKind.LE -> if (positive) {
            listOf(SearchDecision.IntAtMost(variable, threshold))
        } else if (threshold < Long.MAX_VALUE) {
            listOf(SearchDecision.IntAtLeast(variable, threshold + 1L))
        } else {
            return SearchAtomPremise.Unavailable
        }

        AtomKind.EQ -> if (positive) {
            listOf(SearchDecision.IntAtLeast(variable, threshold), SearchDecision.IntAtMost(variable, threshold))
        } else {
            return SearchAtomPremise.Unavailable
        }
    }
    val premises = decisions.map { decision ->
        val premise = context.sharedIntegerPremise(decision)
        if (premise === SearchAtomPremise.Unavailable) return premise
        premise
    }
    return SearchAtomPremise.All(premises)
}

private fun SearchContext.sharedIntegerPremise(decision: SearchDecision): SearchAtomPremise {
    val literal = atomLiteral(decision)
    if (literal != null && boolValue(literal ushr 1) == Lit.isPositive(literal)) {
        return SearchAtomPremise.Asserted(decision)
    }
    return when (decision) {
        is SearchDecision.IntAtLeast -> {
            if (intLowerBound(decision.variable)?.let { it >= decision.lower } == true) {
                intLowerBoundPremise(decision.variable)
            } else {
                SearchAtomPremise.Unavailable
            }
        }

        is SearchDecision.IntAtMost -> {
            if (intUpperBound(decision.variable)?.let { it <= decision.upper } == true) {
                intUpperBoundPremise(decision.variable)
            } else {
                SearchAtomPremise.Unavailable
            }
        }

        else -> SearchAtomPremise.Unavailable
    }
}

private class NativePremiseFrame(val literal: Int, val children: IntArray? = null)
