package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackRecipe
import com.eignex.klause.backtrack.SelectorPortfolio
import com.eignex.klause.backtrack.selector.ActivityBasedSearch
import com.eignex.klause.backtrack.selector.ConflictOrdering
import com.eignex.klause.backtrack.selector.DomWdeg
import com.eignex.klause.backtrack.selector.Impact
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.RegressionVariableSelector
import com.eignex.klause.backtrack.selector.SmallestDomain
import com.eignex.klause.backtrack.selector.SolutionGuided
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.solver.ProblemClass
import com.eignex.klause.util.ArmCatalog

/**
 * The public catalog of backtrack (complete-search) arm recipes — the backtrack counterpart of
 * [LocalSearchCatalog]. It maps the string-label boundary (CLI
 * `bt-arm=`, telemetry) to a fresh [BacktrackRecipe], and exposes the credit-ordered
 * per-[Kind] pool. [BacktrackWorkerConfig] wraps these recipes into runnable portfolio arms; a caller
 * outside `klause` (the CLI) resolves named backtrack pools through here into `PortfolioScenario.btPool`.
 *
 * **Per-kind ranking** ([ranked]): SAT-optimized first (the pigeonhole guard), the conflict-driven
 * workhorse, the LP-intensity spread, LinUCB routing, the free engine, the restart-level selector portfolio,
 * and the dom-wdeg / first-fail / activity heuristic arms. A CSP ranks fewer LP arms and no LinUCB (it has no
 * bound to exploit). A pool built for a model keeps only the arms whose needs the model offers.
 */
object BacktrackCatalog {

    /** The strong CDCL/SAT stack (adaptive restarts, target phasing, 3-tier learned DB, vivification);
     *  the pigeonhole guard. Kept at rank 0 for both kinds. */
    private fun satOptimized() = BacktrackRecipe("satOptimized") { seed, onEvent ->
        BacktrackPresets.satOptimized(randomSeed = seed, onEvent = onEvent)
    }

    /** LastConflict + VSIDS + solution-guided values — the general-COP bound workhorse. */
    private fun conflictDriven() = BacktrackRecipe("conflictDriven") { seed, onEvent ->
        BacktrackPresets.conflictDriven(randomSeed = seed, onEvent = onEvent)
    }

    /** The learned LinUCB variable heuristic ([RegressionVariableSelector], #8) on solution-guided
     *  values — the COP routing / feasibility-reach diversity arm. */
    private fun linUcb() = BacktrackRecipe("linucb") { seed, onEvent ->
        BacktrackParams(
            randomSeed = seed,
            variableSelector = RegressionVariableSelector.linUcb(seed = seed),
            valueSelector = SolutionGuided(IndomainMin),
            phaseSaving = true,
            lubyRestartBase = 256L,
            onEvent = onEvent,
        )
    }

    /** The bare free engine (default heuristics, Luby restarts) — plateau diversity. */
    private fun free() = BacktrackRecipe("free") { seed, onEvent ->
        BacktrackParams(randomSeed = seed, lubyRestartBase = 256L, onEvent = onEvent)
    }

    /** Classic first-fail: smallest-domain variable + min value — the FD default many models are written
     *  for. A fixed-order diversity arm distinct from the VSIDS/dom-wdeg workhorses. */
    private fun firstFail() = BacktrackRecipe("first-fail") { seed, onEvent ->
        BacktrackParams(
            randomSeed = seed,
            variableSelector = SmallestDomain,
            valueSelector = IndomainMin,
            lubyRestartBase = 256L,
            onEvent = onEvent,
        )
    }

    /** Weighted-degree branching (dom/wdeg) on solution-guided values — a conflict-history variable
     *  order distinct from VSIDS, strong on structured CSP/COP. */
    private fun domWdeg() = BacktrackRecipe("domwdeg") { seed, onEvent ->
        BacktrackParams(
            randomSeed = seed,
            variableSelector = DomWdeg(),
            valueSelector = SolutionGuided(IndomainMin),
            phaseSaving = true,
            lubyRestartBase = 256L,
            onEvent = onEvent,
        )
    }

    /** Activity-based search (ABS) variable order + impact-based value order — a probing-driven
     *  heuristic that reads the propagation reaction, distinct from the conflict-driven arms. */
    private fun activity() = BacktrackRecipe("activity") { seed, onEvent ->
        BacktrackParams(
            randomSeed = seed,
            variableSelector = ActivityBasedSearch(),
            valueSelector = Impact(),
            lubyRestartBase = 256L,
            onEvent = onEvent,
        )
    }

