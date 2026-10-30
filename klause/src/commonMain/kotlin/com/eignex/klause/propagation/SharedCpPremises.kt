package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.MutableIntObjectMap

private const val SHARED_PREMISE_VISIT_LIMIT = 4096

internal class CpPublicationPremises(
    private val session: PropagationSession,
    private val context: SearchContext,
    private val sourceIntId: (Int) -> Int,
    private val rootLevel: Int,
) {
    private val memo = MutableIntObjectMap<SearchAtomPremise>()
    private var nativePosition = session.explanationState.undo.size
    private var sharedLevel = context.decisionLevel

    fun bound(variable: Int, upper: Boolean): SearchAtomPremise {
        val state = session.explanationState
        val position = state.undo.size
        val level = context.decisionLevel
        if (!state.undoLogging || position != nativePosition || level != sharedLevel) memo.clear()
        nativePosition = position
        sharedLevel = level
        return state.sharedBoundPremise(variable, upper, context, sourceIntId, rootLevel,
            if (state.undoLogging) memo else null)
    }
}

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
    memo: MutableIntObjectMap<SearchAtomPremise>? = null,
): SearchAtomPremise {
    if (runCancelled) return SearchAtomPremise.Unavailable
    if (endpointLevel(variable, upper) <= rootLevel) return SearchAtomPremise.All(emptyList())
    endpointReason(variable, upper)?.let { antecedents ->
        return sharedPremise(IntArray(antecedents.size) { antecedents[it] xor 1 }, context, sourceIntId, rootLevel,
            memo)
    }
    if (runCancelled) return SearchAtomPremise.Unavailable
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
    memo: MutableIntObjectMap<SearchAtomPremise>? = null,
): SearchAtomPremise {
    val proofs = MutableIntObjectMap<SearchAtomPremise>()
    val visiting = IntHashSet()
    val pending = ArrayDeque<NativePremiseFrame>()
    for (literal in asserted) pending.addLast(NativePremiseFrame(literal))
    var remaining = SHARED_PREMISE_VISIT_LIMIT
    while (pending.isNotEmpty()) {
        work++
        if (runCancelled) return SearchAtomPremise.Unavailable
        if (remaining-- <= 0) return SearchAtomPremise.Unavailable
        val frame = pending.removeLast()
        val literal = frame.literal
        if (proofs.containsKey(literal)) continue
        val children = frame.children
        if (children != null) {
            val leaves = children.map { proofs[it] ?: return SearchAtomPremise.Unavailable }
            val premise = SearchAtomPremise.All(leaves)
            proofs.put(literal, premise)
            if (memo != null && children.all { memo.containsKey(it) }) memo.retain(literal, premise)
            visiting.remove(literal)
            continue
        }
        if (litTruth(literal) != true) return SearchAtomPremise.Unavailable
        val retained = memo?.get(literal)
        if (retained != null) {
            proofs.put(literal, retained)
            continue
        }
        val variable = Lit.variable(literal)
        if (variable < problem.numBoolVars) {
            if (context.boolValue(variable) != Lit.isPositive(literal)) return SearchAtomPremise.Unavailable
            val premise = SearchAtomPremise.Asserted(SearchDecision.Bool(literal))
            proofs.put(literal, premise)
            memo?.retain(literal, premise)
            continue
        }
        val atom = atomIdOf(variable)
        if (atomLevelForConflict(atom) <= rootLevel) {
            val premise = SearchAtomPremise.All(emptyList())
            proofs.put(literal, premise)
            memo?.retain(literal, premise)
            continue
        }
        val antecedents = atomAntecedentsDerived(atom)
        if (runCancelled) return SearchAtomPremise.Unavailable
        if (antecedents == null) {
            // Inherited integer proofs can contain Unavailable below a conjunction.
            val premise = sharedIntegerLeaf(atom, Lit.isPositive(literal), context, sourceIntId)
            if (premise === SearchAtomPremise.Unavailable) return premise
            proofs.put(literal, premise)
            if (memo != null && namedIntegerLeaf(premise, context)) memo.retain(literal, premise)
            continue
        }
        if (!visiting.add(literal)) return SearchAtomPremise.Unavailable
        val next = IntArray(antecedents.size) { antecedents[it] xor 1 }
        pending.addLast(NativePremiseFrame(literal, next))
        for (child in next) pending.addLast(NativePremiseFrame(child))
    }
    return SearchAtomPremise.All(asserted.map { proofs[it] ?: return SearchAtomPremise.Unavailable })
}

private fun MutableIntObjectMap<SearchAtomPremise>.retain(literal: Int, premise: SearchAtomPremise) {
    if (size < SHARED_PREMISE_VISIT_LIMIT) put(literal, premise)
}

private fun PropagationState.namedIntegerLeaf(premise: SearchAtomPremise, context: SearchContext): Boolean {
    val leaves = (premise as? SearchAtomPremise.All)?.premises ?: return false
    for (leaf in leaves) {
        work++
        val asserted = leaf as? SearchAtomPremise.Asserted ?: return false
        val literal = context.atomLiteral(asserted.decision) ?: return false
        if (context.boolValue(Lit.variable(literal)) != Lit.isPositive(literal)) return false
    }
    return true
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
