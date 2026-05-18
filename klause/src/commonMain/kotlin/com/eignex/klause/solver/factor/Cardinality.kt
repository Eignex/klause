package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.solver.localsearch.LocalSearchState

/**
 * `min ≤ (#true literals) ≤ max`. Payload at `intPayload[factorId]` is the count of true
 * literals. AtMostOne, AtLeastOne, ExactlyOne are special cases.
 */
class Cardinality(
    val literals: IntArray,
    val min: Int,
    val max: Int,
) : LocalSearchFactor {

    init {
        require(min in 0..max) { "Cardinality bounds invalid: $min..$max" }
        require(max <= literals.size) { "max ($max) exceeds literal count (${literals.size})" }
    }

    override val boolVars: IntArray = run {
        val seen = LinkedHashSet<Int>()
        for (lit in literals) seen.add(Lit.variable(lit))
        val out = IntArray(seen.size)
        var i = 0
        for (v in seen) out[i++] = v
        out
    }
    override val intVars: IntArray = EmptyIntArray

    /**
     * Number of literals to watch on the at-least-min side (0 if min == 0, else min+1).
     * Capped to [literals].size; oversize means "all literals must be true" which the
     * watched scheme can't represent, in which case [initialBoolWatchers] returns null
     * and the general scanner handles it. */
    private val atLeastWatchSize: Int =
        if (min == 0) 0 else (min + 1).let { if (it > literals.size) -1 else it }
    /** Mirror of [atLeastWatchSize] for the at-most-max side. */
    private val atMostWatchSize: Int =
        if (max == literals.size) 0 else (literals.size - max + 1).let {
            if (it > literals.size) -1 else it
        }

    /** Pre-computed map from a Boolean var id to the sum of polarity signs across every
     *  occurrence in [literals]. Each entry is `+1` for a positive literal occurrence,
     *  `-1` for a negative occurrence, summed if the var appears multiple times. The
     *  delta of flipping [boolVar] from `pre` to `!pre` is then
     *     `(if (pre then -1 else 1)) * signedOccurrencesByVar[boolVar]`
     *  computed in O(1) instead of scanning every literal in the factor. */
    private val signedOccurrencesByVar: com.eignex.klause.util.IntIntMap = run {
        val signs = HashMap<Int, Int>()
        for (lit in literals) {
            val v = Lit.variable(lit)
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
        val n = state.intPayload[factorId]
        return n < min || n > max
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val pre = state.assignment.boolValue(boolVar)
        // Flipping `boolVar` from `pre` to `!pre` shifts each occurrence's truth by
        // ±1; the per-occurrence sign is +1 for positive literals, -1 for negatives.
        // Aggregated as `signedOccurrencesByVar`; the direction depends on `pre`.
        val signed = signedOccurrencesByVar[boolVar]
        if (signed == 0) return 0
        val change = if (pre) -signed else signed
        val n = state.intPayload[factorId]
        val newN = n + change
        val wasViolated = n < min || n > max
        val willViolate = newN < min || newN > max
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val post = state.assignment.boolValue(boolVar)
        val signed = signedOccurrencesByVar[boolVar]
        if (signed == 0) return 0
        val change = if (post) signed else -signed
        val oldN = state.intPayload[factorId]
        val newN = oldN + change
        state.intPayload[factorId] = newN
        val wasViolated = oldN < min || oldN > max
        val willViolate = newN < min || newN > max
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
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

    override fun propagate(state: PropagationState, factorId: Int): Boolean =
        if (initialBoolWatchers != null) propagateWatched(state, factorId)
        else propagateScanning(state)

    /**
     * Watched-literal propagation. The watches array packed into [PropagationState.refPayload]
     * stores the at-least watch indices in `[0, atLeastWatchSize)` and the at-most watch
     * indices in `[atLeastWatchSize, atLeastWatchSize + atMostWatchSize)`. Each side runs
     * a scan-and-replace pass; failures unit-propagate the remaining watched literals.
     */
    private fun propagateWatched(state: PropagationState, factorId: Int): Boolean {
        val watches = (state.refPayload[factorId] as IntArray?) ?: run {
            // Initial placement mirrors `initialBoolWatchers` exactly so the state's wakeup
            // index and the cached watches agree from the start.
            val w = IntArray(atLeastWatchSize + atMostWatchSize) { i ->
                if (i < atLeastWatchSize) i else i - atLeastWatchSize
            }
            state.refPayload[factorId] = w
            w
        }
        if (atLeastWatchSize > 0 &&
            !propagateAtLeastSide(state, factorId, watches, 0, atLeastWatchSize)
        ) return false
        if (atMostWatchSize > 0 &&
            !propagateAtMostSide(state, factorId, watches, atLeastWatchSize,
                atLeastWatchSize + atMostWatchSize)
        ) return false
        return true
    }

    private fun propagateAtLeastSide(
        state: PropagationState, factorId: Int, watches: IntArray, start: Int, end: Int,
    ): Boolean {
        for (i in start until end) {
            if (!litFalseAt(state, watches[i])) continue
            val rep = findNonFalseOutside(state, watches, start, end)
            if (rep < 0) {
                // No replacement → non-watched literals are all false. Other watched
                // literals (excluding the failing one) must all be true to meet `min`.
                return unitPinWatchedToTrue(state, watches, start, end, i)
            }
            state.moveBoolWatcher(factorId, literals[watches[i]], literals[rep])
            watches[i] = rep
        }
        return true
    }

    private fun propagateAtMostSide(
        state: PropagationState, factorId: Int, watches: IntArray, start: Int, end: Int,
    ): Boolean {
        for (i in start until end) {
            if (!litTrueAt(state, watches[i])) continue
            val rep = findNonTrueOutside(state, watches, start, end)
            if (rep < 0) {
                // No replacement → non-watched literals are all true. Other watched
                // literals must all stay non-true (i.e., be forced false) to meet `max`.
                return unitPinWatchedToFalse(state, watches, start, end, i)
            }
            // Watcher index is keyed on the *negation* — that's the lit that transitions
            // to false when the underlying literal becomes true.
            state.moveBoolWatcher(factorId,
                Lit.negate(literals[watches[i]]), Lit.negate(literals[rep]))
            watches[i] = rep
        }
        return true
    }

    private fun findNonFalseOutside(
        state: PropagationState, watches: IntArray, start: Int, end: Int,
    ): Int {
        outer@ for (i in literals.indices) {
            for (w in start until end) if (watches[w] == i) continue@outer
            if (!litFalseAt(state, i)) return i
        }
        return -1
    }

    private fun findNonTrueOutside(
        state: PropagationState, watches: IntArray, start: Int, end: Int,
    ): Int {
        outer@ for (i in literals.indices) {
            for (w in start until end) if (watches[w] == i) continue@outer
            if (!litTrueAt(state, i)) return i
        }
        return -1
    }

    private fun unitPinWatchedToTrue(
        state: PropagationState, watches: IntArray, start: Int, end: Int, skipIdx: Int,
    ): Boolean {
        for (i in start until end) {
            if (i == skipIdx) continue
            val lit = literals[watches[i]]
            val v = Lit.variable(lit)
            val b = state.boolValues[v]
            if (b == null) {
                if (!state.pinBool(v, Lit.isPositive(lit))) return false
            } else if (!Lit.evaluate(lit, b)) {
                return false  // already false → conflict; can't make it true
            }
        }
        return true
    }

    private fun unitPinWatchedToFalse(
        state: PropagationState, watches: IntArray, start: Int, end: Int, skipIdx: Int,
    ): Boolean {
        for (i in start until end) {
            if (i == skipIdx) continue
            val lit = literals[watches[i]]
            val v = Lit.variable(lit)
            val b = state.boolValues[v]
            if (b == null) {
                if (!state.pinBool(v, !Lit.isPositive(lit))) return false
            } else if (Lit.evaluate(lit, b)) {
                return false  // already true → conflict
            }
        }
        return true
    }

    private fun litTrueAt(state: PropagationState, idx: Int): Boolean {
        val lit = literals[idx]
        val b = state.boolValues[Lit.variable(lit)] ?: return false
        return Lit.evaluate(lit, b)
    }

    private fun litFalseAt(state: PropagationState, idx: Int): Boolean {
        val lit = literals[idx]
        val b = state.boolValues[Lit.variable(lit)] ?: return false
        return !Lit.evaluate(lit, b)
    }

    /**
     * Fallback scanner — O(n) per fire, used when the constraint shape can't be watched
     * (`min == n` "all must be true", `max == 0` "none can be true", or the trivial
     * `min == 0 && max == n`). Kept verbatim from the pre-watched-literal implementation
     * since these shapes typically have small or degenerate `literals`.
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
            for (lit in literals) {
                val v = Lit.variable(lit)
                if (state.boolValues[v] != null) continue
                if (!state.pinBool(v, !Lit.isPositive(lit))) return false
            }
            return true
        }
        if (trueCount + unassigned == min && unassigned > 0) {
            for (lit in literals) {
                val v = Lit.variable(lit)
                if (state.boolValues[v] != null) continue
                if (!state.pinBool(v, Lit.isPositive(lit))) return false
            }
        }
        return true
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val n = state.intPayload[factorId]
        if (n in min..max) return
        val wantIncrease = n < min
        if (boolVars.size == literals.size) {
            // Each var appears in exactly one literal — flip helps iff the lit is currently false.
            for (lit in literals) {
                val v = Lit.variable(lit)
                val isTrue = Lit.evaluate(lit, state.assignment.boolValue(v))
                val helpsIncrease = !isTrue
                if (wantIncrease == helpsIncrease) sink.addBoolFlip(v)
            }
            return
        }
        // Repeated-var fallback — aggregate the per-variable net change.
        for (v in boolVars) {
            var netChange = 0
            for (lit in literals) {
                if (Lit.variable(lit) != v) continue
                netChange += if (Lit.evaluate(lit, state.assignment.boolValue(v))) -1 else +1
            }
            if (wantIncrease && netChange > 0) sink.addBoolFlip(v)
            else if (!wantIncrease && netChange < 0) sink.addBoolFlip(v)
        }
    }

    companion object {
        fun atMostOne(literals: IntArray): Cardinality =
            Cardinality(literals, min = 0, max = 1)

        fun atLeastOne(literals: IntArray): Cardinality =
            Cardinality(literals, min = 1, max = literals.size)

        fun exactlyOne(literals: IntArray): Cardinality =
            Cardinality(literals, min = 1, max = 1)

    }
}
