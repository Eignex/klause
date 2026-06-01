package com.eignex.klause.solver.factor

import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `auxBoolVar ↔ (Σ coeffs[i] * intVars[i] ⟨op⟩ bound)`. Created by the compiler when a
 * multi-variable [com.eignex.klause.ast.IntCompare] appears non-top-level so the rest of the
 * Tseitin lowering can treat its truth as a Boolean literal. Payload at `intPayload[factorId]`
 * is the current weighted sum, mirrored from [Linear].
 */
class ReifiedLinear(
    /** Reification literal: true iff the linear relation holds. */
    val auxBoolVar: Int,
    /** Coefficients, parallel to [vars]. */
    val coeffs: IntArray,
    /** Integer variable ids, parallel to [coeffs]. */
    val vars: IntArray,
    /** Relation between the weighted sum and [bound]. */
    val op: LinearOp,
    /** Right-hand-side bound. */
    val bound: Int,
) :
    LocalSearchFactor {

    init {
        require(coeffs.size == vars.size) { "coeffs/vars length mismatch" }
        require(coeffs.isNotEmpty()) { "ReifiedLinear must have at least one term" }
    }

    override val boolVars: IntArray = intArrayOf(auxBoolVar)
    override val intVars: IntArray = vars

    private val coeffLookup: CoeffLookup = CoeffLookup.build(vars, coeffs)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        var sum = 0
        for (i in vars.indices) sum += coeffs[i] * state.assignment.intValue(vars[i])
        state.intPayload[factorId] = sum
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val aux = state.assignment.boolValue(auxBoolVar)
        val holds = holds(state.intPayload[factorId])
        return aux != holds
    }

    /** Graded violation. When the indicator demands the linear hold (`aux = true`) but it
     *  doesn't, the degree is the *linear residual* — how far the sum is from satisfying the
     *  comparison — giving CBLS a gradient on the sum's variables (this is what makes the
     *  element decomposition's `idxMatch → bodyHolds` channels navigable). When `aux = false`
     *  but the linear holds, the natural one-step repair is to flip the indicator, so the
     *  degree is 1 (pushing the sum out of the satisfied region is rarely the right move). */
    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        degreeFor(state.intPayload[factorId], state.assignment.boolValue(auxBoolVar))

    private fun degreeFor(sum: Int, aux: Boolean): Int {
        val h = holds(sum)
        return when {
            aux == h -> 0

            aux -> residual(sum)

            // indicator wants it to hold; grade by how far off
            else -> 1 // indicator wants it false but it holds; flip the aux
        }
    }

    /** Distance the sum must move to satisfy the comparison, given it currently does not —
     *  run through [compressViolation] so far-off reified linears don't dominate the cost. */
    private fun residual(sum: Int): Int = when (op) {
        LinearOp.LE -> compressViolation(sum.toLong() - bound)

        // sum > bound
        LinearOp.GE -> compressViolation(bound.toLong() - sum)

        // sum < bound
        LinearOp.EQ -> {
            val d = sum.toLong() - bound
            compressViolation(if (d < 0) -d else d)
        }

        LinearOp.NE -> 1 // sum == bound; one step off
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val sum = state.intPayload[factorId]
        val aux = state.assignment.boolValue(auxBoolVar)
        return degreeFor(sum, !aux) - degreeFor(sum, aux)
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val sum = state.intPayload[factorId]
        val coeff = coeffOf(intVar)
        val newSum = sum + coeff * (newValue - state.assignment.intValue(intVar))
        return degreeFor(newSum, aux) - degreeFor(sum, aux)
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        // aux already flipped in the assignment; report Δdegree (cost is reconciled by the engine).
        val sum = state.intPayload[factorId]
        val aux = state.assignment.boolValue(auxBoolVar)
        return degreeFor(sum, aux) - degreeFor(sum, !aux)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val coeff = coeffOf(intVar)
        val oldSum = state.intPayload[factorId]
        val newSum = oldSum + coeff * (state.assignment.intValue(intVar) - oldValue)
        state.intPayload[factorId] = newSum
        return degreeFor(newSum, aux) - degreeFor(oldSum, aux)
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

        val aux = state.boolValues[auxBoolVar] ?: return true
        // Thread the aux's current pinning as an extra antecedent for every implied int
        // tighten — the body-propagation path was selected by this pin, so any subsequent
        // conflict must trace back through it.
        val auxAntecedent = com.eignex.klause.solver.Lit.make(auxBoolVar, !aux)
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
     * through 1UIP / self-subsuming minimization with finer granularity than the older
     * bool-lit union would have given.
     */
    private fun composeAuxAntecedents(state: PropagationState): IntArray? = state.composeIntVarAtomAntecedents(vars)

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val aux = state.assignment.boolValue(auxBoolVar)
        val sum = state.intPayload[factorId]
        if (aux == holds(sum)) return
        sink.addBoolFlip(auxBoolVar)
        val auxFlipMove = com.eignex.klause.solver.Move.BoolFlip(auxBoolVar)
        for (i in vars.indices) {
            val v = vars[i]
            val c = coeffs[i]
            if (c == 0) continue
            val cur = state.assignment.intValue(v)
            val sumWithout = sum - c * cur
            // Same-aux snap: shift body so the predicate matches the current aux. Routed
            // through the channeling-aware sink helper so any sibling reified-eq factors
            // on this var get their indicators flipped atomically — without this, LS sets
            // the int and then chases the now-inconsistent indicator bools one at a time,
            // which is the cascade that stalls course-period style decompositions.
            val targetSame = snapTarget(c, sumWithout, aux)
            if (targetSame != null) {
                val clamped = state.problem.intDomains[v].clamp(targetSame)
                if (clamped != cur && aux == holds(sumWithout + c * clamped)) {
                    sink.addChannelingIntSet(state, v, clamped)
                }
            }
            // Toggle-driven sub-region exploration: flip aux *and* shift body so the
            // predicate matches the flipped aux. Strategies that get stuck on the current
            // reification side benefit from atomic transitions to the other side.
            val targetOpp = snapTarget(c, sumWithout, !aux)
            if (targetOpp != null) {
                val clamped = state.problem.intDomains[v].clamp(targetOpp)
                if (clamped != cur && !aux == holds(sumWithout + c * clamped)) {
                    sink.addCompound(listOf(auxFlipMove, com.eignex.klause.solver.Move.IntSet(v, clamped)))
                }
            }
        }
    }

    private fun holds(sum: Int): Boolean = when (op) {
        LinearOp.LE -> sum <= bound
        LinearOp.EQ -> sum == bound
        LinearOp.GE -> sum >= bound
        LinearOp.NE -> sum != bound
    }

    private fun coeffOf(intVar: Int): Int = coeffLookup.coeffOf(intVar)

    private fun snapTarget(coeff: Int, sumWithout: Int, wantHolds: Boolean): Int? {
        // For the canonical "want sum_with_v op bound" direction (wantHolds=true) the snap is
        // the integer value that makes the equality hold. When wantHolds=false we snap to a
        // value that violates the predicate by one unit.
        val numerator = bound - sumWithout
        if (coeff == 0) return null
        val targetEq = numerator / coeff
        return when (op) {
            LinearOp.EQ -> when {
                wantHolds && numerator % coeff != 0 -> null

                // no integer satisfies coeff·v = numerator
                wantHolds -> targetEq

                else -> targetEq + 1
            }

            LinearOp.LE -> if (wantHolds) {
                if (coeff > 0) floorDiv(numerator, coeff) else ceilDiv(numerator, coeff)
            } else {
                if (coeff > 0) floorDiv(numerator, coeff) + 1 else ceilDiv(numerator, coeff) - 1
            }

            LinearOp.GE -> if (wantHolds) {
                if (coeff > 0) ceilDiv(numerator, coeff) else floorDiv(numerator, coeff)
            } else {
                if (coeff > 0) ceilDiv(numerator, coeff) - 1 else floorDiv(numerator, coeff) + 1
            }

            LinearOp.NE -> when {
                // wantHolds (sum ≠ bound): bump var to either side of the equality value.
                wantHolds -> if (numerator % coeff == 0) targetEq + 1 else null

                // !wantHolds (sum == bound): only feasible if numerator divisible by coeff.
                numerator % coeff == 0 -> targetEq

                else -> null
            }
        }
    }

    private fun floorDiv(a: Int, b: Int): Int {
        val q = a / b
        val r = a % b
        return if (r != 0 && (r xor b) < 0) q - 1 else q
    }

    private fun ceilDiv(a: Int, b: Int): Int {
        val q = a / b
        val r = a % b
        return if (r != 0 && (r xor b) >= 0) q + 1 else q
    }

    override val maintainsBreakMakeIncrementally: Boolean get() = true

    /** [boolVars] contains only [auxBoolVar], so a bool flip is always an aux flip. Flipping
     *  aux always toggles violation (sum unchanged), so the aux's own contribution simply
     *  swaps between break and make. */
    override fun updateBoolBreakMakeForFlip(state: LocalSearchState, factorId: Int, flippedVar: Int) {
        val nowViolated = state.assignment.boolValue(auxBoolVar) != holds(state.intPayload[factorId])
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
        val newSum = state.intPayload[factorId]
        val coeff = coeffOf(intVar)
        val newValue = state.assignment.intValue(intVar)
        val oldSum = newSum - coeff * (newValue - oldValue)
        val oldHolds = holds(oldSum)
        val newHolds = holds(newSum)
        if (oldHolds == newHolds) return // aux contribution unchanged
        val aux = state.assignment.boolValue(auxBoolVar)
        val oldViolated = aux != oldHolds
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
