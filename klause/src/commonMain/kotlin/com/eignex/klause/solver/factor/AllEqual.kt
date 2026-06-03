package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `all_equal_int(xs)` — every `xs[i]` takes the same value. Trivially equivalent to a
 * chain of pairwise `xs[i] = xs[0]` equalities, but the dedicated factor lets the
 * engine propagate the full domain intersection in one pass: pick the max of every
 * `xs[i].min` as the common lower bound and the min of every `xs[i].max` as the common
 * upper bound, push those back to every operand, then (when any operand carries interior
 * holes) drop every value not present in *all* operands so the surviving domain is exactly
 * `∩ dom(xs)` — hole-aware GAC, not merely bounds.
 */
class AllEqual(
    /** Integer variable ids required to all take the same value. */
    val xs: IntArray,
) : LocalSearchFactor {

    init {
        require(xs.size >= 2) { "all_equal needs at least two variables" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val v0 = state.assignment.intValue(xs[0])
        for (i in 1 until xs.size) if (state.assignment.intValue(xs[i]) != v0) return true
        return false
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val wasViolated = isViolated(state, factorId)
        val v0 = if (xs[0] == intVar) newValue else state.assignment.intValue(xs[0])
        var willViolate = false
        for (i in xs.indices) {
            val v = if (xs[i] == intVar) newValue else state.assignment.intValue(xs[i])
            if (v != v0) {
                willViolate = true
                break
            }
        }
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    /** Pick the most common current value across [xs] (mode) as the consensus target;
     *  for every dissenting var whose domain contains the target, propose snapping to it.
     *  Falls back to the secondary mode if the primary target is out of some domains. */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        val counts = HashMap<Int, Int>(xs.size)
        for (v in xs) {
            val cur = state.assignment.intValue(v)
            counts[cur] = (counts[cur] ?: 0) + 1
        }
        // Try the top-2 most-frequent values; if first is rejected by some domain, the
        // second often succeeds. Keeps the proposal-set small.
        val ranked = counts.entries.sortedByDescending { it.value }
        for ((target, _) in ranked.take(2)) {
            for (v in xs) {
                val cur = state.assignment.intValue(v)
                if (cur != target && target in state.problem.intDomains[v]) {
                    sink.addChannelingIntSet(state, v, target)
                }
            }
        }
    }

    /** Hole-aware conflict reason: cites the filtered bounds *and* interior holes of every
     *  operand, since an empty intersection can be driven by a hole (e.g. {0,2} ∩ {1}) and
     *  not just by crossed bounds. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectHoleAndBoundAntecedents(state, xs)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // Common domain = ∩ dom(xs[i]) — implement as [maxOfMins, minOfMaxes]. Track which
        // operand supplies each binding bound so the learned-clause antecedents can name
        // just that var rather than the whole array.
        var commonMin = Int.MIN_VALUE
        var commonMax = Int.MAX_VALUE
        var minVar = xs[0] // operand whose lower bound == commonMin
        var maxVar = xs[0] // operand whose upper bound == commonMax
        for (v in xs) {
            val d = state.intDomains[v]
            if (d.min > commonMin) {
                commonMin = d.min
                minVar = v
            }
            if (d.max < commonMax) {
                commonMax = d.max
                maxVar = v
            }
        }
        if (commonMin > commonMax) return false
        // Sharp antecedents: every operand equals every other, so `v ≥ commonMin` is forced
        // *solely* by [minVar]'s lower bound (clause `¬[minVar ≥ commonMin] ∨ [v ≥ commonMin]`
        // is valid since AllEqual ⟹ v = minVar), and likewise `v ≤ commonMax` by [maxVar].
        // Citing only the binding var instead of all `xs` keeps learned clauses short.
        val minAnt = state.composeIntVarAtomAntecedents(intArrayOf(minVar))
        val maxAnt = state.composeIntVarAtomAntecedents(intArrayOf(maxVar))
        for (v in xs) {
            if (!state.tightenIntMin(v, commonMin, minAnt)) return false
            if (!state.tightenIntMax(v, commonMax, maxAnt)) return false
        }
        // Bounds intersection alone leaves interior holes that aren't common to every operand
        // (e.g. {0,2} ∩ {0,1,2} keeps the unsupported 1 in the second var). Since AllEqual ⟹
        // every operand takes the *same* value, the feasible common values are exactly
        // ∩ dom(xs); drop every value missing from some operand. Skip the scan when every
        // operand is contiguous over [commonMin, commonMax] — the bound tighten above already
        // produced the exact intersection, keeping the common contiguous path O(N).
        var anyHole = false
        for (v in xs) {
            val d = state.intDomains[v]
            if (d.size != d.max - d.min + 1) {
                anyHole = true
                break
            }
        }
        if (anyHole) {
            // Hall-style reason: the filtered domains of all operands jointly justify why a
            // value can't be the shared assignment. Mirrors AllDifferent's hole pruning.
            val holeAnt = collectHoleAndBoundAntecedents(state, xs)
            for (value in commonMin..commonMax) {
                var inAll = true
                for (v in xs) {
                    if (value !in state.intDomains[v]) {
                        inAll = false
                        break
                    }
                }
                if (inAll) continue
                for (v in xs) {
                    if (value in state.intDomains[v] && !state.excludeIntValue(v, value, holeAnt)) {
                        return false
                    }
                }
            }
        }
        return true
    }
}
