package com.eignex.klause.solver.search

import com.eignex.klause.util.SharingMemoryObservation

internal class SharedLearnedStore(private val charge: (Long) -> Unit) {
    class Row(val index: Int, var literals: IntArray?, val lbd: Int) {
        var usedEpoch = -1L
        var stages = 0
        var reasonCount = 0
        var attached = false
        var retired = false
        var previous: Row? = null
        var next: Row? = null
        var signaturePrevious: Row? = null
        var signatureNext: Row? = null
        var signature: SignatureBucket? = null
        var firstWatch: Watch? = null
        var secondWatch: Watch? = null
        var unitNext: Row? = null
        var attachPrevious: Row? = null
        var attachNext: Row? = null
        var recheckPrevious: Row? = null
        var recheckNext: Row? = null
        val rank: Long get() = (lbd.toLong() shl Int.SIZE_BITS) or index.toLong()
    }
    class SignatureBucket(val key: Int) { var first: Row? = null; var last: Row? = null }
    class Posting(val literal: Int) { var first: Watch? = null; var last: Watch? = null; var size = 0 }
    class Watch(val row: Row, val posting: Posting) { var previous: Watch? = null; var next: Watch? = null }
    private val rows = LearnedIndex<Row>()
    private val ranks = LearnedIndex<Row>()
    private val signatures = LearnedIndex<SignatureBucket>()
    private val watches = LearnedIndex<Posting>()
    private var first: Row? = null
    private var last: Row? = null
    private var firstUnit: Row? = null
    private var lastUnit: Row? = null
    private var nextIndex = 0
    private var objects = 0
    private var watchNodes = 0
    private var payloadLiterals = 0L
    private var retiring: Row? = null
    private var retirementPhase = 0
    var size = 0
        private set
    var unitCount = 0
        private set
    var useEpoch = 0L
    var ownershipRevision = 0L
        private set
    var watchVisits = 0L
        private set
    val lastIndex: Int get() = nextIndex - 1
    val ownedRows: Int get() = objects
    val hasRetirement: Boolean get() = retiring != null
    val readWork: Long get() = maxOf(rows.readWork, ranks.readWork)
    val scratchBytes: Long get() = maxOf(rows.scratchBytes, ranks.scratchBytes,
        signatures.scratchBytes, watches.scratchBytes) + 64L
    val ownedBytes: Long get() = payloadLiterals * Int.SIZE_BYTES + objects * 130L + watchNodes * 32L +
        signatures.size * 20L + watches.size * 24L + rows.ownedBytes + ranks.ownedBytes +
        signatures.ownedBytes + watches.ownedBytes + 72L

    operator fun get(index: Int): Row {
        charge(rows.readWork)
        return checkNotNull(rows[index.toLong()])
    }
    fun next(after: Long, ranked: Boolean = false): Row? = if (ranked) ranks.next(after, true) else rows.next(after)
    fun explanationOf(index: Int): SearchExplanation {
        val literals = checkNotNull(get(index).literals)
        charge(literals.size.toLong() + 16L)
        return SearchExplanation(literals.copyOf())
    }

    fun add(literals: IntArray, lbd: Int): Row? {
        if (literals.isEmpty()) return null
        var hash = 0
        for (literal in literals) { charge(5L); hash += mix(literal) }
        hash = hash * 31 + literals.size
        charge(signatures.readWork)
        var bucket = signatures[hash.toLong()]
        var existing = bucket?.first
        while (existing != null) {
            charge(1L)
            val stored = checkNotNull(existing.literals)
            if (!existing.retired && sameLiterals(stored, literals)) return null
            existing = existing.signatureNext
        }
        charge(rows.mutationWork + ranks.mutationWork + signatures.mutationWork + 160L)
        val row = Row(nextIndex++, literals, lbd)
        if (bucket == null) {
            bucket = SignatureBucket(hash)
            signatures.put(hash.toLong(), bucket)
        }
        row.signature = bucket
        row.signaturePrevious = bucket.last
        bucket.last?.signatureNext = row
        if (bucket.first == null) bucket.first = row
        bucket.last = row
        row.previous = last
        last?.next = row
        if (first == null) first = row
        last = row
        rows.put(row.index.toLong(), row)
        ranks.put(row.rank, row)
        size++
        objects++
        payloadLiterals += literals.size
        ownershipRevision++
        return row
    }

    fun beginUseWindow(): Long = useEpoch++

    fun retire(row: Row, budget: SharedReclamationBudget) {
        check(!row.retired && retiring == null && row.reasonCount == 0 && row.stages and 4 == 0)
        budget.pay(32L, scratchBytes)
        row.retired = true
        retiring = row
        retirementPhase = 0
        size--
        ownershipRevision++
    }

