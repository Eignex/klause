package com.eignex.klause.presolve

import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntHashSet

/**
 * Blocked-clause elimination (BCE, Järvisalo–Biere–Heule) over the pure-SAT part of the model. A
 * clause `C` is *blocked* on one of its literals `ℓ` when, for every clause `D` containing `¬ℓ`, the
 * resolvent of `C` and `D` on `var(ℓ)` is a tautology (i.e. `C` and `D` clash on some other variable).
 * A blocked clause is satisfiability-redundant and removed; if a solution of the reduced problem
 * falsifies `C`, setting `ℓ` true repairs it without breaking any `D` (each is already satisfied by the
 * clashing literal), which [BlockedReconstruct] does in reverse elimination order.
 *
 * Operates on the shared [SatClauseDb]. The blocking literal's variable must be [SatClauseDb.eligible]
 * — objective-free and appearing solely in clean all-Boolean clauses — so flipping it during
 * reconstruction affects nothing but this database and cannot change the objective. Like BVE this is
 * satisfiability-preserving but **not** solution-set preserving.
 */
internal object BlockedClauseElimination {

    /** Poll the cancellation once per this many clauses considered. */
    private const val CANCEL_POLL_MASK = 0xFF

    /** Skip the blocked check on a literal whose opposite occurs in more than this many clauses — a work
     *  bound so a high-degree literal cannot make the check quadratic. */
    private const val OCCURRENCE_CAP = 4_096

    fun eliminate(
        problem: Problem,
        objectiveBoolVars: Set<Int> = emptySet(),
        cancellation: Cancellation = Cancellation.Never,
    ): PassDelta {
        val nb = problem.numBoolVars
        if (nb == 0) return PassDelta()
        val db = SatClauseDb.build(problem, objectiveBoolVars)

        val removed = ArrayList<Blocked>()
        // BCE is confluent — removing a blocked clause keeps the rest blocked — so a single sweep,
        // retiring clauses as it goes, is sound; later checks simply see fewer clauses to clash against.
        var considered = 0
        for (s in 0 until db.slotCount) {
            val c = db.clause(s) ?: continue
            if ((considered++ and CANCEL_POLL_MASK) == 0 && cancellation()) break
            val blocking = blockingLiteral(db, c) ?: continue
            db.remove(s)
            removed.add(Blocked(c, blocking))
        }

        return db.toDelta(if (removed.isEmpty()) null else BlockedReconstruct(removed)::reconstruct)
    }

    /** A literal of [c] whose variable is eligible and on which [c] is blocked, or `null`. */
    private fun blockingLiteral(db: SatClauseDb, c: IntArray): Int? {
        for (l in c) {
            if (!db.eligible[Lit.variable(l)]) continue
            if (blockedOn(db, c, l)) return l
        }
        return null
    }

    /** Whether [c] is blocked on [l]: every live clause containing `¬l` clashes with [c] elsewhere. */
    private fun blockedOn(db: SatClauseDb, c: IntArray, l: Int): Boolean {
        val opposite = db.occ(Lit.negate(l))
        if (opposite.size > OCCURRENCE_CAP) return false
        val v = Lit.variable(l)
        val cLits = IntHashSet(c.size)
        for (k in c) if (Lit.variable(k) != v) cLits.add(k)
        for (i in 0 until opposite.size) {
            val d = db.clause(opposite[i]) ?: continue
            if (!clashesElsewhere(cLits, d, v)) return false
        }
        return true
    }

    /** Whether some literal of [d] (over a variable other than [v]) has its negation in [cLits] — the
     *  clash that makes the `C`/`D` resolvent on [v] a tautology. */
    private fun clashesElsewhere(cLits: IntHashSet, d: IntArray, v: Int): Boolean {
        for (dl in d) {
            if (Lit.variable(dl) == v) continue
            if (Lit.negate(dl) in cLits) return true
        }
        return false
    }

    /** A removed blocked clause and the literal it was blocked on. */
    private class Blocked(val clause: IntArray, val blockingLit: Int)

    /**
     * Recovers a solution by repairing any falsified blocked clause. In reverse elimination order, a
     * blocked clause that the current assignment leaves unsatisfied is repaired by forcing its blocking
     * literal true; the blocking property guarantees this satisfies the clause without falsifying any
     * clause that contained the opposite literal.
     */
    private class BlockedReconstruct(private val removed: List<Blocked>) {
        fun reconstruct(sample: Sample): Sample {
            if (removed.isEmpty()) return sample
            val bools = sample.bools.copyOf()
            for (i in removed.indices.reversed()) {
                val b = removed[i]
                if (!satisfied(b.clause, bools)) {
                    bools[Lit.variable(b.blockingLit)] = Lit.isPositive(b.blockingLit)
                }
            }
            return Sample(bools, sample.ints)
        }

        private fun satisfied(c: IntArray, bools: BooleanArray): Boolean {
            for (l in c) if (Lit.evaluate(l, bools[Lit.variable(l)])) return true
            return false
        }
    }
}
