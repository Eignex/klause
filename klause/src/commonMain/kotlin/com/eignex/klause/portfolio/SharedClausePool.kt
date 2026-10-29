package com.eignex.klause.portfolio

import com.eignex.klause.propagation.ClauseExchange
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.SharedClause
import com.eignex.klause.solver.result.SharingChannel
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.LongHashSet
import com.eignex.klause.util.SharingMemoryObservation
import com.eignex.klause.util.SharingMemorySnapshot
import com.eignex.klause.util.SharingMemoryStatus
import com.eignex.klause.util.mixLongKey
import com.eignex.klause.util.openAddressingCapacity
import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.stream.Mutex
import com.eignex.kumulant.stream.lock

/**
 * An append-only, de-duplicated pool of session-portable learned nogoods ([SharedClause]) shared
 * across the backtrack arms of one `Problem`. Arms publish their glue
 * clauses and pull others' via a per-arm cursor ([PoolClauseExchange]); every arm benefits from
 * every arm's learning.
 *
 * Used by the [Portfolio] on any number of lanes: several lanes publish concurrently and need a real lock,
 * and across segments the pool is also the *persistent memory* that survives a backtrack arm's handle being
 * rebuilt. The [lock] comes from the executor's
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
 *
 * Glue clauses and the literal block distance (LBD) follow G. Audemard, L. Simon, "Predicting learnt clauses quality
 * in modern SAT solvers", IJCAI 2009, https://www.ijcai.org/Proceedings/09/Papers/074.pdf.
 */