    fun finishRetirement(budget: SharedReclamationBudget) {
        val row = retiring ?: return
        while (true) {
            when (retirementPhase) {
                0 -> removeWatch(row.firstWatch, budget).also { row.firstWatch = null }
                1 -> removeWatch(row.secondWatch, budget).also { row.secondWatch = null }
                2 -> {
                    val bucket = checkNotNull(row.signature)
                    val lastInBucket = bucket.first === row && bucket.last === row
                    budget.pay(32L + if (lastInBucket) signatures.mutationWork else 0L, scratchBytes)
                    row.signaturePrevious?.signatureNext = row.signatureNext
                    row.signatureNext?.signaturePrevious = row.signaturePrevious
                    if (bucket.first === row) bucket.first = row.signatureNext
                    if (bucket.last === row) bucket.last = row.signaturePrevious
                    if (lastInBucket) signatures.remove(bucket.key.toLong())
                    row.signature = null
                    row.signaturePrevious = null
                    row.signatureNext = null
                }
                3 -> {
                    budget.pay(ranks.mutationWork, scratchBytes)
                    ranks.remove(row.rank)
                }
                4 -> {
                    budget.pay(rows.mutationWork, scratchBytes)
                    rows.remove(row.index.toLong())
                }
                else -> {
                    budget.pay(32L, scratchBytes)
                    payloadLiterals -= checkNotNull(row.literals).size
                    row.literals = null
                    row.previous?.next = row.next
                    row.next?.previous = row.previous
                    if (first === row) first = row.next
                    if (last === row) last = row.previous
                    row.previous = null
                    row.next = null
                    objects--
                    retiring = null
                    ownershipRevision++
                    return
                }
            }
            retirementPhase++
            ownershipRevision++
        }
    }

    private fun removeWatch(watch: Watch?, budget: SharedReclamationBudget) {
        if (watch == null) { budget.pay(1L); return }
        val posting = watch.posting
        budget.pay(32L + if (posting.size == 1) watches.mutationWork else 0L, scratchBytes)
        unlink(watch)
        if (posting.size == 0) watches.remove(posting.literal.toLong())
    }

    private fun unlink(watch: Watch) {
        val posting = watch.posting
        watch.previous?.next = watch.next
        watch.next?.previous = watch.previous
        if (posting.first === watch) posting.first = watch.next
        if (posting.last === watch) posting.last = watch.previous
        posting.size--
        watch.previous = null
        watch.next = null
        watchNodes--
    }

    private fun addWatch(row: Row, literal: Int): Watch {
        charge(watches.mutationWork + 64L)
        val posting = watches[literal.toLong()] ?: Posting(literal).also { watches.put(literal.toLong(), it) }
        val watch = Watch(row, posting)
        watch.previous = posting.last
        posting.last?.next = watch
        if (posting.first == null) posting.first = watch
        posting.last = watch
        posting.size++
        watchNodes++
        ownershipRevision++
        return watch
    }

    fun attach(row: Row, host: ClauseWatchHost): ComponentResult {
        val literals = checkNotNull(row.literals)
        charge(16L)
        orderWatches(literals, host)
        row.firstWatch = addWatch(row, literals[0])
        if (literals.size == 1) {
            lastUnit?.unitNext = row
            if (firstUnit == null) firstUnit = row
            lastUnit = row
            unitCount++
        } else row.secondWatch = addWatch(row, literals[1])
        row.attached = true
        val truth = host.truth(literals[0])
        if (truth == true) return ComponentResult.Consistent
        if (truth == false) return conflict(row, host)
        if (literals.size > 1 && host.truth(literals[1]) != false) return ComponentResult.Consistent
        return imply(row, literals[0], host)
    }

    fun recheck(row: Row, host: ClauseWatchHost): ComponentResult {
        if (row.retired) return ComponentResult.Consistent
        val literals = checkNotNull(row.literals)
        if (literals.size == 1) {
            charge(1L)
            watchVisits++
            return when (host.truth(literals[0])) {
                true -> ComponentResult.Consistent
                false -> conflict(row, host)
                null -> imply(row, literals[0], host)
            }
        }
        var unit = -1
        for (literal in literals) {
            charge(1L)
            watchVisits++
            when (host.truth(literal)) {
                true -> return ComponentResult.Consistent
                null -> if (unit >= 0) return ComponentResult.Consistent else unit = literal
                false -> Unit
            }
        }
        return if (unit < 0) conflict(row, host) else imply(row, unit, host)
    }

    fun propagateUnits(host: ClauseWatchHost): ComponentResult {
        var row = firstUnit
        while (row != null) {
            val result = recheck(row, host)
            if (result !is ComponentResult.Consistent) return result
            row = row.unitNext
        }
        return ComponentResult.Consistent
    }

