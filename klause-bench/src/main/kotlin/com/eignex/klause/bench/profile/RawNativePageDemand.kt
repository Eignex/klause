package com.eignex.klause.bench.profile

import com.eignex.klause.propagation.PropagationSession
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.lang.reflect.Field

internal class RawNativePageDemand {
    private val fields = HashMap<Pair<Class<*>, String>, Field>()

    private fun value(owner: Any, name: String): Any? = fields.getOrPut(owner.javaClass to name) {
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    }.get(owner)

    private fun integer(owner: Any, name: String): Int = value(owner, name) as Int
    private fun reference(owner: Any, name: String): Any = checkNotNull(value(owner, name))
    private fun state(session: PropagationSession): Any = reference(session, "retainedState")
    fun work(session: PropagationSession): Long = value(state(session), "work") as Long

    fun read(session: PropagationSession): JsonObject {
        val native = reference(state(session), "nativeEngine")
        check(native.javaClass.simpleName == "NativeSatState")
        val count = integer(native, "learnedCount")
        require(count in 0..MAX_SLOTS)
        val rows = reference(reference(native, "metadata"), "rows")
        check(integer(rows, "columns") == 9 && integer(rows, "retiredFlag") == 4)
        val payloads = reference(native, "learnedPayloads")
        val before = guard(rows, payloads)
        val metadataPages = pages(rows, count, 4)
        val payloadPages = pages(payloads, count, 6)
        val caches = cacheKeys(rows, payloads)
        val candidates = reference(native, "capCandidates")
        val candidateData = value(candidates, "data") as IntArray
        val size = integer(candidates, "size")
        require(size in 0..minOf(candidateData.size, MAX_SLOTS))
        val ranking = reference(native, "capRanking")
        val read = integer(ranking, "read")
        val write = integer(ranking, "write")
        val heap = integer(ranking, "heapSize")
        check(read in 0..size && write in 0..size && heap in 0..write)
        val pending = integer(ranking, "pending")
        val groups = linkedMapOf(
            "candidateUnread" to candidateData.copyOfRange(read, size),
            "candidateHeap" to candidateData.copyOfRange(0, heap),
            "candidateProtectedPrefix" to candidateData.copyOfRange(heap, write),
            "candidatePending" to if (pending < 0) intArrayOf() else intArrayOf(pending),
            "learnedWatchReferences" to watches(native, count),
        )
        val record = buildJsonObject {
            put("retainedSlotHighWater", count)
            put("logicallyRetiredCount", integer(native, "retiredCount"))
            put("metadataPendingRows", integer(native, "retiredMetadataCount"))
            put("metadataPages", metadata(metadataPages))
            put("payloadPages", payload(payloadPages))
            put("metadataCacheKeys", ints(caches.first))
            put("payloadCacheKeys", ints(caches.second))
            put("ranking", buildJsonObject {
                put("stage", (value(ranking, "stage") as Enum<*>).name)
                put("read", read)
                put("write", write)
                put("heapSize", heap)
                put("candidateSize", size)
                put("pending", pending)
                put("prepared", integer(ranking, "prepared"))
                put("cleanupRead", integer(ranking, "cleanupRead"))
                put("passes", value(ranking, "passes") as Long)
            })
            put("demand", buildJsonObject {
                for ((name, indices) in groups) {
                    require(indices.all { it in 0 until count })
                    put(name, demand(indices, metadataPages, payloadPages, caches))
                }
            })
            put("rawFieldsReadOnly", true)
            put("noStoreAccessorInvoked", true)
            put("potentialDemandNotActualMissCounts", true)
        }
        check(sameGuard(before, guard(rows, payloads)))
        return JsonObject(record + ("solverCachesUnchanged" to JsonPrimitive(true)))
    }

    private fun pages(owner: Any, count: Int, shift: Int): Map<Int, Any> {
        val result = HashMap<Int, Any>()
        value(owner, "primary")?.let { result[0] = it }
        val directory = reference(owner, "directory")
        val pending = ArrayDeque<Any>()
        value(directory, "root")?.let(pending::addLast)
        var visits = 0
        while (pending.isNotEmpty()) {
            require(++visits <= MAX_DIRECTORY_NODES)
            val node = pending.removeLast()
            if (node.javaClass.simpleName == "Leaf") {
                val key = integer(node, "key")
                require(key > 0 && key <= ((count - 1).coerceAtLeast(0) ushr shift))
                check(result.put(key, reference(node, "value")) == null)
            } else {
                check(node.javaClass.simpleName == "Branch")
                pending.addLast(reference(node, "zero"))
                pending.addLast(reference(node, "one"))
            }
        }
        check(result.size == integer(owner, "pageCount"))
        check(result.size - (if (result.containsKey(0)) 1 else 0) == integer(directory, "size"))
        return result
    }

