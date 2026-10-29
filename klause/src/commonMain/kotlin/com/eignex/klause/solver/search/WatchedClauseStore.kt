package com.eignex.klause.solver.search

import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.MutableIntObjectMap
import com.eignex.klause.util.SharingMemoryObservation

/**
 * Assignment view and consequence sink a [WatchedClauseStore] propagates through.
 *
 * The store owns the watch invariant; the host owns what an implication or a conflict means. That
 * split is what lets one mechanism serve both the model's own clauses and the clauses a session has
 * learned, instead of each keeping its own copy of the same loop.
 */
internal interface ClauseWatchHost {
    /** Truth of [literal] under the current assignment, or null when its variable is unassigned. */
    fun truth(literal: Int): Boolean?

    /** Decision level that assigned [literal]'s variable, or 0 when it is unassigned. */
    fun levelOf(literal: Int): Int

    /** Record that clause [clause] implies [literal], which no other literal of it can now satisfy. */
    fun implied(clause: Int, literal: Int): ComponentResult

    /** Record that every literal of clause [clause] is falsified. */
    fun conflicted(clause: Int): ComponentResult
}

/**
 * Watch-indexed clause database.
 *
 * Each retained clause keeps its watched literals at positions zero and one, so an assignment wakes
 * only the clauses watching the literal it falsified. A clause is immutable as a set of literals while
 * its literal *order* is owned by the watch machinery, which is why callers read literals through
 * [literalsAt] and never retain the array. The two-watched-literal scheme follows Moskewicz, Madigan,
 * Zhao, Zhang and Malik, "Chaff: Engineering an Efficient SAT Solver", DAC 2001,
 * https://doi.org/10.1145/378239.379017.
 */
internal class WatchedClauseStore {
    /** Watch entries and retracted-implication literals inspected by propagation. */
    var watchVisits: Long = 0
        private set
    internal var ownershipRevision: Long = 0L
        private set
    private val clauses = ClauseArrays()
    private val lbds = IntArrayList()
    private val used = IntArrayList()
    private val watchers = MutableIntObjectMap<IntArrayList>()
    private val signatures = MutableIntObjectMap<IntArrayList>()
    private val unitClauses = IntArrayList()
    private val attached = IntArrayList()

    internal val literalCount: Long get() = clauses.literalCount

    internal val memoryScanWork: Long get() = size.toLong() + watchers.referenceCapacity + signatures.referenceCapacity

    internal val replacementWorkBound: Long
        // Primitive hash indexes can collide; the reservation covers probing and growth as well as visits.
        get() = literalCount * 2L + size.toLong() * size * 64L + memoryScanWork * 4L + 256L

    internal val replacementScratchBound: Long get() = 4096L + size.toLong() * 512L

    internal fun prepareRetained(keep: BooleanArray, step: () -> Unit): WatchedClauseStore {
        val replacement = WatchedClauseStore()
        replacement.watchVisits = watchVisits
        for (index in 0 until size) {
            step()
            if (!keep[index]) continue
            val literals = clauses[index]
            var signature = 0
            for (literal in literals) { step(); signature += avalanche(literal) }
            signature = signature * SIGNATURE_SIZE_FACTOR + literals.size
            val next = replacement.size
            replacement.clauses.add(literals)
            replacement.lbds.add(lbds[index])
            replacement.used.add(used[index])
            replacement.attached.add(0)
            replacement.signatures.getOrPut(signature) { IntArrayList(1) }.add(next)
            if (attached[index] != 0) replacement.watch(next)
        }
        return replacement
    }

    internal fun ownedBytes(step: () -> Unit): Long {
        var bytes = clauses.literalCount * Int.SIZE_BYTES + clauses.capacity.toLong() * MODELED_REFERENCE_BYTES +
            used.backingData.size.toLong() * Int.SIZE_BYTES +
            (lbds.backingData.size.toLong() + unitClauses.backingData.size + attached.backingData.size) * Int.SIZE_BYTES
        bytes += watchers.primitiveCapacityBytes + signatures.primitiveCapacityBytes +
            (watchers.referenceCapacity.toLong() + signatures.referenceCapacity) * MODELED_REFERENCE_BYTES
        for (value in watchers.valuesInternal) {
            step()
            if (value != null) bytes += (value as IntArrayList).backingData.size.toLong() * Int.SIZE_BYTES
        }
        for (value in signatures.valuesInternal) {
            step()
            if (value != null) bytes += (value as IntArrayList).backingData.size.toLong() * Int.SIZE_BYTES
        }
        return bytes
    }

