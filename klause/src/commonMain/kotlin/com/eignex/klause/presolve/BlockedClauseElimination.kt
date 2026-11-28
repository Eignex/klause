package com.eignex.klause.presolve

import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.collections.IntHashSet

// Blocked-clause elimination over the pure-SAT part of the model. A clause `C` is blocked on its literal `ℓ`
// when every resolvent of `C` on `var(ℓ)` is a tautology. A blocked clause is satisfiability-redundant;
// if a reduced solution falsifies `C`, setting `ℓ` true repairs it without breaking any clause containing
// `¬ℓ`. The blocking variable must be [SatClauseDb.eligible] (objective-free, only in clean all-Boolean
// clauses), so the repair affects nothing else. Satisfiability-preserving, not solution-set preserving.
// M. Järvisalo, A. Biere, M. Heule, "Blocked clause elimination", TACAS 2010,
// https://doi.org/10.1007/978-3-642-12002-2_10.
internal object BlockedClauseElimination {

    private const val ATTEMPT_VISIT_CAP = 65_536

    // Bounds the blocked check so a high-degree literal cannot make it quadratic.
    private const val OCCURRENCE_CAP = 4_096

    fun eliminate(
        problem: Problem,
        objectiveBoolVars: Set<Int> = emptySet(),
        cancellation: ExecutionControl = ExecutionControl.Never,
    ): PassDelta = run(problem, objectiveBoolVars, cancellation) { db, rebuild ->
        db.toDelta(rebuild)
    } ?: PassDelta()

    // The blocked check reads only the clause database, so it holds before a finite projection exists.
    fun eliminateSource(
        problem: Problem,
        objectiveBoolVars: Set<Int> = emptySet(),
        cancellation: ExecutionControl = ExecutionControl.Never,
    ): SourceDelta = run(problem, objectiveBoolVars, cancellation) { db, rebuild ->
        db.toSourceDelta(rebuild)
    } ?: SourceDelta()

    private fun <T> run(
        problem: Problem,
        objectiveBoolVars: Set<Int>,
        cancellation: ExecutionControl,
        delta: (SatClauseDb, SourceRebuilds) -> T,
    ): T? {
        if (problem.numBoolVars == 0) return null
        val db = SatClauseDb.build(problem, objectiveBoolVars, cancellation) ?: return null

        val removed = ArrayList<Blocked>()
        // BCE is confluent (removing a blocked clause keeps the rest blocked), so one retiring sweep is sound.
        for (s in 0 until db.slotCount) {
            if (cancellation()) break
            val c = db.clause(s) ?: continue
            val blocking = blockingLiteral(db, c, AttemptWork(cancellation)) ?: continue
            db.remove(s)
            removed.add(Blocked(c, blocking))
        }

        return delta(db, removed.asRebuilds())
    }

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

    private fun clashesElsewhere(cLits: IntHashSet, d: IntArray, v: Int, work: AttemptWork): Boolean {
        for (dl in d) {
            if (!work.visit()) return false
            if (Lit.variable(dl) == v) continue
            if (Lit.negate(dl) in cLits) return true
        }
        return false
    }

    private class AttemptWork(private val cancellation: ExecutionControl) {
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

    private class Blocked(val clause: IntArray, val blockingLit: Int)

    // Latest first: each repair forces the blocking literal of a clause the assignment leaves unsatisfied.
    private fun List<Blocked>.asRebuilds(): SourceRebuilds =
        SourceRebuilds(asReversed().map { RebuildStep.RepairClause(it.clause, it.blockingLit) })
}

// Work units per clause, candidate, occurrence or literal visited, relative to one simplex operation.
private const val BCE_WORK_WEIGHT = 10L
