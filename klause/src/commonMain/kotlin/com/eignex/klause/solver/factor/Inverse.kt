package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `inverse(f, g)` with optional offsets: `f[i] = j  ⇔  g[j - gOffset + fOffset] = i`.
 *
 * The canonical 0-based form is `f[i] = j ⇔ g[j] = i`. MiniZinc emits with index offsets
 * matching its 1-based default; the offsets are encoded into the factor so the dispatch
 * doesn't have to allocate channel vars.
 *
 * Propagation: pin-forcing channels — whenever `f[i]` becomes singleton with value `j`,
 * force `g[j']` to `i'` where the indices apply the offset; vice versa.
 */
class Inverse(
    /** Forward mapping variable ids: `f[i]` is the image of `i`. */
    val f: IntArray,
    /** Inverse mapping variable ids: `g[j]` is the preimage of `j`. */
    val g: IntArray,
    /** Index offset for the [f] domain. */
    val fOffset: Int = 0,
    /** Index offset for the [g] domain. */
    val gOffset: Int = 0,
) : LocalSearchFactor {

    init {
        require(f.size == g.size) { "inverse: f and g must have equal length" }
        require(f.isNotEmpty()) { "inverse: empty arrays" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = f + g

    private fun fValueToGIndex(j: Int): Int = j - gOffset
    private fun gValueToFIndex(i: Int): Int = i - fOffset

    /** Number of currently-violated channel cells: pairs where the f→g and g→f mappings
     *  disagree. Maintained incrementally for LS scoring. */
    private class State(var violated: Int)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        var bad = 0
        // For each f[i]: read its value j, look up g[j - gOff] and require it equals i + fOff.
        for (i in f.indices) {
            val j = state.assignment.intValue(f[i])
            val gIdx = fValueToGIndex(j)
            if (gIdx !in g.indices) {
                bad++
                continue
            }
            val gVal = state.assignment.intValue(g[gIdx])
            if (gVal != i + fOffset) bad++
        }
        // Symmetric check via g side.
        for (i in g.indices) {
            val j = state.assignment.intValue(g[i])
            val fIdx = gValueToFIndex(j)
            if (fIdx !in f.indices) {
                bad++
                continue
            }
            val fVal = state.assignment.intValue(f[fIdx])
            if (fVal != i + gOffset) bad++
        }
        state.refPayload[factorId] = State(bad)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as State
        return s.violated > 0
    }

    /** Default brute-force: simulate, recount, return delta. Cost O(n) per query — fine
     *  for the structurally simple inverse but sub-optimal vs. an incremental Δ. */
    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val current = state.assignment.intValue(intVar)
        if (current == newValue) return 0
        val before = s.violated
        val after = simulateViolations(state, intVar, newValue)
        val wasViolated = before > 0
        val willViolate = after > 0
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val wasViolated = s.violated > 0
        s.violated = countViolations(state)
        val nowViolated = s.violated > 0
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    private fun countViolations(state: LocalSearchState): Int {
        var bad = 0
        for (i in f.indices) {
            val j = state.assignment.intValue(f[i])
            val gIdx = fValueToGIndex(j)
            if (gIdx !in g.indices) {
                bad++
                continue
            }
            val gVal = state.assignment.intValue(g[gIdx])
            if (gVal != i + fOffset) bad++
        }
        for (i in g.indices) {
            val j = state.assignment.intValue(g[i])
            val fIdx = gValueToFIndex(j)
            if (fIdx !in f.indices) {
                bad++
                continue
            }
            val fVal = state.assignment.intValue(f[fIdx])
            if (fVal != i + gOffset) bad++
        }
        return bad
    }

    private fun simulateViolations(state: LocalSearchState, intVar: Int, newValue: Int): Int {
        var bad = 0
        for (i in f.indices) {
            val j = if (f[i] == intVar) newValue else state.assignment.intValue(f[i])
            val gIdx = fValueToGIndex(j)
            if (gIdx !in g.indices) {
                bad++
                continue
            }
            val gVal = if (g[gIdx] == intVar) newValue else state.assignment.intValue(g[gIdx])
            if (gVal != i + fOffset) bad++
        }
        for (i in g.indices) {
            val j = if (g[i] == intVar) newValue else state.assignment.intValue(g[i])
            val fIdx = gValueToFIndex(j)
            if (fIdx !in f.indices) {
                bad++
                continue
            }
            val fVal = if (f[fIdx] == intVar) newValue else state.assignment.intValue(f[fIdx])
            if (fVal != i + gOffset) bad++
        }
        return bad
    }

    /*
     * GAC for the inverse channel: range-tighten to the legal index span, force
     * singletons across the channel, and prune value-by-value: if `i + fOffset` is
     * absent from `dom(g[j])`, also remove `j + gOffset` from `dom(f[i])`, and
     * symmetrically. The bidirectional value-removal step exhausts every pruning
     * derivable from `f[i]=j ⇔ g[j]=i`; the only stronger reasoning would be matching-
     * based (Hall sets), which inverse's bijection structure rarely needs in practice.
     */

    /** Hole-aware conflict reason. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectHoleAndBoundAntecedents(state, intVars)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // Range tightens are structural (no input antecedents).
        val gLo = gOffset
        val gHi = gOffset + g.size - 1
        for (i in f.indices) {
            if (!state.tightenIntMin(f[i], gLo)) return false
            if (!state.tightenIntMax(f[i], gHi)) return false
        }
        val fLo = fOffset
        val fHi = fOffset + f.size - 1
        for (i in g.indices) {
            if (!state.tightenIntMin(g[i], fLo)) return false
            if (!state.tightenIntMax(g[i], fHi)) return false
        }
        // Singleton-forcing: the source var's int trail antecedents drive the pin.
        for (i in f.indices) {
            val d = state.intDomains[f[i]]
            if (d.min != d.max) continue
            val gIdx = d.min - gOffset
            if (gIdx !in g.indices) return false
            val ant = state.composeIntVarAtomAntecedents(intArrayOf(f[i]))
            if (!state.tightenIntMin(g[gIdx], i + fOffset, ant)) return false
            if (!state.tightenIntMax(g[gIdx], i + fOffset, ant)) return false
        }
        for (i in g.indices) {
            val d = state.intDomains[g[i]]
            if (d.min != d.max) continue
            val fIdx = d.min - fOffset
            if (fIdx !in f.indices) return false
            val ant = state.composeIntVarAtomAntecedents(intArrayOf(g[i]))
            if (!state.tightenIntMin(f[fIdx], i + gOffset, ant)) return false
            if (!state.tightenIntMax(f[fIdx], i + gOffset, ant)) return false
        }
        // Bidirectional value removal: for each (i, gIdx) where gIdx is in range, the
        // channel forces "j+gOffset in dom(f[i])  iff  i+fOffset in dom(g[gIdx])".
        // Whichever side has the value missing, remove from the other.
        for (i in f.indices) {
            val df = state.intDomains[f[i]]
            for (gIdx in g.indices) {
                val jVal = gIdx + gOffset // value f[i] would take to point to g[gIdx]
                val iVal = i + fOffset // value g[gIdx] would take to point back to f[i]
                val dg = state.intDomains[g[gIdx]]
                val fHas = jVal in df
                val gHas = iVal in dg
                if (fHas && !gHas) {
                    val ant = state.composeIntVarAtomAntecedents(intArrayOf(g[gIdx]))
                    if (!state.excludeIntValue(f[i], jVal, ant)) return false
                } else if (!fHas && gHas) {
                    val ant = state.composeIntVarAtomAntecedents(intArrayOf(f[i]))
                    if (!state.excludeIntValue(g[gIdx], iVal, ant)) return false
                }
            }
        }
        return true
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        // Find a mismatched cell and propose setting one side to match the other.
        for (i in f.indices) {
            val j = state.assignment.intValue(f[i])
            val gIdx = fValueToGIndex(j)
            if (gIdx !in g.indices) {
                // f[i]'s value out of range: nudge into range.
                val d = state.problem.intDomains[f[i]]
                val mid = ((d.min + d.max) / 2)
                if (mid in d && mid != j) sink.addChannelingIntSet(state, f[i], mid)
                return
            }
            val gVal = state.assignment.intValue(g[gIdx])
            if (gVal != i + fOffset) {
                // Two repair candidates: align g side or change f's value.
                val gd = state.problem.intDomains[g[gIdx]]
                val target = i + fOffset
                if (target in gd && target != gVal) sink.addChannelingIntSet(state, g[gIdx], target)
                // Or change f[i] to point where g[gIdx] currently points back.
                val gFwd = gVal - fOffset
                if (gFwd in 0 until g.size) {
                    val targetFwd = gFwd + gOffset
                    val fd = state.problem.intDomains[f[i]]
                    if (targetFwd in fd && targetFwd != j) sink.addChannelingIntSet(state, f[i], targetFwd)
                }
                // Symmetric: scan for some jPrime where g[jPrime] already equals (i+fOffset),
                // and propose f[i] = jPrime + gOffset. Repairs i's constraint without touching
                // any g[*]. Necessary when the desired value already lives elsewhere on g.
                val fd = state.problem.intDomains[f[i]]
                for (jPrime in g.indices) {
                    if (state.assignment.intValue(g[jPrime]) == i + fOffset) {
                        val tgt = jPrime + gOffset
                        if (tgt in fd && tgt != j) {
                            sink.addChannelingIntSet(state, f[i], tgt)
                            break
                        }
                    }
                }
                return
            }
        }
    }
}
