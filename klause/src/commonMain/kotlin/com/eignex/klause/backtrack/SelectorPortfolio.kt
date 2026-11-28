package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.ValueSelector
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.search.BranchingConflict
import com.eignex.klause.solver.search.VarRef
import com.eignex.kumulant.bandit.univariate.MultiArmedBandit
import com.eignex.kumulant.bandit.univariate.UCB1
import kotlin.random.Random

// Restart-level heuristic portfolio: a bandit picks one (variable, value) selector arm at construction and, at each
// restart, rewards the previous arm and picks the next (UCB1: Auer, Cesa-Bianchi and Fischer, "Finite-time Analysis
// of the Multiarmed Bandit Problem", Machine Learning 47, 2002, https://doi.org/10.1023/A:1013689704352).
//
// Both variableSelector and valueSelector must come from the same instance to share state. At restart the variable
// delegate, fired first by the engine, updates the bandit and switches arms; the value delegate only forwards.
// The default reward is 1.0 when the run found a solution, else 0.0.
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

    data class Arm(val label: String, val variableSelector: VariableSelector, val valueSelector: ValueSelector)

    data class RunStats(
        val conflicts: Int,
        val solutionsFound: Int,
    )

    private var currentArm: Int = bandit.choose()
    private var conflicts: Int = 0
    private var solutionsFound: Int = 0

    val current: Arm get() = arms[currentArm]

    val currentArmIndex: Int get() = currentArm

    val variableSelector: VariableSelector = object : VariableSelector {
        // The portfolio drives arm switching across a single search; not reused across solves.
        override fun fresh() = this
        override fun pick(session: PropagationSession, rng: Random) = current.variableSelector.pick(session, rng)
        override fun onConflict(varRef: VarRef) {
            conflicts++
            current.variableSelector.onConflict(varRef)
        }
        override fun onConflict(varRef: VarRef?, conflict: BranchingConflict) {
            conflicts++
            current.variableSelector.onConflict(varRef, conflict)
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
            updateAndSwitch()
            current.variableSelector.onRestart()
        }
    }

    val valueSelector: ValueSelector = object : ValueSelector {
        override fun fresh() = this
        override fun values(session: PropagationSession, varRef: VarRef, rng: Random) =
            current.valueSelector.values(session, varRef, rng)
        override fun onConflict(varRef: VarRef, value: Long) = current.valueSelector.onConflict(varRef, value)
        override fun onCommit(varRef: VarRef, value: Long) = current.valueSelector.onCommit(varRef, value)
        override fun onSolution(snapshot: Sample) = current.valueSelector.onSolution(snapshot)
        override fun onRestart() {
            // The variable-heuristic delegate, fired first, already switched arms.
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
        // exploration is UCB1's alpha.
        fun ucb1(arms: List<Arm>, exploration: Double = 1.0): SelectorPortfolio = SelectorPortfolio(
            arms = arms,
            bandit = MultiArmedBandit(nbrArms = arms.size, policy = UCB1(alpha = exploration)),
        )
    }
}