    internal fun memory(sample: SharingMemoryObservation, protectedClause: (Int) -> Boolean = { false }): Boolean {
        var watchBytes = 0L
        var signatureBytes = 0L
        for (value in watchers.valuesInternal) {
            sample.step()
            if (value != null) watchBytes += (value as IntArrayList).backingData.size.toLong() * Int.SIZE_BYTES
        }
        for (value in signatures.valuesInternal) {
            sample.step()
            if (value != null) signatureBytes += (value as IntArrayList).backingData.size.toLong() * Int.SIZE_BYTES
        }
        var payload = 0L
        for (clause in clauses) { sample.step(); payload += clause.size.toLong() * Int.SIZE_BYTES }
        sample.record(mapOf(
            "sharedLearnedObjects" to clauses.size.toLong(),
            "sharedLearnedPayloadBytes" to payload,
            "sharedLearnedIndexCapacityBytes" to watchBytes + signatureBytes + watchers.primitiveCapacityBytes +
                signatures.primitiveCapacityBytes + lbds.backingData.size.toLong() * Int.SIZE_BYTES +
                unitClauses.backingData.size.toLong() * Int.SIZE_BYTES +
                (used.backingData.size.toLong() + attached.backingData.size) * Int.SIZE_BYTES,
            "sharedLearnedReferenceSlots" to clauses.capacity.toLong() + watchers.referenceCapacity +
                signatures.referenceCapacity,
        ))
        val protectedIds = sample.seen<Int>()
        for (index in 0 until size) {
            val clause = clauses[index]
            val protected = protectedClause(index)
            if (protected) sample.retain(protectedIds, index)
            sample.owned(clause, clause.size.toLong() * Int.SIZE_BYTES, protected)
        }
        val protected = protectedIds.size > 0
        sample.ownedReferences(clauses, clauses.capacity.toLong(), protected)
        sample.owned(used.backingData, used.backingData.size.toLong() * Int.SIZE_BYTES, protected)
        sample.owned(lbds.backingData, lbds.backingData.size.toLong() * Int.SIZE_BYTES, protected)
        sample.owned(attached.backingData, attached.backingData.size.toLong() * Int.SIZE_BYTES, protected)
        sample.owned(unitClauses.backingData, unitClauses.backingData.size.toLong() * Int.SIZE_BYTES, protected)
        var protectedWatches = false
        for (value in watchers.valuesInternal) {
            sample.step()
            if (value == null) continue
            val list = value as IntArrayList
            var protectedPosting = false
            for (position in 0 until list.size) {
                sample.step()
                if (sample.contains(protectedIds, list[position])) protectedPosting = true
            }
            protectedWatches = protectedWatches || protectedPosting
            sample.owned(list, list.backingData.size.toLong() * Int.SIZE_BYTES, protectedPosting)
        }
        sample.owned(watchers, watchers.primitiveCapacityBytes, protectedWatches)
        sample.ownedReferences(watchers.valuesInternal, watchers.referenceCapacity.toLong(), protectedWatches)
        var protectedSignatures = false
        for (value in signatures.valuesInternal) {
            sample.step()
            if (value == null) continue
            val list = value as IntArrayList
            var protectedPosting = false
            for (position in 0 until list.size) {
                sample.step()
                if (sample.contains(protectedIds, list[position])) protectedPosting = true
            }
            protectedSignatures = protectedSignatures || protectedPosting
            sample.owned(list, list.backingData.size.toLong() * Int.SIZE_BYTES, protectedPosting)
        }
        sample.owned(signatures, signatures.primitiveCapacityBytes, protectedSignatures)
        sample.ownedReferences(signatures.valuesInternal, signatures.referenceCapacity.toLong(), protectedSignatures)
        return protected
    }

    /** Number of retained clauses. */
    val size: Int get() = clauses.size

    /** Indices of the retained clauses that hold a single literal. */
    val units: IntArrayList get() = unitClauses

    /** Literals of clause [index], watched literals first. */
    fun literalsAt(index: Int): IntArray = clauses[index]

    /** Literal block distance recorded for clause [index] when it was learned. */
    fun lbdAt(index: Int): Int = lbds[index]

    /** Whether clause [index] has implied or conflicted since the last reduction. */
    fun usedAt(index: Int): Boolean = used[index] != 0

