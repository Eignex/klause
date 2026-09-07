package com.eignex.klause.solver.search

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Lit
import com.eignex.klause.util.IntArrayList

/**
 * Boolean cardinalities hosted by the shared session, so `min ≤ #true ≤ max` is enforced on a route
 * that never builds a finite projection.
 *
 * A cardinality states no arithmetic, so an exact core reading linear rows skips it exactly as it skips
 * a clause — and this component is what makes that skip sound. Counting is the whole mechanism: a
 * literal's assignment wakes only the cardinalities holding it, and each bound explains itself in clause
 * form, the true literals' negations for the upper bound and the false literals themselves for the
 * lower one, so every deduction can enter shared learning.
 *
 * CP keeps its own watched-literal cardinality propagator for a finite projection; this is the counting
 * one open and hybrid component sets use.
 */
class CardinalitySearchComponent(cardinalities: Iterable<Cardinality>) : SearchComponent {
    private val literals: Array<IntArray>
    private val mins: IntArray
    private val maxs: IntArray

    /** Cardinalities each Boolean variable occurs in, as `occStart[v] until occStart[v + 1]` of [occOf]. */
    private val occStart: IntArray
    private val occOf: IntArray

    /** Cardinalities whose count has not been re-examined since a literal of theirs was assigned. */
    private val pending = IntArrayList()
    private val queued: BooleanArray
    private var active: SearchContext? = null

    init {
        val sources = cardinalities.toList()
        literals = Array(sources.size) { sources[it].literals }
        mins = IntArray(sources.size) { sources[it].min }
        maxs = IntArray(sources.size) { sources[it].max }
        queued = BooleanArray(sources.size)
        var maxVar = -1
        for (lits in literals) {
            for (lit in lits) {
                val v = Lit.variable(lit)
                if (v > maxVar) maxVar = v
            }
        }
        val starts = IntArray(maxVar + 2)
        for (lits in literals) for (lit in lits) starts[Lit.variable(lit) + 1]++
        for (v in 1 until starts.size) starts[v] += starts[v - 1]
        occStart = starts
        occOf = IntArray(starts[starts.size - 1])
        val cursor = starts.copyOf()
        for (c in literals.indices) for (lit in literals[c]) occOf[cursor[Lit.variable(lit)]++] = c
    }

    override fun initialize(context: SearchContext): ComponentResult = withContext(context) {
        for (c in literals.indices) enqueue(c)
        drain()
    }

