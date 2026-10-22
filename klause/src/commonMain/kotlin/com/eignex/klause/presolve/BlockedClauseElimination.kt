package com.eignex.klause.presolve

import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntHashSet

/**
 * Blocked-clause elimination (BCE) over the pure-SAT part of the model. A
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
 *
 * M. Järvisalo, A. Biere, M. Heule, "Blocked clause elimination", TACAS 2010,
 * https://doi.org/10.1007/978-3-642-12002-2_10.
 */
internal object BlockedClauseElimination {

    private const val ATTEMPT_VISIT_CAP = 65_536

    /** Skip the blocked check on a literal whose opposite occurs in more than this many clauses — a work
     *  bound so a high-degree literal cannot make the check quadratic. */
    private const val OCCURRENCE_CAP = 4_096

    fun eliminate(
        problem: Problem,
        objectiveBoolVars: Set<Int> = emptySet(),
        cancellation: Cancellation = Cancellation.Never,
    ): PassDelta = run(problem, objectiveBoolVars, cancellation) { db, rebuild ->
        db.toDelta(rebuild)
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
        val db = SatClauseDb.build(problem, objectiveBoolVars, cancellation) ?: return null

        val removed = ArrayList<Blocked>()
        // BCE is confluent — removing a blocked clause keeps the rest blocked — so a single sweep,
        // retiring clauses as it goes, is sound; later checks simply see fewer clauses to clash against.
        for (s in 0 until db.slotCount) {
            if (cancellation()) break
            val c = db.clause(s) ?: continue
            val blocking = blockingLiteral(db, c, AttemptWork(cancellation)) ?: continue
            db.remove(s)
            removed.add(Blocked(c, blocking))
        }

        return delta(db, removed.asRebuilds())
    }

    /** A literal of [c] whose variable is eligible and on which [c] is blocked, or `null`. */
    private fun blockingLiteral(db: SatClauseDb, c: IntArray, work: AttemptWork): Int? {
        if (!work.visit()) return null
        for (l in c) {
            if (!work.visit()) return null
            if (!db.eligible[Lit.variable(l)]) continue
            if (blockedOn(db, c, l, work)) return l
            if (work.stopped) return null
        }
        return null
    }

    /** Whether [c] is blocked on [l]: every live clause containing `¬l` clashes with [c] elsewhere. */
    private fun blockedOn(db: SatClauseDb, c: IntArray, l: Int, work: AttemptWork): Boolean {
        val opposite = db.occ(Lit.negate(l))
        if (opposite.size > OCCURRENCE_CAP) return false
        if (opposite.size == 0) return true
        val v = Lit.variable(l)
        val cLits = IntHashSet()
        for (k in c) {
            if (!work.visit()) return false
            if (Lit.variable(k) != v) cLits.add(k)
        }
        for (i in 0 until opposite.size) {
            if (!work.visit()) return false
            val d = db.clause(opposite[i]) ?: continue
            if (!clashesElsewhere(cLits, d, v, work)) return false
        }
        return true
    }

    /** Whether some literal of [d] (over a variable other than [v]) has its negation in [cLits] — the
     *  clash that makes the `C`/`D` resolvent on [v] a tautology. */
    private fun clashesElsewhere(cLits: IntHashSet, d: IntArray, v: Int, work: AttemptWork): Boolean {
        for (dl in d) {
            if (!work.visit()) return false
            if (Lit.variable(dl) == v) continue
            if (Lit.negate(dl) in cLits) return true
        }
        return false
    }

    private class AttemptWork(private val cancellation: Cancellation) {
        private var visits = 0
        var stopped = false
            private set

        fun visit(): Boolean {
            if (stopped) return false
            if (visits >= ATTEMPT_VISIT_CAP) {
                stopped = true
                cancellation.presolveBudget()?.activeInvocation?.interrupted = true
                return false
            }
            visits++
            cancellation.charge(BCE_WORK_WEIGHT)
            stopped = cancellation()
            return !stopped
        }
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

// Work units per clause, candidate, occurrence or literal visited, relative to one simplex operation.
private const val BCE_WORK_WEIGHT = 10L
