package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move.BoolFlip
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `auxBoolVar ↔ (#true literals in [min, max])`. Created by the compiler when a
 * [com.eignex.klause.ast.CardinalityExpr] / `AtMost` / `AtLeast` appears non-top-level so the
 * Tseitin lowering can treat its truth as a Boolean literal. Payload at `longPayload(factorId)`
 * is the count of true literals, mirrored from [Cardinality].
 */
class ReifiedCardinality(override val auxBoolVar: Int, literals: IntArray, min: Int, max: Int) :
    CardinalitySumFactor(literals, min, max, excludedVar = auxBoolVar),
    ReifiedFactor {

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        ReifiedCardinality(boolMap[auxBoolVar], literals.remapLits(boolMap), min, max)

    override val boolVars: IntArray = literals.litVars(auxBoolVar)

    override fun holdsNow(state: LocalSearchState, factorId: Int): Boolean = holds(state.longPayload[factorId])

    override fun residualNow(state: LocalSearchState, factorId: Int, softCap: Int): Int =
        residual(state.longPayload[factorId], softCap)

    private fun degreeFor(n: Long, aux: Boolean, softCap: Int): Int =
        reifiedDegree(aux, holds(n)) { residual(n, softCap) }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val n = state.longPayload[factorId]
        val cap = state.violationSoftCap
        return if (boolVar == auxBoolVar) {
            degreeFor(n, !aux, cap) - degreeFor(n, aux, cap)
        } else {
            val change = signedFlipDelta(state, signedByVar, boolVar, current = true)
            degreeFor(n + change, aux, cap) - degreeFor(n, aux, cap)
        }
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val oldN = state.longPayload[factorId]
        val cap = state.violationSoftCap
        if (boolVar == auxBoolVar) {
            val newAux = state.assignment.boolValue(auxBoolVar)
            return degreeFor(oldN, newAux, cap) - degreeFor(oldN, !newAux, cap)
        }
        val change = signedFlipDelta(state, signedByVar, boolVar, current = false)
        val newN = oldN + change
        state.longPayload[factorId] = newN
        val aux = state.assignment.boolValue(auxBoolVar)
        return degreeFor(newN, aux, cap) - degreeFor(oldN, aux, cap)
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
        val n = state.longPayload[factorId]
        if (aux == holds(n)) return
        sink.addBoolFlip(auxBoolVar)
        val auxFlip = BoolFlip(auxBoolVar)
        val wantInRange = aux
        for (lit in literals) {
            val v = Lit.variable(lit)
            if (v == auxBoolVar) continue
            val isTrue = Lit.evaluate(lit, state.assignment.boolValue(v))
            val newN = n + if (isTrue) -1 else 1
            // Same-aux body flip: drives count toward the predicate matching current aux.
            if (wantInRange == holds(newN)) sink.addBoolFlip(v)
            // Toggle-driven sub-region exploration: pair aux flip with a body flip that
            // drives count toward the *opposite* predicate, so strategies can atomically
            // transition to the other reification side.
            if (wantInRange != holds(newN)) {
                sink.addCompound(listOf(auxFlip, BoolFlip(v)))
            }
        }
    }

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
        val newN = state.longPayload[factorId]
        val newAux = state.assignment.boolValue(auxBoolVar)
        val oldAux: Boolean
        val oldN: Long
        if (flippedVar == auxBoolVar) {
            oldAux = !newAux
            oldN = newN
        } else {
            oldAux = newAux
            val signedFlipped = signedByVar[flippedVar]
            if (signedFlipped == 0) return // body lit whose occurrences cancel — nothing changed
            val flippedPost = state.assignment.boolValue(flippedVar)
            val changeV = if (flippedPost) signedFlipped else -signedFlipped
            oldN = newN - changeV
        }
        val cap = state.violationSoftCap
        val oldDeg = degreeFor(oldN, oldAux, cap)
        val newDeg = degreeFor(newN, newAux, cap)
        for (u in boolVars) {
            // Graded Δ each var's flip would produce (the value deltaIfBoolFlipped returns),
            // evaluated against the pre- and post-flip (count, aux) — break/make track its sign.
            val preDelta: Int
            val postDelta: Int
            if (u == auxBoolVar) {
                preDelta = degreeFor(oldN, !oldAux, cap) - oldDeg
                postDelta = degreeFor(newN, !newAux, cap) - newDeg
            } else {
                val signedU = signedByVar[u]
                if (signedU == 0) {
                    preDelta = 0
                    postDelta = 0
                } else {
                    val uPost = state.assignment.boolValue(u)
                    val uPre = if (u == flippedVar) !uPost else uPost
                    val preChangeU = if (uPre) -signedU else signedU
                    val postChangeU = if (uPost) -signedU else signedU
                    preDelta = degreeFor(oldN + preChangeU, oldAux, cap) - oldDeg
                    postDelta = degreeFor(newN + postChangeU, newAux, cap) - newDeg
                }
            }
            val preBreak = preDelta > 0
            val preMake = preDelta < 0
            val postBreak = postDelta > 0
            val postMake = postDelta < 0
            if (preBreak != postBreak) {
                if (postBreak) state.boolBreakCount[u]++ else state.boolBreakCount[u]--
            }
            if (preMake != postMake) {
                if (postMake) state.boolMakeCount[u]++ else state.boolMakeCount[u]--
            }
        }
    }
}