    override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
        if (decision !is SearchDecision.Bool) return ComponentResult.Consistent
        return withContext(context) {
            wake(Lit.variable(decision.literal))
            drain()
        }
    }

    override fun propagate(context: SearchContext): ComponentResult = withContext(context) {
        // A degenerate bound (`min == size`, `max == 0`) deduces from the empty assignment, so it is woken
        // by nothing and re-examined at the root, where a rebuilt seed can unassign a literal without any
        // retraction at all.
        if (context.decisionLevel == 0) for (c in literals.indices) enqueue(c)
        drain()
    }

    override fun retract(decisionLevel: Int) {
        // Draining is synchronous, so anything still queued was interrupted by a conflict at the level
        // being left. A retraction only shrinks the assignment, and a consequence of the smaller one was
        // asserted no deeper than the level it survives at, so nothing else needs re-examining.
        for (i in 0 until pending.size) queued[pending[i]] = false
        pending.clear()
    }

    /**
     * Counts every cardinality once more at a candidate leaf.
     *
     * Propagation already refutes a violated count, so this repeats work on the path where the session is
     * about to surface a model. It stays because the component's own reachability argument — that every
     * assignment of a literal wakes the cardinalities holding it — is the kind a later change to how the
     * session publishes Boolean facts could quietly break, and a wrong verdict is what that would cost.
     */
    override fun check(context: SearchContext): ComponentCheck {
        val previous = active
        active = context
        try {
            for (c in literals.indices) {
                val counts = count(c)
                if (trueCount(counts) > maxs[c]) {
                    return ComponentCheck.Infeasible(SearchExplanation(violatedAbove(c)))
                }
                if (literals[c].size - falseCount(counts) < mins[c]) {
                    return ComponentCheck.Infeasible(SearchExplanation(violatedBelow(c)))
                }
            }
            return ComponentCheck.Feasible
        } finally {
            active = previous
        }
    }

    private fun drain(): ComponentResult {
        while (!pending.isEmpty()) {
            val c = pending[pending.size - 1]
            pending.truncateTo(pending.size - 1)
            queued[c] = false
            val result = examine(c)
            if (result !is ComponentResult.Consistent) return result
        }
        return ComponentResult.Consistent
    }

    /**
     * Recount cardinality [c] and act on whichever bound the count has reached.
     *
     * The four outcomes are the ones a count admits: more true literals than the upper bound and fewer
     * non-false than the lower one are refutations, while a count sitting exactly on either bound fixes
     * every literal still unassigned — false on the upper bound, true on the lower one.
     */
    private fun examine(c: Int): ComponentResult {
        val lits = literals[c]
        val counts = count(c)
        val trues = trueCount(counts)
        val falses = falseCount(counts)
        if (trues > maxs[c]) return ComponentResult.Conflict(SearchExplanation(violatedAbove(c)))
        if (lits.size - falses < mins[c]) return ComponentResult.Conflict(SearchExplanation(violatedBelow(c)))
        if (trues + falses == lits.size) return ComponentResult.Consistent
        if (trues == maxs[c]) return fix(c, toTrue = false)
        if (lits.size - falses == mins[c]) return fix(c, toTrue = true)
        return ComponentResult.Consistent
    }

    /**
     * Assign every still-unassigned literal of [c] to [toTrue], with the antecedents that force it.
     *
     * Both bounds have one antecedent set for all of the literals they fix — the true literals on the
     * upper bound, the false ones on the lower — so the clause differs only in the literal it asserts.
     */
    private fun fix(c: Int, toTrue: Boolean): ComponentResult {
        val context = session()
        val antecedents = assignedLiterals(c, wanted = !toTrue, negated = !toTrue, limit = Int.MAX_VALUE)
        for (lit in literals[c]) {
            val v = Lit.variable(lit)
            if (context.boolValue(v) != null) continue
            val implied = if (toTrue) lit else Lit.negate(lit)
            val clause = IntArray(antecedents.size + 1)
            antecedents.copyInto(clause)
            clause[antecedents.size] = implied
            val result = context.imply(implied, SearchExplanation(clause))
            if (result !is ComponentResult.Consistent) return result
            // The session delivers an implication to every component except the one that made it, so this
            // component wakes on its own consequences — a literal it fixes can be the one that pushes
            // another cardinality, or this one under a repeated variable, onto a bound.
            wake(v)
        }
        return ComponentResult.Consistent
    }

    /**
     * The clause refuting a count above the upper bound, trimmed to the `max + 1` true literals that
     * violate it. Any larger set is implied by this one, and the shorter clause is what enters learning.
     */
    private fun violatedAbove(c: Int): IntArray =
        assignedLiterals(c, wanted = true, negated = true, limit = maxs[c] + 1)

    /** The clause refuting a count below the lower bound, trimmed to `size - min + 1` false literals. */
    private fun violatedBelow(c: Int): IntArray =
        assignedLiterals(c, wanted = false, negated = false, limit = literals[c].size - mins[c] + 1)

    /**
     * Literals of [c] whose truth is [wanted], at most [limit] of them, negated when [negated].
     *
     * A repeated variable can put the same literal in twice, which only makes the clause redundant. A
     * complementary pair cannot both be [wanted], so the result is never a tautology.
     */
    private fun assignedLiterals(c: Int, wanted: Boolean, negated: Boolean, limit: Int): IntArray {
        val context = session()
        val lits = literals[c]
        var n = 0
        for (lit in lits) {
            val value = context.boolValue(Lit.variable(lit)) ?: continue
            if (Lit.evaluate(lit, value) == wanted) n++
        }
        val out = IntArray(if (n < limit) n else limit)
        var w = 0
        for (lit in lits) {
            if (w == out.size) break
            val value = context.boolValue(Lit.variable(lit)) ?: continue
            if (Lit.evaluate(lit, value) == wanted) out[w++] = if (negated) Lit.negate(lit) else lit
        }
        return out
    }

    /** True and false literal counts of [c], packed so one scan answers both. */
    private fun count(c: Int): Long {
        val context = session()
        var trues = 0
        var falses = 0
        for (lit in literals[c]) {
            val value = context.boolValue(Lit.variable(lit)) ?: continue
            if (Lit.evaluate(lit, value)) trues++ else falses++
        }
        return (trues.toLong() shl Int.SIZE_BITS) or falses.toLong()
    }

    private fun trueCount(counts: Long): Int = (counts ushr Int.SIZE_BITS).toInt()

    private fun falseCount(counts: Long): Int = counts.toInt()

    private fun enqueue(c: Int) {
        if (queued[c]) return
        queued[c] = true
        pending.add(c)
    }

    private fun wake(variable: Int) {
        if (variable + 1 >= occStart.size) return
        for (i in occStart[variable] until occStart[variable + 1]) enqueue(occOf[i])
    }

    private fun session(): SearchContext =
        requireNotNull(active) { "cardinality propagation ran outside a session callback" }

    private inline fun withContext(context: SearchContext, body: () -> ComponentResult): ComponentResult {
        val previous = active
        active = context
        val result = body()
        active = previous
        return result
    }
}
