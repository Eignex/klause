package com.eignex.klause.presolve

import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

/**
 * Bounded variable elimination (BVE, Eén–Biere) over the pure-SAT part of the model. A Boolean
 * variable `v` is eliminated by resolving every clause containing `v` against every clause containing
 * `¬v` and replacing all of them with the (non-tautological) resolvents — the projection of `v` out of
 * the clause set — but only when that does not increase the clause count (the *bounded* rule). A
 * monotone `v` (appearing in only one polarity) is a degenerate case: its clauses are satisfiable by the
 * pure value and simply drop, adding no resolvents.
 *
 * Operates on the shared [SatClauseDb], so a variable is eliminable only when [SatClauseDb.eligible] —
 * objective-free and appearing solely in clean all-Boolean clauses. The eliminated `v` is left
 * unconstrained in the reduced problem, so this is **not** solution-set preserving (a complete
 * enumerator over-counts); its value is recovered from the removed clauses by [asRebuilds].
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
    ): PassDelta = run(problem, objectiveBoolVars, cancellation) { db, rebuild ->
        db.toDelta(rebuild.asSampleLift())
    } ?: PassDelta()

    /**
     * [eliminate] over a canonical source model.
     *
     * Resolution reads the clause database and nothing else — no domain, no propagation — so the whole
     * argument holds before a finite projection exists. `SatClauseDb.build` marks a Boolean ineligible
     * when any factor that is not a clean clause touches it, scanning every factor directly, so a
     * Boolean an integer row reads is never resolved away and the open columns beside it are untouched.
     */
    fun eliminateSource(
        problem: Problem,
        objectiveBoolVars: Set<Int> = emptySet(),
        cancellation: Cancellation = Cancellation.Never,
    ): SourceDelta = run(problem, objectiveBoolVars, cancellation) { db, rebuild ->
        db.toSourceDelta(rebuild)
    } ?: SourceDelta()

    private fun <T> run(
        problem: Problem,
        objectiveBoolVars: Set<Int>,
        cancellation: Cancellation,
        delta: (SatClauseDb, BoolRebuilds) -> T,
    ): T? {
        val nb = problem.numBoolVars
        if (nb == 0) return null
        val db = SatClauseDb.build(problem, objectiveBoolVars)

        val eliminations = ArrayList<VarElim>()
        // Cheapest-first: a low-degree variable is likeliest to eliminate within the bound.
        val order = (0 until nb)
            .filter { db.eligible[it] }
            .sortedBy { db.occ(Lit.make(it, true)).size + db.occ(Lit.make(it, false)).size }
        for ((idx, v) in order.withIndex()) {
            if ((idx and CANCEL_POLL_MASK) == 0 && cancellation()) break
            eliminateVar(v, db, eliminations)
        }

        return delta(db, eliminations.asRebuilds())
    }

    private fun eliminateVar(v: Int, db: SatClauseDb, eliminations: ArrayList<VarElim>) {
        val posSlots = IntArrayList()
        val negSlots = IntArrayList()
        val posLits = ArrayList<IntArray>()
        val negLits = ArrayList<IntArray>()
        gather(db, Lit.make(v, true), posSlots, posLits)
        gather(db, Lit.make(v, false), negSlots, negLits)
        if (posLits.isEmpty() && negLits.isEmpty()) return

        val clauses = ArrayList<IntArray>(posLits.size + negLits.size)
        clauses.addAll(posLits)
        clauses.addAll(negLits)

        // Monotone variable: its clauses are all satisfied by the pure value, so they drop with no
        // resolvent. Otherwise resolve pos × neg and keep the elimination only if it is bounded.
        val resolvents: List<IntArray>
        if (posLits.isEmpty() || negLits.isEmpty()) {
            resolvents = emptyList()
        } else {
            if (posLits.size.toLong() * negLits.size > RESOLVENT_PRODUCT_CAP) return
            val out = ArrayList<IntArray>()
            val seen = HashSet<List<Int>>() // exact literal-set identity — a lossy hash could drop a needed resolvent
            for (a in posLits.indices) {
                for (b in negLits.indices) {
                    val r = resolve(posLits[a], negLits[b], v) ?: continue // tautology
                    if (seen.add(r.sorted())) out.add(r)
                    if (out.size > clauses.size) return // unbounded: give up before committing
                }
            }
            resolvents = out
        }

        // Commit: remove v's clauses, add the resolvents, and record the removed clauses for reconstruction.
        for (k in 0 until posSlots.size) db.remove(posSlots[k])
        for (k in 0 until negSlots.size) db.remove(negSlots[k])
        for (r in resolvents) db.add(r)
        eliminations.add(VarElim(v, clauses))
    }

    /** Collect the live slots (and their literals) that contain [lit] into the parallel lists. */
    private fun gather(db: SatClauseDb, lit: Int, slots: IntArrayList, lits: ArrayList<IntArray>) {
        val col = db.occ(lit)
        for (k in 0 until col.size) {
            val s = col[k]
            val c = db.clause(s) ?: continue
            slots.add(s)
            lits.add(c)
        }
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
     * The eliminations as the steps that recover them, latest first.
     *
     * Each variable is set to satisfy the clauses it appeared in: a clause not satisfied by its other
     * (already-recovered) literals forces `v` to the polarity of its `v` literal, and bounded resolution
     * guarantees the two polarities are never forced at once. Reversed, so a variable whose clauses
     * reference a later-eliminated variable reads that variable's recovered value.
     */
    private fun List<VarElim>.asRebuilds(): BoolRebuilds =
        BoolRebuilds(asReversed().map { BoolRebuild.SatisfyClauses(it.v, it.clauses) })
}
