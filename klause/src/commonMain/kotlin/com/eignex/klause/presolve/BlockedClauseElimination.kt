package com.eignex.klause.presolve

import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntHashSet

/**
 * Blocked-clause elimination (BCE, Järvisalo–Biere–Heule) over the pure-SAT part of the model. A
 * clause `C` is *blocked* on one of its literals `ℓ` when, for every clause `D` containing `¬ℓ`, the
 * resolvent of `C` and `D` on `var(ℓ)` is a tautology (i.e. `C` and `D` clash on some other variable).
 * A blocked clause is satisfiability-redundant and removed; if a solution of the reduced problem
 * falsifies `C`, setting `ℓ` true repairs it without breaking any `D` (each is already satisfied by the
 * clashing literal), which [asRebuilds] states in reverse elimination order.
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
    ): PassDelta = run(problem, objectiveBoolVars, cancellation) { db, rebuild ->
        db.toDelta(rebuild.asSampleLift())
    } ?: PassDelta()

    /**
     * [eliminate] over a canonical source model.
     *
     * The blocked check reads the clause database and nothing else — no domain, no propagation — so the
     * argument holds before a finite projection exists. A blocking literal's variable must be eligible,
     * and `SatClauseDb.build` marks a Boolean ineligible when any factor that is not a clean clause
     * touches it, so the repair never flips a Boolean an integer row or a reified row reads.
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
        delta: (SatClauseDb, SourceRebuilds) -> T,
    ): T? {
        if (problem.numBoolVars == 0) return null
        val db = SatClauseDb.build(problem, objectiveBoolVars)

        val removed = ArrayList<Blocked>()
        // BCE is confluent — removing a blocked clause keeps the rest blocked — so a single sweep,
        // retiring clauses as it goes, is sound; later checks simply see fewer clauses to clash against.
        var considered = 0
        for (s in 0 until db.slotCount) {
            val c = db.clause(s) ?: continue
            if ((considered++ and CANCEL_POLL_MASK) == 0 && cancellation()) break
            val blocking = blockingLiteral(db, c, cancellation) ?: continue
            db.remove(s)
            removed.add(Blocked(c, blocking))
        }

        return delta(db, removed.asRebuilds())
    }

    /** A literal of [c] whose variable is eligible and on which [c] is blocked, or `null`. */
    private fun blockingLiteral(db: SatClauseDb, c: IntArray, cancellation: Cancellation): Int? {
        cancellation.charge(BCE_WORK_WEIGHT * (1L + c.size))
        for (l in c) {
            if (!db.eligible[Lit.variable(l)]) continue
            if (blockedOn(db, c, l, cancellation)) return l
        }
        return null
    }

    /** Whether [c] is blocked on [l]: every live clause containing `¬l` clashes with [c] elsewhere. */
    private fun blockedOn(db: SatClauseDb, c: IntArray, l: Int, cancellation: Cancellation): Boolean {
        val opposite = db.occ(Lit.negate(l))
        if (opposite.size > OCCURRENCE_CAP) return false
        cancellation.charge(BCE_WORK_WEIGHT * (c.size.toLong() + opposite.size))
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
     * The removed clauses as the steps that repair them, latest first.
     *
     * A blocked clause the assignment leaves unsatisfied is repaired by forcing its blocking literal
     * true; the blocking property guarantees this satisfies the clause without falsifying any clause
     * that contained the opposite literal.
     */
    private fun List<Blocked>.asRebuilds(): SourceRebuilds =
        SourceRebuilds(asReversed().map { RebuildStep.RepairClause(it.clause, it.blockingLit) })
}

// Work units per occurrence visited, against one simplex op. No model in the calibration sample (60 MIPLIB 2017,
// 40 QF_LIA and 40 MiniZinc Challenge) ran this pass, so it takes the weight the other per-term passes measured.
private const val BCE_WORK_WEIGHT = 10L
