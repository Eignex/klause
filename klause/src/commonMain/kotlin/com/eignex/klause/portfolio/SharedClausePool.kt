package com.eignex.klause.portfolio

import com.eignex.klause.propagation.ClauseExchange
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.SharedClause
import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.stream.Mutex
import com.eignex.kumulant.stream.lock

/**
 * An append-only, de-duplicated pool of session-portable learned nogoods ([SharedClause]) shared
 * across the backtrack arms of one [com.eignex.klause.solver.Problem]. Arms publish their glue
 * clauses and pull others' via a per-arm cursor ([PoolClauseExchange]); every arm benefits from
 * every arm's learning.
 *
 * Used by both portfolio executors: the parallel `Portfolio` (many arms publishing concurrently —
 * needs a real lock) and the single-threaded [SequentialPortfolio] (arms run in time-sliced
 * segments, so the pool is also the *persistent memory* that survives a backtrack arm's session
 * rebuild between segments — it has no resume otherwise). The [lock] comes from the executor's
 * [Concurrency] via [com.eignex.kumulant.stream.lock]: a no-op under `Concurrency.None` (the single
 * core pays nothing), a platform mutex under the parallel executor's concurrent writers.
 *
 * De-dup is by [SharedClause.key]; a bounded [cap] stops growth (the pool simply stops accepting
 * new clauses when full — it never evicts, so per-arm cursors stay valid).
 */
internal class SharedClausePool(
    private val lock: Mutex = Concurrency.None.lock(),
    private val cap: Int = DEFAULT_CAP,
) {
    private val clauses = ArrayList<SharedClause>()
    private val keys = HashSet<Long>()

    /** Append the unseen clauses of [batch] (by key), up to [cap]. */
    fun publish(batch: List<SharedClause>) {
        if (batch.isEmpty()) return
        lock.withLock {
            for (c in batch) {
                if (clauses.size >= cap) break
                if (keys.add(c.key)) clauses.add(c)
            }
        }
    }

    /** The clauses appended at index ≥ [cursor], paired with the new cursor (the current size). */
    fun drainSince(cursor: Int): Drained = lock.withLock {
        val size = clauses.size
        if (cursor >= size) Drained(emptyList(), size) else Drained(ArrayList(clauses.subList(cursor, size)), size)
    }

    /** A drained batch + the advanced cursor. */
    internal class Drained(val clauses: List<SharedClause>, val cursor: Int)

    internal companion object {
        const val DEFAULT_CAP = 50_000
    }
}

/**
 * A per-arm [ClauseExchange] over a [SharedClausePool]: at each restart it imports the nogoods
 * published since this arm last looked and exports the arm's new glue clauses (LBD ≤ [maxLbd],
 * length ≤ [maxLen]). A `seen` key-set holds every key this arm has already imported or exported, so
 * it never re-imports its own clauses nor re-exports a clause twice; the pool de-dups globally on top.
 */
internal class PoolClauseExchange(
    private val pool: SharedClausePool,
    private val maxLbd: Int = DEFAULT_MAX_LBD,
    private val maxLen: Int = DEFAULT_MAX_LEN,
) : ClauseExchange {
    private var cursor = 0
    private val seen = HashSet<Long>()

    override fun onRestart(session: PropagationSession) {
        val drained = pool.drainSince(cursor)
        cursor = drained.cursor
        for (c in drained.clauses) {
            if (seen.add(c.key)) session.importClause(c)
        }
        export(session)
    }

    override fun onSearchStart(session: PropagationSession) {
        // Fresh session (a single-threaded portfolio rebuilds the arm's session every segment): its
        // learned DB is empty, so re-import the whole pool — including this arm's own clauses from
        // earlier segments, which the persistent-session `seen`/`cursor` would otherwise suppress.
        // Resetting both makes the import unconditional; the pool de-dups any re-export by key (#381).
        cursor = 0
        seen.clear()
        onRestart(session)
    }

    override fun onSearchEnd(session: PropagationSession) = export(session)

    /** Publish a globally-valid nogood straight to the pool (no LBD/length filter), deduped by the
     *  `seen` set so this arm neither double-publishes it nor re-imports its own. */
    override fun publishGlobal(clause: SharedClause) {
        if (seen.add(clause.key)) pool.publish(listOf(clause))
    }

    /** Publish this arm's not-yet-seen glue clauses; safe at any decision level (read-only on the
     *  trail). The `seen` set guards against re-export within a session; the pool de-dups globally. */
    private fun export(session: PropagationSession) {
        val fresh = session.exportGlueClauses(maxLbd, maxLen).filter { seen.add(it.key) }
        if (fresh.isNotEmpty()) pool.publish(fresh)
    }

    internal companion object {
        const val DEFAULT_MAX_LBD = 4
        const val DEFAULT_MAX_LEN = 8
    }
}
