package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackRecipe
import com.eignex.klause.backtrack.SelectorPortfolio
import com.eignex.klause.backtrack.selector.ActivityBasedSearch
import com.eignex.klause.backtrack.selector.ConflictOrdering
import com.eignex.klause.backtrack.selector.DomWdeg
import com.eignex.klause.backtrack.selector.Impact
import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.IndomainRandom
import com.eignex.klause.backtrack.selector.MaxSd
import com.eignex.klause.backtrack.selector.RegressionVariableSelector
import com.eignex.klause.backtrack.selector.SmallestDomain
import com.eignex.klause.backtrack.selector.SolutionGuided
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.solver.ProblemClass
import com.eignex.klause.util.ArmCatalog

/**
 * Catalog of backtrack (complete-search) arm recipes, keyed by the labels of the CLI `bt-arm=` option and
 * telemetry. [ranked] gives each [Kind]'s pool in credit order.
 */
object BacktrackCatalog {

    // The pigeonhole guard.
    private fun satOptimized() = BacktrackRecipe("satOptimized") { seed, onEvent ->
        BacktrackPresets.satOptimized(randomSeed = seed, onEvent = onEvent)
    }

    private fun clausal(arm: BacktrackArm, decay: Double, maxLearned: Int = 2000) =
        BacktrackRecipe(arm.label) { seed, onEvent ->
            BacktrackPresets.satOptimized(randomSeed = seed, inprocess = false, onEvent = onEvent).copy(
                variableSelector = Vsids(decay = decay),
                valueSelector = IndomainMax,
                targetPhasing = false,
                adaptiveRestart = false,
                emaRestart = true,
                maxLearnedClauses = maxLearned,
                pbLearning = false,
            )
        }

    private fun conflictDriven() = BacktrackRecipe("conflictDriven") { seed, onEvent ->
        BacktrackPresets.conflictDriven(randomSeed = seed, onEvent = onEvent)
    }

    private fun supportAware() = BacktrackRecipe("support-aware") { seed, onEvent ->
        BacktrackPresets.conflictDriven(randomSeed = seed, onEvent = onEvent).copy(
            valueSelector = MaxSd(fallback = IndomainRandom),
        )
    }

    private fun propagationGuided() = BacktrackRecipe("propagation-guided") { seed, onEvent ->
        BacktrackPresets.conflictDriven(randomSeed = seed, onEvent = onEvent).copy(
            valueSelector = MaxSd(probeWorkLimit = 262_144L),
        )
    }

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

    private fun free() = BacktrackRecipe("free") { seed, onEvent ->
        BacktrackParams(randomSeed = seed, lubyRestartBase = 256L, onEvent = onEvent)
    }

    // The fixed order many FD models are written for.
    private fun firstFail() = BacktrackRecipe("first-fail") { seed, onEvent ->
        BacktrackParams(
            randomSeed = seed,
            variableSelector = SmallestDomain,
            valueSelector = IndomainMin,
            lubyRestartBase = 256L,
            onEvent = onEvent,
        )
    }

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

    private fun activity() = BacktrackRecipe("activity") { seed, onEvent ->
        BacktrackParams(
            randomSeed = seed,
            variableSelector = ActivityBasedSearch(),
            valueSelector = Impact(),
            lubyRestartBase = 256L,
            onEvent = onEvent,
        )
    }

    private fun lpArm(emphasis: LpEmphasis) = BacktrackRecipe("lp-${emphasis.name.lowercase()}") { seed, onEvent ->
        BacktrackPresets.conflictDriven(randomSeed = seed, onEvent = onEvent).copy(lpConfig = LpConfig(emphasis))
    }

    private fun lpTreeSearchArm() = BacktrackRecipe("lp-lbtree") { seed, onEvent ->
        val base = BacktrackPresets.conflictDriven(randomSeed = seed, onEvent = onEvent)
        base.copy(lpConfig = LpConfig(LpEmphasis.DEFAULT), lpPlan = base.lpPlan.copy(lbTreeSearch = true))
    }

    // Switches its (variable, value) heuristic at every Luby restart under a UCB1 bandit. A fresh portfolio
    // per worker because it holds mutable bandit state.
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

    private fun objectiveGuided() = BacktrackRecipe("objective-guided") { seed, onEvent ->
        BacktrackPresets.conflictDriven(randomSeed = seed, onEvent = onEvent).copy(objectiveGuidedValues = true)
    }

