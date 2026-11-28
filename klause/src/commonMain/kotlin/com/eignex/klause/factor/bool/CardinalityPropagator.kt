package com.eignex.klause.factor.bool

import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.PbAccumulator
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.moveBoolWatcher

internal class CardinalityPropagator(
    val boolVars: IntArray,
    val intVars: IntArray,
    val literals: IntArray,
    private val min: Int,
    private val max: Int,
) : Propagator {

    // -1 when `min + 1` exceeds the literal count, which the watched scheme can't represent.
    private val atLeastWatchSize: Int =
        if (min == 0) 0 else (min + 1).let { if (it > literals.size) -1 else it }

    private val atMostWatchSize: Int =
        if (max == literals.size) {
            0
        } else {
            (literals.size - max + 1).let {
                if (it > literals.size) -1 else it
            }
        }

    // Generalizes the two-watch scheme to cardinality (Chai and Kuehlmann, "A fast pseudo-Boolean
    // constraint solver", IEEE TCAD 24(3), 2005, https://doi.org/10.1109/TCAD.2004.842808):
    //  - At-least side: watch `min + 1` literals. When one becomes false with no non-false replacement,
    //    the remaining `min` must all be true.
    //  - At-most side: watch `n - max + 1` literals as non-true. When one becomes true with no non-true
    //    replacement, the remaining `n - max` must all be false.
    // Null falls back to the scanner when a side wants more watches than literals or both sides are trivial.
    override val initialBoolWatchers: IntArray? = run {
        if (atLeastWatchSize < 0 || atMostWatchSize < 0) return@run null
        if (atLeastWatchSize == 0 && atMostWatchSize == 0) return@run null
        val out = IntArray(atLeastWatchSize + atMostWatchSize)
        var w = 0
        // At-least watch set: the first `min + 1` literals.
        for (i in 0 until atLeastWatchSize) out[w++] = literals[i]
        // At-most watch set: negations of the first `n - max + 1` positions.
        for (i in 0 until atMostWatchSize) out[w++] = Lit.negate(literals[i])
        out
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean = if (initialBoolWatchers != null) {
        propagateWatched(state, factorId)
    } else {
        propagateScanning(state)
    }

    // A min-side failure is explained by the false literals, a max-side failure by the negated true ones.
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        var trueCount = 0
        var falseCount = 0
        for (lit in literals) {
            val b = state.boolValues[Lit.variable(lit)] ?: continue
            if (Lit.evaluate(lit, b)) trueCount++ else falseCount++
        }
        if (literals.size - falseCount < min) {
            val out = IntArray(falseCount)
            var w = 0
            for (lit in literals) {
                val b = state.boolValues[Lit.variable(lit)] ?: continue
                if (!Lit.evaluate(lit, b)) out[w++] = lit
            }
            return out
        }
        if (trueCount > max) {
            val out = IntArray(trueCount)
            var w = 0
            for (lit in literals) {
                val b = state.boolValues[Lit.variable(lit)] ?: continue
                if (Lit.evaluate(lit, b)) out[w++] = Lit.negate(lit)
            }
            return out
        }
        return null
    }

    // Loads the relevant half as a `≥` reason for cutting-planes analysis: a forced-true own literal selects
    // `Σ ℓ ≥ min`, a forced-false one `Σ ¬ℓ ≥ n − max`. A seed (`forcedLit == Lit.NONE`) picks the violated side.
    fun loadReason(acc: PbAccumulator, forcedLit: Int, state: PropagationState): Boolean {
        val n = literals.size
        val ones = LongArray(n) { 1L }
        fun loadAtLeast() = if (min <= 0) false else acc.loadPb(ones, literals, geBound = min.toLong())
        fun loadAtMost(): Boolean {
            if (max >= n) return false
            val flipped = IntArray(n) { literals[it] xor 1 }
            return acc.loadPb(ones, flipped, geBound = (n - max).toLong())
        }
        if (forcedLit == Lit.NONE) {
            // Seed: pick the violated bound from the current assignment.
            var trueCount = 0
            var nonFalse = 0
            for (lit in literals) {
                val b = state.boolValues[Lit.variable(lit)]
                if (b == null) {
                    nonFalse++
                } else if (Lit.evaluate(lit, b)) {
                    trueCount++
                    nonFalse++
                }
            }
            return when {
                trueCount > max -> loadAtMost()
                nonFalse < min -> loadAtLeast()
                else -> false
            }
        }
        val v = Lit.variable(forcedLit)
        var occ = 0
        var found = false
        for (lit in literals) {
            if (Lit.variable(lit) == v) {
                occ = lit
                found = true
                break
            }
        }
        if (!found) return false
        return if (occ == forcedLit) loadAtLeast() else loadAtMost()
    }

    // At-least pins are explained by the false literals, at-most pins by the negated true literals.
    private fun antecedentsForPin(state: PropagationState, excludeVar: Int, collectTrue: Boolean): IntArray? {
        var n = 0
        for (lit in literals) {
            val v = Lit.variable(lit)
            if (v == excludeVar) continue
            val b = state.boolValues[v] ?: continue
            if (Lit.evaluate(lit, b) == collectTrue) n++
        }
        if (n == 0) return null
        val out = IntArray(n)
        var w = 0
        for (lit in literals) {
            val v = Lit.variable(lit)
            if (v == excludeVar) continue
            val b = state.boolValues[v] ?: continue
            if (Lit.evaluate(lit, b) == collectTrue) out[w++] = if (collectTrue) Lit.negate(lit) else lit
        }
        return out
    }

    // [PropagationState.refPayload] holds the at-least watch indices in `[0, atLeastWatchSize)` and the
    // at-most ones after them.
    private fun propagateWatched(state: PropagationState, factorId: Int): Boolean {
        val watches = (state.refPayload[factorId] as IntArray?) ?: run {
            val w = IntArray(atLeastWatchSize + atMostWatchSize) { i ->
                if (i < atLeastWatchSize) i else i - atLeastWatchSize
            }
            state.refPayload[factorId] = w
            w
        }
        if (atLeastWatchSize > 0 &&
            !propagateSide(state, factorId, watches, 0, atLeastWatchSize, atLeast = true)
        ) {
            return false
        }
        if (atMostWatchSize > 0 &&
            !propagateSide(
                state,
                factorId,
                watches,
                atLeastWatchSize,
                atLeastWatchSize + atMostWatchSize,
                atLeast = false,
            )
        ) {
            return false
        }
        return true
    }

    // The two sides mirror under literal negation: a watcher at the bad value (false for at-least, true for
    // at-most) moves to an outside literal not at that value, or else the other watched literals are pinned.
    private fun propagateSide(
        state: PropagationState,
        factorId: Int,
        watches: IntArray,
        start: Int,
        end: Int,
        atLeast: Boolean,
    ): Boolean {
        val triggerValue = !atLeast
        for (i in start until end) {
            if (!litIs(state, watches[i], triggerValue)) continue
            val rep = findNonOutside(state, watches, start, end, triggerValue)
            if (rep < 0) return unitPinWatched(state, watches, start, end, i, pinTrue = atLeast)
            val from = if (atLeast) literals[watches[i]] else Lit.negate(literals[watches[i]])
            val to = if (atLeast) literals[rep] else Lit.negate(literals[rep])
            state.moveBoolWatcher(factorId, from, to)
            watches[i] = rep
        }
        return true
    }

    private fun findNonOutside(state: PropagationState, watches: IntArray, start: Int, end: Int, value: Boolean): Int {
        outer@ for (i in literals.indices) {
            for (w in start until end) if (watches[w] == i) continue@outer
            if (!litIs(state, i, value)) return i
        }
        return -1
    }

    private fun unitPinWatched(
        state: PropagationState,
        watches: IntArray,
        start: Int,
        end: Int,
        skipIdx: Int,
        pinTrue: Boolean,
    ): Boolean {
        for (i in start until end) {
            if (i == skipIdx) continue
            val lit = literals[watches[i]]
            val v = Lit.variable(lit)
            val b = state.boolValues[v]
            if (b == null) {
                val ant = antecedentsForPin(state, v, collectTrue = !pinTrue)
                if (!state.pinBool(v, Lit.isPositive(lit) == pinTrue, ant)) return false
            } else if (Lit.evaluate(lit, b) != pinTrue) {
                return false
            }
        }
        return true
    }

    private fun litIs(state: PropagationState, idx: Int, value: Boolean): Boolean {
        val lit = literals[idx]
        val b = state.boolValues[Lit.variable(lit)] ?: return false
        return Lit.evaluate(lit, b) == value
    }

    // O(n) per fire, for shapes the watched scheme can't represent.
    private fun propagateScanning(state: PropagationState): Boolean {
        var trueCount = 0
        var falseCount = 0
        for (lit in literals) {
            val v = Lit.variable(lit)
            val b = state.boolValues[v] ?: continue
            if (Lit.evaluate(lit, b)) trueCount++ else falseCount++
        }
        val unassigned = literals.size - trueCount - falseCount
        if (trueCount > max) return false
        if (trueCount + unassigned < min) return false
        if (trueCount == max && unassigned > 0) {
            val ant = antecedentsForPin(state, excludeVar = -1, collectTrue = true)
            for (lit in literals) {
                val v = Lit.variable(lit)
                if (state.boolValues[v] != null) continue
                if (!state.pinBool(v, !Lit.isPositive(lit), ant)) return false
            }
            return true
        }
        if (trueCount + unassigned == min && unassigned > 0) {
            val ant = antecedentsForPin(state, excludeVar = -1, collectTrue = false)
            for (lit in literals) {
                val v = Lit.variable(lit)
                if (state.boolValues[v] != null) continue
                if (!state.pinBool(v, Lit.isPositive(lit), ant)) return false
            }
        }
        return true
    }
}