    /** An LP arm: the conflict-driven core with the LP-relaxation family resolved at [emphasis].
     *  `AGGRESSIVE` is the whole structurally-applicable family (cuts + hulls + probe); `DEFAULT` is
     *  simplex bounding + objective propagation without the expensive cut machinery; `CONSERVATIVE` is
     *  the cheap combinatorial bounds only. Built only where [emphasis] has something to enable. */
    private fun lpArm(emphasis: LpEmphasis) = BacktrackRecipe("lp-${emphasis.name.lowercase()}") { seed, onEvent ->
        BacktrackPresets.conflictDriven(randomSeed = seed, onEvent = onEvent).copy(lpConfig = LpConfig(emphasis))
    }

    private fun lpTreeSearchArm() = BacktrackRecipe("lp-lbtree") { seed, onEvent ->
        val base = BacktrackPresets.conflictDriven(randomSeed = seed, onEvent = onEvent)
        base.copy(lpConfig = LpConfig(LpEmphasis.DEFAULT), lpPlan = base.lpPlan.copy(lbTreeSearch = true))
    }

    /** A restart-level selector portfolio: one arm that switches its (variable, value) heuristic
     *  at every Luby restart under a UCB1 bandit — VSIDS / dom-wdeg / activity / conflict-ordering paired
     *  with min / impact / solution-guided values — so the arm learns per instance which complete-search
     *  heuristic suits it. A fresh portfolio per worker (it holds mutable bandit state). */
    private fun selectorSwitch() = BacktrackRecipe("selector-switch") { seed, onEvent ->
        val palette = SelectorPortfolio.ucb1(
            listOf(
                SelectorPortfolio.Arm("vsids+min", Vsids(), IndomainMin),
                SelectorPortfolio.Arm("domwdeg+solguided", DomWdeg(), SolutionGuided(IndomainMin)),
                SelectorPortfolio.Arm("activity+impact", ActivityBasedSearch(), Impact()),
                SelectorPortfolio.Arm("cos-domwdeg+min", ConflictOrdering(DomWdeg()), IndomainMin),
            ),
        )
        BacktrackParams(
            randomSeed = seed,
            variableSelector = palette.variableSelector,
            valueSelector = palette.valueSelector,
            lubyRestartBase = 100L,
            onEvent = onEvent,
        )
    }

    /** The conflict-driven workhorse with objective-guided value selection: dive toward each
     *  variable's cost-minimising polarity first, so the incumbent improves fast. Built only on a model with
     *  an objective — a diversity arm distinct from the cost-agnostic value orders above. */
    private fun objectiveGuided() = BacktrackRecipe("objective-guided") { seed, onEvent ->
        BacktrackPresets.conflictDriven(randomSeed = seed, onEvent = onEvent).copy(objectiveGuidedValues = true)
    }

    /** Build the recipe for [arm]'s typed identity — the single place each arm's params originate.
     *  Keeps [BacktrackArm.label] and the produced recipe's [BacktrackRecipe.label] in lockstep. */
    private fun make(arm: BacktrackArm): BacktrackRecipe = when (arm) {
        BacktrackArm.SatOptimized -> satOptimized()
        BacktrackArm.ConflictDriven -> conflictDriven()
        BacktrackArm.LpAggressive -> lpArm(LpEmphasis.AGGRESSIVE)
        BacktrackArm.LpDefault -> lpArm(LpEmphasis.DEFAULT)
        BacktrackArm.LpLbTree -> lpTreeSearchArm()
        BacktrackArm.LpConservative -> lpArm(LpEmphasis.CONSERVATIVE)
        BacktrackArm.LinUcb -> linUcb()
        BacktrackArm.Free -> free()
        BacktrackArm.SelectorSwitch -> selectorSwitch()
        BacktrackArm.FirstFail -> firstFail()
        BacktrackArm.DomWdeg -> domWdeg()
        BacktrackArm.Activity -> activity()
        BacktrackArm.ObjectiveGuided -> objectiveGuided()
    }

    private val copOrder = listOf(
        BacktrackArm.SatOptimized,
        BacktrackArm.ConflictDriven,
        BacktrackArm.LpAggressive,
        BacktrackArm.LpDefault,
        BacktrackArm.LpLbTree,
        BacktrackArm.LpConservative,
        BacktrackArm.LinUcb,
        BacktrackArm.Free,
        BacktrackArm.SelectorSwitch,
        BacktrackArm.DomWdeg,
        BacktrackArm.FirstFail,
        BacktrackArm.Activity,
        BacktrackArm.ObjectiveGuided,
    )
    private val cspOrder = listOf(
        BacktrackArm.SatOptimized,
        BacktrackArm.ConflictDriven,
        // The relaxation refutes a satisfaction node by infeasibility, which needs no objective — the
        // emphasis is the axis here exactly as it is for the optimize palette.
        BacktrackArm.LpDefault,
        BacktrackArm.LpAggressive,
        BacktrackArm.Free,
        BacktrackArm.SelectorSwitch,
        BacktrackArm.DomWdeg,
        BacktrackArm.FirstFail,
        BacktrackArm.Activity,
    )

