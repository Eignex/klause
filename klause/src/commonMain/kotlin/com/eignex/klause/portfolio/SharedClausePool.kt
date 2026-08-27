package com.eignex.klause.portfolio

import com.eignex.klause.propagation.ClauseExchange
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.SharedClause
import com.eignex.klause.util.LongHashSet
import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.stream.Mutex
import com.eignex.kumulant.stream.lock

/**
 * An append-only, de-duplicated pool of session-portable learned nogoods ([SharedClause]) shared
 * across the backtrack arms of one `Problem`. Arms publish their glue
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
 * De-dup is by [SharedClause.key]. A bounded [cap] stops growth: a publish that would overflow first
 * compacts the pool to half, keeping globally-published nogoods (soundness-motivated shares that
 * bypassed the glue filter) ahead of the best glue by (LBD, length), so publishing keeps working for
 * the whole solve instead of silently stopping at the cap. Compaction bumps a generation stamp that
 * rides in the high bits of every cursor: a cursor from an older generation re-drains the whole pool,
 * and the arm-side `seen` key-set makes that re-drain duplicate-free — so eviction costs a stale arm
 * one full (deduped) import, never a missed or double-registered clause. Evicted keys leave the
 * de-dup set, so a re-derived clause can re-enter.
 */
internal class SharedClausePool(
    private val lock: Mutex = Concurrency.None.lock(),
    private val cap: Int = DEFAULT_CAP,
    /** Default LBD bound for the arms' glue export ([PoolClauseExchange]); the pool carries it so
     *  every arm of one portfolio shares a single tunable filter (`--param clause-share-lbd`). */
    val shareMaxLbd: Int = PoolClauseExchange.DEFAULT_MAX_LBD,
    /** Default length bound for the arms' glue export, the length half of the shared filter
     *  (`--param clause-share-len`). */
    val shareMaxLen: Int = PoolClauseExchange.DEFAULT_MAX_LEN,
) {
    private val clauses = ArrayList<SharedClause>()
    private val global = ArrayList<Boolean>()
    private val keys = LongHashSet()
    private var generation = 0

    /** Append the unseen clauses of [batch] (by key), compacting on overflow. [isGlobal] marks a
     *  globally-published nogood for retention priority over filtered glue. */
    fun publish(batch: List<SharedClause>, isGlobal: Boolean = false) {
        if (batch.isEmpty()) return
        lock.withLock {
            for (c in batch) {
                if (clauses.size >= cap) compact()
                if (clauses.size >= cap) break
                if (keys.add(c.key)) {
                    clauses.add(c)
                    global.add(isGlobal)
                }
            }
        }
    }

    /** The clauses appended at index ≥ [cursor] of the cursor's generation, paired with the advanced
     *  cursor. A cursor from an older generation restarts at index 0 (see the class doc). */
    fun drainSince(cursor: Long): Drained = lock.withLock {
        val from = if (generationOf(cursor) == generation) indexOf(cursor) else 0
        val size = clauses.size
        val advanced = cursorOf(generation, size)
        if (from >= size) {
            Drained(emptyList(), advanced)
        } else {
            Drained(ArrayList(clauses.subList(from, size)), advanced)
        }
    }

    /** Keep the best half — globals first, then by (LBD, length), stable within ties — and start a
     *  new generation. Callers hold [lock]. */
    private fun compact() {
        val target = cap / 2
        val order = clauses.indices.sortedWith(
            compareByDescending<Int> { global[it] }.thenBy { clauses[it].lbd }.thenBy { lengthOf(clauses[it]) },
        )
        val keep = BooleanArray(clauses.size)
        for (k in 0 until minOf(target, order.size)) keep[order[k]] = true
        val keptClauses = ArrayList<SharedClause>(target)
        val keptGlobal = ArrayList<Boolean>(target)
        keys.clear()
        for (i in clauses.indices) {
            if (!keep[i]) continue
            keptClauses.add(clauses[i])
            keptGlobal.add(global[i])
            keys.add(clauses[i].key)
        }
        clauses.clear()
        clauses.addAll(keptClauses)
        global.clear()
        global.addAll(keptGlobal)
        generation++
    }

    /** A drained batch + the advanced cursor. */
    internal class Drained(val clauses: List<SharedClause>, val cursor: Long)

    internal companion object {
        const val DEFAULT_CAP = 50_000

        private fun cursorOf(generation: Int, index: Int): Long = (generation.toLong() shl 32) or index.toLong()
        private fun generationOf(cursor: Long): Int = (cursor ushr 32).toInt()
        private fun indexOf(cursor: Long): Int = cursor.toInt()
        private fun lengthOf(c: SharedClause): Int = c.boolLits.size + c.atomQuads.size / SharedClause.QUAD
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
    private val maxLbd: Int = pool.shareMaxLbd,
    private val maxLen: Int = pool.shareMaxLen,
    /** Skip permanent (search-conditioned) clauses on export — the incumbent objective bound and
     *  blocking nogoods. Required for an arm learning under assumptions/an incumbent (LNS repair): those
     *  clauses hold only under its pins, so sharing them globally is unsound. */
    private val skipPermanent: Boolean = false,
    /** Publish globally-valid nogoods (LP Farkas) via [publishGlobal]. An LNS-repair arm derives Farkas
     *  certificates under its pins, so they are not globally valid there — it disables this. */
    private val shareGlobalNogoods: Boolean = true,
) : ClauseExchange {
    private var cursor = 0L
    private val seen = LongHashSet()

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
        // Resetting both makes the import unconditional; the pool de-dups any re-export by key.
        cursor = 0L
        seen.clear()
        onRestart(session)
    }

    override fun onSearchEnd(session: PropagationSession) = export(session)

    /** Publish a globally-valid nogood straight to the pool (no LBD/length filter), deduped by the
     *  `seen` set so this arm neither double-publishes it nor re-imports its own. */
    override fun publishGlobal(clause: SharedClause) {
        if (!shareGlobalNogoods) return
        if (seen.add(clause.key)) pool.publish(listOf(clause), isGlobal = true)
    }

    /** Publish this arm's not-yet-seen glue clauses; safe at any decision level (read-only on the
     *  trail). The `seen` set guards against re-export within a session; the pool de-dups globally. */
    private fun export(session: PropagationSession) {
        val fresh = session.exportGlueClauses(maxLbd, maxLen, skipPermanent).filter { seen.add(it.key) }
        if (fresh.isNotEmpty()) pool.publish(fresh)
    }

    internal companion object {
        // 6/12 measured best on the 4-core pool (see PortfolioScenario.clauseShareMaxLbd).
        const val DEFAULT_MAX_LBD = 6
        const val DEFAULT_MAX_LEN = 12
    }
}
