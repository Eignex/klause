package com.eignex.klause.solver.factor

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move.BoolFlip
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.IntIntMap

/**
 * `Σ weights`i` * lit_i ⟨op⟩ bound` over Boolean literals (each contributing its weight when
 * true, 0 when false). Payload at `intPayload[factorId]` is the current weighted sum.
 */
class PseudoBoolean(
    /** Weights, parallel to [literals]. */
    val weights: IntArray,
    /** Boolean literals contributing their weight when true. */
    val literals: IntArray,
    /** Relation between the weighted sum and [bound]. */
    val op: PbOp,
    /** Right-hand-side bound. */
    val bound: Int,
) : Factor {

    init {
        require(weights.size == literals.size) { "weights/literals length mismatch" }
        require(weights.isNotEmpty()) { "PseudoBoolean must have at least one term" }
    }

    override fun structuralKey(): String = "pb:$op:$bound:" + literals.indices.sortedBy { literals[it] }.joinToString(
        ",",
    ) { "${literals[it]}=${weights[it]}" }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        PseudoBoolean(weights, literals.remapLits(boolMap), op, bound)

    override val boolVars: IntArray = literals.litVars()
    override val intVars: IntArray = EmptyIntArray

    /** Sum of `weight`i` * sign(literals`i`)` per Boolean variable. Flipping `v` shifts
     *  the running sum by `(if v_was_true then -signed[v] else +signed[v])`, computed in
     *  O(1) instead of scanning every literal in the factor. */
    private val signedWeightByVar: IntIntMap = buildSignedWeightByVar(weights, literals)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        var sum = 0L
        for (i in literals.indices) {
            if (Lit.evaluate(literals[i], state.assignment.boolValue(Lit.variable(literals[i])))) {
                sum += weights[i].toLong()
            }
        }
        state.longPayload[factorId] = sum
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = violates(state.longPayload[factorId])

    /** Graded violation: the weighted-sum residual `distance(sum)`, compressed — gives CBLS a
     *  gradient toward the bound instead of a flat boolean. */
    private fun degreeOf(sum: Long, softCap: Int): Int = compressViolation(distance(sum), softCap)

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        degreeOf(state.longPayload[factorId], state.violationSoftCap)

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val change = changeOnFlip(state, boolVar, current = true)
        val sum = state.longPayload[factorId]
        return degreeOf(sum + change, state.violationSoftCap) - degreeOf(sum, state.violationSoftCap)
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val change = changeOnFlip(state, boolVar, current = false)
        val oldSum = state.longPayload[factorId]
        val newSum = oldSum + change
        state.longPayload[factorId] = newSum
        return degreeOf(newSum, state.violationSoftCap) - degreeOf(oldSum, state.violationSoftCap)
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean =
        propagatePbBounds(state, weights, literals, op, bound.toLong())

    /** Clause-form nogood when propagation fails: the disjunction of each pinned
     *  literal's false-form. The current pinning across the constraint's literals
     *  forced the sum into the infeasible range; flipping any one of them is a
     *  necessary condition for a satisfying solution. Sound but coarse — analyzer
     *  minimization typically trims redundant literals during 1UIP resolution. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        pbFalseFormAntecedents(state, literals, excludeVar = -1, extraLit = 0)

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val sum = state.longPayload[factorId]
        if (!violates(sum)) return
        val curDist = distance(sum)
        for (i in literals.indices) {
            val lit = literals[i]
            val v = Lit.variable(lit)
            val isTrue = Lit.evaluate(lit, state.assignment.boolValue(v))
            val change = if (isTrue) -weights[i] else weights[i]
            // Propose any flip that doesn't worsen the violation distance, even neutral flips.
            // Strict reduction is too restrictive on tight constraints where no single flip
            // crosses the boundary; tabu and probSAT scoring break the resulting cycles.
            if (distance(sum + change) <= curDist) sink.addBoolFlip(v)
        }
    }

    /** Self-preserving moves during objective descent. For PB the natural structured move
     *  is a "swap two literals with equal effective weights": flip a true literal i and a
     *  false literal j with `effectiveWeight(i) == effectiveWeight(j)`, where the
     *  effective weight is `weights`i`` for positive lits and `-weights`i`` for negative.
     *  The sum stays unchanged so any feasible op (LE/GE/EQ) remains feasible. The
     *  engine scores each by objective delta and applies the best improving one.
     *
     *  For LE with slack, also propose unilateral flips that consume some slack: any
     *  true positive-lit can be flipped to false (sum decreases), any false negative-lit
     *  can be flipped to true (sum also decreases) — both safe under LE. Symmetric under
     *  GE. */
    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (literals.size < 2) return
        val sum = state.longPayload[factorId]
        // Group literals by their (effective weight, current truth value). Effective
        // weight: positive lit → +weights`i`; negative lit → -weights`i`.
        // Sum-preserving swap: true-effwt-W + false-effwt-W. Equivalently match on
        // signed weight magnitude AND opposing truth states.
        val trueByWeight = HashMap<Int, IntArrayList>()
        val falseByWeight = HashMap<Int, IntArrayList>()
        for (i in literals.indices) {
            val lit = literals[i]
            val v = Lit.variable(lit)
            val isTrue = Lit.evaluate(lit, state.assignment.boolValue(v))
            val effW = if (Lit.isPositive(lit)) weights[i] else -weights[i]
            val bucket = if (isTrue) trueByWeight else falseByWeight
            bucket.getOrPut(effW) { IntArrayList() }.add(v)
        }
        var proposed = 0
        outer@ for ((w, trueVars) in trueByWeight) {
            val falseVars = falseByWeight[w] ?: continue
            for (i in 0 until trueVars.size) {
                for (j in 0 until falseVars.size) {
                    if (trueVars[i] == falseVars[j]) continue // same var — degenerate
                    sink.addCompound(
                        listOf(
                            BoolFlip(trueVars[i]),
                            BoolFlip(falseVars[j]),
                        ),
                    )
                    proposed++
                    if (proposed >= PAIR_PROPOSAL_CAP) break@outer
                }
            }
        }
        // Slack-consuming unilateral flips. Only when the inequality has positive slack.
        val slack = when (op) {
            PbOp.LE -> bound - sum
            PbOp.GE -> sum - bound
            PbOp.EQ -> 0L
        }
        if (slack > 0L) {
            for (i in literals.indices) {
                val lit = literals[i]
                val v = Lit.variable(lit)
                val isTrue = Lit.evaluate(lit, state.assignment.boolValue(v))
                val change = if (isTrue) -weights[i] else weights[i]
                val effChange = if (Lit.isPositive(lit)) change else -change
                val newSum = sum + effChange
                if (op == PbOp.LE && newSum <= bound) {
                    sink.addBoolFlip(v)
                } else if (op == PbOp.GE && newSum >= bound) {
                    sink.addBoolFlip(v)
                }
            }
        }
    }

    private fun violates(sum: Long): Boolean = !pbHolds(sum, op, bound)

    private fun distance(sum: Long): Long = pbDistance(sum, op, bound)

    private fun changeOnFlip(state: LocalSearchState, boolVar: Int, current: Boolean): Int {
        val signed = signedWeightByVar[boolVar]
        if (signed == 0) return 0
        // If we want the delta against the *current* value of `boolVar`: flipping from
        // true → false contributes `-signed`; false → true contributes `+signed`. If we
        // want the delta from the *pre*-flip value (i.e. the engine has already committed
        // the flip), the polarity inverts.
        val pre = if (current) {
            state.assignment.boolValue(boolVar)
        } else {
            !state.assignment.boolValue(boolVar)
        }
        return if (pre) -signed else signed
    }

    override val maintainsBreakMakeIncrementally: Boolean get() = true

    /** O(arity) — replaces the engine's two `deltaIfBoolFlipped`-driven passes (each itself
     *  O(arity)) with a single per-var diff. Computes pre- vs post-flip break/make
     *  contributions from [signedWeightByVar] and applies only the changes. */
    override fun updateBoolBreakMakeForFlip(state: LocalSearchState, factorId: Int, flippedVar: Int) {
        val signedFlipped = signedWeightByVar[flippedVar]
        if (signedFlipped == 0) return
        val newSum = state.longPayload[factorId]
        val flippedPost = state.assignment.boolValue(flippedVar)
        val changeV = if (flippedPost) signedFlipped else -signedFlipped
        val oldSum = newSum - changeV
        for (u in boolVars) {
            val signedU = signedWeightByVar[u]
            if (signedU == 0) continue
            val uPost = state.assignment.boolValue(u)
            val uPre = if (u == flippedVar) !uPost else uPost
            val oldChangeU = if (uPre) -signedU else signedU
            val newChangeU = if (uPost) -signedU else signedU
            // Break/make track the sign of the graded Δ each var's flip would produce (the same
            // value deltaIfBoolFlipped returns), evaluated against the pre- and post-flip sums.
            val preDelta = degreeOf(oldSum + oldChangeU, state.violationSoftCap) -
                degreeOf(oldSum, state.violationSoftCap)
            val postDelta = degreeOf(newSum + newChangeU, state.violationSoftCap) -
                degreeOf(newSum, state.violationSoftCap)
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

    private companion object {
        const val PAIR_PROPOSAL_CAP: Int = 32
    }
}

/**
 * Range `[sumLo, sumHi]` reachable by `Σ weights`i` * lit_i` given current pins.
 *
 * Per-literal contribution: `{0, w}` (or `{w, 0}` for negative weights) when unassigned;
 * `{w}` when literal pinned true; `{0}` when pinned false.
 */
internal fun pbSumRange(state: PropagationState, weights: IntArray, literals: IntArray): LongArray {
    var lo = 0L
    var hi = 0L
    for (i in literals.indices) {
        val w = weights[i].toLong()
        val v = Lit.variable(literals[i])
        val b = state.boolValues[v]
        when {
            b == null -> {
                lo += minOf(0L, w)
                hi += maxOf(0L, w)
            }

            Lit.evaluate(literals[i], b) -> {
                lo += w
                hi += w
            }

            else -> { /* contributes 0 */ }
        }
    }
    return longArrayOf(lo, hi)
}

/**
 * Build a clause-form antecedent set for a pin emitted by [propagatePbBounds] /
 * `propagatePbNotEqual`: each currently-pinned constraint literal (excluding the var
 * about to be pinned, which is still unassigned) expressed in its currently-*false*
 * polarity, plus an optional context literal (e.g. the reif var for
 * [ReifiedPseudoBoolean]). Returns `null` when nothing was pinned and no context lit —
 * meaning the pin is a level-0 fact with no logical preconditions.
 */
internal fun pbFalseFormAntecedents(
    state: PropagationState,
    literals: IntArray,
    excludeVar: Int,
    extraLit: Int, // 0 == no extra literal
): IntArray? {
    var n = 0
    if (extraLit != 0) n++
    val seen = IntHashSet()
    for (lit in literals) {
        val v = Lit.variable(lit)
        if (v == excludeVar) continue
        if (extraLit != 0 && v == Lit.variable(extraLit)) continue
        if (!seen.add(v)) continue
        if (state.boolValues[v] != null) n++
    }
    if (n == 0) return null
    val out = IntArray(n)
    var w = 0
    if (extraLit != 0) out[w++] = extraLit
    seen.clear()
    for (lit in literals) {
        val v = Lit.variable(lit)
        if (v == excludeVar) continue
        if (extraLit != 0 && v == Lit.variable(extraLit)) continue
        if (!seen.add(v)) continue
        val b = state.boolValues[v] ?: continue
        out[w++] = Lit.make(v, !b)
    }
    return out
}

/**
 * Shared bounds-propagation routine for `Σ weights`i` * lit_i ⟨op⟩ bound`. Used by
 * [PseudoBoolean] directly and by [ReifiedPseudoBoolean] when its aux Boolean is pinned.
 * Returns `false` iff the constraint is infeasible. [extraLit] is an optional context
 * literal (currently false in state) to include in every pin's antecedents — used by
 * [ReifiedPseudoBoolean] to thread its reif-var pin into each implied propagation.
 */
internal fun propagatePbBounds(
    state: PropagationState,
    weights: IntArray,
    literals: IntArray,
    op: PbOp,
    bound: Long,
    extraLit: Int = 0,
): Boolean {
    val n = literals.size
    val litLo = LongArray(n)
    val litHi = LongArray(n)
    var sumLo = 0L
    var sumHi = 0L
    for (i in 0 until n) {
        val w = weights[i].toLong()
        val v = Lit.variable(literals[i])
        val b = state.boolValues[v]
        val lo: Long
        val hi: Long
        when {
            b == null -> {
                lo = minOf(0L, w)
                hi = maxOf(0L, w)
            }

            Lit.evaluate(literals[i], b) -> {
                lo = w
                hi = w
            }

            else -> {
                lo = 0L
                hi = 0L
            }
        }
        litLo[i] = lo
        litHi[i] = hi
        sumLo += lo
        sumHi += hi
    }
    when (op) {
        PbOp.LE -> if (sumLo > bound) return false
        PbOp.GE -> if (sumHi < bound) return false
        PbOp.EQ -> if (sumLo > bound || sumHi < bound) return false
    }
    for (i in 0 until n) {
        val w = weights[i].toLong()
        if (w == 0L) continue
        val v = Lit.variable(literals[i])
        if (state.boolValues[v] != null) continue
        val otherLo = sumLo - litLo[i]
        val otherHi = sumHi - litHi[i]
        val trueOk = pbFeasible(op, otherLo + w, otherHi + w, bound)
        val falseOk = pbFeasible(op, otherLo, otherHi, bound)
        if (!trueOk && !falseOk) return false
        if (!trueOk) {
            val ant = pbFalseFormAntecedents(state, literals, excludeVar = v, extraLit = extraLit)
            if (!state.pinBool(v, !Lit.isPositive(literals[i]), ant)) return false
        } else if (!falseOk) {
            val ant = pbFalseFormAntecedents(state, literals, excludeVar = v, extraLit = extraLit)
            if (!state.pinBool(v, Lit.isPositive(literals[i]), ant)) return false
        }
    }
    return true
}

private fun pbFeasible(op: PbOp, lo: Long, hi: Long, bound: Long): Boolean = when (op) {
    PbOp.LE -> lo <= bound
    PbOp.GE -> hi >= bound
    PbOp.EQ -> lo <= bound && hi >= bound
}
