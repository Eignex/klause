package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState

private const val SET_BITSET_MAX_PROPOSALS = 8

/**
 * Native bulk set-algebra propagators over indicator-bool arrays. A set var is represented
 * by a parallel `IntArray` of bool var ids, one per universe element (this is the same
 * `SetLayout.indicatorBoolIds` the compiler already produces). Two operands are passed
 * aligned over a unified universe — a `-1` sentinel at position `i` means "this universe
 * element is not in this set's universe", which the subset/eq/disjoint factors treat as
 * the corresponding forced-false constant.
 *
 * Why bulk: the existing lowering emits one [Clause] per universe position per constraint;
 * over a 1024-element universe that's 1024 factors per `subsetOf`. The bulk factor here
 * does the same propagation in a single fire, with the bool watcher set scoped to the
 * factor as a whole. Conflict reasons are computed per implicating position so analysis
 * strength matches the per-clause baseline.
 *
 * **Strength.** These propagators are GAC over the indicator-bool representation: every
 * deduction the clause baseline would make, the factor makes too. The bitset bookkeeping
 * is for grouping work, not for cutting it.
 */

/** `leftBools ⊆ rightBools`: for each universe position `i`, enforce
 *  `leftBools[i] → rightBools[i]`. A `rightBools[i] == -1` pin forces `leftBools[i] = false`. */
class SetBitsetSubset(
    /** Indicator bool ids of the candidate subset. */
    val leftBools: IntArray,
    /** Indicator bool ids of the candidate superset, parallel to [leftBools]. */
    val rightBools: IntArray,
) : LocalSearchFactor {

    init {
        require(leftBools.size == rightBools.size) { "SetBitsetSubset: parallel arrays must have equal length" }
        require(leftBools.isNotEmpty()) { "SetBitsetSubset: empty universe" }
    }

    override val boolVars: IntArray = run {
        val seen = LinkedHashSet<Int>()
        for (b in leftBools) if (b >= 0) seen.add(b)
        for (b in rightBools) if (b >= 0) seen.add(b)
        val out = IntArray(seen.size)
        var i = 0
        for (v in seen) out[i++] = v
        out
    }
    override val intVars: IntArray = EmptyIntArray

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        for (i in leftBools.indices) {
            val lb = leftBools[i]
            if (lb < 0) continue
            if (!state.assignment.boolValue(lb)) continue
            val rb = rightBools[i]
            if (rb < 0) return true
            if (!state.assignment.boolValue(rb)) return true
        }
        return false
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val wasV = isViolated(state, factorId)
        // Simulate flip locally without mutating state.
        var nowV = false
        for (i in leftBools.indices) {
            val lb = leftBools[i]
            if (lb < 0) continue
            val lv = if (lb == boolVar) !state.assignment.boolValue(lb) else state.assignment.boolValue(lb)
            if (!lv) continue
            val rb = rightBools[i]
            if (rb < 0) {
                nowV = true
                break
            }
            val rv = if (rb == boolVar) !state.assignment.boolValue(rb) else state.assignment.boolValue(rb)
            if (!rv) {
                nowV = true
                break
            }
        }
        return (if (nowV) 1 else 0) - (if (wasV) 1 else 0)
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        // For each violating position (left=true, right=false-or-absent) propose:
        // (a) flip left to false, (b) flip right to true (if right exists), (c) compound both.
        var emitted = 0
        for (i in leftBools.indices) {
            if (emitted >= SET_BITSET_MAX_PROPOSALS) break
            val lb = leftBools[i]
            if (lb < 0 || !state.assignment.boolValue(lb)) continue
            val rb = rightBools[i]
            if (rb >= 0 && state.assignment.boolValue(rb)) continue
            sink.addBoolFlip(lb)
            if (rb >= 0) {
                sink.addBoolFlip(rb)
                sink.addCompound(listOf(Move.BoolFlip(lb), Move.BoolFlip(rb)))
            }
            emitted++
        }
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        // Conflict means some position has left=true and either right=false or right absent.
        // Cite that position's left-true literal (and right-false literal when applicable).
        for (i in leftBools.indices) {
            val lb = leftBools[i]
            if (lb < 0) continue
            if (!state.litTrue(Lit.make(lb, positive = true))) continue
            val rb = rightBools[i]
            if (rb < 0) return intArrayOf(Lit.make(lb, positive = true))
            if (state.litFalse(Lit.make(rb, positive = true))) {
                return intArrayOf(Lit.make(lb, positive = true), Lit.negate(Lit.make(rb, positive = true)))
            }
        }
        return null
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        for (i in leftBools.indices) {
            val lb = leftBools[i]
            if (lb < 0) continue
            val lLit = Lit.make(lb, positive = true)
            val rb = rightBools[i]
            if (rb < 0) {
                // No right-universe slot ⇒ leftBools[i] must be false.
                if (state.litTrue(lLit)) return false
                if (!state.litFalse(lLit)) {
                    if (!state.pinLit(Lit.negate(lLit), antecedents = null)) return false
                }
                continue
            }
            val rLit = Lit.make(rb, positive = true)
            val lT = state.litTrue(lLit)
            val rF = state.litFalse(rLit)
            if (lT && rF) return false
            if (lT) {
                if (!state.litTrue(rLit)) {
                    if (!state.pinLit(rLit, antecedents = intArrayOf(lLit))) return false
                }
            } else if (rF) {
                if (!state.litFalse(lLit)) {
                    if (!state.pinLit(Lit.negate(lLit), antecedents = intArrayOf(Lit.negate(rLit)))) return false
                }
            }
        }
        return true
    }
}

