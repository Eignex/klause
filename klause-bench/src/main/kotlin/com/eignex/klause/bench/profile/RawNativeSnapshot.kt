package com.eignex.klause.bench.profile

import com.eignex.klause.propagation.PropagationSession
import java.lang.reflect.Field

internal class RawNativeSnapshot {
    data class Policy(
        val permanent: Int,
        val origin: Int,
        val scan: Int,
        val first: Int,
        val second: Int,
        val tier: Int,
        val recentUsed: Int,
        val lifecycleStages: Int,
    )
    data class Row(val nativeIndex: Int, val lbd: Int, val literals: IntArray, val policy: Policy)
    data class Header(
        val slots: Int,
        val baseCount: Int,
        val truth: ByteArray,
        val reasons: IntArray,
        val cursors: IntArray,
        val cleanupCursors: List<Int?>,
    )
    data class Arena(val starts: IntArray, val literals: IntArray)
    data class Image(
        val header: Header,
        val arena: Arena,
        val clauses: List<Row>,
        val watches: List<LongArray>,
        val cachesUnchanged: Boolean,
    )

    private val fields = HashMap<Pair<Class<*>, String>, Field>()

    private fun value(owner: Any, name: String): Any? = fields.getOrPut(owner.javaClass to name) {
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    }.get(owner)

    private fun has(owner: Any, name: String): Boolean = owner.javaClass.declaredFields.any { it.name == name }
    private fun integer(owner: Any, name: String): Int = value(owner, name) as Int
    private fun reference(owner: Any, name: String): Any = checkNotNull(value(owner, name))
    private fun state(session: PropagationSession): Any =
        reference(session, if (has(session, "retainedState")) "retainedState" else "state")
    private fun native(session: PropagationSession): Any = reference(state(session), "nativeEngine")

    fun count(session: PropagationSession): Int = integer(native(session), "learnedCount")
    fun work(session: PropagationSession): Long = value(state(session), "work") as Long

    fun read(session: PropagationSession): Image {
        val owner = native(session)
        check(owner.javaClass.simpleName == "NativeSatState")
        val slots = integer(owner, "learnedCount")
        require(slots in 0..32768)
        val before = cacheValues(owner)
        val clauses = if (has(owner, "metadata")) pagedRows(owner, slots) else linearRows(owner, slots)
        require(clauses.sumOf { it.literals.size.toLong() } <= 2097152L)
        val arena = reference(owner, "arena")
        val starts = (value(arena, "starts") as IntArray).copyOf()
        val literals = (value(arena, "lits") as IntArray).copyOf()
        val base = integer(owner, "baseCount")
        check(starts.size == base + 1 && starts.last() == literals.size)
        val header = Header(slots, base, (value(owner, "boolTruth") as ByteArray).copyOf(),
            (value(state(session), "boolReason") as IntArray).copyOf(),
            intArrayOf(integer(owner, "bcpHead"), integer(owner, "recheckHead"),
                if (value(owner, "unitsPending") as Boolean) 1 else 0),
            listOf("watchCleanupLiteral", "watchCleanupIndex", "sweptRetirements", "sweepingRetirements",
                "watchCleanupScan", "watchCleanupEpoch")
                .map { if (has(owner, it)) integer(owner, it) else null })
        check(header.truth.size == 250 && header.reasons.size == 250 && base == 1065)
        val watches = watches(owner)
        return Image(header, Arena(starts, literals), clauses, watches, sameCaches(before, cacheValues(owner)))
    }

    private fun listData(owner: Any, name: String, minimum: Int): IntArray {
        val list = reference(owner, name)
        val data = value(list, "data") as IntArray
        require(integer(list, "size") in minimum..data.size)
        return data
    }

    private fun linearRows(owner: Any, count: Int): List<Row> {
        val payload = reference(owner, "learnedLits")
        val literals = value(payload, "data") as IntArray
        val end = integer(payload, "size")
        require(end in 0..literals.size)
        val starts = listData(owner, "learnedStarts", count)
        val lbds = listData(owner, "learnedLbd", count)
        val permanent = listData(owner, "learnedPermanent", count)
        val origins = listData(owner, "learnedOrigin", count)
        val tiers = listData(owner, "learnedTier", count)
        val used = listData(owner, "learnedUsed", count)
        val stages = listData(owner, "lifecycleStages", count)
        val scans = listData(owner, "learnedScanPos", count)
        val positions = listData(owner, "learnedWatchPos", count * 2)
        var total = 0L
        return List(count) { index ->
            val last = if (index + 1 == count) end else starts[index + 1]
            require(starts[index] in 0..last && last <= end)
            total += last - starts[index]
            require(total <= 2097152L)
            Row(index, lbds[index], literals.copyOfRange(starts[index], last),
                Policy(permanent[index], origins[index], scans[index], positions[index * 2], positions[index * 2 + 1],
                    tiers[index], used[index], stages[index] and 3))
        }
    }

