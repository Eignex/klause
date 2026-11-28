package com.eignex.klause.solver.search

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Lit
import com.eignex.util.collections.IntArrayList

/**
 * Boolean cardinalities hosted by the shared session, so `min ≤ #true ≤ max` is enforced on a route
 * that never builds a finite projection.
 *
 * An exact core reading linear rows skips cardinalities as it skips clauses; this component makes that
 * skip sound. A literal's assignment wakes only the cardinalities holding it, and each bound explains
 * itself in clause form so every deduction can enter shared learning.
 */
class CardinalitySearchComponent(cardinalities: Iterable<Cardinality>) : SearchComponent {
    private val literals: Array<IntArray>
    private val mins: IntArray
    private val maxs: IntArray

    // Cardinalities of variable v are occOf[occStart[v] until occStart[v + 1]].
    private val occStart: IntArray
    private val occOf: IntArray

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
     * Recounts every cardinality at a candidate leaf. Guards the assumption that every Boolean assignment
     * wakes the cardinalities holding it; a gap there would otherwise surface as a wrong verdict.
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

    // All literals fixed by one bound share an antecedent set, so their clauses differ only in the
    // asserted literal.
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
            // The session does not deliver an implication back to the component that made it.
            wake(v)
        }
        return ComponentResult.Consistent
    }

    // Trimmed to the max + 1 violating literals so the shorter clause enters learning.
    private fun violatedAbove(c: Int): IntArray =
        assignedLiterals(c, wanted = true, negated = true, limit = maxs[c] + 1)

    private fun violatedBelow(c: Int): IntArray =
        assignedLiterals(c, wanted = false, negated = false, limit = literals[c].size - mins[c] + 1)

    // A complementary pair cannot both be wanted, so the result is never a tautology.
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

    // True count in the high word, false count in the low word.
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