/** `leftBools ∩ rightBools = ∅`: for each universe position `i` where both `leftBools[i]`
 *  and `rightBools[i]` are non-negative, enforce `¬(leftBools[i] ∧ rightBools[i])`. */
class SetBitsetDisjoint(
    /** Indicator bool ids of the left set. */
    val leftBools: IntArray,
    /** Indicator bool ids of the right set, parallel to [leftBools]. */
    val rightBools: IntArray,
) : LocalSearchFactor {

    init {
        require(leftBools.size == rightBools.size) { "SetBitsetDisjoint: parallel arrays must have equal length" }
        require(leftBools.isNotEmpty()) { "SetBitsetDisjoint: empty universe" }
    }

    override val boolVars: IntArray = run {
        val seen = LinkedHashSet<Int>()
        for (i in leftBools.indices) {
            val lb = leftBools[i]
            val rb = rightBools[i]
            if (lb >= 0 && rb >= 0) {
                seen.add(lb)
                seen.add(rb)
            }
        }
        val out = IntArray(seen.size)
        var i = 0
        for (v in seen) out[i++] = v
        out
    }
    override val intVars: IntArray = EmptyIntArray

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        for (i in leftBools.indices) {
            val lb = leftBools[i]
            val rb = rightBools[i]
            if (lb < 0 || rb < 0) continue
            if (state.assignment.boolValue(lb) && state.assignment.boolValue(rb)) return true
        }
        return false
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val wasV = isViolated(state, factorId)
        var nowV = false
        for (i in leftBools.indices) {
            val lb = leftBools[i]
            val rb = rightBools[i]
            if (lb < 0 || rb < 0) continue
            val lv = if (lb == boolVar) !state.assignment.boolValue(lb) else state.assignment.boolValue(lb)
            val rv = if (rb == boolVar) !state.assignment.boolValue(rb) else state.assignment.boolValue(rb)
            if (lv && rv) {
                nowV = true
                break
            }
        }
        return (if (nowV) 1 else 0) - (if (wasV) 1 else 0)
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        // For each colliding position (both sides true) propose flipping either side
        // (or a compound flipping both — useful when the violation is shared with another
        // factor on the same indicator).
        var emitted = 0
        for (i in leftBools.indices) {
            if (emitted >= SET_BITSET_MAX_PROPOSALS) break
            val lb = leftBools[i]
            val rb = rightBools[i]
            if (lb < 0 || rb < 0) continue
            if (!state.assignment.boolValue(lb) || !state.assignment.boolValue(rb)) continue
            sink.addBoolFlip(lb)
            sink.addBoolFlip(rb)
            sink.addCompound(listOf(Move.BoolFlip(lb), Move.BoolFlip(rb)))
            emitted++
        }
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        for (i in leftBools.indices) {
            val lb = leftBools[i]
            val rb = rightBools[i]
            if (lb < 0 || rb < 0) continue
            val lLit = Lit.make(lb, positive = true)
            val rLit = Lit.make(rb, positive = true)
            if (state.litTrue(lLit) && state.litTrue(rLit)) {
                return intArrayOf(lLit, rLit)
            }
        }
        return null
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        for (i in leftBools.indices) {
            val lb = leftBools[i]
            val rb = rightBools[i]
            if (lb < 0 || rb < 0) continue
            val lLit = Lit.make(lb, positive = true)
            val rLit = Lit.make(rb, positive = true)
            val lT = state.litTrue(lLit)
            val rT = state.litTrue(rLit)
            if (lT && rT) return false
            if (lT && !state.litFalse(rLit)) {
                if (!state.pinLit(Lit.negate(rLit), antecedents = intArrayOf(lLit))) return false
            } else if (rT && !state.litFalse(lLit)) {
                if (!state.pinLit(Lit.negate(lLit), antecedents = intArrayOf(rLit))) return false
            }
        }
        return true
    }
}

