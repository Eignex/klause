package com.eignex.klause.factor.bool

import com.eignex.klause.factor.bool.internals.pbFalseFormAntecedents
import com.eignex.klause.factor.bool.internals.propagatePbBounds
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.LearnedPropagator
import com.eignex.klause.propagation.PbAccumulator
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.installLitWatch
import com.eignex.klause.propagation.removeBoolWatch
import com.eignex.klause.solver.Lit
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList

/** CP propagator for [PseudoBoolean]: bounds propagation for `Σ weights_i * lit_i ⟨op⟩ bound`.
 *
 *  [watched] enables a weighted watched-literal wakeup scheme, used for learned
 *  `≥` constraints so they don't wake on every literal change. While the summed weight of the non-false
 *  watched literals is at least `bound + maxCoeff`, the constraint has slack ≥ every coefficient and can
 *  neither propagate nor conflict — so it only needs to fire when a *watched* literal goes false. Base
 *  pseudo-Boolean constraints keep the occurrence-list wakeup (`watched = false`). */
internal class PseudoBooleanPropagator(
    override val boolVars: IntArray,
    override val intVars: IntArray,
    private val weights: LongArray,
    private val literals: IntArray,
    private val op: PbOp,
    private val bound: Long,
    private val watched: Boolean = false,
) : LearnedPropagator {

    /** Weight covering target for the watched scheme: while the non-false watched weight reaches this,
     *  no literal can be forced. Only meaningful for a `≥` [watched] constraint. */
    private val watchTarget: Long = if (watched) bound + (weights.maxOrNull() ?: 0L) else 0L

    /** Literal indices ordered by descending weight — the greedy pick order for the smallest covering
     *  watch set. Built only in [watched] mode. */
    private val byWeightDesc: IntArray = if (watched) {
        (0 until literals.size).sortedByDescending { weights[it] }.toIntArray()
    } else {
        EmptyIntArray
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean =
        if (watched) propagateWatched(state, factorId) else propagatePbBounds(state, weights, literals, op, bound)

    /** Set by [selectWatched]: true iff the last selection reached [watchTarget] (constraint can't
     *  propagate). Single-threaded per session, so a field is safe. */
    private var lastSelectionCovered = false

    /**
     * Select a covering set of currently-non-false literals whose weights sum to at least [watchTarget],
     * preferring the heaviest (fewest watches). Returns all non-false literals when the target can't be
     * met (the constraint is in the propagating regime and must wake on every remaining literal); sets
     * [lastSelectionCovered] to whether the target was reached.
     */
    fun selectWatched(state: PropagationState): IntArray {
        val out = IntArrayList(byWeightDesc.size)
        var sum = 0L
        lastSelectionCovered = false
        for (idx in byWeightDesc) {
            val lit = literals[idx]
            if (state.litFalse(lit)) continue
            out.add(lit)
            sum += weights[idx]
            if (sum >= watchTarget) {
                lastSelectionCovered = true
                return out.toIntArray()
            }
        }
        return out.toIntArray() // target unreachable ⇒ watch every non-false literal
    }

    /**
     * Watched-literal propagation. Recomputes the covering watch set from the current assignment and
     * reconciles [PropagationState.refPayload]`[factorId]` (the installed watched literals) against it. If
     * the set covers [watchTarget] the constraint can't propagate — return true without scanning. Only
     * when the covering target is unreachable does it fall through to full bounds propagation.
     */
    private fun propagateWatched(state: PropagationState, factorId: Int): Boolean {
        val old = state.refPayload[factorId] as? IntArray ?: EmptyIntArray
        val next = selectWatched(state)
        // Reconcile watches against the new selection: drop the dropped, install the added. Safe to mutate the
        // watcher index here — propagate runs after the wakeup walk over the fired literal's list.
        for (lit in old) if (!next.contains(lit)) state.removeBoolWatch(factorId, lit)
        for (lit in next) if (!old.contains(lit)) state.installLitWatch(lit, factorId)
        state.refPayload[factorId] = next
        // Covered ⇒ slack ≥ every coefficient ⇒ nothing to do. Otherwise bounds-propagate (force / detect
        // conflict); [next] then watches every non-false literal so any further change re-fires.
        if (lastSelectionCovered) return true
        return propagatePbBounds(state, weights, literals, op, bound)
    }

    /** Clause-form nogood when propagation fails: the disjunction of each pinned
     *  literal's false-form. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        pbFalseFormAntecedents(state, literals, excludeVar = -1, extraLit = 0)

    /**
     * Load this constraint into [acc] as a coefficient-carrying `≥` reason for pseudo-Boolean
     * cutting-planes conflict analysis. `GE` loads directly; `LE` flips every literal's
     * polarity and sets the degree to `Σweights − bound`. An `EQ` is two `≥` constraints — the relevant
     * half is the one that forced [forcedLit] (forced-true own-literal ⇒ lower `≥` bound; forced-false ⇒
     * upper `≤` bound); with no forced literal (a seed) the direction is ambiguous and it falls back to
     * the clause-form reason. Also returns false on overflow.
     */
    fun loadReason(acc: PbAccumulator, forcedLit: Int, state: PropagationState): Boolean = when (op) {
        PbOp.GE -> acc.loadPb(weights, literals, geBound = bound)

        PbOp.LE -> loadLe(acc)

        // An equality is two `≥` constraints. A resolved pivot picks the half by its forced polarity; a
        // seed (forcedLit == 0, the conflicting constraint) picks the violated half from [state].
        PbOp.EQ -> when {
            forcedLit != 0 -> if (forcedByGeSide(
                    forcedLit,
                )
            ) {
                acc.loadPb(weights, literals, geBound = bound)
            } else {
                loadLe(acc)
            }

            geSideViolated(state) -> acc.loadPb(weights, literals, geBound = bound)

            else -> loadLe(acc)
        }
    }

    /** For a seed equality conflict: true iff the lower-bound half `Σ w·ℓ ≥ bound` is the violated one
     *  (the achievable sum of the non-false literals falls short of the bound). */
    private fun geSideViolated(state: PropagationState): Boolean {
        var nonFalseSum = 0L
        for (i in literals.indices) {
            val b = state.boolValues[Lit.variable(literals[i])]
            if (b == null || Lit.evaluate(literals[i], b)) nonFalseSum += weights[i]
        }
        return nonFalseSum < bound
    }

    /** Load the `≤` constraint `Σ w·ℓ ≤ bound` as its `≥` complement `Σ w·¬ℓ ≥ (Σw − bound)`. */
    private fun loadLe(acc: PbAccumulator): Boolean {
        var sum = 0L
        for (w in weights) sum += w
        val flipped = IntArray(literals.size) { literals[it] xor 1 } // toggle the polarity bit
        return acc.loadPb(weights, flipped, geBound = sum - bound)
    }

    /** True iff the equality's own literal on [forcedLit]'s variable is exactly [forcedLit] (now true) —
     *  i.e. the lower-bound (`≥`) half needed it true. */
    private fun forcedByGeSide(forcedLit: Int): Boolean {
        val v = Lit.variable(forcedLit)
        for (lit in literals) if (Lit.variable(lit) == v) return lit == forcedLit
        return true
    }
}
