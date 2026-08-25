package com.eignex.klause.factor.bool

import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.PbAccumulator
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.moveBoolWatcher

/** CP propagator for [Cardinality]: generalised watched-literal propagation for `min ≤ count ≤ max`. */
internal class CardinalityPropagator(
    val boolVars: IntArray,
    val intVars: IntArray,
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

    /**
     * Load the constraint into [acc] as a coefficient-carrying `≥` reason for cutting-planes conflict
     * analysis. A cardinality is two unit-weight bounds; the relevant half is chosen by
     * [forcedLit] when resolving a pivot (forced-true own-literal ⇒ at-least side `Σ ℓ ≥ min`; forced-false
     * ⇒ at-most side `Σ ℓ ≤ max` ⇒ `Σ ¬ℓ ≥ n − max`). For a *seed* (`forcedLit == 0`, the conflicting
     * constraint) the violated side is detected from [state]: too many true literals ⇒ at-most, too few
     * non-false ⇒ at-least. Returns false for a degenerate bound or a variable not in scope.
     */
    fun loadReason(acc: PbAccumulator, forcedLit: Int, state: PropagationState): Boolean {
        val n = literals.size
        val ones = LongArray(n) { 1L }
        fun loadAtLeast() = if (min <= 0) false else acc.loadPb(ones, literals, geBound = min.toLong())
        fun loadAtMost(): Boolean {
            if (max >= n) return false
            val flipped = IntArray(n) { literals[it] xor 1 }
            return acc.loadPb(ones, flipped, geBound = (n - max).toLong())
        }
        if (forcedLit == 0) {
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
        // Forced-true own-literal ⇒ at-least side needed it; forced-false ⇒ at-most side.
        return if (occ == forcedLit) loadAtLeast() else loadAtMost()
    }

    /** Antecedents for a forced pin. For an at-least pin ([collectTrue] = false) the reason is the
     *  currently-*false* literals (their being false forced the pin), recorded as-is. For an at-most
     *  pin ([collectTrue] = true) it is the negations of the currently-*true* literals (their being
     *  true forced the pin to false). Excludes [excludeVar]; returns `null` when nothing to record. */
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

    /**
     * Propagate one watched side. [atLeast] = true is the at-least side (pins unwatched literals
     * *true* once every watcher is false); [atLeast] = false is the at-most side (pins them *false*
     * once every watcher is true). The two are exact mirrors under literal negation: the trigger is
     * a watcher already assigned to the "bad" value ([atLeast] ⇒ false, else true), the replacement
     * is any outside literal not yet at that value, and a watch relocates on the plain literal
     * (at-least) or its negation (at-most).
     */
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

    /** Index of a literal outside the watched range whose truth is not [value] (unassigned counts),
     *  or -1 if every outside literal is already assigned [value]. */
    private fun findNonOutside(state: PropagationState, watches: IntArray, start: Int, end: Int, value: Boolean): Int {
        outer@ for (i in literals.indices) {
            for (w in start until end) if (watches[w] == i) continue@outer
            if (!litIs(state, i, value)) return i
        }
        return -1
    }

    /** Pins every watched literal except [skipIdx] to [pinTrue] (true = at-least, false = at-most);
     *  returns false on conflict. */
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

    /** True iff the literal at index [idx] is currently assigned and evaluates to [value]
     *  (unassigned ⇒ false for either value). */
    private fun litIs(state: PropagationState, idx: Int, value: Boolean): Boolean {
        val lit = literals[idx]
        val b = state.boolValues[Lit.variable(lit)] ?: return false
        return Lit.evaluate(lit, b) == value
    }

    /**
     * Fallback scanner — O(n) per fire, used when the constraint shape can't be watched
     * (`min == n` "all must be true", `max == 0` "none can be true", or the trivial
     * `min == 0 && max == n`). The plain scan suffices because these shapes typically have
     * small or degenerate [literals].
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
