package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.MoveSink
import com.eignex.klause.solver.PropagationState
import com.eignex.klause.solver.SolverState

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
) : Factor {

    init { require(literals.isNotEmpty()) { "Clause must have at least one literal" } }

    override val boolVars: IntArray = run {
        val seen = LinkedHashSet<Int>()
        for (lit in literals) seen.add(Lit.variable(lit))
        val out = IntArray(seen.size)
        var i = 0
        for (v in seen) out[i++] = v
        out
    }
    override val intVars: IntArray = EMPTY

    private class Watches(var w1: Int, var w2: Int)

    override fun initialize(state: SolverState, factorId: Int) {
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

    override fun isViolated(state: SolverState, factorId: Int): Boolean {
        val w = state.refPayload[factorId] as Watches
        if (litTrue(state, w.w1)) return false
        if (w.w2 >= 0 && litTrue(state, w.w2)) return false
        return true
    }

    override fun deltaIfBoolFlipped(state: SolverState, factorId: Int, boolVar: Int): Int {
        val w = state.refPayload[factorId] as Watches
        val wasViolated = isViolated(state, factorId)
        val nowViolated = !anyLitTrueAfterFlip(state, boolVar)
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyBoolFlip(state: SolverState, factorId: Int, boolVar: Int): Int {
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

    override fun proposeRepairMoves(state: SolverState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        for (v in boolVars) sink.addBoolFlip(v)
    }

    private fun litTrue(state: SolverState, idx: Int): Boolean {
        if (idx < 0) return false
        val lit = literals[idx]
        return Lit.evaluate(lit, state.assignment.boolValue(Lit.variable(lit)))
    }

    /** Pre-flip evaluation of literal at [idx] reconstructed from the post-flip assignment.
     *  When the literal's variable matches [flippedVar], the bool's pre-flip value is the
     *  negation of its current value. */
    private fun wasLitTrue(state: SolverState, idx: Int, flippedVar: Int): Boolean {
        if (idx < 0) return false
        val lit = literals[idx]
        val v = Lit.variable(lit)
        val post = state.assignment.boolValue(v)
        val pre = if (v == flippedVar) !post else post
        return Lit.evaluate(lit, pre)
    }

    /** True iff at least one literal would be true if [boolVar] were flipped. Pre-flip path
     *  used by [deltaIfBoolFlipped]; the assignment hasn't been mutated yet. */
    private fun anyLitTrueAfterFlip(state: SolverState, boolVar: Int): Boolean {
        for (lit in literals) {
            val v = Lit.variable(lit)
            val pre = state.assignment.boolValue(v)
            val post = if (v == boolVar) !pre else pre
            if (Lit.evaluate(lit, post)) return true
        }
        return false
    }

    /** Find a literal index (other than [exclude1] and [exclude2]) currently evaluating true. */
    private fun findTrueLitExcept(state: SolverState, exclude1: Int, exclude2: Int): Int {
        for (i in literals.indices) {
            if (i == exclude1 || i == exclude2) continue
            if (litTrue(state, i)) return i
        }
        return -1
    }

    private companion object {
        val EMPTY: IntArray = IntArray(0)
    }
}