    private fun make(arm: BacktrackArm): BacktrackRecipe = when (arm) {
        BacktrackArm.SatOptimized -> satOptimized()
        BacktrackArm.ClausalBalanced -> clausal(arm, 0.975, maxLearned = 4000)
        BacktrackArm.ClausalStable -> clausal(arm, 0.99)
        BacktrackArm.ConflictDriven -> conflictDriven()
        BacktrackArm.SupportAware -> supportAware()
        BacktrackArm.PropagationGuided -> propagationGuided()
        BacktrackArm.CoreGuided -> BacktrackRecipe(BacktrackArm.CoreGuided.label) { seed, event ->
            BacktrackPresets.satOptimized(randomSeed = seed, inprocess = false, onEvent = event)
        }
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
        BacktrackArm.LpDefault,
        BacktrackArm.LpAggressive,
        BacktrackArm.LpLbTree,
        BacktrackArm.LpConservative,
        BacktrackArm.SelectorSwitch,
        BacktrackArm.LinUcb,
        BacktrackArm.Free,
        BacktrackArm.DomWdeg,
        BacktrackArm.FirstFail,
        BacktrackArm.Activity,
        BacktrackArm.ObjectiveGuided,
        BacktrackArm.ClausalBalanced,
        BacktrackArm.ClausalStable,
        BacktrackArm.CoreGuided,
        BacktrackArm.SupportAware,
        BacktrackArm.PropagationGuided,
    )
    private val cspOrder = listOf(
        BacktrackArm.SatOptimized,
        BacktrackArm.ConflictDriven,
        BacktrackArm.SupportAware,
        BacktrackArm.PropagationGuided,
        // The relaxation refutes a satisfaction node by infeasibility, which needs no objective.
        BacktrackArm.LpDefault,
        BacktrackArm.LpAggressive,
        BacktrackArm.LpConservative,
        BacktrackArm.DomWdeg,
        BacktrackArm.FirstFail,
        BacktrackArm.Free,
        BacktrackArm.SelectorSwitch,
        BacktrackArm.Activity,
        BacktrackArm.ClausalBalanced,
        BacktrackArm.ClausalStable,
    )

    private fun rankedArms(kind: Kind): List<BacktrackArm> = when (kind) {
        Kind.COP -> copOrder
        Kind.CSP -> cspOrder
    }

    private val catalog = ArmCatalog(BacktrackArm.entries, BacktrackArm::label, ::make)

    /** Every arm label across both kinds. */
    fun labels(): List<String> = catalog.labels(copOrder)

    /** Every arm label for [kind], in credit order. */
    fun labels(kind: Kind): List<String> = catalog.labels(rankedArms(kind))

    /** A fresh recipe for the arm named [label]. */
    fun byLabel(label: String): BacktrackRecipe = catalog.byLabel(label)

    /** One fresh recipe for every arm of [kind], in credit order. */
    fun ranked(kind: Kind): List<BacktrackRecipe> = catalog.ranked(rankedArms(kind))

    internal fun ranked(kind: Kind, facts: ProblemFacts): List<BacktrackRecipe> =
        catalog.ranked(rankedArms(kind, facts))

    internal fun rankedArms(kind: Kind, facts: ProblemFacts): List<BacktrackArm> {
        val order = rankedArms(kind)
        val ranked = if (kind == Kind.CSP && facts.profile.problemClass == ProblemClass.Sat) {
            listOf(BacktrackArm.ClausalBalanced) + order.filter { it != BacktrackArm.ClausalBalanced }
        } else {
            order
        }
        return ranked.filter { facts.eligibility(it.label, it.needs).admitted }
    }

    internal fun recipe(arm: BacktrackArm): BacktrackRecipe = make(arm)

    internal fun armOf(label: String): BacktrackArm? = BacktrackArm.entries.firstOrNull { it.label == label }

    /** Per-arm factories of fresh recipes for [kind], in credit order. */
    fun factories(kind: Kind): List<() -> BacktrackRecipe> = catalog.factories(rankedArms(kind))
}

// label is the external name (CLI `bt-arm=`, telemetry) and must match the built recipe's label. needs is
// what the arm needs from a model to differ from the arm it is built on.
internal enum class BacktrackArm(val label: String, val role: ArmRole, val needs: Set<ArmNeed> = emptySet()) {
    SatOptimized("satOptimized", ArmRole.LearningProof),
    ClausalBalanced("clausal-balanced", ArmRole.LearningProof, setOf(ArmNeed.Clausal)),
    ClausalStable("clausal-stable", ArmRole.LearningProof, setOf(ArmNeed.Clausal)),
    ConflictDriven("conflictDriven", ArmRole.LearningProof),
    SupportAware("support-aware", ArmRole.SelectorDiversity, setOf(ArmNeed.ExtensionalSupports)),
    PropagationGuided("propagation-guided", ArmRole.SelectorDiversity, setOf(ArmNeed.ExtensionalSupports)),
    CoreGuided("core-guided", ArmRole.ObjectiveCores, setOf(ArmNeed.BooleanObjective)),
    LpAggressive("lp-aggressive", ArmRole.LpBounding, setOf(ArmNeed.Relaxation(LpEmphasis.AGGRESSIVE))),
    LpDefault("lp-default", ArmRole.LpBounding, setOf(ArmNeed.Relaxation(LpEmphasis.DEFAULT))),
    LpLbTree(
        "lp-lbtree",
        ArmRole.LpBounding,
        setOf(ArmNeed.Objective, ArmNeed.Relaxation(LpEmphasis.DEFAULT)),
    ),
    LpConservative("lp-conservative", ArmRole.LpBounding, setOf(ArmNeed.Relaxation(LpEmphasis.CONSERVATIVE))),
    LinUcb("linucb", ArmRole.SelectorDiversity),
    Free("free", ArmRole.SelectorDiversity),
    SelectorSwitch("selector-switch", ArmRole.SelectorDiversity),
    DomWdeg("domwdeg", ArmRole.SelectorDiversity),
    FirstFail("first-fail", ArmRole.SelectorDiversity),
    Activity("activity", ArmRole.SelectorDiversity),
    ObjectiveGuided("objective-guided", ArmRole.SelectorDiversity, setOf(ArmNeed.Objective)),
}
