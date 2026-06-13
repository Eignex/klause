package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.backtrack.selector.InputOrder
import com.eignex.klause.solver.backtrack.selector.LargestDomain
import com.eignex.klause.solver.backtrack.selector.LargestUpperBound
import com.eignex.klause.solver.backtrack.selector.MaxRegret
import com.eignex.klause.solver.backtrack.selector.SmallestDomain
import com.eignex.klause.solver.backtrack.selector.SmallestLowerBound
import com.eignex.klause.solver.backtrack.selector.ValueSelector
import com.eignex.klause.solver.backtrack.selector.VarRef
import com.eignex.klause.solver.backtrack.selector.VariableSelector
import com.eignex.klause.solver.propagation.PropagationResult
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random

/**
 * Within-tier variable selection for [TieredVariableSelector]. A tier restricts the
 * candidate set to its listed variables, so the dynamic heap-backed heuristics aren't
 * reusable here; these are the strategies MiniZinc search annotations name, evaluated
 * by a linear scan over the tier's (typically small) variable list.
 */
enum class TierVarSelect {
    /** First free variable in the tier's listed order (`input_order`). */
    InputOrder,

    /** Smallest current domain (`first_fail`). */
    SmallestDomain,

    /** Largest current domain (`anti_first_fail`). */
    LargestDomain,

    /** Smallest domain minimum (`smallest`). */
    SmallestLowerBound,

    /** Largest domain maximum (`largest`). */
    LargestUpperBound,

    /** Uniformly random free variable (`random_order`). */
    RandomOrder,

    /** Largest gap between the two smallest domain values (`max_regret`). */
    MaxRegret,
}

/**
 * One search tier: the variable ids it owns (in annotation order), how to pick among
 * them, and how to order values for them. Mirrors one `int_search` / `bool_search`
 * block of a MiniZinc `seq_search`.
 */
class SearchTier(
    /** Bool var ids in this tier, in the annotated array's order. */
    val boolVars: IntArray,
    /** Int var ids in this tier, in the annotated array's order. */
    val intVars: IntArray,
    /** Variable selection within the tier. */
    val varSelect: TierVarSelect,
    /** Value ordering for variables this tier owns. */
    val valueSelector: ValueSelector,
)

/**
 * Static search phases over annotated variable arrays (MiniZinc `seq_search`). Picks from
 * the first tier that still has a free variable, using that tier's [TierVarSelect];
 * when every tier is fully assigned, delegates to [fallback] so the search completes the
 * remaining (typically introduced/auxiliary) variables. Conflict and propagation hooks
 * forward to the fallback so an activity-driven fallback keeps learning during the
 * tiered phase.
 */
