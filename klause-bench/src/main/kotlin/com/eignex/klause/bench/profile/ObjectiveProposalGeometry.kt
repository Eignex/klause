package com.eignex.klause.bench.profile

import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.solver.objective.IncrementalObjective

internal fun configureObjectiveProposals(
    state: LocalSearchState,
    objective: IncrementalObjective,
    leafHotspots: Boolean,
    geometricSteps: Boolean,
): Boolean = Class.forName("com.eignex.klause.localsearch.ObjectiveProposalGeometryKt").methods.single {
    it.name == "configureObjectiveProposals"
}.invoke(null, state, objective, leafHotspots, geometricSteps) as Boolean
