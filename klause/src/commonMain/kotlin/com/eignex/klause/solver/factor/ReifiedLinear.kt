package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move.BoolFlip
import com.eignex.klause.solver.Move.IntSet
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `auxBoolVar ↔ (Σ coeffs[i] * intVars[i] ⟨op⟩ bound)`. Created by the compiler when a
 * multi-variable [com.eignex.klause.ast.IntCompare] appears non-top-level so the rest of the
 * Tseitin lowering can treat its truth as a Boolean literal. Payload at `intPayload[factorId]`
 * is the current weighted sum, mirrored from [Linear].
 */
class ReifiedLinear private constructor(
    /** Reification literal: true iff the linear relation holds. */
    val auxBoolVar: Int,
    terms: CoalescedTerms,
    /** Relation between the weighted sum and [bound]. */
    val op: LinearOp,
    /** Right-hand-side bound. */
    val bound: Int,
) : Factor {

    /** Integer variable ids, parallel to [coeffs]; each variable appears at most once. */
    val vars: IntArray = terms.vars

    /** Coefficients, parallel to [vars]. */
    val coeffs: IntArray = terms.coeffs

    /**
     * `auxBoolVar ↔ (Σ coeffs[i] * vars[i] ⟨op⟩ bound)`. Duplicate variables are coalesced
     * (their coefficients summed) so the local-search payload stays consistent regardless of
     * caller (issue #84).
     */
    constructor(auxBoolVar: Int, coeffs: IntArray, vars: IntArray, op: LinearOp, bound: Int) :
        this(auxBoolVar, coalesceLinearTerms(vars, coeffs), op, bound)

    init {
        require(coeffs.isNotEmpty()) { "ReifiedLinear must have at least one term" }
    }

    override val boolVars: IntArray = intArrayOf(auxBoolVar)
    override val intVars: IntArray = vars

    private val coeffLookup: CoeffLookup = CoeffLookup.build(vars, coeffs)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        var sum = 0L
        for (i in vars.indices) sum += coeffs[i].toLong() * state.assignment.intValue(vars[i])
        state.longPayload[factorId] = sum
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val aux = state.assignment.boolValue(auxBoolVar)
        val holds = holds(state.longPayload[factorId])
        return aux != holds
    }

    /** Graded violation. When the indicator demands the linear hold (`aux = true`) but it
     *  doesn't, the degree is the *linear residual* — how far the sum is from satisfying the
     *  comparison — giving CBLS a gradient on the sum's variables (this is what makes the
     *  element decomposition's `idxMatch → bodyHolds` channels navigable). When `aux = false`
     *  but the linear holds, the natural one-step repair is to flip the indicator, so the
     *  degree is 1 (pushing the sum out of the satisfied region is rarely the right move). */
    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        degreeFor(state.longPayload[factorId], state.assignment.boolValue(auxBoolVar), state.violationSoftCap)

    private fun degreeFor(sum: Long, aux: Boolean, softCap: Int): Int {
        val h = holds(sum)
        return when {
            aux == h -> 0

            // indicator wants it to hold; grade by how far off (shared residual)
            aux -> linearResidual(sum, op, bound, softCap)

            else -> 1 // indicator wants it false but it holds; flip the aux
        }
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val sum = state.longPayload[factorId]
        val aux = state.assignment.boolValue(auxBoolVar)
        return degreeFor(sum, !aux, state.violationSoftCap) - degreeFor(sum, aux, state.violationSoftCap)
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val sum = state.longPayload[factorId]
        val coeff = coeffOf(intVar)
        val newSum = sum + coeff.toLong() * (newValue - state.assignment.intValue(intVar))
        return degreeFor(newSum, aux, state.violationSoftCap) - degreeFor(sum, aux, state.violationSoftCap)
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        // aux already flipped in the assignment; report Δdegree (cost is reconciled by the engine).
        val sum = state.longPayload[factorId]
        val aux = state.assignment.boolValue(auxBoolVar)
        return degreeFor(sum, aux, state.violationSoftCap) - degreeFor(sum, !aux, state.violationSoftCap)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val coeff = coeffOf(intVar)
        val oldSum = state.longPayload[factorId]
        val newSum = oldSum + coeff.toLong() * (state.assignment.intValue(intVar) - oldValue)
        state.longPayload[factorId] = newSum
        return degreeFor(newSum, aux, state.violationSoftCap) - degreeFor(oldSum, aux, state.violationSoftCap)
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val range = linearSumRange(state, coeffs, vars)
        val sumLo = range[0]
        val sumHi = range[1]
        val bnd = bound.toLong()
        val alwaysHolds = when (op) {
            LinearOp.LE -> sumHi <= bnd
            LinearOp.GE -> sumLo >= bnd
            LinearOp.EQ -> sumLo == bnd && sumHi == bnd
            LinearOp.NE -> sumHi < bnd || sumLo > bnd
        }
        val neverHolds = when (op) {
            LinearOp.LE -> sumLo > bnd
            LinearOp.GE -> sumHi < bnd
            LinearOp.EQ -> sumLo > bnd || sumHi < bnd
            LinearOp.NE -> sumLo == bnd && sumHi == bnd
        }
        // Aux pin antecedents: union of the int-fact antecedents that drove sumLo/sumHi
        // into the always/never-holds region. LCG-style transitive reasoning — each int
        // bound's recorded `intMinAntecedents` / `intMaxAntecedents` traces back to the
        // bool decisions that established it.
        if (alwaysHolds) {
            val ant = composeAuxAntecedents(state)
            return state.pinBool(auxBoolVar, true, ant)
        }
        if (neverHolds) {
            val ant = composeAuxAntecedents(state)
            return state.pinBool(auxBoolVar, false, ant)
        }
        // Bounds alone miss the case where a single-term EQ targets a value that is unreachable
        // *inside* the bound interval — an interior domain hole, or a bound not divisible by the
        // coefficient. The equality can then never hold, so pin the aux false now with a
        // hole-aware antecedent. Without this the aux stays free, search may set it true, and the
        // resulting empty-domain conflict carries a bounds-only (hole-blind) reason that yields an
        // unsound learned clause — the latent false-UNSAT of #121.
        if (op == LinearOp.EQ && vars.size == 1 && eqTargetUnreachable(state)) {
            return state.pinBool(auxBoolVar, false, collectHoleAndBoundAntecedents(state, vars))
        }

        val aux = state.boolValues[auxBoolVar] ?: return true
        // Thread the aux's current pinning as an extra antecedent for every implied int
        // tighten — the body-propagation path was selected by this pin, so any subsequent
        // conflict must trace back through it.
        val auxAntecedent = Lit.make(auxBoolVar, !aux)
        return if (aux) {
            propagateLinearBounds(state, coeffs, vars, op, bnd, extraLit = auxAntecedent)
        } else {
            when (op) {
                LinearOp.LE -> propagateLinearBounds(
                    state,
                    coeffs,
                    vars,
                    LinearOp.GE,
                    bnd + 1,
                    extraLit = auxAntecedent,
                )

                LinearOp.GE -> propagateLinearBounds(
                    state,
                    coeffs,
                    vars,
                    LinearOp.LE,
                    bnd - 1,
                    extraLit = auxAntecedent,
                )

                LinearOp.EQ -> propagateLinearBounds(state, coeffs, vars, LinearOp.NE, bnd, extraLit = auxAntecedent)

                LinearOp.NE -> propagateLinearBounds(state, coeffs, vars, LinearOp.EQ, bnd, extraLit = auxAntecedent)
            }
        }
    }

    /**
     * Compose aux-pin antecedents as per-bound atom-lits over the involved vars. For each
     * var with `min` tighter than its initial domain, emit `¬[v ≥ d.min]`; similarly for
     * the `max` side. The implicit clause `(⋀ premise atom-lits) → aux` resolves cleanly
     * through 1UIP / self-subsuming minimization at per-bound granularity.
     */
    private fun composeAuxAntecedents(state: PropagationState): IntArray? = state.composeIntVarAtomAntecedents(vars)

    /** For a single-term `c·x = bound`, true when `bound/c` is not an integer in `x`'s current
     *  domain — i.e. the equality is unsatisfiable even though `bound` lies within `x`'s bounds
     *  (an interior hole) or `bound` is not divisible by `c`. */
    private fun eqTargetUnreachable(state: PropagationState): Boolean {
        val c = coeffs[0].toLong()
        val b = bound.toLong()
        if (c == 0L) return b != 0L
        if (b % c != 0L) return true
        val value = b / c
        if (value < Int.MIN_VALUE.toLong() || value > Int.MAX_VALUE.toLong()) return true
        return value.toInt() !in state.intDomains[vars[0]]
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val aux = state.assignment.boolValue(auxBoolVar)
        val sum = state.longPayload[factorId]
        if (aux == holds(sum)) return
        sink.addBoolFlip(auxBoolVar)
        val auxFlipMove = BoolFlip(auxBoolVar)
        for (i in vars.indices) {
            val v = vars[i]
            val c = coeffs[i]
            if (c == 0) continue
            val cur = state.assignment.intValue(v)
            val sumWithout = sum - c.toLong() * cur
            // Same-aux snap: shift body so the predicate matches the current aux. Routed
            // through the channeling-aware sink helper so any sibling reified-eq factors
            // on this var get their indicators flipped atomically — without this, LS sets
            // the int and then chases the now-inconsistent indicator bools one at a time,
            // which is the cascade that stalls course-period style decompositions.
            val targetSame = snapTarget(c, sumWithout, aux)
            if (targetSame != null) {
                val clamped = state.problem.intDomains[v].clampLong(targetSame)
                if (clamped != cur && aux == holds(sumWithout + c.toLong() * clamped)) {
                    sink.addChannelingIntSet(state, v, clamped)
                }
            }
            // Toggle-driven sub-region exploration: flip aux *and* shift body so the
            // predicate matches the flipped aux. Strategies that get stuck on the current
            // reification side benefit from atomic transitions to the other side.
            val targetOpp = snapTarget(c, sumWithout, !aux)
            if (targetOpp != null) {
                val clamped = state.problem.intDomains[v].clampLong(targetOpp)
                if (clamped != cur && !aux == holds(sumWithout + c.toLong() * clamped)) {
                    sink.addCompound(listOf(auxFlipMove, IntSet(v, clamped)))
                }
            }
        }
    }

    private fun holds(sum: Long): Boolean = linearHolds(sum, op, bound)

    private fun coeffOf(intVar: Int): Int = coeffLookup.coeffOf(intVar)

    private fun snapTarget(coeff: Int, sumWithout: Long, wantHolds: Boolean): Long? =
        snapLinearTarget(op, bound, coeff, sumWithout, wantHolds)

    override val maintainsBreakMakeIncrementally: Boolean get() = true

    /** [boolVars] contains only [auxBoolVar], so a bool flip is always an aux flip. Flipping
     *  aux always toggles violation (sum unchanged), so the aux's own contribution simply
     *  swaps between break and make. */
    override fun updateBoolBreakMakeForFlip(state: LocalSearchState, factorId: Int, flippedVar: Int) {
        val nowViolated = state.assignment.boolValue(auxBoolVar) != holds(state.longPayload[factorId])
        if (nowViolated) {
            state.boolBreakCount[auxBoolVar]--
            state.boolMakeCount[auxBoolVar]++
        } else {
            state.boolMakeCount[auxBoolVar]--
            state.boolBreakCount[auxBoolVar]++
        }
    }

    override val maintainsIntBreakMakeIncrementallyForIntSet: Boolean get() = true

    /** Aux's break/make contribution depends only on `holds(sum)` and `aux`. An int set
     *  may flip `holds`, in which case the aux's contribution swaps; otherwise no change. */
    override fun updateIntBreakMakeForIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int) {
        val newSum = state.longPayload[factorId]
        val coeff = coeffOf(intVar)
        val newValue = state.assignment.intValue(intVar)
        val oldSum = newSum - coeff.toLong() * (newValue - oldValue)
        val oldHolds = holds(oldSum)
        val newHolds = holds(newSum)
        if (oldHolds == newHolds) return // aux contribution unchanged
        val aux = state.assignment.boolValue(auxBoolVar)
        val newViolated = aux != newHolds
        // oldViolated != newViolated, so the aux's contribution swaps break↔make.
        if (newViolated) {
            state.boolBreakCount[auxBoolVar]--
            state.boolMakeCount[auxBoolVar]++
        } else {
            state.boolMakeCount[auxBoolVar]--
            state.boolBreakCount[auxBoolVar]++
        }
    }
}
