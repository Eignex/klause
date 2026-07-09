package com.eignex.klause.localsearch

import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.Objective
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList
import kotlin.random.Random

/**
 * The optimize-phase objective view of an ongoing solve: the injected [objective], the pre-feasibility
 * shaping [shapingLambda], and the cached set of int decision variables the objective depends on. The
 * per-move objective *delta* scoring stays on [LocalSearchState] (it reads the live assignment); this
 * holds only the objective-hot-spot bias that structured sources sample from.
 */
class ObjectiveShaping {

    /** Objective injected by the engine during a `minimize` call; `null` otherwise — strategies
     *  consulting [LocalSearchState.shapedBreakScore] fall back to the unshaped break score. */
    var objective: Objective? = null
        internal set

    /** Lambda coefficient from `params.costShaping` for pre-feasibility shaping. Set by the engine
     *  on entering `minimize`; 0.0 (no shaping) otherwise or under
     *  [com.eignex.klause.localsearch.CostShaping.FeasibilityFirst]. */
    var shapingLambda: Double = 0.0
        internal set

    private var objIntVarsCache: IntArray? = null
    private var objIntVarsFor: Objective? = null

    /**
     * Int decision variables the current [objective] depends on — nonzero-coefficient vars of a
     * [LinearObjective], leaf vars of a [FunctionalObjective], empty for any other shape or a
     * satisfiability problem. Recomputed only when [objective] changes. This is the *objective*
     * hot-spot set: the feasible-phase analogue of the violated-factor bias the infeasibility-phase
     * sources already use, so an objective-descent structural move can concentrate on variables that
     * actually move the objective rather than swapping objective-irrelevant pairs.
     */
    val objectiveIntVars: IntArray
        get() {
            val obj = objective ?: return EmptyIntArray
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
            for (v in obj.intCoefficients.indices) if (obj.intCoefficients[v] != 0L) out.add(v)
            IntArray(out.size) { out[it] }
        }

        is FunctionalObjective -> obj.leafVars.copyOf()

        else -> EmptyIntArray
    }

    /** Sample an int decision variable biased toward the objective gradient ([objectiveIntVars]),
     *  consuming one RNG int, or `-1` when the objective exposes no per-var int direction. The shared
     *  hot-spot variable-selection primitive for feasible-phase structured sources. */
    fun objectiveHotSpotIntVar(rng: Random): Int {
        val vs = objectiveIntVars
        return if (vs.isEmpty()) -1 else vs[rng.nextInt(vs.size)]
    }
}