    /** Record that clause [index] implied a literal or derived a conflict. */
    fun markUsed(index: Int) {
        used[index] = 1
    }

    /** Clauses watching [literal], or null when no clause does. */
    fun watchersOf(literal: Int): IntArrayList? = watchers[literal]

    /** Start watching [literal] in clause [index]. */
    fun addWatcher(literal: Int, index: Int) {
        watchers.getOrPut(literal) { IntArrayList() }.add(index)
        ownershipRevision++
    }

    /**
     * Retain [literals] unwatched, or return -1 when an equivalent clause is already stored.
     *
     * Watching is a separate step because the two literals a clause must watch depend on the
     * assignment as it stands when the clause is first examined, not when it was learned.
     */
    fun add(literals: IntArray, lbd: Int): Int {
        if (literals.isEmpty()) return -1
        val bucket = signatures.getOrPut(signatureOf(literals)) { IntArrayList(1) }
        for (position in 0 until bucket.size) {
            if (sameLiterals(clauses[bucket[position]], literals)) return -1
        }
        val index = clauses.size
        bucket.add(index)
        clauses.add(literals)
        lbds.add(lbd)
        used.add(0)
        attached.add(0)
        ownershipRevision++
        return index
    }

    /** Watch clause [index] on the literals currently held at its first two positions. */
    fun watch(index: Int) {
        attached[index] = 1
        val literals = clauses[index]
        addWatcher(literals[0], index)
        if (literals.size == 1) unitClauses.add(index) else addWatcher(literals[1], index)
    }

    /** Clear every use flag after a reduction has selected its survivors. */
    fun clearUsed() {
        for (index in 0 until used.size) used[index] = 0
    }

    /**
     * Order-insensitive fingerprint of [literals], so a permutation of a stored clause lands in the
     * same bucket. Literals are avalanched before being summed, which keeps sets that share a sum of
     * raw values apart.
     */
    private fun signatureOf(literals: IntArray): Int {
        var accumulated = 0
        for (literal in literals) accumulated += avalanche(literal)
        return accumulated * SIGNATURE_SIZE_FACTOR + literals.size
    }

    private fun avalanche(value: Int): Int {
        var mixed = value xor (value ushr SHIFT_HIGH)
        mixed *= MIX_FIRST
        mixed = mixed xor (mixed ushr SHIFT_MID)
        mixed *= MIX_SECOND
        return mixed xor (mixed ushr SHIFT_HIGH)
    }

    private fun sameLiterals(stored: IntArray, candidate: IntArray): Boolean {
        if (stored.size != candidate.size) return false
        val left = stored.copyOf().also(IntArray::sort)
        val right = candidate.copyOf().also(IntArray::sort)
        return left.contentEquals(right)
    }

    /**
     * Choose watches for clause [index] against the current assignment and evaluate it once.
     *
     * Watching is deferred to this point because the two literals a clause must watch depend on the
     * assignment as it stands when the clause is first examined, not when it was stored.
     */
    fun attach(index: Int, host: ClauseWatchHost): ComponentResult {
        val literals = clauses[index]
        orderWatches(literals, host)
        watch(index)
        val watched = literals[0]
        val watchedTruth = host.truth(watched)
        if (watchedTruth == true) return ComponentResult.Consistent
        if (watchedTruth == false) return conflictOn(index, host)
        if (literals.size > 1 && host.truth(literals[1]) != false) return ComponentResult.Consistent
        return implyFrom(index, watched, host)
    }

    fun recheck(index: Int, host: ClauseWatchHost): ComponentResult {
        var unit = -1
        for (literal in clauses[index]) {
            watchVisits++
            when (host.truth(literal)) {
                true -> return ComponentResult.Consistent
                null -> if (unit >= 0) return ComponentResult.Consistent else unit = literal
                false -> Unit
            }
        }
        return if (unit < 0) conflictOn(index, host) else implyFrom(index, unit, host)
    }

    /**
     * Re-examine every single-literal clause.
     *
     * A unit clause is woken by no assignment, so a caller must run this whenever a retraction may
     * have unassigned one of their literals. Stops at and returns the first result that is not
     * [ComponentResult.Consistent], so the caller can keep the units pending.
     */
    fun propagateUnits(host: ClauseWatchHost): ComponentResult {
        unitClauses.forEach { index ->
            val literal = clauses[index][0]
            when (host.truth(literal)) {
                true -> Unit

                false -> return conflictOn(index, host)

                null -> {
                    val result = implyFrom(index, literal, host)
                    if (result !is ComponentResult.Consistent) return result
                }
            }
        }
        return ComponentResult.Consistent
    }