/** `leftBools = rightBools`: bidirectional subset over the unified universe. */
class SetBitsetEq(
    /** Indicator bool ids of the left set. */
    val leftBools: IntArray,
    /** Indicator bool ids of the right set, parallel to [leftBools]. */
    val rightBools: IntArray,
) : LocalSearchFactor {

    init {
        require(leftBools.size == rightBools.size) { "SetBitsetEq: parallel arrays must have equal length" }
        require(leftBools.isNotEmpty()) { "SetBitsetEq: empty universe" }
    }

    override val boolVars: IntArray = run {
        val seen = LinkedHashSet<Int>()
        for (b in leftBools) if (b >= 0) seen.add(b)
        for (b in rightBools) if (b >= 0) seen.add(b)
        val out = IntArray(seen.size)
        var i = 0
        for (v in seen) out[i++] = v
        out
    }
    override val intVars: IntArray = EmptyIntArray

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        for (i in leftBools.indices) {
            val lb = leftBools[i]
            val rb = rightBools[i]
            val lv = lb >= 0 && state.assignment.boolValue(lb)
            val rv = rb >= 0 && state.assignment.boolValue(rb)
            if (lv != rv) return true
        }
        return false
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val wasV = isViolated(state, factorId)
        var nowV = false
        for (i in leftBools.indices) {
            val lb = leftBools[i]
            val rb = rightBools[i]
            val lvRaw = lb >= 0 && state.assignment.boolValue(lb)
            val rvRaw = rb >= 0 && state.assignment.boolValue(rb)
            val lv = if (lb == boolVar) !lvRaw else lvRaw
            val rv = if (rb == boolVar) !rvRaw else rvRaw
            if (lv != rv) {
                nowV = true
                break
            }
        }
        return (if (nowV) 1 else 0) - (if (wasV) 1 else 0)
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        // For each mismatched position, flipping either side resolves the local mismatch.
        // (A compound that flips both would just swap which side is true — still mismatched —
        //  so we don't propose one for Eq.)
        var emitted = 0
        for (i in leftBools.indices) {
            if (emitted >= SET_BITSET_MAX_PROPOSALS) break
            val lb = leftBools[i]
            val rb = rightBools[i]
            val lv = lb >= 0 && state.assignment.boolValue(lb)
            val rv = rb >= 0 && state.assignment.boolValue(rb)
            if (lv == rv) continue
            if (lb >= 0) sink.addBoolFlip(lb)
            if (rb >= 0) sink.addBoolFlip(rb)
            emitted++
        }
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        for (i in leftBools.indices) {
            val lb = leftBools[i]
            val rb = rightBools[i]
            val lLit = if (lb >= 0) Lit.make(lb, positive = true) else -1
            val rLit = if (rb >= 0) Lit.make(rb, positive = true) else -1
            val lT = lb >= 0 && state.litTrue(lLit)
            val rT = rb >= 0 && state.litTrue(rLit)
            if (lT && rb < 0) return intArrayOf(lLit)
            if (rT && lb < 0) return intArrayOf(rLit)
            if (lT && rb >= 0 && state.litFalse(rLit)) return intArrayOf(lLit, Lit.negate(rLit))
            if (rT && lb >= 0 && state.litFalse(lLit)) return intArrayOf(rLit, Lit.negate(lLit))
        }
        return null
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        for (i in leftBools.indices) {
            val lb = leftBools[i]
            val rb = rightBools[i]
            // Both absent ⇒ vacuous position.
            if (lb < 0 && rb < 0) continue
            if (lb < 0) {
                // Right side must be false.
                val rLit = Lit.make(rb, positive = true)
                if (state.litTrue(rLit)) return false
                if (!state.litFalse(rLit)) {
                    if (!state.pinLit(Lit.negate(rLit), antecedents = null)) return false
                }
                continue
            }
            if (rb < 0) {
                val lLit = Lit.make(lb, positive = true)
                if (state.litTrue(lLit)) return false
                if (!state.litFalse(lLit)) {
                    if (!state.pinLit(Lit.negate(lLit), antecedents = null)) return false
                }
                continue
            }
            val lLit = Lit.make(lb, positive = true)
            val rLit = Lit.make(rb, positive = true)
            val lT = state.litTrue(lLit)
            val lF = state.litFalse(lLit)
            val rT = state.litTrue(rLit)
            val rF = state.litFalse(rLit)
            if (lT && rF) return false
            if (lF && rT) return false
            if (lT && !rT) {
                if (!state.pinLit(rLit, antecedents = intArrayOf(lLit))) return false
            } else if (rT && !lT) {
                if (!state.pinLit(lLit, antecedents = intArrayOf(rLit))) return false
            } else if (lF && !rF) {
                if (!state.pinLit(Lit.negate(rLit), antecedents = intArrayOf(Lit.negate(lLit)))) return false
            } else if (rF && !lF) {
                if (!state.pinLit(Lit.negate(lLit), antecedents = intArrayOf(Lit.negate(rLit)))) return false
            }
        }
        return true
    }
}
