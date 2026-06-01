package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `symmetric_all_different(xs)` — `xs` is a self-inverse permutation: `xs[xs[i]] = i` for
 * every `i`. Strictly stronger than `all_different` (which just demands distinctness):
 * each value also points back to its pointer.
 *
 * [indexOffset] is the value `xs[0]` would take to mean position 0 — typically `1` for
 * the MZN 1-based default.
 *
 * Propagation: all-different singleton-conflict detection inherited from `AllDifferent`,
 * plus a self-inverse check on singletons.
 */
class SymmetricAllDifferent(
    /** Involution variable ids: `xs[i]` and its image must pair symmetrically. */
    val xs: IntArray,
    /** Integer representing index 0 of [xs]. */
    val indexOffset: Int = 0,
) : LocalSearchFactor {

    init {
        require(xs.isNotEmpty()) { "symmetric_all_different: empty xs" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val seen = HashSet<Int>()
        for (i in xs.indices) {
            val v = state.assignment.intValue(xs[i])
            if (!seen.add(v)) return true
            // Self-inverse: xs[xs[i] - offset] = i + offset.
            val target = v - indexOffset
            if (target !in xs.indices) return true
            if (state.assignment.intValue(xs[target]) != i + indexOffset) return true
        }
        return false
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val wasViolated = isViolated(state, factorId)
        val seen = HashSet<Int>()
        var willViolate = false
        for (i in xs.indices) {
            val v = if (xs[i] == intVar) newValue else state.assignment.intValue(xs[i])
            if (!seen.add(v)) {
                willViolate = true
                break
            }
            val target = v - indexOffset
            if (target !in xs.indices) {
                willViolate = true
                break
            }
            val backVal = if (xs[target] == intVar) newValue else state.assignment.intValue(xs[target])
            if (backVal != i + indexOffset) {
                willViolate = true
                break
            }
        }
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    /** Repair: at each broken self-inverse pair (i, xs[i]), propose Compound swaps that
     *  fix the involution and the mirroring constraint simultaneously. */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        for (i in xs.indices) {
            val v = state.assignment.intValue(xs[i])
            val target = v - indexOffset
            if (target !in xs.indices) {
                // Out-of-range: snap xs[i] into the legal range.
                val di = state.problem.intDomains[xs[i]]
                val pick = (i + indexOffset).takeIf { it in di } ?: continue
                if (pick != v) sink.addChannelingIntSet(state, xs[i], pick)
                continue
            }
            val backVal = state.assignment.intValue(xs[target])
            val want = i + indexOffset
            if (backVal != want) {
                // Self-inverse broken at (i, target). Propose snapping xs[target] to want.
                if (want in state.problem.intDomains[xs[target]] && want != backVal) {
                    sink.addChannelingIntSet(state, xs[target], want)
                }
                // Or snap xs[i] so it points to its own current mirror.
                val xiDom = state.problem.intDomains[xs[i]]
                val backTarget = backVal - indexOffset
                if (backTarget in xs.indices) {
                    // Pick a value where the mirror is consistent.
                    val candidate = backTarget + indexOffset // points at j with xs[j]=v...; trial
                    if (candidate in xiDom && candidate != v) sink.addChannelingIntSet(state, xs[i], candidate)
                }
                // Self-pair fallback: xs[i] = i (self-map) trivially satisfies the involution
                // at position i and frees `target` from the collision.
                val selfPair = i + indexOffset
                if (selfPair in xiDom && selfPair != v) sink.addChannelingIntSet(state, xs[i], selfPair)
            }
        }
    }

    /** Bound-only conflict reason. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectLinearTightenAntecedents(state, xs, excludeIdx = -1, extraLit = 0)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // Tighten each xs[i] into the legal index range.
        val lo = indexOffset
        val hi = indexOffset + xs.size - 1
        for (v in xs) {
            if (!state.tightenIntMin(v, lo)) return false
            if (!state.tightenIntMax(v, hi)) return false
        }
        // AllDifferent singleton conflict.
        val taken = HashSet<Int>()
        for (v in xs) {
            val d = state.intDomains[v]
            if (d.min != d.max) continue
            if (!taken.add(d.min)) return false
        }
        // Self-inverse forcing on singletons: xs[i] = j (singleton) → xs[j - offset] = i + offset.
        for (i in xs.indices) {
            val d = state.intDomains[xs[i]]
            if (d.min != d.max) continue
            val target = d.min - indexOffset
            if (target !in xs.indices) return false
            val mirror = i + indexOffset
            val ant = state.composeIntVarAtomAntecedents(intArrayOf(xs[i]))
            if (!state.tightenIntMin(xs[target], mirror, ant)) return false
            if (!state.tightenIntMax(xs[target], mirror, ant)) return false
        }
        return true
    }
}
