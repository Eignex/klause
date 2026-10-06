package com.eignex.klause.portfolio

import com.eignex.klause.localsearch.localSearchSupports
import com.eignex.klause.lp.bounding.LpAutoConfig
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.propagation.BakedProblem

/**
 * What an arm needs from a model to do anything its siblings do not. A curated pool builds an arm only on
 * a model that offers all of them, and gives the slot to the next arm in its order.
 */
internal sealed interface ArmNeed {
    /** An objective: the arm steers by it, so on a satisfaction model it is a copy of its base arm. */
    data object Objective : ArmNeed

    /** A model local search can run; on any other it declines at once. */
    data object LocalSearch : ArmNeed

    /** An LP relaxation with some technique to enable at [emphasis] under the run's `--lp` ceiling; without
     *  one the arm is a copy of the conflict-driven arm it is built on. */
    data class Relaxation(val emphasis: LpEmphasis) : ArmNeed
}

/**
 * The facts about one model that decide which arms are worth building on it. Each is computed at most once,
 * and only when some arm asks for it.
 */
internal class ProblemFacts(
    /** Whether the model has an objective. */
    val optimizing: Boolean,
    /** Whether the model has continuous columns, which reorders the backtrack pool; see [BacktrackCatalog]. */
    val realColumns: Boolean,
    localSearch: () -> Boolean,
    private val relaxation: (LpEmphasis) -> Boolean,
) {
    private val localSearch by lazy(localSearch)
    private val relaxations = HashMap<LpEmphasis, Boolean>()

    /** Whether the model offers [need]. */
    fun offers(need: ArmNeed): Boolean = when (need) {
        ArmNeed.Objective -> optimizing
        ArmNeed.LocalSearch -> localSearch
        is ArmNeed.Relaxation -> relaxations.getOrPut(need.emphasis) { relaxation(need.emphasis) }
    }

    /** Whether the model offers every one of [needs]. */
    fun offersAll(needs: Collection<ArmNeed>): Boolean = needs.all(::offers)

    /** The two ways to learn a model's facts. */
    companion object {
        /** The facts of [problem], solved as [kind] with the arms' LP capped under [lpCeiling]. */
        fun of(problem: BakedProblem, kind: Kind, lpCeiling: LpConfig): ProblemFacts = ProblemFacts(
            optimizing = kind == Kind.COP,
            realColumns = problem.numRealVars > 0,
            localSearch = { localSearchSupports(problem) },
            relaxation = { emphasis ->
                LpAutoConfig.resolve(problem, LpConfig(emphasis).cappedUnder(lpCeiling)) != LpPlan()
            },
        )

        /** Facts for composing without a model: every need is met except an objective a [kind] lacks. */
        fun assumed(kind: Kind, realColumns: Boolean = false): ProblemFacts =
            ProblemFacts(kind == Kind.COP, realColumns, localSearch = { !realColumns }, relaxation = { true })
    }
}
