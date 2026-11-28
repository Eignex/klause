package com.eignex.klause.localsearch

import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.Objective
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.collections.IntArrayList
import kotlin.random.Random

/**
 * Optimize-phase objective view of a solve: the injected [objective], the shaping [shapingLambda], and the int
 * variables the objective depends on, which structured sources sample from. Per-move delta scoring stays on
 * [LocalSearchState].
 */
class ObjectiveShaping {

    /** Objective of the running minimization, or null outside one. */
    var objective: Objective? = null
        internal set

    /** Pre-feasibility shaping weight on the objective; 0.0 outside minimization and under
     *  [com.eignex.klause.localsearch.CostShaping.FeasibilityFirst]. */
    var shapingLambda: Double = 0.0
        internal set

    internal var feasibilityGated: Boolean = false

    internal var proposalHotspots: Objective? = null
    internal var proposalSeeds: FunctionalObjective? = null
    internal var adjacentProposalSteps: Boolean = false
    internal var boundedProposalLeaves: Boolean = false
    internal var proposalLeafCursor: Int = 0
    internal var geometricProposalCandidates: Long = 0L
    internal var geometricProposalRequested: Boolean = false

    private var objIntVarsCache: IntArray? = null
    private var objIntVarsFor: Objective? = null

    /**
     * Int decision variables the [objective] depends on: nonzero-coefficient vars of a [LinearObjective], leaf vars
     * of a [FunctionalObjective], else empty. Objective descent concentrates structural moves on them.
     */
    val objectiveIntVars: IntArray
        get() {
            val obj = proposalHotspots ?: objective ?: return emptyIntArray()
            val cached = objIntVarsCache
            if (cached != null && objIntVarsFor === obj) return cached
            val computed = computeObjectiveIntVars(obj)
            objIntVarsCache = computed
            objIntVarsFor = obj
            return computed
        }

    private fun computeObjectiveIntVars(obj: Objective): IntArray = when (obj) {
        is LinearObjective -> {
            val out = IntArrayList()
            for (v in obj.intCoefficientValues.indices) if (obj.intCoefficientValues[v] != 0L) out.add(v)
            IntArray(out.size) { out[it] }
        }

        is FunctionalObjective -> obj.leafVars.copyOf()

        else -> emptyIntArray()
    }

    /** A uniform member of [objectiveIntVars], consuming one RNG int, or `-1` when it is empty. */
    fun objectiveHotSpotIntVar(rng: Random): Int {
        val vs = objectiveIntVars
        return if (vs.isEmpty()) -1 else vs[rng.nextInt(vs.size)]
    }
}