class TieredVariableSelector(
    /** The search phases, in exploration order. */
    val tiers: List<SearchTier>,
    /** Completes the variables no tier owns once every tier is assigned. */
    val fallback: VariableSelector,
) : VariableSelector {

    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        for (tier in tiers) {
            pickInTier(tier, session, rng)?.let { return it }
        }
        return fallback.pick(session, rng)
    }

    private fun pickInTier(tier: SearchTier, session: PropagationSession, rng: Random): VarRef? =
        when (tier.varSelect) {
            TierVarSelect.InputOrder -> firstFree(tier, session)
            TierVarSelect.RandomOrder -> randomFree(tier, session, rng)
            TierVarSelect.SmallestDomain -> bestFree(tier, session) { size, _, _ -> -size.toLong() }
            TierVarSelect.LargestDomain -> bestFree(tier, session) { size, _, _ -> size.toLong() }
            TierVarSelect.SmallestLowerBound -> bestFree(tier, session) { _, min, _ -> -min.toLong() }
            TierVarSelect.LargestUpperBound -> bestFree(tier, session) { _, _, max -> max.toLong() }
            TierVarSelect.MaxRegret -> maxRegretFree(tier, session)
        }

    /** Free variable with the largest gap between its two smallest domain values (`max_regret`);
     *  free bools have the fixed regret `1`. Ties keep the earliest listed variable. The regret
     *  needs the second-smallest *value*, which [bestFree]'s `(size, min, max)` score can't see. */
    private fun maxRegretFree(tier: SearchTier, session: PropagationSession): VarRef? {
        var best: VarRef? = null
        var bestRegret = Int.MIN_VALUE
        for (v in tier.boolVars) {
            if (session.boolValue(v) == null && 1 > bestRegret) {
                best = VarRef.Bool(v)
                bestRegret = 1
            }
        }
        for (v in tier.intVars) {
            val d = session.intDomain(v)
            if (d.size <= 1) continue
            val regret = d.valueAt(1) - d.valueAt(0)
            if (regret > bestRegret) {
                best = VarRef.IntVar(v)
                bestRegret = regret
            }
        }
        return best
    }

    private fun firstFree(tier: SearchTier, session: PropagationSession): VarRef? {
        for (v in tier.boolVars) if (session.boolValue(v) == null) return VarRef.Bool(v)
        for (v in tier.intVars) if (session.intDomain(v).size > 1) return VarRef.IntVar(v)
        return null
    }

    private fun randomFree(tier: SearchTier, session: PropagationSession, rng: Random): VarRef? {
        val candidates = ArrayList<VarRef>(tier.boolVars.size + tier.intVars.size)
        for (v in tier.boolVars) if (session.boolValue(v) == null) candidates.add(VarRef.Bool(v))
        for (v in tier.intVars) if (session.intDomain(v).size > 1) candidates.add(VarRef.IntVar(v))
        return if (candidates.isEmpty()) null else candidates[rng.nextInt(candidates.size)]
    }

    /** Argmax of [score]`(size, min, max)` over the tier's free variables; bools score
     *  as the domain `{0, 1}`. Ties keep the earliest listed variable. */
    private inline fun bestFree(
        tier: SearchTier,
        session: PropagationSession,
        score: (size: Int, min: Int, max: Int) -> Long,
    ): VarRef? {
        var best: VarRef? = null
        var bestScore = Long.MIN_VALUE
        for (v in tier.boolVars) {
            if (session.boolValue(v) != null) continue
            val s = score(2, 0, 1)
            if (s > bestScore) {
                bestScore = s
                best = VarRef.Bool(v)
            }
        }
        for (v in tier.intVars) {
            val d = session.intDomain(v)
            if (d.size <= 1) continue
            val s = score(d.size, d.min, d.max)
            if (s > bestScore) {
                bestScore = s
                best = VarRef.IntVar(v)
            }
        }
        return best
    }

    override fun onConflict(varRef: VarRef) = fallback.onConflict(varRef)
    override fun onConflict(varRef: VarRef, unsat: PropagationResult.Unsat) = fallback.onConflict(varRef, unsat)
    override fun onCommit(varRef: VarRef) = fallback.onCommit(varRef)
    override fun onPropagation(implied: PropagationResult.Implied) = fallback.onPropagation(implied)
    override fun onRestart() = fallback.onRestart()
    override fun onSolution(snapshot: Sample) = fallback.onSolution(snapshot)
}

/**
 * Per-tier value ordering companion to [TieredVariableSelector]: variables owned by a
 * tier use that tier's [SearchTier.valueSelector]; everything else uses [fallback].
 * Hooks broadcast to every tier heuristic plus the fallback so stateful wrappers
 * (solution-guided, impact) stay coherent regardless of which tier consulted them.
 */
class TieredValueSelector(
    /** The search phases, in ownership-priority order. */
    val tiers: List<SearchTier>,
    /** Orders values for the variables no tier owns. */
    val fallback: ValueSelector,
    numBoolVars: Int,
    numIntVars: Int,
) : ValueSelector {

    // var id → owning tier index + 1; 0 = unowned (fallback). First-listed tier wins
    // when arrays overlap.
    private val boolOwner = IntArray(numBoolVars)
    private val intOwner = IntArray(numIntVars)

    init {
        for ((idx, tier) in tiers.withIndex()) {
            for (v in tier.boolVars) if (v < numBoolVars && boolOwner[v] == 0) boolOwner[v] = idx + 1
            for (v in tier.intVars) if (v < numIntVars && intOwner[v] == 0) intOwner[v] = idx + 1
        }
    }

    private fun selectorFor(varRef: VarRef): ValueSelector {
        val owner = when (varRef) {
            is VarRef.Bool -> if (varRef.varId < boolOwner.size) boolOwner[varRef.varId] else 0
            is VarRef.IntVar -> if (varRef.varId < intOwner.size) intOwner[varRef.varId] else 0
        }
        return if (owner == 0) fallback else tiers[owner - 1].valueSelector
    }

    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Int> =
        selectorFor(varRef).values(session, varRef, rng)

    override fun onConflict(varRef: VarRef, value: Int) = selectorFor(varRef).onConflict(varRef, value)
    override fun onCommit(varRef: VarRef, value: Int) = selectorFor(varRef).onCommit(varRef, value)

    override fun onRestart() {
        for (tier in tiers) tier.valueSelector.onRestart()
        fallback.onRestart()
    }

    override fun onSolution(snapshot: Sample) {
        for (tier in tiers) tier.valueSelector.onSolution(snapshot)
        fallback.onSolution(snapshot)
    }
}
