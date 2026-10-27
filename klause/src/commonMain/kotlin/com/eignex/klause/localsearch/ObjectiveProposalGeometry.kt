package com.eignex.klause.localsearch

import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.BIG_ONE

internal fun prepareObjectiveProposals(state: LocalSearchState, objective: IncrementalObjective?): Sequence<Unit> = sequence {
    state.shaping.proposalHotspots = null
    state.shaping.proposalSeeds = null
    state.shaping.adjacentProposalSteps = false
    state.shaping.boundedProposalLeaves = false
    state.shaping.proposalLeafCursor = 0
    state.shaping.geometricProposalCandidates = 0L
    state.shaping.geometricProposalRequested = true
    val functional = objective as? FunctionalObjective ?: return@sequence
    val canonical = state.shaping.objective as? LinearObjective ?: return@sequence
    val terms = functional.termForm ?: return@sequence
    val network = state.invariants ?: return@sequence
    if (canonical.constant != terms.constant) return@sequence
    for (maintained in functional.coneMaintenanceSteps(network::maintains)) {
        if (maintained == false) return@sequence
        yield(Unit)
    }
    for ((left, right) in listOf(canonical.intCoefficientValues to terms.intCoefficientValues,
        canonical.boolWeightValues to terms.boolWeightValues)) {
        for (i in 0 until maxOf(left.size, right.size)) {
            if (left.getOrElse(i) { 0L } != right.getOrElse(i) { 0L }) return@sequence
            if ((i + 1) % 256 == 0) yield(Unit)
        }
    }
    for (i in canonical.realCoefficientValues.indices) {
        if (canonical.realCoefficientValues[i] != 0.0) return@sequence
        if ((i + 1) % 256 == 0) yield(Unit)
    }
    state.shaping.proposalSeeds = functional
    state.shaping.boundedProposalLeaves = true
}

internal fun configureObjectiveProposals(
    state: LocalSearchState,
    objective: IncrementalObjective,
    leafHotspots: Boolean,
    geometricSteps: Boolean,
): Boolean {
    val functional = objective as? FunctionalObjective ?: return false
    val canonical = state.shaping.objective as? LinearObjective ?: return false
    val terms = functional.termForm ?: return false
    val network = state.invariants ?: return false
    if (!functional.conesMaintained(network::maintains) || canonical.constant != terms.constant) return false
    fun agrees(left: LongArray, right: LongArray): Boolean =
        (0 until maxOf(left.size, right.size)).all { left.getOrElse(it) { 0L } == right.getOrElse(it) { 0L } }
    if (!agrees(canonical.intCoefficientValues, terms.intCoefficientValues) ||
        !agrees(canonical.boolWeightValues, terms.boolWeightValues) ||
        canonical.realCoefficientValues.any { it != 0.0 }
    ) return false
    state.shaping.proposalHotspots = if (leafHotspots) functional else canonical
    state.shaping.proposalSeeds = functional
    state.shaping.adjacentProposalSteps = !geometricSteps
    return true
}

internal fun objectiveProposalValuesAgree(
    state: LocalSearchState,
    objective: IncrementalObjective,
    move: Move,
): Boolean {
    val functional = objective as? FunctionalObjective ?: return false
    val canonical = state.shaping.objective as? LinearObjective ?: functional.termForm ?: return false
    val destination = state.inspectMove(move, canonical).assignment
    val value = canonical.evaluateExact(destination)
    return value.den == BIG_ONE && functional.evaluateExact(destination) == value.num
}
