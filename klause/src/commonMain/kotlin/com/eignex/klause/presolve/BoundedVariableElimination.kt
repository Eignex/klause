package com.eignex.klause.presolve

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

/**
 * Bounded variable elimination (BVE, Eén–Biere) over the pure-SAT part of the model (#24). A Boolean
 * variable `v` is eliminated by resolving every clause containing `v` against every clause containing
 * `¬v` and replacing all of them with the (non-tautological) resolvents — the projection of `v` out of
 * the clause set — but only when that does not increase the clause count (the *bounded* rule). A
 * monotone `v` (appearing in only one polarity) is a degenerate case: its clauses are satisfiable by the
 * pure value and simply drop, adding no resolvents.
 *
 * Eligibility is deliberately narrow so the elimination and its reconstruction stay in Boolean space:
 *  - `v` must be an objective-free Boolean variable, and
 *  - every factor mentioning `v` must be an all-Boolean [Clause] (`allLiteralsBool`), i.e. no
 *    cardinality / pseudo-Boolean / reified / integer factor and no atom-literal touches `v`.
 *
 * The eliminated `v` is left unconstrained in the reduced problem, so this is **not** solution-set
 * preserving (a complete enumerator over-counts, #507); its value is recovered from the removed clauses
 * by [BveReconstruct]. Resolution of two all-Boolean clauses yields an all-Boolean clause, so the
 * eligibility invariant is preserved and further eliminations can chain on the resolvents.
 */
internal object BoundedVariableElimination {

    /** Skip a variable whose `|pos| · |neg|` resolvent product exceeds this — a per-variable work bound
     *  so a high-degree variable cannot make the pairwise resolution quadratic-blow-up. */
    private const val RESOLVENT_PRODUCT_CAP = 4_096

    /** Poll the cancellation once per this many variables considered. */
    private const val CANCEL_POLL_MASK = 0xFF

    fun eliminate(
        problem: Problem,
        objectiveBoolVars: Set<Int> = emptySet(),
        cancellation: Cancellation = Cancellation.Never,
    ): PassDelta {
        val nb = problem.numBoolVars
        if (nb == 0) return PassDelta()
        val factors = problem.factors

        // A variable is eliminable only while every factor touching it is a clean all-Boolean clause.
        val eligible = BooleanArray(nb) { it !in objectiveBoolVars }
        for (f in factors) {
            if (isCleanClause(f, nb)) continue
            for (w in f.boolVars) if (w in 0 until nb) eligible[w] = false
        }
        if (!eligible.any { it }) return PassDelta()

        // Clause database over the clean clauses: `slotLits[s]` is a clause (null once removed), `slotOrig`
        // its input factor index (or -1 for a resolvent). Tautological input clauses are always true, so
        // they are dropped as vacuous rather than entered.
        val slotLits = ArrayList<IntArray?>()
        val slotOrig = IntArrayList()
        val vacuous = IntArrayList()
        for (i in factors.indices) {
            val f = factors[i]
            if (!isCleanClause(f, nb)) continue
            val lits = (f as Clause).literals
            if (isTautology(lits)) {
                vacuous.add(i)
            } else {
                slotLits.add(lits)
                slotOrig.add(i)
            }
        }
        // Per-variable occurrence lists into the slot database; stale (removed) slots are skipped on read.
        val occ = Array(nb) { IntArrayList() }
        for (s in slotLits.indices) slotLits[s]?.let { addOcc(occ, s, it, nb) }

        val eliminations = ArrayList<VarElim>()
        var changed = false

        // Cheapest-first: a low-degree variable is likeliest to eliminate within the bound.
        val order = (0 until nb).filter { eligible[it] }.sortedBy { occ[it].size }
        for ((idx, v) in order.withIndex()) {
            if ((idx and CANCEL_POLL_MASK) == 0 && cancellation()) break
            if (eliminateVar(v, slotLits, slotOrig, occ, nb, eliminations)) changed = true
        }

        if (!changed && vacuous.isEmpty()) return PassDelta()

        val dropped = IntArrayList()
        for (i in 0 until vacuous.size) dropped.add(vacuous[i])
        val added = ArrayList<Factor>()
        for (s in slotLits.indices) {
            val lits = slotLits[s]
            val orig = slotOrig[s]
            when {
                lits == null && orig >= 0 -> dropped.add(orig)

                // an input clause consumed by an elimination
                lits != null && orig < 0 -> added.add(Clause(lits)) // a surviving resolvent
                // lits != null && orig >= 0: input clause survives unchanged — kept, not in the delta.
                // lits == null && orig < 0: a resolvent later consumed — nothing to emit.
            }
        }
        return PassDelta(
            droppedIndices = dropped.toIntArray(),
            addedFactors = added,
            reconstruct = BveReconstruct(eliminations)::reconstruct,
        )
    }

