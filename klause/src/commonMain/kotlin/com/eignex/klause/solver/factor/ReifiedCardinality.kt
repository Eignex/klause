package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `auxBoolVar ↔ (#true literals in [min, max])`. Created by the compiler when a
 * [com.eignex.klause.ast.CardinalityExpr] / `AtMost` / `AtLeast` appears non-top-level so the
 * Tseitin lowering can treat its truth as a Boolean literal. Payload at `intPayload[factorId]`
 * is the count of true literals, mirrored from [Cardinality].
 */
class ReifiedCardinality(val auxBoolVar: Int, val literals: IntArray, val min: Int, val max: Int) : LocalSearchFactor {

    init {
        require(min in 0..max) { "Cardinality bounds invalid: $min..$max" }
        require(max <= literals.size) { "max ($max) exceeds literal count (${literals.size})" }
    }

    override val boolVars: IntArray = run {
        val unique = LinkedHashSet<Int>()
        unique.add(auxBoolVar)
        for (lit in literals) unique.add(Lit.variable(lit))
        val out = IntArray(unique.size)
        var i = 0
        for (v in unique) out[i++] = v
        out
    }
    override val intVars: IntArray = EmptyIntArray

    /** Net polarity-signed occurrence count per Boolean variable in [literals] (excluding
     *  [auxBoolVar] — aux flips don't affect the body count). `+1` per positive
     *  occurrence, `-1` per negative; vars whose occurrences cancel exactly have entry 0
     *  and don't shift the count when flipped. */
    private val signedOccurrencesByVar: com.eignex.klause.util.IntIntMap = run {
        val signs = HashMap<Int, Int>()
        for (lit in literals) {
            val v = Lit.variable(lit)
            if (v == auxBoolVar) continue
            val sign = if (Lit.isPositive(lit)) 1 else -1
            signs[v] = (signs[v] ?: 0) + sign
        }
        com.eignex.klause.util.IntIntMap.build(
            keys = signs.keys.toIntArray(),
            values = signs.values.toIntArray(),
            absent = 0,
        )
    }

