package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.solver.localsearch.LocalSearchState

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
class Clause(
    val literals: IntArray,
) : LocalSearchFactor {

    init { require(literals.isNotEmpty()) { "Clause must have at least one literal" } }

    override val boolVars: IntArray = run {
        val seen = LinkedHashSet<Int>()
        for (lit in literals) seen.add(Lit.variable(lit))
        val out = IntArray(seen.size)
        var i = 0
        for (v in seen) out[i++] = v
        out
    }
    override val intVars: IntArray = EmptyIntArray

    /** Pre-computed `boolVar → literal index` lookup. Cheap to materialise once at
     *  construction; turns the per-flip "find my literal" loop into a hash lookup. The
     *  compile path doesn't generate clauses where a var appears multiple times (`v` and
     *  `¬v` together would be a tautology and gets dropped). Sentinel `-1` for absent. */
    private val litIndexByVar: com.eignex.klause.util.IntIntMap = com.eignex.klause.util.IntIntMap.build(
        keys = IntArray(literals.size) { Lit.variable(literals[it]) },
        values = IntArray(literals.size) { it },
        absent = -1,
    )

    private class Watches(var w1: Int, var w2: Int)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val w = state.refPayload[factorId] as? Watches ?: Watches(0, if (literals.size > 1) 1 else -1)
        // Reseat watches: prefer two distinct true literals; fall back to two distinct indices.
        var first = -1
        var second = -1
        for (i in literals.indices) {
            if (litTrue(state, i)) {
                if (first == -1) first = i
                else if (second == -1) { second = i; break }
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

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // Walk literals once. Detect a satisfying literal, count unassigned literals, remember the
        // last unassigned one. If satisfied → nothing to do. If 0 unassigned and none satisfied →
        // Unsat. If exactly 1 unassigned and none satisfied → pin it to its required polarity.
        var unassignedCount = 0
        var unassignedLit = 0
        for (lit in literals) {
            val v = Lit.variable(lit)
            val b = state.boolValues[v]
            if (b == null) {
                unassignedCount++
                unassignedLit = lit
                if (unassignedCount > 1) return true  // not yet unit; no propagation possible
            } else if (Lit.evaluate(lit, b)) {
                return true  // clause already satisfied
            }
        }
        return when (unassignedCount) {
            0 -> false  // all literals false → contradiction
            1 -> state.pinBool(Lit.variable(unassignedLit), Lit.isPositive(unassignedLit))
            else -> true
        }
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
    }}
