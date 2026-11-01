package com.eignex.klause.propagation

import com.eignex.klause.solver.search.LearnedKind
import com.eignex.klause.solver.search.LearnedLifecycle
import com.eignex.klause.solver.search.LearnedStage
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.OriginCounts

/**
 * Learned-constraint database for [PropagationState]: the constraints learned during search plus the
 * parallel policy columns the three-tier reduction and forgetting passes read. Registration,
 * lookup and pruning live in `ClauseDb.kt`. Every learned constraint is stored as a [Propagator] so the
 * database is agnostic to the learned-constraint kind: a [com.eignex.klause.factor.bool.ClausePropagator]
 * and a pseudo-Boolean cutting-planes propagator share the same policy columns and forgetting machinery.
 */
internal class LearnedClauseDb(
    /** Count of binary (2-literal) clauses known — original problem clauses plus learned ones.
     *  Gates binary-resolution minimization, which is a no-op without binary clauses.
     *  Over-approximates after forgetting (never decremented), which only costs a harmless no-op
     *  pass — never correctness. */
    var binaryClauseCount: Int,
) {
    val lifecycle = LearnedLifecycle()
    val stages = IntArrayList()
    val pendingAttach = IntArrayList()
    internal val capCandidates = IntArrayList(1)
    internal val capRanking = LearnedCapRanking(capCandidates)
    internal var permanentClauseCount = 0
    internal var nonClauseCount = 0
    internal var retiredCount = 0
    internal var capCalls = 0L
    internal var capWork = 0L
    internal var capVisits = 0L
    internal var capBoundaryDefers = 0L
    internal var capPreparationDefers = 0L
    private var capAllocationCharged = false
    val units = IntArrayList()
    var unitsPending = false

    /** Learned constraints accumulated during search (LCG-style nogoods produced by [ConflictAnalyzer]).
     *  Their factor ids live in `[problem.numFactors, totalFactorCount)` — treat them like any other
     *  [Propagator] via `factorAt`; they participate in propagation through the per-literal watcher index
     *  just like static factors. Survives `restore` (learned constraints are facts about the original
     *  problem, not trail state); pruned by `forgetLearnedClauses`. */
    var store = ArrayList<LearnedPropagator>()

    internal var integerRows: ArrayList<LearnedIntegerPropagator>? = null

    /** LBD (literal block distance) per learned clause, parallel to [store]: lower = more re-usable.
     *  Forgetting policies key on it (G. Audemard, L. Simon, "Predicting Learnt Clauses Quality in
     *  Modern SAT Solvers", IJCAI 2009, https://www.ijcai.org/Proceedings/09/Papers/074.pdf). */
    val lbds = IntArrayList()

    /** 1 for clauses that must survive every forgetting pass, parallel to [store].
     *  Solution-blocking nogoods are the main client: dropping one re-opens an already reported
     *  leaf and the search can revisit it forever. */
    val permanent = IntArrayList()

    /** Three-tier database tier per learned clause, parallel to [store], stored as
     *  [ClauseTier] ordinals. [ClauseTier.UNSET] until the reduction policy first classifies it by
     *  LBD; the policy then promotes/demotes clauses between tiers based on reuse, so the tier is
     *  persistent state rather than a pure function of LBD. */
    val tier = IntArrayList()

    /** 1 iff the learned clause has detected a conflict or forced a unit since the last reduction,
     *  parallel to [store]. The three-tier reduction policy reads this to promote reused clauses
     *  and demote idle ones, then clears it for survivors. */
    val usedFlags = IntArrayList()

    /** The origin an imported clause came from, parallel to [store]; [OWN_ORIGIN] for one learned here. */
    val origins = IntArrayList()

    /** Uses of imported clauses, by origin. */
    val importUses = OriginCounts()

    /** Number of learned clauses. */
    val size: Int get() = store.size

    internal val activeCount: Int get() = size - retiredCount

    internal fun retiredOf(index: Int): Boolean = stages[index] and RETIRED != 0

    internal fun trackCapClause(index: Int, isPermanent: Boolean): Long {
        var work = 1L
        if (!capAllocationCharged) {
            work++
            capAllocationCharged = true
        }
        if (isPermanent) permanentClauseCount++ else {
            if (capCandidates.size == capCandidates.backingData.size) work += capCandidates.size * 3L
            capCandidates.add(index)
        }
        return work
    }

    internal fun resetCapIndex() {
        permanentClauseCount = 0
        nonClauseCount = 0
        retiredCount = 0
        capCandidates.clear()
        capRanking.reset()
    }

    internal fun retire(index: Int) {
        check(permanent[index] == 0 && !retiredOf(index))
        stages[index] = stages[index] or RETIRED
        retiredCount++
        lifecycle.note(LearnedKind.Clause, LearnedStage.Retired)
    }

    internal fun capWindowStatistics(): Map<String, Long> = if (capCalls == 0L) emptyMap() else mapOf(
        "learnedCapGeneralBackend" to 1L,
        "learnedCapCalls" to capCalls,
        "learnedCapWork" to capWork,
        "learnedCapVisits" to capVisits,
        "learnedCapBoundaryDefers" to capBoundaryDefers,
        "learnedCapFloorDefers" to 0L,
        "learnedCapPreparationDefers" to capPreparationDefers,
        "learnedCapRankedPasses" to capRanking.passes,
        "learnedCapPermanentClauses" to permanentClauseCount.toLong(),
        "learnedCapNonClauseFloor" to nonClauseCount.toLong(),
        "learnedCapRetainedSlots" to size.toLong(),
        "learnedCapRetiredSlots" to retiredCount.toLong(),
        "learnedCapActiveClauses" to activeCount.toLong(),
    )

    private companion object { const val RETIRED = 8 }
}

/** The origin of a learned clause the session derived itself rather than imported. */
internal const val OWN_ORIGIN = -1

/** The origin of an order-atom channeling clause: structural, rebuilt by every session that allocates the atoms, so
 *  never shared with another arm. */
internal const val CHANNELING_ORIGIN = -2
