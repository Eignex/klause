package com.eignex.klause.solver.search

import com.eignex.klause.util.SharingMemoryObservation
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.MutableIntObjectMap
import com.eignex.util.collections.storage.borrowBackingArray
import com.eignex.util.collections.storage.forEachValueSlot
import com.eignex.util.collections.storage.primitiveCapacityBytes
import com.eignex.util.collections.storage.referenceCapacity
import com.eignex.util.collections.storage.valueStorageIdentity

// The store owns the watch invariant; the host owns what an implication or a conflict means.
internal interface ClauseWatchHost {
    fun truth(literal: Int): Boolean?

    // 0 when the literal's variable is unassigned.
    fun levelOf(literal: Int): Int

    fun implied(clause: Int, literal: Int): ComponentResult

    fun conflicted(clause: Int): ComponentResult
}

// Watched literals sit at positions zero and one. The watch machinery owns each clause's literal order,
// so callers never retain the array. Two-watched-literal scheme of Moskewicz, Madigan, Zhao, Zhang and
// Malik, "Chaff: Engineering an Efficient SAT Solver", DAC 2001, https://doi.org/10.1145/378239.379017.
internal class WatchedClauseStore {
    // Watch entries and retracted-implication literals inspected by propagation.
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

    internal fun memory(sample: SharingMemoryObservation, protectedClause: (Int) -> Boolean = { false }): Boolean {
        var watchBytes = 0L
        var signatureBytes = 0L
        watchers.forEachValueSlot { value ->
            sample.step()
            if (value != null) watchBytes += value.borrowBackingArray().size.toLong() * Int.SIZE_BYTES
        }
        signatures.forEachValueSlot { value ->
            sample.step()
            if (value != null) signatureBytes += value.borrowBackingArray().size.toLong() * Int.SIZE_BYTES
        }
        var payload = 0L
        for (clause in clauses) {
            sample.step()
            payload += clause.size.toLong() * Int.SIZE_BYTES
        }
        sample.record(mapOf(
            "sharedLearnedObjects" to clauses.size.toLong(),
            "sharedLearnedPayloadBytes" to payload,
            "sharedLearnedIndexCapacityBytes" to watchBytes + signatureBytes + watchers.primitiveCapacityBytes +
                signatures.primitiveCapacityBytes + lbds.borrowBackingArray().size.toLong() * Int.SIZE_BYTES +
                unitClauses.borrowBackingArray().size.toLong() * Int.SIZE_BYTES +
                (used.borrowBackingArray().size.toLong() + attached.borrowBackingArray().size) * Int.SIZE_BYTES,
            "sharedLearnedReferenceSlots" to clauses.capacity.toLong() + watchers.referenceCapacity +
                signatures.referenceCapacity,
        ))
        val protectedIds = sample.seenIds()
        for (index in 0 until size) {
            val clause = clauses[index]
            val protected = protectedClause(index)
            if (protected) sample.retain(protectedIds, index)
            sample.owned(clause, clause.size.toLong() * Int.SIZE_BYTES, protected)
        }
        val protected = protectedIds.size > 0
        sample.ownedReferences(clauses, clauses.capacity.toLong(), protected)
        sample.owned(used.borrowBackingArray(), used.borrowBackingArray().size.toLong() * Int.SIZE_BYTES, protected)
        sample.owned(lbds.borrowBackingArray(), lbds.borrowBackingArray().size.toLong() * Int.SIZE_BYTES, protected)
        sample.owned(
            attached.borrowBackingArray(),
            attached.borrowBackingArray().size.toLong() * Int.SIZE_BYTES,
            protected,
        )
        sample.owned(
            unitClauses.borrowBackingArray(),
            unitClauses.borrowBackingArray().size.toLong() * Int.SIZE_BYTES,
            protected,
        )
        var protectedWatches = false
        watchers.forEachValueSlot { value ->
            sample.step()
            if (value == null) return@forEachValueSlot
            val list = value
            var protectedPosting = false
            for (position in 0 until list.size) {
                sample.step()
                if (sample.contains(protectedIds, list[position])) {
                    protectedPosting = true
                    break
                }
            }
            protectedWatches = protectedWatches || protectedPosting
            sample.owned(list, list.borrowBackingArray().size.toLong() * Int.SIZE_BYTES, protectedPosting)
        }
        sample.owned(watchers, watchers.primitiveCapacityBytes, protectedWatches)
        sample.ownedReferences(watchers.valueStorageIdentity, watchers.referenceCapacity.toLong(), protectedWatches)
        var protectedSignatures = false
        signatures.forEachValueSlot { value ->
            sample.step()
            if (value == null) return@forEachValueSlot
            val list = value
            var protectedPosting = false
            for (position in 0 until list.size) {
                sample.step()
                if (sample.contains(protectedIds, list[position])) {
                    protectedPosting = true
                    break
                }
            }
            protectedSignatures = protectedSignatures || protectedPosting
            sample.owned(list, list.borrowBackingArray().size.toLong() * Int.SIZE_BYTES, protectedPosting)
        }
        sample.owned(signatures, signatures.primitiveCapacityBytes, protectedSignatures)
        sample.ownedReferences(
            signatures.valueStorageIdentity, signatures.referenceCapacity.toLong(), protectedSignatures,
        )
        return protected
    }

    val size: Int get() = clauses.size

    val units: IntArrayList get() = unitClauses

    fun literalsAt(index: Int): IntArray = clauses[index]

    fun lbdAt(index: Int): Int = lbds[index]

    // Whether the clause has implied or conflicted since the last reduction.
    fun usedAt(index: Int): Boolean = used[index] != 0

    fun markUsed(index: Int) {
        used[index] = 1
    }

    fun watchersOf(literal: Int): IntArrayList? = watchers[literal]

    fun addWatcher(literal: Int, index: Int) {
        watchers.getOrPut(literal) { IntArrayList() }.add(index)
        ownershipRevision++
    }

    // Retains the clause unwatched, or returns -1 when an equivalent clause is already stored. Watches
    // depend on the assignment when the clause is first examined, so attaching is a separate step.
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

    fun watch(index: Int) {
        attached[index] = 1
        val literals = clauses[index]
        addWatcher(literals[0], index)
        if (literals.size == 1) unitClauses.add(index) else addWatcher(literals[1], index)
    }

    fun clearUsed() {
        for (index in 0 until used.size) used[index] = 0
    }

    // Order-insensitive, so a permutation of a stored clause lands in the same bucket. Avalanching
    // before summing keeps sets with equal raw sums apart.
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

    // A unit clause is woken by no assignment, so callers run this whenever a retraction may have
    // unassigned a unit literal. Returns the first non-consistent result so the caller can stay pending.
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

    // Copied because the watch machinery reorders the stored array.
    fun explanationOf(index: Int): SearchExplanation = SearchExplanation(clauses[index].copyOf())

    // Among falsified literals the deepest is preferred: it is unassigned first, so the watch stays
    // valid after a backjump.
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
        const val UNASSIGNED_WATCH_RANK = Int.MAX_VALUE - 1

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