    /**
     * Visit every clause watching [falsified], restoring the two-watch invariant.
     *
     * A clause that finds a non-falsified replacement literal moves its watch and leaves this list; a
     * clause with no replacement either implies its remaining watch or is in conflict.
     */
    fun propagate(falsified: Int, host: ClauseWatchHost): ComponentResult {
        val watching = watchers[falsified] ?: return ComponentResult.Consistent
        var source = 0
        var target = 0
        var outcome: ComponentResult = ComponentResult.Consistent
        while (source < watching.size) {
            watchVisits++
            val index = watching[source++]
            val literals = clauses[index]
            if (literals[0] == falsified && literals.size > 1) {
                literals[0] = literals[1]
                literals[1] = falsified
            }
            val other = literals[0]
            if (host.truth(other) == true) {
                watching[target++] = index
                continue
            }
            val replacement = replacementWatch(literals, host)
            if (replacement > 0) {
                literals[1] = literals[replacement]
                literals[replacement] = falsified
                addWatcher(literals[1], index)
                continue
            }
            watching[target++] = index
            outcome = if (host.truth(other) == false) {
                conflictOn(index, host)
            } else {
                implyFrom(index, other, host)
            }
            if (outcome !is ComponentResult.Consistent) break
        }
        while (source < watching.size) watching[target++] = watching[source++]
        watching.truncateTo(target)
        return outcome
    }

    /** The clause-form explanation of clause [index], safe for the caller to retain. */
    fun explanationOf(index: Int): SearchExplanation = SearchExplanation(clauses[index].copyOf())

    /**
     * Move the two literals that must be watched to the front of [literals].
     *
     * An unassigned or satisfied literal is always preferable, and among falsified literals the one
     * assigned deepest is preferable: it is the first to be unassigned, which is what keeps a watch
     * from going stale after a backjump.
     */
    private fun orderWatches(literals: IntArray, host: ClauseWatchHost) {
        for (slot in 0 until minOf(2, literals.size)) {
            var best = slot
            for (candidate in slot + 1 until literals.size) {
                if (watchRank(literals[candidate], host) > watchRank(literals[best], host)) best = candidate
            }
            val swapped = literals[slot]
            literals[slot] = literals[best]
            literals[best] = swapped
        }
    }

    private fun watchRank(literal: Int, host: ClauseWatchHost): Int = when (host.truth(literal)) {
        true -> SATISFIED_WATCH_RANK
        null -> UNASSIGNED_WATCH_RANK
        false -> host.levelOf(literal)
    }

    private fun replacementWatch(literals: IntArray, host: ClauseWatchHost): Int {
        for (position in 2 until literals.size) {
            if (host.truth(literals[position]) != false) return position
        }
        return -1
    }

    private fun conflictOn(index: Int, host: ClauseWatchHost): ComponentResult {
        markUsed(index)
        return host.conflicted(index)
    }

    private fun implyFrom(index: Int, literal: Int, host: ClauseWatchHost): ComponentResult {
        markUsed(index)
        return host.implied(index, literal)
    }

    private companion object {
        const val MODELED_REFERENCE_BYTES = 8L
        /** Watch preference for an unassigned literal; below a satisfied one, above any level. */
        const val UNASSIGNED_WATCH_RANK = Int.MAX_VALUE - 1

        /** Watch preference for a satisfied literal, which never needs replacing. */
        const val SATISFIED_WATCH_RANK = Int.MAX_VALUE
        const val SIGNATURE_SIZE_FACTOR = 31
        const val SHIFT_HIGH = 16
        const val SHIFT_MID = 13
        const val MIX_FIRST = -2048144789
        const val MIX_SECOND = -1028477387
    }

    private class ClauseArrays : Iterable<IntArray> {
        private var data: Array<IntArray?> = arrayOfNulls(8)
        var size: Int = 0
            private set
        var literalCount: Long = 0
            private set
        val capacity: Int get() = data.size
        operator fun get(index: Int): IntArray = checkNotNull(data[index])
        fun add(value: IntArray) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
            literalCount += value.size
        }
        override fun iterator(): Iterator<IntArray> = object : Iterator<IntArray> {
            private var position = 0
            override fun hasNext(): Boolean = position < size
            override fun next(): IntArray {
                if (!hasNext()) throw NoSuchElementException()
                return get(position++)
            }
        }
    }
}