    private fun cacheKeys(rows: Any, payloads: Any): Pair<IntArray, IntArray> {
        val metadata = value(rows, "cachedPages") as Array<*>
        val payload = value(payloads, "cachedIndices") as IntArray
        require(metadata.size == 2048 && payload.size == 2048)
        return IntArray(metadata.size) { index ->
            metadata[index]?.let { integer(reference(it, "slot"), "key") } ?: -1
        } to payload.copyOf()
    }

    private fun metadata(pages: Map<Int, Any>): JsonArray = JsonArray(pages.toSortedMap().map { (key, page) ->
        val values = value(page, "values") as IntArray
        val admissions = value(page, "admissions") as LongArray
        require(admissions.size in 1..16 && values.size == admissions.size * 9)
        buildJsonObject {
            put("key", key)
            put("capacity", admissions.size)
            put("retainedMask", integer(page, "retained"))
            put("pendingMask", integer(page, "pending"))
            put("queued", integer(page, "queued"))
            put("stageValues", ints(IntArray(admissions.size) { values[it * 9 + 4] }))
        }
    })

    private fun payload(pages: Map<Int, Any>): JsonArray = JsonArray(pages.toSortedMap().map { (key, page) ->
        val rows = value(page, "rows") as Array<*>
        require(rows.size in 1..64)
        buildJsonObject {
            put("key", key)
            put("capacity", rows.size)
            put("live", integer(page, "live"))
            put("queued", integer(page, "queued"))
            put("words", value(page, "words") as Long)
        }
    })

    private fun watches(native: Any, count: Int): IntArray {
        val sizes = value(native, "watchSizes") as IntArray
        val entries = value(native, "watchEntries") as Array<*>
        check(sizes.size == 500 && entries.size == sizes.size && sizes.all { it >= 0 })
        require(sizes.sumOf { it.toLong() } <= MAX_WATCH_ENTRIES)
        val base = integer(native, "baseCount")
        check(base == 1065)
        val result = ArrayList<Int>()
        for (literal in sizes.indices) {
            val size = sizes[literal]
            val entry = entries[literal]
            if (entry == null) {
                check(size == 0)
                continue
            }
            check(integer(entry, "size") == size)
            val pages = value(entry, "pages") as Array<*>
            for (index in 0 until size) {
                val handle = (pages[index ushr 4] as LongArray)[index and 15].toInt()
                require(handle in 0 until base + count)
                if (handle >= base) result.add(handle - base)
            }
        }
        return result.toIntArray()
    }

    private fun demand(
        indices: IntArray,
        metadata: Map<Int, Any>,
        payloads: Map<Int, Any>,
        caches: Pair<IntArray, IntArray>,
    ): JsonObject {
        val grouped = indices.groupBy { it ushr 4 }.toSortedMap()
        return buildJsonObject {
            put("references", indices.size)
            put("distinctHandles", indices.toSet().size)
            put("distinctMetadataPages", grouped.size)
            put("byMetadataPage", JsonArray(grouped.map { (key, handles) ->
                val page = metadata[key]
                val active = handles.filter { index ->
                    page != null && (integer(page, "retained") and (1 shl (index and 15))) != 0 &&
                        ((value(page, "values") as IntArray)[(index and 15) * 9 + 4] and 4) == 0
                }
                buildJsonObject {
                    put("key", key)
                    put("references", handles.size)
                    put("distinctHandles", handles.toSet().size)
                    put("physicallyPresent", page != null)
                    put("metadataCacheHit", key == 0 || caches.first[key and caches.first.lastIndex] == key)
                    put("logicallyLiveReferences", active.size)
                    put("livePayloadAbsentReferences", active.count { !payloads.containsKey(it ushr 6) })
                    put("livePayloadCacheMissReferences", active.count {
                        val payloadKey = it ushr 6
                        payloadKey != 0 && caches.second[payloadKey and caches.second.lastIndex] != payloadKey
                    })
                }
            }))
        }
    }

    private fun guard(rows: Any, payloads: Any): List<Any?> = buildList {
        for (store in listOf(rows, payloads)) {
            val directory = reference(store, "directory")
            add(value(directory, "root"))
            add(value(directory, "size"))
            add(value(directory, "lastWork"))
            val cache = value(store, "cachedPages") as Array<*>
            add(cache)
            addAll(cache)
        }
        val indices = value(payloads, "cachedIndices") as IntArray
        add(indices)
        addAll(indices.toList())
    }

    private fun sameGuard(before: List<Any?>, after: List<Any?>): Boolean =
        before.size == after.size && before.indices.all { index ->
            val first = before[index]
            val second = after[index]
            if (first is Number && second is Number) first == second else first === second
        }

    private fun ints(values: IntArray): JsonArray = JsonArray(values.map(::JsonPrimitive))

    private companion object {
        const val MAX_SLOTS = 131072
        const val MAX_DIRECTORY_NODES = 32768
        const val MAX_WATCH_ENTRIES = 262144L
    }
}