internal class SharedClausePool(
    private val lock: Mutex = Concurrency.None.lock(),
    private val cap: Int = DEFAULT_CAP,
    internal val measureMemory: Boolean = false,
    internal val memorySampleWork: Long = SharingMemoryObservation.DEFAULT_WORK,
    internal val memorySampleScratchEntries: Int = SharingMemoryObservation.DEFAULT_SCRATCH_ENTRIES,
    /** Default LBD bound for the arms' glue export ([PoolClauseExchange]); the pool carries it so
     *  every arm of one portfolio shares a single tunable filter (`--param clause-share-lbd`). */
    val shareMaxLbd: Int = PoolClauseExchange.DEFAULT_MAX_LBD,
    /** Default length bound for the arms' glue export, the length half of the shared filter
     *  (`--param clause-share-len`). */
    val shareMaxLen: Int = PoolClauseExchange.DEFAULT_MAX_LEN,
    internal val softLimitBytes: Long? = null,
    internal val reclamationWork: Long = DEFAULT_RECLAMATION_WORK,
    internal val reclamationScratchEntries: Int = DEFAULT_RECLAMATION_SCRATCH,
) {
    private var store = Store(0)
    private var generation = 0
    private var compactions = 0L
    private var declined = 0L
    private var deferred = 0L

    init {
        require(cap > 0)
        require(softLimitBytes == null || softLimitBytes >= 0L)
        require(reclamationWork >= 0L && reclamationScratchEntries >= 0)
    }

    internal fun memory(): Map<String, Long> = lock.withLock {
        mapOf(
            "poolClauses" to store.size.toLong(),
            "poolPayloadBytes" to store.payloadBytes,
            "poolKeyCapacityBytes" to store.keyCapacityBytes,
            "poolOriginCapacityBytes" to store.capacity.toLong() * Int.SIZE_BYTES,
            "poolReferenceSlots" to store.capacity.toLong(),
            "poolPrivateIndexBytes" to store.indexBytes,
            "poolOwnedBytes" to store.ownedBytes,
            "poolProtectedBytes" to 0L,
            "poolGeneration" to generation.toLong(),
            "poolCompactions" to compactions,
            "poolDeclined" to declined,
            "poolReclamationDeferred" to deferred,
        )
    }

    /** Append the unseen clauses of [batch] (by key) as published by arm [origin], compacting on overflow.
     *  [isGlobal] marks a globally-published nogood for retention priority over filtered glue. A clause
     *  already pooled keeps the arm that published it first. */
    fun publish(
        batch: List<SharedClause>,
        origin: Int = NO_ORIGIN,
        isGlobal: Boolean = false,
        maxWork: Long = reclamationWork,
        maxScratchEntries: Int = reclamationScratchEntries,
        cancelled: () -> Boolean = { false },
        charge: () -> Unit = {},
    ): Int = lock.withLock {
        val budget = Budget(maxWork, maxScratchEntries, cancelled, charge)
        var published = 0
        try {
            for (c in batch) {
                budget.step()
                if (store.contains(c.key, budget)) continue
                if (store.size >= cap || exceedsLimit(c, budget)) compact(budget)
                if (store.size >= cap || exceedsLimit(c, budget)) {
                    declined++
                    continue
                }
                if (store.size == store.capacity) grow(budget)
                if (store.append(c, isGlobal, origin, budget, softLimitBytes ?: Long.MAX_VALUE)) published++
                else declined++
            }
        } catch (_: Stopped) {
            deferred++
        }
        published
    }

    internal fun reclaim(
        maxWork: Long = reclamationWork,
        maxScratchEntries: Int = reclamationScratchEntries,
        cancelled: () -> Boolean = { false },
        charge: () -> Unit = {},
    ): Reclamation = lock.withLock {
        val before = store.ownedBytes
        val budget = Budget(maxWork, maxScratchEntries, cancelled, charge)
        val status = try {
            if (store.size == 0) ReclamationStatus.UNCHANGED else {
                compact(budget)
                ReclamationStatus.RECLAIMED
            }
        } catch (_: Stopped) {
            deferred++
            if (cancelled()) ReclamationStatus.CANCELLED else ReclamationStatus.DEFERRED
        }
        Reclamation(status, before, store.ownedBytes, budget.work, budget.scratchEntries)
    }

    /** The clauses appended at index ≥ [cursor] of the cursor's generation, paired with the advanced
     *  cursor. A cursor from an older generation restarts at index 0 (see the class doc). */
    fun drainSince(cursor: Long): Drained = lock.withLock {
        val from = if (generationOf(cursor) == generation) indexOf(cursor) else 0
        val size = store.size
        val advanced = cursorOf(generation, size)
        if (from >= size) {
            Drained(emptyList(), EmptyIntArray, advanced)
        } else {
            Drained(List(size - from) { store.clause(from + it) },
                IntArray(size - from) { store.origins[from + it] }, advanced)
        }
    }

    internal fun drainSinceFunded(
        cursor: Long,
        cancelled: () -> Boolean,
        charge: () -> Unit,
    ): Drained? = lock.withLock {
        val from = if (generationOf(cursor) == generation) indexOf(cursor) else 0
        val size = store.size
        val count = (size - from).coerceAtLeast(0)
        if (count.toLong() * 4L + 3L > reclamationWork || count.toLong() * 2L > reclamationScratchEntries) {
            return@withLock null
        }
        val budget = Budget(reclamationWork, reclamationScratchEntries, cancelled, charge)
        try {
            budget.reserve(count.toLong() * 2L)
            val clauses = List(count) { budget.step(); store.clause(from + it) }
            val origins = IntArray(count) { budget.step(); store.origins[from + it] }
            budget.step()
            Drained(clauses, origins, cursorOf(generation, size))
        } catch (_: Stopped) {
            null
        }
    }

    private fun exceedsLimit(clause: SharedClause, budget: Budget): Boolean {
        val limit = softLimitBytes ?: return false
        val capacity = if (store.size == store.capacity) nextCapacity() else store.capacity
        return Store.indexBytes(capacity) + store.payloadBytes + store.additionalPayload(clause, budget) > limit
    }

    private fun nextCapacity(): Int {
        val desired = minOf(cap, maxOf(1, store.capacity * 2))
        val limit = softLimitBytes ?: return desired
        return if (Store.indexBytes(desired) + store.payloadBytes <= limit) desired else store.size + 1
    }

    private fun grow(budget: Budget) {
        val replacement = preparedStore(nextCapacity(), budget)
        for (i in 0 until store.size) {
            budget.step()
            replacement.append(store.clause(i), store.global[i], store.origins[i], budget)
        }
        budget.step()
        store = replacement
    }

    private fun compact(budget: Budget) {
        budget.reserve(store.size * 2L)
        val order = IntArray(store.size) { budget.step(); it }
        sort(order, budget)
        val limit = softLimitBytes?.div(2L) ?: Long.MAX_VALUE
        var target = minOf(cap / 2, store.size / 2)
        while (Store.indexBytes(target) > limit) {
            budget.step()
            target /= 2
        }
        val replacement = preparedStore(target, budget)
        val keep = BooleanArray(store.size)
        for (i in order) {
            budget.step()
            if (replacement.size >= target) break
            if (replacement.append(store.clause(i), store.global[i], store.origins[i], budget, limit)) keep[i] = true
        }
        // Restore insertion order without retaining another set of payload or index arrays.
        var next = 0
        for (i in keep.indices) {
            budget.step()
            if (!keep[i]) continue
            replacement.clauses[next] = store.clause(i)
            replacement.global[next] = store.global[i]
            replacement.origins[next++] = store.origins[i]
        }
        budget.step()
        store = replacement
        generation++
        compactions++
    }

    private fun preparedStore(capacity: Int, budget: Budget): Store {
        budget.reserve(Store.scratchEntries(capacity))
        return Store(capacity)
    }

    private fun sort(order: IntArray, budget: Budget) {
        fun compare(left: Int, right: Int): Int {
            budget.step()
            val a = store.clause(left)
            val b = store.clause(right)
            return when {
                store.global[left] != store.global[right] -> if (store.global[left]) -1 else 1
                a.lbd != b.lbd -> a.lbd.compareTo(b.lbd)
                lengthOf(a) != lengthOf(b) -> lengthOf(a).compareTo(lengthOf(b))
                else -> left.compareTo(right)
            }
        }
        fun sift(start: Int, end: Int) {
            var root = start
            while (root * 2 + 1 < end) {
                budget.step()
                var child = root * 2 + 1
                if (child + 1 < end && compare(order[child], order[child + 1]) < 0) child++
                if (compare(order[root], order[child]) >= 0) break
                val value = order[root]
                order[root] = order[child]
                order[child] = value
                root = child
            }
        }
        for (i in order.size / 2 - 1 downTo 0) sift(i, order.size)
        for (end in order.lastIndex downTo 1) {
            budget.step()
            val value = order[0]
            order[0] = order[end]
            order[end] = value
            sift(0, end)
        }
    }

    private class Store(val capacity: Int) {
        val clauses = arrayOfNulls<SharedClause>(capacity)
        val global = BooleanArray(capacity)
        val origins = IntArray(capacity)
        private val keyCapacity = if (capacity == 0) 0 else openAddressingCapacity(capacity)
        private val ownerCapacity = if (capacity == 0) 0 else openAddressingCapacity(capacity * 2)
        private val keys = LongArray(keyCapacity)
        private val used = BooleanArray(keyCapacity)
        private val owners = arrayOfNulls<Any>(ownerCapacity)
        var size = 0
        var payloadBytes = 0L
        val keyCapacityBytes: Long get() = keyCapacity.toLong() * 9L
        val indexBytes: Long get() = indexBytes(capacity)
        val ownedBytes: Long get() = indexBytes + payloadBytes

        fun clause(index: Int): SharedClause = checkNotNull(clauses[index])

        fun contains(key: Long, budget: Budget): Boolean = size > 0 && used[keySlot(key, budget)]

        private fun keySlot(key: Long, budget: Budget): Int {
            var index = mixLongKey(key) and (keyCapacity - 1)
            while (true) {
                budget.step()
                if (!used[index] || keys[index] == key) return index
                index = (index + 1) and (keyCapacity - 1)
            }
        }

        private fun ownerSlot(owner: Any, budget: Budget, reserved: Int = -1): Int {
            var index = owner.hashCode() and (ownerCapacity - 1)
            while (true) {
                budget.step()
                if (index != reserved && (owners[index] == null || owners[index] === owner)) return index
                index = (index + 1) and (ownerCapacity - 1)
            }
        }

        fun additionalPayload(clause: SharedClause, budget: Budget): Long {
            if (size == 0) return clause.payloadBytes
            var bytes = 0L
            if (owners[ownerSlot(clause.boolLits, budget)] == null) {
                bytes += clause.boolLits.size.toLong() * Int.SIZE_BYTES
            }
            if (owners[ownerSlot(clause.atomQuads, budget)] == null) {
                bytes += clause.atomQuads.size.toLong() * Long.SIZE_BYTES
            }
            return bytes
        }

        fun append(
            clause: SharedClause,
            isGlobal: Boolean,
            origin: Int,
            budget: Budget,
            limit: Long = Long.MAX_VALUE,
        ): Boolean {
            val key = keySlot(clause.key, budget)
            val boolOwner = ownerSlot(clause.boolLits, budget)
            val atomOwner = ownerSlot(clause.atomQuads, budget, if (owners[boolOwner] == null) boolOwner else -1)
            val boolBytes = if (owners[boolOwner] == null) clause.boolLits.size.toLong() * Int.SIZE_BYTES else 0L
            val atomBytes = if (owners[atomOwner] == null) clause.atomQuads.size.toLong() * Long.SIZE_BYTES else 0L
            if (ownedBytes + boolBytes + atomBytes > limit) return false
            budget.step()
            keys[key] = clause.key
            used[key] = true
            owners[boolOwner] = clause.boolLits
            owners[atomOwner] = clause.atomQuads
            payloadBytes += boolBytes + atomBytes
            clauses[size] = clause
            global[size] = isGlobal
            origins[size++] = origin
            return true
        }

        companion object {
            fun indexBytes(capacity: Int): Long = if (capacity == 0) 0L else
                capacity.toLong() * 13L + openAddressingCapacity(capacity).toLong() * 9L +
                    openAddressingCapacity(capacity * 2).toLong() * 8L

            fun scratchEntries(capacity: Int): Long = if (capacity == 0) 0L else
                capacity.toLong() * 3L + openAddressingCapacity(capacity).toLong() * 2L +
                    openAddressingCapacity(capacity * 2).toLong()
        }
    }

    private class Budget(
        private val maxWork: Long,
        private val maxScratchEntries: Int,
        private val cancelled: () -> Boolean,
        private val charge: () -> Unit,
    ) {
        var work = 0L
        var scratchEntries = 0

        init { require(maxWork >= 0L && maxScratchEntries >= 0) }

        fun step() {
            if (work >= maxWork || cancelled()) throw Stopped()
            charge()
            work++
            if (cancelled()) throw Stopped()
        }

        fun reserve(entries: Long) {
            step()
            if (entries > maxScratchEntries - scratchEntries) throw Stopped()
            var remaining = entries
            while (remaining-- > 0L) step()
            scratchEntries += entries.toInt()
        }
    }

    private class Stopped : RuntimeException()

    internal enum class ReclamationStatus { RECLAIMED, UNCHANGED, DEFERRED, CANCELLED }

    internal data class Reclamation(
        val status: ReclamationStatus,
        val beforeBytes: Long,
        val afterBytes: Long,
        val work: Long,
        val scratchEntries: Int,
    )

    /** A drained batch, the arm that published each of its clauses, and the advanced cursor. */
    internal class Drained(val clauses: List<SharedClause>, val origins: IntArray, val cursor: Long)

    internal companion object {
        const val DEFAULT_CAP = 50_000
        private const val DEFAULT_RECLAMATION_WORK = 4_000_000L
        private const val DEFAULT_RECLAMATION_SCRATCH = 2_000_000

        /** The origin of a clause whose publisher named no arm. */
        const val NO_ORIGIN = -1

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
    /** The arm this exchange publishes for; see [SharedClausePool.publish]. */
    private val origin: Int = SharedClausePool.NO_ORIGIN,
    /** Where uses of imported clauses are counted for the arms that published them; null counts nothing. */
    private val tally: ContributionTally? = null,
    private val maxLbd: Int = pool.shareMaxLbd,
    private val maxLen: Int = pool.shareMaxLen,
    /** Skip permanent (search-conditioned) clauses on export — the incumbent objective bound and
     *  blocking nogoods. Required for an arm learning under assumptions/an incumbent (LNS repair): those
     *  clauses hold only under its pins, so sharing them globally is unsound. */
    private val skipPermanent: Boolean = false,
    /** Publish globally-valid nogoods (LP Farkas) via [publishGlobal]. An LNS-repair arm derives Farkas
     *  certificates under its pins, so they are not globally valid there — it disables this. */
    private val shareGlobalNogoods: Boolean = true,
    /** Where this arm's clause traffic is recorded; null records nothing. */
    private val meter: SharingMeter? = null,
) : ClauseExchange {
    private var cursor = 0L
    private val seen = LongHashSet()
    private var sessionIdentity: Any? = null

    private fun observe(session: PropagationSession) {
        val m = meter ?: return
        if (!pool.measureMemory) return
        val sample = session.sharingMemory(pool.memorySampleWork, pool.memorySampleScratchEntries)
        val work = sample.work
        m.clauseCount("accountingWork", work)
        m.clauseMemorySample(pool.memory() + mapOf(
            "seenKeys" to seen.size.toLong(),
            "seenCapacityBytes" to seen.allocatedBytes,
        ) + sample.telemetry())
    }

    private fun retainSession(session: PropagationSession) {
        if (sessionIdentity == null) {
            sessionIdentity = session.sharingIdentity
            meter?.clauseCount("sessions", 1L)
        }
        if (sessionIdentity === session.sharingIdentity) {
            val identity = session.sharingIdentity
            session.retainedSharingMemory = { sample ->
                if (sessionIdentity === identity) sample.owned(seen, seen.allocatedBytes, protected = true)
            }
            session.retainedSharingSize = { if (sessionIdentity === identity) seen.allocatedBytes else 0L }
        }
    }

    private fun admit(key: Long, session: PropagationSession? = null): Boolean? {
        val limit = pool.softLimitBytes ?: return seen.add(key)
        val added = seen.tryAddBounded(
            key, limit, pool.reclamationWork, pool.reclamationScratchEntries.toLong() * Long.SIZE_BYTES,
            cancelled = { session?.cancelRequested == true },
            charge = { session?.chargeSharingWork(1L) },
        )
        if (added == null) meter?.clauseCount("seenDeclined", 1L)
        return added
    }

    override fun onRestart(session: PropagationSession) = metered {
        retainSession(session)
        val drained = if (pool.softLimitBytes == null) pool.drainSince(cursor) else pool.drainSinceFunded(
            cursor, { session.cancelRequested }, { session.chargeSharingWork(1L) },
        )
        if (drained == null) {
            meter?.clauseCount("drainPreparationDeclined", 1L)
            return@metered
        }
        cursor = drained.cursor
        var imported = 0
        var duplicates = 0
        val before = session.work
        session.chargeSharingWork(drained.clauses.size.toLong())
        var literalVisits = 0L
        for (i in drained.clauses.indices) {
            val c = drained.clauses[i]
            // An arm's own clause, re-imported into a rebuilt session, earns it nothing.
            val from = drained.origins[i].takeIf { it != origin } ?: SharedClausePool.NO_ORIGIN
            val admitted = admit(c.key, session)
            if (admitted == true) {
                literalVisits += c.boolLits.size + c.atomQuads.size / SharedClause.QUAD
                session.importClause(c, from)
                imported++
            }
            if (admitted == false) duplicates++
        }
        meter?.clauseCount("inspected", drained.clauses.size.toLong())
        meter?.clauseCount("translatedLiterals", literalVisits)
        meter?.clauseCount("importWork", session.work - before)
        meter?.clauseMemory(mapOf("drainReferenceSlots" to drained.clauses.size.toLong(),
            "drainOriginBytes" to drained.origins.size.toLong() * Int.SIZE_BYTES))
        meter?.imported(SharingChannel.Clauses, imported, drained.cursor)
        meter?.duplicates(SharingChannel.Clauses, duplicates)
        countUses(session)
        export(session)
        observe(session)
    }

    override fun onSearchStart(session: PropagationSession) {
        // A fresh native store needs the pool's retained clauses, including this arm's old exports.
        // Rebinding assumptions on the same store preserves its imports and duplicate filter.
        if (sessionIdentity !== session.sharingIdentity) {
            cursor = 0L
            seen.release()
            sessionIdentity = session.sharingIdentity
            meter?.clauseCount("sessions", 1L)
        }
        onRestart(session)
    }

    override fun onSearchEnd(session: PropagationSession) = metered {
        retainSession(session)
        if (!session.cancelRequested) {
            countUses(session)
            export(session)
        }
        observe(session)
    }

    override fun onSearchClose(session: PropagationSession) = metered {
        if (sessionIdentity != null && sessionIdentity !== session.sharingIdentity) return@metered
        if (!session.cancelRequested) {
            countUses(session)
            export(session)
        }
        if (pool.measureMemory) meter?.clauseMemorySample(
            SharingMemorySnapshot(SharingMemoryStatus.STALE, emptyMap(), 0L, 0).telemetry(),
        )
        if (sessionIdentity == null || sessionIdentity === session.sharingIdentity) {
            session.retainedSharingMemory = null
            session.retainedSharingSize = null
            sessionIdentity = null
            cursor = 0L
            seen.release()
            meter?.clauseMemory(mapOf("seenKeys" to 0L, "seenCapacityBytes" to 0L))
        }
    }

    private fun countUses(session: PropagationSession) {
        if (tally == null && meter == null) return
        session.drainImportUses { from, uses ->
            tally?.note(Contribution.Clause, from, uses.toDouble())
            meter?.clauseCount("uses", uses)
        }
    }

    /** Publish a globally-valid nogood straight to the pool (no LBD/length filter), deduped by the
     *  `seen` set so this arm neither double-publishes it nor re-imports its own. */
    override fun publishGlobal(clause: SharedClause) {
        if (!shareGlobalNogoods) return
        metered {
            if (admit(clause.key) == true) {
                val published = pool.publish(listOf(clause), origin, isGlobal = true)
                meter?.exported(SharingChannel.Clauses, published)
            }
        }
    }

    /** Publish this arm's not-yet-seen glue clauses; safe at any decision level (read-only on the
     *  trail). The `seen` set guards against re-export within a session; the pool de-dups globally. */
    private fun export(session: PropagationSession) {
        val candidates = exportCandidates(session) ?: return
        val fresh = candidates.filter { admit(it.key, session) == true }
        val published = if (fresh.isEmpty()) 0 else pool.publish(
            fresh,
            origin,
            cancelled = { session.cancelRequested },
            charge = { session.chargeSharingWork(1L) },
        )
        meter?.exported(SharingChannel.Clauses, published)
    }

    private fun exportCandidates(session: PropagationSession): List<SharedClause>? {
        val alreadyShared: (Long) -> Boolean = { key ->
            (key in seen).also { if (it) meter?.clauseCount("exportDuplicates", 1L) }
        }
        if (pool.softLimitBytes == null) return session.exportGlueClausesBounded(
            maxLbd, maxLen, skipPermanent, alreadyShared = alreadyShared, cancelled = { false },
        )
        val count = session.learnedClauseCount.toLong()
        val width = maxLen.coerceAtLeast(0).toLong() + 1L
        val workLimit = pool.reclamationWork
        val scratchLimit = pool.reclamationScratchEntries.toLong()
        val workFits = width <= workLimit / 32L / width
        val perClause = if (workFits) width * width * 32L else Long.MAX_VALUE
        if (!workFits || count > workLimit / perClause || count > scratchLimit / (width * 8L + 2L)) {
            meter?.clauseCount("exportPreparationDeclined", 1L)
            return null
        }
        session.chargeSharingWork(count * perClause)
        if (session.cancelRequested) {
            meter?.clauseCount("exportPreparationDeclined", 1L)
            return null
        }
        val candidates = session.exportGlueClausesBounded(
            maxLbd, maxLen, skipPermanent, alreadyShared = alreadyShared, cancelled = { session.cancelRequested },
        )
        if (candidates == null) meter?.clauseCount("exportPreparationDeclined", 1L)
        return candidates
    }

    private inline fun metered(crossinline block: () -> Unit) {
        val m = meter
        if (m == null) block() else m.timed(SharingChannel.Clauses) { block() }
    }

    internal companion object {
        const val DEFAULT_MAX_LBD = 6
        const val DEFAULT_MAX_LEN = 12
    }
}