    override fun initialize(state: LocalSearchState, factorId: Int) {
        var count = 0
        for (lit in literals) {
            if (Lit.evaluate(lit, state.assignment.boolValue(Lit.variable(lit)))) count++
        }
        state.intPayload[factorId] = count
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val aux = state.assignment.boolValue(auxBoolVar)
        val holds = inRange(state.intPayload[factorId])
        return aux != holds
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val n = state.intPayload[factorId]
        val wasViolated = aux != inRange(n)
        if (boolVar == auxBoolVar) {
            // aux flips; payload unchanged.
            return if (wasViolated) -1 else +1
        }
        // Some constrained literal flips: count changes by net effect.
        val change = changeOnFlip(state, boolVar, current = true)
        val newN = n + change
        val willViolate = aux != inRange(newN)
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val oldN = state.intPayload[factorId]
        if (boolVar == auxBoolVar) {
            val nowViolated = aux != inRange(oldN)
            return if (nowViolated) +1 else -1
        }
        val change = changeOnFlip(state, boolVar, current = false)
        val newN = oldN + change
        state.intPayload[factorId] = newN
        val wasViolated = aux != inRange(oldN)
        val nowViolated = aux != inRange(newN)
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    /**
     * Δ to payload count from flipping `boolVar`. With `current = true` the assignment still
     * holds the pre-flip value (used by [deltaIfBoolFlipped]); with `current = false` the
     * assignment has been updated (used by [applyBoolFlip]). O(1) via [signedOccurrencesByVar].
     */
    private fun changeOnFlip(state: LocalSearchState, boolVar: Int, current: Boolean): Int {
        val signed = signedOccurrencesByVar[boolVar]
        if (signed == 0) return 0
        val pre = if (current) {
            state.assignment.boolValue(boolVar)
        } else {
            !state.assignment.boolValue(boolVar)
        }
        return if (pre) -signed else signed
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        var trueCount = 0
        var falseCount = 0
        for (lit in literals) {
            val v = Lit.variable(lit)
            val b = state.boolValues[v] ?: continue
            if (Lit.evaluate(lit, b)) trueCount++ else falseCount++
        }
        val unassigned = literals.size - trueCount - falseCount
        val minPossible = trueCount
        val maxPossible = trueCount + unassigned

        // Fact about the body: definitely in [min, max], or definitely outside?
        val definitelyIn = minPossible >= min && maxPossible <= max
        val definitelyOut = maxPossible < min || minPossible > max
        if (definitelyIn) {
            val ant = pbFalseFormAntecedents(state, literals, excludeVar = auxBoolVar, extraLit = 0)
            return state.pinBool(auxBoolVar, true, ant)
        }
        if (definitelyOut) {
            val ant = pbFalseFormAntecedents(state, literals, excludeVar = auxBoolVar, extraLit = 0)
            return state.pinBool(auxBoolVar, false, ant)
        }

        val aux = state.boolValues[auxBoolVar] ?: return true
        // Aux is pinned — thread its current pinning into each derived literal pin so 1UIP
        // can resolve back through this reification.
        val auxAntecedent = Lit.make(auxBoolVar, !aux)
        if (aux) {
            // aux pinned true → body must hold: count ∈ [min, max]. Mirror Cardinality's
            // boundary-forcing pass.
            if (trueCount == max && unassigned > 0) {
                val ant = pbFalseFormAntecedents(state, literals, excludeVar = -1, extraLit = auxAntecedent)
                for (lit in literals) {
                    val v = Lit.variable(lit)
                    if (state.boolValues[v] != null) continue
                    if (!state.pinBool(v, !Lit.isPositive(lit), ant)) return false
                }
            } else if (trueCount + unassigned == min && unassigned > 0) {
                val ant = pbFalseFormAntecedents(state, literals, excludeVar = -1, extraLit = auxAntecedent)
                for (lit in literals) {
                    val v = Lit.variable(lit)
                    if (state.boolValues[v] != null) continue
                    if (!state.pinBool(v, Lit.isPositive(lit), ant)) return false
                }
            }
            return true
        }

        // aux pinned false → body must NOT hold: final count ∉ [min, max], i.e., must end up
        // either *strictly below* min or *strictly above* max. With `x = additional trues`
        // picked from the `unassigned` literals:
        //   feasible x values are [0, min−trueCount−1] ∪ [max−trueCount+1, unassigned].
        // The "down" branch is feasible only if `min − trueCount − 1 ≥ 0`, i.e. `trueCount < min`.
        // The "up" branch is feasible only if `max − trueCount + 1 ≤ unassigned`, i.e.
        // `trueCount + unassigned > max`. When exactly one branch is feasible the propagator
        // can force the asymmetric extreme:
        //   - up-only & need == unassigned → force every unassigned literal *true*.
        //   - down-only & cap == 0          → force every unassigned literal *false*.
        // Any other combination is undetermined; future literal pins narrow it organically.
        if (unassigned == 0) return true // no flexibility left to force anyway
        val downBranchFeasible = trueCount < min
        val upBranchFeasible = trueCount + unassigned > max
        // The double-infeasibility case (both branches blocked) is unreachable here: it's
        // equivalent to `definitelyIn`, which the early-return above already converted to
        // a `pinBool(auxBoolVar, true)` — that pin conflicts with the pre-pinned aux=false
        // and `revertAndUnsat` surfaces Unsat at the session level before we land in this
        // body.
        when {
            !downBranchFeasible && upBranchFeasible -> {
                // Must escape upward. Required additional trues: `max - trueCount + 1`.
                // Unique forcing when that requirement equals `unassigned` — every
                // unassigned literal must flip true.
                val need = max - trueCount + 1
                if (need == unassigned) {
                    val ant = pbFalseFormAntecedents(state, literals, excludeVar = -1, extraLit = auxAntecedent)
                    for (lit in literals) {
                        val v = Lit.variable(lit)
                        if (state.boolValues[v] != null) continue
                        if (!state.pinBool(v, Lit.isPositive(lit), ant)) return false
                    }
                }
            }

            !upBranchFeasible && downBranchFeasible -> {
                // Must stay below min. Allowed at most `min - trueCount - 1` extra trues —
                // when that cap is zero, force every unassigned literal false.
                val cap = min - trueCount - 1
                if (cap == 0) {
                    val ant = pbFalseFormAntecedents(state, literals, excludeVar = -1, extraLit = auxAntecedent)
                    for (lit in literals) {
                        val v = Lit.variable(lit)
                        if (state.boolValues[v] != null) continue
                        if (!state.pinBool(v, !Lit.isPositive(lit), ant)) return false
                    }
                }
            }
            // both branches feasible (or both infeasible — handled by definitelyIn): no
            // unique forcing this round.
        }
        return true
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val aux = state.assignment.boolValue(auxBoolVar)
        val n = state.intPayload[factorId]
        if (aux == inRange(n)) return
        sink.addBoolFlip(auxBoolVar)
        val auxFlip = com.eignex.klause.solver.Move.BoolFlip(auxBoolVar)
        val wantInRange = aux
        for (lit in literals) {
            val v = Lit.variable(lit)
            if (v == auxBoolVar) continue
            val isTrue = Lit.evaluate(lit, state.assignment.boolValue(v))
            val newN = n + if (isTrue) -1 else 1
            // Same-aux body flip: drives count toward the predicate matching current aux.
            if (wantInRange == inRange(newN)) sink.addBoolFlip(v)
            // Toggle-driven sub-region exploration: pair aux flip with a body flip that
            // drives count toward the *opposite* predicate, so strategies can atomically
            // transition to the other reification side.
            if (wantInRange != inRange(newN)) {
                sink.addCompound(listOf(auxFlip, com.eignex.klause.solver.Move.BoolFlip(v)))
            }
        }
    }

    private fun inRange(count: Int): Boolean = count in min..max

    /** Clause-form nogood for any pin failure: every currently-pinned constraint literal's
     *  false-form, plus the aux literal when pinned. The current pinning collectively
     *  forced the propagation path that failed. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        val auxLit = state.boolValues[auxBoolVar]?.let { Lit.make(auxBoolVar, !it) } ?: 0
        return pbFalseFormAntecedents(state, literals, excludeVar = -1, extraLit = auxLit)
    }

    override val maintainsBreakMakeIncrementally: Boolean get() = true

    /** Recover the pre-flip count and aux value from the now-committed state, then walk
     *  each touched variable once applying the change in its break/make contribution. */
    override fun updateBoolBreakMakeForFlip(state: LocalSearchState, factorId: Int, flippedVar: Int) {
        val newN = state.intPayload[factorId]
        val newAux = state.assignment.boolValue(auxBoolVar)
        val oldAux: Boolean
        val oldN: Int
        if (flippedVar == auxBoolVar) {
            oldAux = !newAux
            oldN = newN
        } else {
            oldAux = newAux
            val signedFlipped = signedOccurrencesByVar[flippedVar]
            if (signedFlipped == 0) return // body lit whose occurrences cancel — nothing changed
            val flippedPost = state.assignment.boolValue(flippedVar)
            val changeV = if (flippedPost) signedFlipped else -signedFlipped
            oldN = newN - changeV
        }
        val oldViolated = oldAux != inRange(oldN)
        val newViolated = newAux != inRange(newN)
        for (u in boolVars) {
            val preViolatedIfU: Boolean
            val postViolatedIfU: Boolean
            if (u == auxBoolVar) {
                preViolatedIfU = !oldAux != inRange(oldN)
                postViolatedIfU = !newAux != inRange(newN)
            } else {
                val signedU = signedOccurrencesByVar[u]
                if (signedU == 0) {
                    preViolatedIfU = oldViolated
                    postViolatedIfU = newViolated
                } else {
                    val uPost = state.assignment.boolValue(u)
                    val uPre = if (u == flippedVar) !uPost else uPost
                    val preChangeU = if (uPre) -signedU else signedU
                    val postChangeU = if (uPost) -signedU else signedU
                    preViolatedIfU = oldAux != inRange(oldN + preChangeU)
                    postViolatedIfU = newAux != inRange(newN + postChangeU)
                }
            }
            val preBreak = !oldViolated && preViolatedIfU
            val preMake = oldViolated && !preViolatedIfU
            val postBreak = !newViolated && postViolatedIfU
            val postMake = newViolated && !postViolatedIfU
            if (preBreak != postBreak) {
                if (postBreak) state.boolBreakCount[u]++ else state.boolBreakCount[u]--
            }
            if (preMake != postMake) {
                if (postMake) state.boolMakeCount[u]++ else state.boolMakeCount[u]--
            }
        }
    }
}
