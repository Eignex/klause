package com.eignex.klause.solver.factor.bool

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.solver.propagation.moveBoolWatcher

/** CP propagator for [Cardinality]: generalised watched-literal propagation for `min ≤ count ≤ max`. */
internal class CardinalityPropagator(
    override val boolVars: IntArray,
    override val intVars: IntArray,
    private val literals: IntArray,
    private val min: Int,
    private val max: Int,
) : Propagator {

    /** Number of literals to watch on the at-least-min side (0 if min == 0, else min+1).
     *  Capped to [literals].size; oversize means "all literals must be true" which the
     *  watched scheme can't represent, in which case [initialBoolWatchers] returns null
     *  and the general scanner handles it. */
    private val atLeastWatchSize: Int =
        if (min == 0) 0 else (min + 1).let { if (it > literals.size) -1 else it }

    /** Mirror of [atLeastWatchSize] for the at-most-max side. */
    private val atMostWatchSize: Int =
        if (max == literals.size) {
            0
        } else {
            (literals.size - max + 1).let {
                if (it > literals.size) -1 else it
            }
        }

    /**
     * Watched-literal opt-in. Generalises clause's two-watch scheme to cardinality:
     *  - At-least-min side: watch `min + 1` literals; the constraint stays alive while
     *    all are non-false. When one becomes false and no non-false replacement exists,
     *    the remaining `min` must all be true → unit-propagate them.
     *  - At-most-max side: symmetric, on literal negations — watch `n - max + 1`
     *    literals as non-true candidates; when one becomes true and no non-true
     *    replacement exists, the remaining `n - max` must all stay false → force them.
     *
     * Falls back to occurrence-list wakeup (returns `null`) when either side wants
     * more watches than the clause has literals (degenerate: `min == n` or `max == 0`),
     * or when both sides are trivial (`min == 0 && max == n`). The general scanner in
     * [propagate] handles those cases correctly without watched-literal bookkeeping.
     */
    override val initialBoolWatchers: IntArray? = run {
        if (atLeastWatchSize < 0 || atMostWatchSize < 0) return@run null
        if (atLeastWatchSize == 0 && atMostWatchSize == 0) return@run null
        val out = IntArray(atLeastWatchSize + atMostWatchSize)
        var w = 0
        // At-least watch set: positive literals at the first `min + 1` positions.
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

    /**
     * Clause-form nogood when [propagate] returns false. Two failure modes:
     *  - min-side: `trueCount + unassigned < min` — too many literals are forced false to
     *    ever reach `min` true. The disjunction of currently-false literals must contain
     *    at least one truth.
     *  - max-side: `trueCount > max` — too many literals are forced true. Their negations
     *    form the violated disjunction (at least one must actually be false).
     */
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

    /** Collect every currently-false literal in [literals] whose variable differs from
     *  [excludeVar]. Returned as the antecedents array for a pin: their collectively being
     *  false is exactly what forced the pin. Returns `null` if nothing to record. */
    private fun antecedentsForTruePin(state: PropagationState, excludeVar: Int): IntArray? {
        var n = 0
        for (lit in literals) {
            val v = Lit.variable(lit)
            if (v == excludeVar) continue
            val b = state.boolValues[v] ?: continue
            if (!Lit.evaluate(lit, b)) n++
        }
        if (n == 0) return null
        val out = IntArray(n)
        var w = 0
        for (lit in literals) {
            val v = Lit.variable(lit)
            if (v == excludeVar) continue
            val b = state.boolValues[v] ?: continue
            if (!Lit.evaluate(lit, b)) out[w++] = lit
        }
        return out
    }

    /** Mirror of [antecedentsForTruePin] for at-most pins: antecedents are the negations
     *  of currently-true literals, since their being true forced this pin to false. */
    private fun antecedentsForFalsePin(state: PropagationState, excludeVar: Int): IntArray? {
        var n = 0
        for (lit in literals) {
            val v = Lit.variable(lit)
            if (v == excludeVar) continue
            val b = state.boolValues[v] ?: continue
            if (Lit.evaluate(lit, b)) n++
        }
        if (n == 0) return null
        val out = IntArray(n)
        var w = 0
        for (lit in literals) {
            val v = Lit.variable(lit)
            if (v == excludeVar) continue
            val b = state.boolValues[v] ?: continue
            if (Lit.evaluate(lit, b)) out[w++] = Lit.negate(lit)
        }
        return out
    }

    /**
     * Watched-literal propagation. The watches array packed into [PropagationState.refPayload]
     * stores the at-least watch indices in `[0, atLeastWatchSize)` and the at-most watch
     * indices in `[atLeastWatchSize, atLeastWatchSize + atMostWatchSize)`. Each side runs
     * a scan-and-replace pass; failures unit-propagate the remaining watched literals.
     */
    private fun propagateWatched(state: PropagationState, factorId: Int): Boolean {
        val watches = (state.refPayload[factorId] as IntArray?) ?: run {
            val w = IntArray(atLeastWatchSize + atMostWatchSize) { i ->
                if (i < atLeastWatchSize) i else i - atLeastWatchSize
            }
            state.refPayload[factorId] = w
            w
        }
        if (atLeastWatchSize > 0 &&
            !propagateAtLeastSide(state, factorId, watches, 0, atLeastWatchSize)
        ) {
            return false
        }
        if (atMostWatchSize > 0 &&
            !propagateAtMostSide(
                state,
                factorId,
                watches,
                atLeastWatchSize,
                atLeastWatchSize + atMostWatchSize,
            )
        ) {
            return false
        }
        return true
    }

    /** Propagates the at-least side: pins unwatched literals to true when all watchers are false. */
    private fun propagateAtLeastSide(
        state: PropagationState,
        factorId: Int,
        watches: IntArray,
        start: Int,
        end: Int,
    ): Boolean {
        for (i in start until end) {
            if (!litFalseAt(state, watches[i])) continue
            val rep = findNonFalseOutside(state, watches, start, end)
            if (rep < 0) {
                return unitPinWatchedToTrue(state, watches, start, end, i)
            }
            state.moveBoolWatcher(factorId, literals[watches[i]], literals[rep])
            watches[i] = rep
        }
        return true
    }

    /** Propagates the at-most side: pins unwatched literals to false when all watchers are true. */
    private fun propagateAtMostSide(
        state: PropagationState,
        factorId: Int,
        watches: IntArray,
        start: Int,
        end: Int,
    ): Boolean {
        for (i in start until end) {
            if (!litTrueAt(state, watches[i])) continue
            val rep = findNonTrueOutside(state, watches, start, end)
            if (rep < 0) {
                return unitPinWatchedToFalse(state, watches, start, end, i)
            }
            state.moveBoolWatcher(
                factorId,
                Lit.negate(literals[watches[i]]),
                Lit.negate(literals[rep]),
            )
            watches[i] = rep
        }
        return true
    }

    /** Returns the index of a non-false literal outside the watched range, or -1 if none. */
    private fun findNonFalseOutside(state: PropagationState, watches: IntArray, start: Int, end: Int): Int {
        outer@ for (i in literals.indices) {
            for (w in start until end) if (watches[w] == i) continue@outer
            if (!litFalseAt(state, i)) return i
        }
        return -1
    }

    /** Returns the index of a non-true literal outside the watched range, or -1 if none. */
    private fun findNonTrueOutside(state: PropagationState, watches: IntArray, start: Int, end: Int): Int {
        outer@ for (i in literals.indices) {
            for (w in start until end) if (watches[w] == i) continue@outer
            if (!litTrueAt(state, i)) return i
        }
        return -1
    }

    /** Pins all watched literals except the one at [skipIdx] to true; returns false on conflict. */
    private fun unitPinWatchedToTrue(
        state: PropagationState,
        watches: IntArray,
        start: Int,
        end: Int,
        skipIdx: Int,
    ): Boolean {
        for (i in start until end) {
            if (i == skipIdx) continue
            val lit = literals[watches[i]]
            val v = Lit.variable(lit)
            val b = state.boolValues[v]
            if (b == null) {
                val ant = antecedentsForTruePin(state, v)
                if (!state.pinBool(v, Lit.isPositive(lit), ant)) return false
            } else if (!Lit.evaluate(lit, b)) {
                return false
            }
        }
        return true
    }

    /** Pins all watched literals except the one at [skipIdx] to false; returns false on conflict. */
    private fun unitPinWatchedToFalse(
        state: PropagationState,
        watches: IntArray,
        start: Int,
        end: Int,
        skipIdx: Int,
    ): Boolean {
        for (i in start until end) {
            if (i == skipIdx) continue
            val lit = literals[watches[i]]
            val v = Lit.variable(lit)
            val b = state.boolValues[v]
            if (b == null) {
                val ant = antecedentsForFalsePin(state, v)
                if (!state.pinBool(v, !Lit.isPositive(lit), ant)) return false
            } else if (Lit.evaluate(lit, b)) {
                return false
            }
        }
        return true
    }

    /** Returns true iff literal at index [idx] is currently assigned true. */
    private fun litTrueAt(state: PropagationState, idx: Int): Boolean {
        val lit = literals[idx]
        val b = state.boolValues[Lit.variable(lit)] ?: return false
        return Lit.evaluate(lit, b)
    }

    /** Returns true iff literal at index [idx] is currently assigned false. */
    private fun litFalseAt(state: PropagationState, idx: Int): Boolean {
        val lit = literals[idx]
        val b = state.boolValues[Lit.variable(lit)] ?: return false
        return !Lit.evaluate(lit, b)
    }

    /**
     * Fallback scanner — O(n) per fire, used when the constraint shape can't be watched
     * (`min == n` "all must be true", `max == 0` "none can be true", or the trivial
     * `min == 0 && max == n`). Kept verbatim from the pre-watched-literal implementation
     * since these shapes typically have small or degenerate [literals].
     */
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
            val ant = antecedentsForFalsePin(state, excludeVar = -1)
            for (lit in literals) {
                val v = Lit.variable(lit)
                if (state.boolValues[v] != null) continue
                if (!state.pinBool(v, !Lit.isPositive(lit), ant)) return false
            }
            return true
        }
        if (trueCount + unassigned == min && unassigned > 0) {
            val ant = antecedentsForTruePin(state, excludeVar = -1)
            for (lit in literals) {
                val v = Lit.variable(lit)
                if (state.boolValues[v] != null) continue
                if (!state.pinBool(v, Lit.isPositive(lit), ant)) return false
            }
        }
        return true
    }
}
