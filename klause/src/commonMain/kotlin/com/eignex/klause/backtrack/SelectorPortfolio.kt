package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.ValueSelector
import com.eignex.klause.backtrack.selector.VarRef
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import com.eignex.kumulant.bandit.univariate.MultiArmedBandit
import com.eignex.kumulant.bandit.univariate.UCB1
import kotlin.random.Random

/**
 * Restart-level heuristic portfolio with bandit-driven arm selection. Composes a fixed set
 * of `(VariableSelector, ValueSelector)` configurations into one logical "search strategy
 * picker" that learns which configuration works best on the current instance and switches
 * between them at every Luby restart.
 *
 * Each `arm` is one named strategy. The [bandit] (any kumulant [MultiArmedBandit] — UCB1,
 * UCB1Tuned, ThompsonSampling, etc.) is consulted at construction to pick the initial arm,
 * and at every restart to update the previous arm's reward and choose the next arm.
 *
 *  - [variableSelector] / [valueSelector] expose the portfolio as drop-in slots for
 *    [BacktrackParams]. **Both slots must reference the same `SelectorPortfolio` instance
 *    to share state**; treat the portfolio as the unit, not the two slots.
 *  - At restart, the variable-heuristic delegate is responsible for the bandit update +
 *    arm switch; the value-heuristic delegate just forwards `onRestart` to the (now new)
 *    arm's inner value heuristic. Engine fires var first, then value, which matches this
 *    convention.
 *
 * Reward signal:
 *  - [rewardFn] receives [RunStats] for the run that just ended and returns a Double the
 *    bandit will fold into its accumulator. Higher = better, by convention.
 *  - Default reward = `1.0` if a solution was found during the run, else `0.0` — a clean
 *    Bernoulli signal that pairs well with [UCB1] / `BetaBernoulliTS`. For optimisation,
 *    plug in a custom function that returns improvement in incumbent objective.
 *
 * Construction example (UCB1 over four classical configs):
 * ```
 * val portfolio = SelectorPortfolio(
 *     arms = listOf(
 *         Arm("vsids+random", Vsids(), IndomainRandom),
 *         Arm("domwdeg+min",   DomWdeg(), IndomainMin),
 *         Arm("cos+impact",    ConflictOrdering(DomWdeg()), Impact()),
 *         Arm("abs+maxsd",     ActivityBasedSearch(), MaxSd()),
 *     ),
 *     bandit = MultiArmedBandit(nbrArms = 4, policy = UCB1(alpha = 1.0)),
 * )
 * solver.solve(BacktrackParams(
 *     variableSelector = portfolio.variableSelector,
 *     valueSelector    = portfolio.valueSelector,
 *     lubyRestartBase   = 100L,
 * ))
 * ```
 */
internal class SelectorPortfolio(
    val arms: List<Arm>,
    private val bandit: MultiArmedBandit<*>,
    private val rewardFn: (RunStats) -> Double = { stats -> if (stats.solutionsFound > 0) 1.0 else 0.0 },
) {
    init {
        require(arms.isNotEmpty()) { "portfolio must have at least one arm" }
        require(bandit.nbrArms == arms.size) {
            "bandit arm count (${bandit.nbrArms}) must equal arms.size (${arms.size})"
        }
    }

    /** One strategy in the portfolio's palette. */
    data class Arm(val label: String, val variableSelector: VariableSelector, val valueSelector: ValueSelector)

    /** Statistics passed to [rewardFn] at the end of every Luby-restart-bounded run. */
    data class RunStats(
        /** Number of [VariableSelector.onConflict] events during the run. */
        val conflicts: Int,
        /** Number of SAT leaves found during the run (`onSolution` events). */
        val solutionsFound: Int,
    )

    private var currentArm: Int = bandit.choose()
    private var conflicts: Int = 0
    private var solutionsFound: Int = 0

    /** The arm currently driving search. Updates at every restart. */
    val current: Arm get() = arms[currentArm]

    /** Index of [current] within [arms]. Inspectable for telemetry. */
    val currentArmIndex: Int get() = currentArm

    /** Drop-in [VariableSelector] slot for [BacktrackParams.variableSelector]. */
    val variableSelector: VariableSelector = object : VariableSelector {
        override fun pick(session: PropagationSession, rng: Random) = current.variableSelector.pick(session, rng)
        override fun onConflict(varRef: VarRef) {
            conflicts++
            current.variableSelector.onConflict(varRef)
        }
        override fun onConflict(varRef: VarRef, unsat: PropagationResult.Unsat) {
            conflicts++
            current.variableSelector.onConflict(varRef, unsat)
        }
        override fun onCommit(varRef: VarRef) = current.variableSelector.onCommit(varRef)
        override fun onPropagation(implied: PropagationResult.Implied) = current.variableSelector.onPropagation(implied)
        override fun onSolution(snapshot: Sample) {
            solutionsFound++
            current.variableSelector.onSolution(snapshot)
        }
        override fun onRestart() {
            // Update the bandit on the just-ended run, switch to the next arm, then forward
            // restart to the (now new) arm's variable heuristic for its own decay/reset.
            updateAndSwitch()
            current.variableSelector.onRestart()
        }
    }

    /** Drop-in [ValueSelector] slot for [BacktrackParams.valueSelector]. */
    val valueSelector: ValueSelector = object : ValueSelector {
        override fun values(session: PropagationSession, varRef: VarRef, rng: Random) =
            current.valueSelector.values(session, varRef, rng)
        override fun onConflict(varRef: VarRef, value: Long) = current.valueSelector.onConflict(varRef, value)
        override fun onCommit(varRef: VarRef, value: Long) = current.valueSelector.onCommit(varRef, value)
        override fun onSolution(snapshot: Sample) = current.valueSelector.onSolution(snapshot)
        override fun onRestart() {
            // Bandit update is owned by the variable-heuristic delegate (fired first by the
            // engine). Here we just forward restart to the (now new) arm's value heuristic.
            current.valueSelector.onRestart()
        }
    }

    private fun updateAndSwitch() {
        val reward = rewardFn(RunStats(conflicts, solutionsFound))
        bandit.update(currentArm, reward)
        currentArm = bandit.choose()
        conflicts = 0
        solutionsFound = 0
    }

    companion object {
        /**
         * Convenience: build a portfolio backed by classical [UCB1] over the supplied arms.
         * `exploration` is UCB1's `alpha` (1.0 = textbook default).
         */
        fun ucb1(arms: List<Arm>, exploration: Double = 1.0): SelectorPortfolio = SelectorPortfolio(
            arms = arms,
            bandit = MultiArmedBandit(nbrArms = arms.size, policy = UCB1(alpha = exploration)),
        )
    }
}