    private fun pagedRows(owner: Any, count: Int): List<Row> {
        val rows = reference(reference(owner, "metadata"), "rows")
        val columns = integer(rows, "columns")
        check(columns == 9 && integer(rows, "retiredFlag") == 4)
        val policies = pages(rows)
        val payloads = pages(reference(owner, "learnedPayloads"))
        val result = ArrayList<Row>()
        var total = 0L
        for (index in 0 until count) {
            val page = policies[index ushr 4] ?: continue
            val slot = index and 15
            if (integer(page, "retained") and (1 shl slot) == 0) continue
            val values = value(page, "values") as IntArray
            val offset = slot * columns
            if (values[offset + 4] and 4 != 0) continue
            val payload = checkNotNull(payloads[index ushr 6])
            val data = value(payload, "rows") as Array<*>
            val literals = data[index and 63] as IntArray
            total += literals.size
            require(total <= 2097152L)
            result.add(Row(index, values[offset], literals.copyOf(),
                Policy(values[offset + 1], values[offset + 5], values[offset + 6],
                    values[offset + 7], values[offset + 8], values[offset + 2],
                    if (values[offset + 4] and 8 == 0) 0 else 1, values[offset + 4] and 3)))
        }
        check(result.size == count - integer(owner, "retiredCount"))
        return result
    }

    private fun pages(owner: Any): Map<Int, Any> {
        val result = HashMap<Int, Any>()
        value(owner, "primary")?.let { result[0] = it }
        val directory = reference(owner, "directory")
        val pending = ArrayDeque<Any>()
        value(directory, "root")?.let(pending::addLast)
        var visits = 0
        while (pending.isNotEmpty()) {
            require(++visits <= 4096)
            val node = pending.removeLast()
            if (has(node, "key")) {
                val key = integer(node, "key")
                require(key > 0 && result.put(key, reference(node, "value")) == null)
            } else {
                pending.addLast(reference(node, "zero"))
                pending.addLast(reference(node, "one"))
            }
        }
        return result
    }

    private fun watches(owner: Any): List<LongArray> {
        val sizes = value(owner, "watchSizes") as IntArray
        val entries = value(owner, "watchEntries") as Array<*>
        require(sizes.size == 500 && entries.size == sizes.size && sizes.all { it >= 0 })
        require(sizes.sumOf { it.toLong() } <= 65536L)
        return List(sizes.size) { literal ->
            val size = sizes[literal]
            val entry = entries[literal]
            if (entry is LongArray) {
                check(size <= entry.size)
                entry.copyOf(size)
            } else if (entry == null) {
                check(size == 0)
                LongArray(0)
            } else {
                check(integer(entry, "size") == size)
                val pages = value(entry, "pages") as Array<*>
                LongArray(size) { index -> (pages[index ushr 4] as LongArray)[index and 15] }
            }
        }
    }

    private fun cacheValues(owner: Any): List<Any?> {
        if (!has(owner, "metadata")) return emptyList()
        val payloads = reference(owner, "learnedPayloads")
        val rows = reference(reference(owner, "metadata"), "rows")
        val values = arrayListOf<Any?>()
        if (has(payloads, "cachedIndices")) {
            val indices = value(payloads, "cachedIndices") as IntArray
            val pages = value(payloads, "cachedPages") as Array<*>
            values.add(indices)
            values.addAll(indices.toList())
            values.add(pages)
            values.addAll(pages)
        } else {
            values.add(value(payloads, "cachedIndex"))
            values.add(value(payloads, "cachedPage"))
        }
        for (store in listOf(payloads, rows)) {
            val directory = reference(store, "directory")
            values.add(value(directory, "root"))
            values.add(value(directory, "size"))
            values.add(value(directory, "lastWork"))
        }
        val cache = value(rows, "cachedPages") as Array<*>
        values.add(cache)
        values.addAll(cache)
        return values
    }

    private fun sameCaches(before: List<Any?>, after: List<Any?>): Boolean =
        before.size == after.size && before.indices.all { index ->
            val first = before[index]
            val second = after[index]
            if (first is Number && second is Number) first == second else first === second
        }
}