    fun propagate(falsified: Int, host: ClauseWatchHost): ComponentResult {
        charge(watches.readWork)
        var watch = watches[falsified.toLong()]?.first
        while (watch != null) {
            val current = watch
            watch = current.next
            charge(1L)
            watchVisits++
            val row = current.row
            if (row.retired) continue
            val literals = checkNotNull(row.literals)
            if (literals[0] == falsified && literals.size > 1) {
                literals[0] = literals[1]
                literals[1] = falsified
            }
            val other = literals[0]
            if (host.truth(other) == true) continue
            var replacement = -1
            for (position in 2 until literals.size) {
                charge(1L)
                if (host.truth(literals[position]) != false) { replacement = position; break }
            }
            if (replacement >= 0) {
                // The old posting remains a valid owner until its replacement is fully funded.
                charge(watches.mutationWork + 32L)
                val nextWatch = addWatch(row, literals[replacement])
                unlink(current)
                if (current.posting.size == 0) watches.remove(falsified.toLong())
                if (row.firstWatch === current) row.firstWatch = nextWatch else row.secondWatch = nextWatch
                literals[1] = literals[replacement]
                literals[replacement] = falsified
                continue
            }
            val result = if (host.truth(other) == false) conflict(row, host) else imply(row, other, host)
            if (result !is ComponentResult.Consistent) return result
        }
        return ComponentResult.Consistent
    }

    private fun imply(row: Row, literal: Int, host: ClauseWatchHost): ComponentResult {
        row.usedEpoch = useEpoch
        return host.implied(row.index, literal)
    }

    private fun conflict(row: Row, host: ClauseWatchHost): ComponentResult {
        row.usedEpoch = useEpoch
        return host.conflicted(row.index)
    }

    private fun orderWatches(literals: IntArray, host: ClauseWatchHost) {
        for (slot in 0 until minOf(2, literals.size)) {
            var best = slot
            for (candidate in slot + 1 until literals.size) {
                charge(1L)
                if (watchRank(literals[candidate], host) > watchRank(literals[best], host)) best = candidate
            }
            val swapped = literals[slot]
            literals[slot] = literals[best]
            literals[best] = swapped
        }
    }

    private fun watchRank(literal: Int, host: ClauseWatchHost): Int = when (host.truth(literal)) {
        true -> Int.MAX_VALUE
        null -> Int.MAX_VALUE - 1
        false -> host.levelOf(literal)
    }

    private fun sameLiterals(left: IntArray, right: IntArray): Boolean {
        if (left.size != right.size) return false
        for (literal in left) {
            var leftCount = 0
            var rightCount = 0
            for (value in left) { charge(1L); if (value == literal) leftCount++ }
            for (value in right) { charge(1L); if (value == literal) rightCount++ }
            if (leftCount != rightCount) return false
        }
        return true
    }

    private fun mix(value: Int): Int {
        var mixed = value xor (value ushr 16)
        mixed *= -2048144789
        mixed = mixed xor (mixed ushr 13)
        mixed *= -1028477387
        return mixed xor (mixed ushr 16)
    }

    fun memory(sample: SharingMemoryObservation, protected: (Row) -> Boolean): Boolean {
        var anyProtected = false
        var row = first
        while (row != null) {
            sample.step()
            val retained = protected(row)
            anyProtected = anyProtected || retained
            sample.owned(row, 26L, retained)
            sample.ownedReferences(row, 13L, retained)
            row.literals?.let { sample.owned(it, it.size.toLong() * Int.SIZE_BYTES, retained) }
            row.firstWatch?.let { sample.ownedReferences(it, 4L, retained) }
            row.secondWatch?.let { sample.ownedReferences(it, 4L, retained) }
            row = row.next
        }
        rows.memory(sample, protected)
        ranks.memory(sample, protected)
        signatures.memory(sample) { bucket ->
            var retained = false
            var entry = bucket.first
            while (entry != null) {
                sample.step()
                retained = protected(entry) || retained
                entry = entry.signatureNext
            }
            sample.owned(bucket, 4L, retained)
            sample.ownedReferences(bucket, 2L, retained)
            retained
        }
        watches.memory(sample) { posting ->
            var retained = false
            var entry = posting.first
            while (entry != null) { sample.step(); retained = protected(entry.row) || retained; entry = entry.next }
            sample.owned(posting, 8L, retained)
            sample.ownedReferences(posting, 2L, retained)
            retained
        }
        sample.ownedReferences(this, 9L, anyProtected)
        sample.record(mapOf(
            "sharedLearnedObjects" to size.toLong(),
            "sharedLearnedRetiredObjects" to (objects - size).toLong(),
            "sharedLearnedPayloadBytes" to payloadLiterals * Int.SIZE_BYTES,
            "sharedLearnedIndexCapacityBytes" to (ownedBytes - payloadLiterals * Int.SIZE_BYTES),
            "sharedLearnedReferenceSlots" to (objects * 13L + watchNodes * 4L +
                signatures.size * 2L + watches.size * 2L +
                (rows.size + ranks.size + signatures.size + watches.size) * 3L + 13L),
        ))
        return anyProtected
    }
}
