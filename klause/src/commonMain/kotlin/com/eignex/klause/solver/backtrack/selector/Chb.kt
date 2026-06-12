package com.eignex.klause.solver.backtrack.selector

import com.eignex.klause.solver.propagation.PropagationResult
import com.eignex.klause.solver.propagation.PropagationSession
import com.eignex.klause.util.IndexedMaxHeap
import com.eignex.klause.util.IntArrayList
import kotlin.random.Random

/**
 * Conflict-History-Based branching (Liang-Ganesh-Poupart-Czarnecki, "Learning Rate Based
 * Branching Heuristics for SAT Solvers", SAT 2016) in its ERWA (exponential recency
 * weighted average) form. A drop-in [VariableSelector] alternative to [Vsids] for
 * SAT-heavy configs: as cheap to maintain, and frequently stronger on structured SAT.
 *
 * Each variable carries a score `Q[v]`, updated by exponential recency weighting on every
 * event the variable participates in:
 *     `Q[v] ← (1 − α)·Q[v] + α·r,   r = multiplier / (conflicts − lastConflict[v] + 1)`
 * The reward `r` is *larger* the more recently `v` took part in a conflict (a smaller
 * denominator), and `multiplier` is [conflictReward] (1.0) for the variables in the conflict
 * reason set and [assignReward] (0.9) for variables merely assigned along the way — the
 * standard CHB split that values conflict participation above plain propagation. The step
 * size α starts at [alphaStart] and decays by [alphaStep] per conflict down to [alphaFloor],
 * so early conflicts move scores fast and the estimate settles as evidence accumulates.
 *
 * Picks the unpinned variable with the highest `Q`. Ties broken by variable-id order (bools
 * precede ints). Scores persist across [onRestart] — like VSIDS, the learned ordering is the
 * point. Uses the same combined-id [IndexedMaxHeap] and pinned-skip pick machinery as
 * [Vsids], so it drops into the [BacktrackParams.variableHeuristic] slot with no engine
 * changes.
 *
 * Hook mapping (exactly the hooks VSIDS already consumes):
 *  - [onConflict] — advances the conflict counter, decays α, and bumps every variable in the
 *    conflict reason set (and the failed decision) with the conflict multiplier; those
 *    variables' `lastConflict` is set to the current count, so their reward denominator is 1.
 *  - [onPropagation] / [onCommit] — assigned-variable reward, recency-discounted by how long
 *    ago the variable last appeared in a conflict.
 *  - [onRestart] — no-op; scores persist.
 *
 * Since every `Q` is a convex combination of rewards in `[0, 1]`, scores stay in `[0, 1]` and
 * never need rescaling (unlike VSIDS's growing increment).
 */
class Chb(
    private val alphaStart: Double = 0.4,
    private val alphaFloor: Double = 0.06,
    private val alphaStep: Double = 1e-6,
    private val conflictReward: Double = 1.0,
    private val assignReward: Double = 0.9,
) : VariableSelector {

    init {
        require(alphaStart in alphaFloor..1.0) { "CHB alphaStart must be in alphaFloor..1.0, got $alphaStart" }
        require(alphaFloor in 0.0..1.0) { "CHB alphaFloor must be in 0.0..1.0, got $alphaFloor" }
        require(alphaStep >= 0.0) { "CHB alphaStep must be >= 0, got $alphaStep" }
    }

    private var alpha: Double = alphaStart
    private var conflicts: Long = 0

    // Combined index space: 0..numBool-1 are bool ids; numBool..numBool+numInt-1 are int ids
    // offset by numBool. Mirrors [Vsids] exactly so picks are O((1 + pinned-skip)·log n).
    private var heap: IndexedMaxHeap? = null
    private var lastConflict: LongArray = LongArray(0)
    private var numBoolCached: Int = 0
    private var numIntCached: Int = 0
    private val pickSkipBuffer = IntArrayList(16)

    private fun ensureSized(numBool: Int, numInt: Int) {
        if (heap != null && numBoolCached == numBool && numIntCached == numInt) return
        val n = numBool + numInt
        val h = IndexedMaxHeap(n)
        for (i in 0 until n) h.insert(i, 0.0)
        heap = h
        lastConflict = LongArray(n)
        numBoolCached = numBool
        numIntCached = numInt
    }

    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        val problem = session.problem
        ensureSized(problem.numBoolVars, problem.numIntVars)
        val h = requireNotNull(heap)
        pickSkipBuffer.clear()
        var result: VarRef? = null
        while (h.size > 0) {
            val id = h.extractMax()
            if (id < numBoolCached) {
                if (session.boolValue(id) == null) {
                    result = VarRef.Bool(id)
                    break
                }
            } else {
                val intId = id - numBoolCached
                if (session.intDomain(intId).size > 1) {
                    result = VarRef.IntVar(intId)
                    break
                }
            }
            pickSkipBuffer.add(id)
        }
        if (result != null) {
            when (result) {
                is VarRef.Bool -> h.restore(result.varId)
                is VarRef.IntVar -> h.restore(result.varId + numBoolCached)
            }
        }
        for (i in 0 until pickSkipBuffer.size) h.restore(pickSkipBuffer.get(i))
        return result
    }

    override fun onConflict(varRef: VarRef, unsat: PropagationResult.Unsat) {
        val h = heap ?: return
        conflicts++
        for (b in unsat.conflictBools) bumpConflict(h, idOfBool(b))
        for (i in unsat.conflictInts) bumpConflict(h, idOfInt(i))
        when (varRef) {
            is VarRef.Bool -> bumpConflict(h, idOfBool(varRef.varId))
            is VarRef.IntVar -> bumpConflict(h, idOfInt(varRef.varId))
        }
        // Decay the learning rate toward the floor as conflicts accumulate.
        if (alpha > alphaFloor) alpha = (alpha - alphaStep).coerceAtLeast(alphaFloor)
    }

    override fun onPropagation(implied: PropagationResult.Implied) {
        val h = heap ?: return
        implied.forEachBool { id, _ -> if (id < numBoolCached) bumpAssign(h, id) }
        implied.forEachInt { id, _ -> if (id < numIntCached) bumpAssign(h, numBoolCached + id) }
    }

    override fun onCommit(varRef: VarRef) {
        val h = heap ?: return
        when (varRef) {
            is VarRef.Bool -> bumpAssign(h, idOfBool(varRef.varId))
            is VarRef.IntVar -> bumpAssign(h, idOfInt(varRef.varId))
        }
    }

    private fun idOfBool(v: Int): Int = if (v < numBoolCached) v else -1
    private fun idOfInt(v: Int): Int = if (v < numIntCached) numBoolCached + v else -1

    /** Conflict-side reward: the variable just took part in a conflict, so its recency
     *  denominator collapses to 1 and it earns the full [conflictReward]. */
    private fun bumpConflict(h: IndexedMaxHeap, id: Int) {
        if (id < 0) return
        lastConflict[id] = conflicts
        updateQ(h, id, conflictReward)
    }

    /** Assignment-side reward: recency-discounted by how long ago the variable last appeared
     *  in a conflict. Leaves `lastConflict` untouched. */
    private fun bumpAssign(h: IndexedMaxHeap, id: Int) {
        if (id < 0) return
        val recency = (conflicts - lastConflict[id] + 1).toDouble()
        updateQ(h, id, assignReward / recency)
    }

    private fun updateQ(h: IndexedMaxHeap, id: Int, reward: Double) {
        h.updateKey(id, (1.0 - alpha) * h.keyOf(id) + alpha * reward)
    }
}
