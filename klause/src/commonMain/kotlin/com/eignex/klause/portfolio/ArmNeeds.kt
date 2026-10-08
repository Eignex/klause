package com.eignex.klause.portfolio

import com.eignex.klause.lp.bounding.LpAutoConfig
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.ProblemClass
import com.eignex.klause.solver.ProblemProfile

/**
 * What an arm needs from a model to do anything its siblings do not. A curated pool builds an arm only on
 * a model that offers all of them, and gives the slot to the next arm in its order.
 */
internal sealed interface ArmNeed {
    /** An objective: the arm steers by it, so on a satisfaction model it is a copy of its base arm. */
    data object Objective : ArmNeed

    /** An LP relaxation with some technique to enable at [emphasis] under the run's `--lp` ceiling; without
     *  one the arm is a copy of the conflict-driven arm it is built on. */
    data class Relaxation(val emphasis: LpEmphasis) : ArmNeed
}

/**
 * The facts about one model that decide which arms are worth building on it: its [profile], and which LP techniques
 * it gives something to do. Each relaxation fact is computed at most once, and only when some arm asks for it.
 */
internal class ProblemFacts(
    /** The model's classification. */
    val profile: ProblemProfile,
    private val relaxation: (LpEmphasis) -> Boolean,
) {
    private val relaxations = HashMap<LpEmphasis, Boolean>()

    /** Whether the model offers [need]. */
    fun offers(need: ArmNeed): Boolean = when (need) {
        ArmNeed.Objective -> profile.optimizing
        is ArmNeed.Relaxation -> relaxations.getOrPut(need.emphasis) { relaxation(need.emphasis) }
    }

    /** Whether the model offers every one of [needs]. */
    fun offersAll(needs: Collection<ArmNeed>): Boolean = needs.all(::offers)

    /** The two ways to learn a model's facts. */
    companion object {
        /** The facts of [problem], classified as [profile], with the arms' LP capped under [lpCeiling]. */
        fun of(problem: BakedProblem, profile: ProblemProfile, lpCeiling: LpConfig): ProblemFacts = ProblemFacts(
            profile,
            relaxation = { emphasis ->
                LpAutoConfig.resolve(problem, LpConfig(emphasis).cappedUnder(lpCeiling)) != LpPlan()
            },
        )

        /** Facts for composing without a model: a [ProblemClass.FiniteCp] model of [kind] that offers every
         *  relaxation, or one of [problemClass]. */
        fun assumed(kind: Kind, problemClass: ProblemClass = ProblemClass.FiniteCp): ProblemFacts =
            ProblemFacts(ProblemProfile(problemClass, kind == Kind.COP, wide = false, scheduling = false)) { true }
    }
}

/** The [Kind] a solve with this profile runs as. */
internal val ProblemProfile.kind: Kind get() = if (optimizing) Kind.COP else Kind.CSP