    private fun rankedArms(kind: Kind): List<BacktrackArm> = when (kind) {
        Kind.COP -> copOrder
        Kind.CSP -> cspOrder
    }

    /**
     * [rankedArms] with [BacktrackArm.LpDefault] second when the model has continuous columns. A row that
     * touches a real column never propagates in CP, so on such a model node LP is the only propagation those
     * rows get. Keeping the SAT guard first and admitting the LP workhorse in the second slot gives the
     * default mixed pool that propagation without changing its size or its local-search positions.
     */
    private fun rankedArms(kind: Kind, realColumns: Boolean): List<BacktrackArm> {
        val order = rankedArms(kind)
        if (!realColumns) return order
        val rest = order.filter { it != BacktrackArm.LpDefault }
        return rest.take(1) + BacktrackArm.LpDefault + rest.drop(1)
    }

    /** The shared string-boundary / order-driven accessors (see [ArmCatalog]); this catalog supplies
     *  the per-[Kind] order ([rankedArms]) to the pool builders. */
    private val catalog = ArmCatalog(BacktrackArm.entries, BacktrackArm::label, ::make)

    /** Every arm label across both kinds (COP is the superset), for validation and error messages. */
    fun labels(): List<String> = catalog.labels(copOrder)

    /** Every arm label for [kind], in credit order — for enumerating the pool by name. */
    fun labels(kind: Kind): List<String> = catalog.labels(rankedArms(kind))

    /** A fresh recipe for the arm named [label] (the single string boundary — CLI `bt-arm=`, campaigns). */
    fun byLabel(label: String): BacktrackRecipe = catalog.byLabel(label)

    /** One fresh recipe for every arm of [kind], in credit order; see [rankedArms] for [realColumns]. */
    fun ranked(kind: Kind, realColumns: Boolean = false): List<BacktrackRecipe> =
        catalog.ranked(rankedArms(kind, realColumns))

    /** One fresh recipe for every arm of [kind] the model behind [facts] offers the needs of, in credit order. */
    internal fun ranked(kind: Kind, facts: ProblemFacts): List<BacktrackRecipe> {
        val order = rankedArms(kind, facts.profile.realColumns)
        // A small finite optimization pool needs a relaxation beside its SAT guard: the LP arm finds
        // witnesses that the two LP-free cores miss, without probing an entire intensity palette.
        val ranked = if (kind == Kind.COP && facts.profile.problemClass == ProblemClass.FiniteCp &&
            !facts.binaryIntegers
        ) {
            val rest = order.filter { it != BacktrackArm.LpDefault }
            rest.take(1) + BacktrackArm.LpDefault + rest.drop(1)
        } else {
            order
        }
        return catalog.ranked(ranked.filter { facts.offersAll(it.needs) })
    }

    /** Per-arm recipe factories for [kind], in credit order — each builds a fresh recipe (the factory
     *  shape a campaign or the CLI feeds to `PortfolioScenario.btPool`). */
    fun factories(kind: Kind): List<() -> BacktrackRecipe> = catalog.factories(rankedArms(kind))
}

/**
 * Typed identity of every backtrack catalog arm — the backtrack counterpart of
 * [LocalSearchArm]. [BacktrackCatalog] orders and instantiates these;
 * [label] is the external name (CLI `bt-arm=` / campaign / telemetry), kept in lockstep with the built
 * recipe's label. [needs] is what the arm needs from a model to differ from the arm it is built on.
 */
internal enum class BacktrackArm(val label: String, val needs: Set<ArmNeed> = emptySet()) {
    SatOptimized("satOptimized"),
    ConflictDriven("conflictDriven"),
    LpAggressive("lp-aggressive", setOf(ArmNeed.Relaxation(LpEmphasis.AGGRESSIVE))),
    LpDefault("lp-default", setOf(ArmNeed.Relaxation(LpEmphasis.DEFAULT))),
    LpLbTree("lp-lbtree", setOf(ArmNeed.Objective, ArmNeed.Relaxation(LpEmphasis.DEFAULT))),
    LpConservative("lp-conservative", setOf(ArmNeed.Relaxation(LpEmphasis.CONSERVATIVE))),
    LinUcb("linucb"),
    Free("free"),
    SelectorSwitch("selector-switch"),
    DomWdeg("domwdeg"),
    FirstFail("first-fail"),
    Activity("activity"),
    ObjectiveGuided("objective-guided", setOf(ArmNeed.Objective)),
}