    /** Try to eliminate [v] against the live clause database; returns true if it was eliminated. */
    private fun eliminateVar(
        v: Int,
        slotLits: ArrayList<IntArray?>,
        slotOrig: IntArrayList,
        occ: Array<IntArrayList>,
        nb: Int,
        eliminations: ArrayList<VarElim>,
    ): Boolean {
        // Gather v's live clauses, keeping each slot index alongside its (non-null) literals so the commit
        // can null the slot without re-reading through a nullable.
        val posSlots = IntArrayList()
        val negSlots = IntArrayList()
        val posLits = ArrayList<IntArray>()
        val negLits = ArrayList<IntArray>()
        val col = occ[v]
        val seenSlots = IntHashSet(col.size)
        for (k in 0 until col.size) {
            val s = col[k]
            val lits = slotLits[s] ?: continue
            if (!seenSlots.add(s)) continue
            var hasPos = false
            var hasNeg = false
            for (l in lits) if (Lit.variable(l) == v) if (Lit.isPositive(l)) hasPos = true else hasNeg = true
            if (hasPos) {
                posSlots.add(s)
                posLits.add(lits)
            }
            if (hasNeg) {
                negSlots.add(s)
                negLits.add(lits)
            }
        }
        if (posLits.isEmpty() && negLits.isEmpty()) return false

        val clauses = ArrayList<IntArray>(posLits.size + negLits.size)
        clauses.addAll(posLits)
        clauses.addAll(negLits)

        // Monotone variable: its clauses are all satisfied by the pure value, so they drop with no
        // resolvent. Otherwise resolve pos × neg and keep the elimination only if it is bounded.
        val resolvents: List<IntArray>
        if (posLits.isEmpty() || negLits.isEmpty()) {
            resolvents = emptyList()
        } else {
            if (posLits.size.toLong() * negLits.size > RESOLVENT_PRODUCT_CAP) return false
            val out = ArrayList<IntArray>()
            val seen = HashSet<List<Int>>() // exact literal-set identity — a lossy hash could drop a needed resolvent
            for (a in posLits.indices) {
                for (b in negLits.indices) {
                    val r = resolve(posLits[a], negLits[b], v) ?: continue // tautology
                    if (seen.add(r.sorted())) out.add(r)
                    if (out.size > clauses.size) return false // unbounded: give up before committing
                }
            }
            resolvents = out
        }

        // Commit: remove v's clauses, add the resolvents, and record the removed clauses for reconstruction.
        for (k in 0 until posSlots.size) slotLits[posSlots[k]] = null
        for (k in 0 until negSlots.size) slotLits[negSlots[k]] = null
        for (r in resolvents) {
            val s = slotLits.size
            slotLits.add(r)
            slotOrig.add(-1)
            addOcc(occ, s, r, nb)
        }
        eliminations.add(VarElim(v, clauses))
        return true
    }

    /** Add slot [s] to the occurrence list of every Boolean variable in [lits]. */
    private fun addOcc(occ: Array<IntArrayList>, s: Int, lits: IntArray, nb: Int) {
        for (l in lits) {
            val w = Lit.variable(l)
            if (w in 0 until nb) occ[w].add(s)
        }
    }

    private fun isCleanClause(f: Factor, nb: Int): Boolean = f is Clause && f.allLiteralsBool(nb)

    /** A clause containing a literal and its negation — always true. */
    private fun isTautology(lits: IntArray): Boolean {
        val seen = IntHashSet(lits.size)
        for (l in lits) {
            if (Lit.negate(l) in seen) return true
            seen.add(l)
        }
        return false
    }

    /** Resolve [c1] (contains `+v`) and [c2] (contains `¬v`) on [v]: their literals minus the `v`
     *  literals, deduplicated; `null` when the result is a tautology (contains a literal and its
     *  negation). */
    private fun resolve(c1: IntArray, c2: IntArray, v: Int): IntArray? {
        val lits = IntHashSet(c1.size + c2.size)
        for (l in c1) if (Lit.variable(l) != v) lits.add(l)
        for (l in c2) {
            if (Lit.variable(l) == v) continue
            if (Lit.negate(l) in lits) return null
            lits.add(l)
        }
        return lits.toIntArray()
    }

    /** One eliminated variable and the clauses that mentioned it, for solution reconstruction. */
    private class VarElim(val v: Int, val clauses: List<IntArray>)

    /**
     * Recovers eliminated Boolean variables from a solution of the reduced problem. Each variable is set
     * to satisfy the clauses it appeared in: a clause not satisfied by its other (already-recovered)
     * literals forces `v` to the polarity of its `v` literal; bounded resolution guarantees the two
     * polarities are never forced at once. Eliminations are replayed in reverse order, so a variable
     * whose clauses reference a later-eliminated variable reads that variable's recovered value.
     */
    private class BveReconstruct(private val eliminations: List<VarElim>) {
        fun reconstruct(sample: Sample): Sample {
            if (eliminations.isEmpty()) return sample
            val bools = sample.bools.copyOf()
            for (i in eliminations.indices.reversed()) {
                val e = eliminations[i]
                var value = false
                for (c in e.clauses) {
                    if (satisfiedIgnoring(c, e.v, bools)) continue
                    value = valueSatisfying(c, e.v)
                    break
                }
                bools[e.v] = value
            }
            return Sample(bools, sample.ints)
        }

        /** True if clause [c] is satisfied by a literal other than the one over [v]. */
        private fun satisfiedIgnoring(c: IntArray, v: Int, bools: BooleanArray): Boolean {
            for (l in c) {
                val w = Lit.variable(l)
                if (w == v) continue
                if (Lit.evaluate(l, bools[w])) return true
            }
            return false
        }

        /** The value of [v] that makes its literal in clause [c] true. */
        private fun valueSatisfying(c: IntArray, v: Int): Boolean {
            for (l in c) if (Lit.variable(l) == v) return Lit.isPositive(l)
            return false
        }
    }
}
