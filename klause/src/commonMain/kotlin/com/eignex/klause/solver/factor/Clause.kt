package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntIntMap

/**
 * Disjunction of Boolean literals.
 *
 * Uses two-watched-literal scheme (Zhang–Stickel 1996, ported to local search). Two indices
 * into [literals] are watched at any time; the clause is satisfied iff at least one watched
 * literal evaluates to true. When an accepted flip turns a watched literal false, we scan the
 * unwatched literals for a true one to rewatch, finishing in O(1) amortized when most flips
 * leave the watches alone. Single-literal clauses have only one watch (`w2 = -1`).
 *
 * Tautologies (a variable appearing as both `+v` and `-v`) are detected at construction; we
 * pick those two indices as the watches and the clause is permanently satisfied.
 */
class Clause(val literals: IntArray) : LocalSearchFactor {

    init {
        require(literals.isNotEmpty()) { "Clause must have at least one literal" }
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

    /** Initial two-watched-literal wakeup positions. Unit clauses watch their single
     *  literal so they fire when it becomes false; longer clauses watch literals[0] and
     *  literals[1] to start. The CP engine routes per-literal wakeups through this set
     *  via [com.eignex.klause.solver.propagation.PropagationState.boolWatchersByLit]; as
     *  watches drift during propagation, [propagate] keeps the index in sync by calling
     *  `state.moveBoolWatcher`. */
    override val initialBoolWatchers: IntArray =
        if (literals.size == 1) {
            intArrayOf(literals[0])
        } else {
            intArrayOf(literals[0], literals[1])
        }

    /** When [propagate] returns false, every literal of this clause was false — that's
     *  the textbook clause-form nogood for conflict analysis. Returning the literals
     *  directly (no copy) is safe because the analyzer only reads them. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray = literals

    /** Pre-computed `boolVar → literal index` lookup. Cheap to materialise once at
     *  construction; turns the per-flip "find my literal" loop into a hash lookup. The
     *  compile path doesn't generate clauses where a var appears multiple times (`v` and
     *  `¬v` together would be a tautology and gets dropped). Sentinel `-1` for absent. */
    private val litIndexByVar: IntIntMap = IntIntMap.build(
        keys = IntArray(literals.size) { Lit.variable(literals[it]) },
        values = IntArray(literals.size) { it },
        absent = -1,
    )

    /** CP-only memo: are all literals plain bool vars (no atom-lits)? `null` until first
     *  queried. A pure-bool clause only ever fires when a watched bool literal just went
     *  false at the *current* decision level, so its effective level is exactly the current
     *  decision level — letting the propagation dispatch skip the per-fire level scan. Atom-
     *  lit clauses can fire on an atom that flipped at a sub-decision level, so they still
     *  need the scan. Intrinsic to the clause (numBoolVars is fixed per Problem), so it's
     *  valid across learned-clause forget/remap. Unused by the local-search path. */
    private var pureBoolMemo: Boolean? = null

    /** True iff every literal is a plain bool var (variable id `< numBoolVars`), memoised. */
    fun allLiteralsBool(numBoolVars: Int): Boolean = pureBoolMemo ?: run {
        var allBool = true
        for (lit in literals) {
            if (Lit.variable(lit) >= numBoolVars) {
                allBool = false
                break
            }
        }
        pureBoolMemo = allBool
        allBool
    }

    private class Watches(var w1: Int, var w2: Int)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val w = state.refPayload[factorId] as? Watches ?: Watches(0, if (literals.size > 1) 1 else -1)
        // Reseat watches: prefer two distinct true literals; fall back to two distinct indices.
        var first = -1
        var second = -1
        var trueCount = 0
        for (i in literals.indices) {
            if (litTrue(state, i)) {
                trueCount++
                if (first == -1) {
                    first = i
                } else if (second == -1) {
                    second = i
                }
            }
        }
        if (first == -1) {
            w.w1 = 0
            w.w2 = if (literals.size > 1) 1 else -1
        } else if (second == -1) {
            w.w1 = first
            w.w2 = if (literals.size > 1) (if (first == 0) 1 else 0) else -1
        } else {
            w.w1 = first
            w.w2 = second
        }
        state.refPayload[factorId] = w
        state.intPayload[factorId] = trueCount
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val w = state.refPayload[factorId] as Watches
        if (litTrue(state, w.w1)) return false
        if (w.w2 >= 0 && litTrue(state, w.w2)) return false
        return true
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val li = litIndexByVar[boolVar]
        if (li < 0) return 0
        val w = state.refPayload[factorId] as Watches
        // The watched-literal `applyBoolFlip` maintains the *weak* invariant — "if any
        // literal is true then at least one watch is true" — not the strong one ("every
        // true literal up to 2 is watched"). We exploit the weak invariant to short-cut
        // most flips and fall back to a scan only for the unlucky case where we flip
        // a watch that's the only currently-true watch and need to know whether some
        // non-watch literal is also true.
        val w1True = litTrue(state, w.w1)
        val w2True = w.w2 >= 0 && litTrue(state, w.w2)
        val wasViolated = !w1True && !w2True

        val nowViolated = when {
            // Both watches true: at most one of them is the flipped literal; the other
            // stays true regardless.
            w1True && w2True -> false

            // Only w1 is currently true. Flipping a non-watch leaves w1 alone — still
            // satisfied. Flipping `w.w1` (== `li`) sends w1 false; we then need to
            // know whether any other literal happens to be true (weak invariant
            // permits true non-watch literals when at least one watch is true).
            w1True -> if (li != w.w1) false else !anyOtherLitTrue(state, li)

            w2True -> if (li != w.w2) false else !anyOtherLitTrue(state, li)

            // Currently violated: every literal is false (this is the strong half of
            // the invariant — when both watches are false, no literal is true, since
            // applyBoolFlip would have rewatched otherwise). Flipping literals[li]
            // makes it true → clause becomes satisfied.
            else -> false
        }
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    /** True iff some literal at an index other than [excludeIdx] is currently true.
     *  Only invoked when the watched-literal short-cuts can't conclude, so the O(arity)
     *  cost is rare in practice (most flips of well-satisfied clauses stay in the
     *  both-watches-true branch). */
    private fun anyOtherLitTrue(state: LocalSearchState, excludeIdx: Int): Boolean {
        for (i in literals.indices) {
            if (i == excludeIdx) continue
            if (litTrue(state, i)) return true
        }
        return false
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val w = state.refPayload[factorId] as Watches
        // Pre-flip status of each watch (assignment is already flipped; reconstruct old value).
        val w1WasTrue = wasLitTrue(state, w.w1, boolVar)
        val w2WasTrue = if (w.w2 >= 0) wasLitTrue(state, w.w2, boolVar) else false
        val wasSatisfied = w1WasTrue || w2WasTrue

        // Post-flip status of each watch.
        var w1NowTrue = litTrue(state, w.w1)
        var w2NowTrue = if (w.w2 >= 0) litTrue(state, w.w2) else false

        // Update numTrueLits: the flipped var appears in exactly one literal (no tautologies
        // by construction). Determine whether that literal went true→false or false→true.
        val li = litIndexByVar[boolVar]
        if (li >= 0) {
            val nowTrue = litTrue(state, li)
            state.intPayload[factorId] += if (nowTrue) 1 else -1
        }

        // If a watch went true → false, look for another true literal to rewatch.
        if (!w1NowTrue) {
            val replacement = findTrueLitExcept(state, w.w1, w.w2)
            if (replacement >= 0) {
                w.w1 = replacement
                w1NowTrue = true
            }
        }
        if (w.w2 >= 0 && !w2NowTrue && !w1NowTrue) {
            val replacement = findTrueLitExcept(state, w.w2, w.w1)
            if (replacement >= 0) {
                w.w2 = replacement
                w2NowTrue = true
            }
        }

        val isSatisfied = w1NowTrue || w2NowTrue
        val nowViolated = !isSatisfied
        val wasViolated = !wasSatisfied
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override val maintainsBreakMakeIncrementally: Boolean get() = true

    /** O(arity) — but typically O(1) — update of [LocalSearchState.boolBreakCount] /
     *  [LocalSearchState.boolMakeCount] in response to [flippedVar] being flipped.
     *
     *  Only the 0↔1 and 1↔2 transitions of `numTrueLits` change break/make contributions:
     *   - `0→1`: clause was violated (every var made it satisfiable); now critically sat
     *     with [flippedVar] as the critical literal.
     *   - `1→0`: critical was [flippedVar]; now violated; every var becomes a make candidate.
     *   - `1→2`: previous critical (now non-critical) loses its break.
     *   - `2→1`: the remaining true literal becomes critical and gains a break.
     *
     *  Transitions 2↔3, 3↔4, ... touch no break/make state. */
    override fun updateBoolBreakMakeForFlip(state: LocalSearchState, factorId: Int, flippedVar: Int) {
        val li = litIndexByVar[flippedVar]
        if (li < 0) return // flippedVar isn't in this clause (shouldn't happen via occurrence list)
        val newCount = state.intPayload[factorId]
        val nowTrue = litTrue(state, li)
        // Old count was newCount - (delta), where delta = ±1 depending on lit transition.
        val oldCount = if (nowTrue) newCount - 1 else newCount + 1
        when {
            oldCount == 0 && newCount == 1 -> {
                // Was violated → critically sat. Drop the makeCount contribution every var
                // had, add the breakCount for the new critical (the flipped var's lit).
                for (v in boolVars) state.boolMakeCount[v]--
                state.boolBreakCount[flippedVar]++
            }

            oldCount == 1 && newCount == 0 -> {
                // Critically sat → violated. The pre-flip critical was the flipped var's
                // lit (since that's the lit that went true→false). Drop its break; add
                // make for every var.
                state.boolBreakCount[flippedVar]--
                for (v in boolVars) state.boolMakeCount[v]++
            }

            oldCount == 1 && newCount == 2 -> {
                // Old critical (the other true lit) is no longer critical.
                val oldCriticalIdx = findTrueLitExceptIndex(state, li)
                if (oldCriticalIdx >= 0) {
                    state.boolBreakCount[Lit.variable(literals[oldCriticalIdx])]--
                }
            }

            oldCount == 2 && newCount == 1 -> {
                // The remaining true lit becomes critical.
                val newCriticalIdx = findTrueLitExceptIndex(state, li)
                if (newCriticalIdx >= 0) {
                    state.boolBreakCount[Lit.variable(literals[newCriticalIdx])]++
                }
            }
            // 2↔3, 3↔4, ...: no change to break/make contributions.
        }
    }

    /** Find a literal index other than [excludeIdx] that's currently true. Used by the
     *  incremental break/make update to identify the (old or new) critical literal. */
    private fun findTrueLitExceptIndex(state: LocalSearchState, excludeIdx: Int): Int {
        for (i in literals.indices) {
            if (i == excludeIdx) continue
            if (litTrue(state, i)) return i
        }
        return -1
    }

    /**
     * Two-watched-literal propagation (Zhang–Stickel / MiniSAT). Caches a pair of literal
     * indices on [PropagationState.refPayload]; each fire checks at most those two before
     * looking further. Common case (both watches non-false): returns in O(1) regardless of
     * clause arity. Slow case (a watch turned false): scans for a non-false replacement,
     * O(arity) worst-case but amortised across many fires since most flips don't disturb
     * the watches.
     *
     * Soundness across [PropagationSession] push/pop: watches deliberately aren't
     * snapshotted (see [PropagationState.refPayload] kdoc). After a pop, watches may
     * point at literals that are again unassigned at the restored level — perfectly
     * valid, since the invariant is "watches point at non-false literals" and unassigned
     * counts as non-false. If a restored state actually makes the watches stale (some
     * literal at the watch position is somehow false), the first propagate call
     * re-validates and moves them.
     */
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        if (literals.size == 1) {
            // Unit clause: no second watch to play with. Trivial check-or-pin.
            val lit = literals[0]
            val t = state.litTruth(lit)
            return if (t == null) {
                state.pinLit(lit, antecedents = null)
            } else {
                t // already true → satisfied; already false → conflict.
            }
        }
        val watches = (state.refPayload[factorId] as IntArray?) ?: run {
            // First fire on this session: initial watches at indices 0 and 1. The body
            // below re-validates if either is already false.
            val w = intArrayOf(0, 1)
            state.refPayload[factorId] = w
            w
        }
        // Fast path: if either watched literal is currently true, the clause is satisfied
        // and we don't even need to look further. This is the dominant case once watches
        // have settled, and is what makes the propagator amortised-O(1) per fire.
        if (litTrue(state, watches[0]) || litTrue(state, watches[1])) return true

        // If a watch is on a now-false literal, scan for a non-false replacement. Skip
        // both currently-watched indices so the two watches stay distinct. Whenever we
        // move a watch, notify the state so its per-literal wakeup index stays in sync —
        // future flips of the *old* literal's var won't wake this clause anymore (correct,
        // since we're no longer watching it), and flips of the *new* literal's var will.
        if (litFalse(state, watches[0])) {
            val rep = findNonFalseLitExcept(state, watches[0], watches[1])
            if (rep >= 0) {
                state.moveBoolWatcher(factorId, literals[watches[0]], literals[rep])
                watches[0] = rep
            }
        }
        if (litFalse(state, watches[1])) {
            val rep = findNonFalseLitExcept(state, watches[1], watches[0])
            if (rep >= 0) {
                state.moveBoolWatcher(factorId, literals[watches[1]], literals[rep])
                watches[1] = rep
            }
        }
        // Re-evaluate after potential moves. If either is now true, satisfied.
        if (litTrue(state, watches[0]) || litTrue(state, watches[1])) return true

        // No true literal. Each watch is either false (no replacement was found) or
        // unassigned. Outcomes:
        //   both false        → no literal can be true → contradiction
        //   one false, one ?  → ? is the only candidate → unit-pin it
        //   both unassigned   → clause not yet determined → no propagation
        val w0False = litFalse(state, watches[0])
        val w1False = litFalse(state, watches[1])
        return when {
            w0False && w1False -> false

            // Unit propagation: pin the unassigned watch with antecedents = every other
            // literal in the clause (all of which are now false). At conflict-analysis
            // time the analyzer uses these to walk the implication graph back from this
            // pin.
            w0False -> pinUnit(state, watches[1])

            w1False -> pinUnit(state, watches[0])

            else -> true
        }
    }

    /** Unit-propagate the literal at [unitIdx] to true, recording every other literal in
     *  the clause as the antecedent set. Uses [PropagationState.pinLit] so atom-literal
     *  clauses re-derive as int-bound tightens on the underlying var. */
    private fun pinUnit(state: PropagationState, unitIdx: Int): Boolean {
        val unitLit = literals[unitIdx]
        val antecedents: IntArray? = if (literals.size <= 1) {
            null
        } else {
            val out = IntArray(literals.size - 1)
            var w = 0
            for (i in literals.indices) if (i != unitIdx) out[w++] = literals[i]
            out
        }
        return state.pinLit(unitLit, antecedents)
    }

    private fun litTrue(state: PropagationState, idx: Int): Boolean = state.litTrue(literals[idx])

    private fun litFalse(state: PropagationState, idx: Int): Boolean = state.litFalse(literals[idx])

    private fun findNonFalseLitExcept(state: PropagationState, excludeA: Int, excludeB: Int): Int {
        for (i in literals.indices) {
            if (i == excludeA || i == excludeB) continue
            if (!litFalse(state, i)) return i
        }
        return -1
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        for (v in boolVars) sink.addBoolFlip(v)
    }

    private fun litTrue(state: LocalSearchState, idx: Int): Boolean {
        if (idx < 0) return false
        val lit = literals[idx]
        return Lit.evaluate(lit, state.assignment.boolValue(Lit.variable(lit)))
    }

    /** Pre-flip evaluation of literal at [idx] reconstructed from the post-flip assignment.
     *  When the literal's variable matches [flippedVar], the bool's pre-flip value is the
     *  negation of its current value. */
    private fun wasLitTrue(state: LocalSearchState, idx: Int, flippedVar: Int): Boolean {
        if (idx < 0) return false
        val lit = literals[idx]
        val v = Lit.variable(lit)
        val post = state.assignment.boolValue(v)
        val pre = if (v == flippedVar) !post else post
        return Lit.evaluate(lit, pre)
    }

    /** True iff at least one literal would be true if [boolVar] were flipped. Pre-flip path
     *  used by [deltaIfBoolFlipped]; the assignment hasn't been mutated yet. */
    private fun anyLitTrueAfterFlip(state: LocalSearchState, boolVar: Int): Boolean {
        for (lit in literals) {
            val v = Lit.variable(lit)
            val pre = state.assignment.boolValue(v)
            val post = if (v == boolVar) !pre else pre
            if (Lit.evaluate(lit, post)) return true
        }
        return false
    }

    /** Find a literal index (other than [exclude1] and [exclude2]) currently evaluating true. */
    private fun findTrueLitExcept(state: LocalSearchState, exclude1: Int, exclude2: Int): Int {
        for (i in literals.indices) {
            if (i == exclude1 || i == exclude2) continue
            if (litTrue(state, i)) return i
        }
        return -1
    }
}
