package com.eignex.klause.bench.source

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * Bounds the corpus cache under [root] to [capBytes] by evicting whole collections, least recently used
 * first. Every collection directory is re-fetchable, so evicting one only costs a re-download.
 *
 * Each collection's size and last use live in `.meta/<id>.properties`, so neither a lookup nor an eviction
 * pass walks the instance files: the size is recorded when a fetch completes (and measured once for a
 * collection fetched before it was tracked), and the last use is written on the first use in this JVM.
 * Collections named in [inUse] are never evicted; [CorpusFetcher] shares one set across the run.
 */
internal class CorpusCache(
    private val root: File,
    private val capBytes: Long?,
    private val inUse: MutableSet<String> = HashSet(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {

    /** One collection directory in the cache. */
    data class Entry(val id: String, val bytes: Long, val lastUsedMillis: Long, val inUse: Boolean)

    /** The outcome of an [enforce] pass: what was evicted and the total left behind. */
    data class Eviction(val evicted: List<Entry>, val totalBytes: Long)

    private val metaDir: File get() = File(root, META_DIR)

    private val trashDir: File get() = File(root, TRASH_DIR)

    /** Whether collection [id] is fetched. An eviction moves the directory out of place in one rename, so a
     *  collection is either whole here or absent, never half-deleted. */
    fun isPresent(id: String): Boolean = File(root, id).let { it.isDirectory && it.list()?.isNotEmpty() == true }

    /** Pin [id] for the rest of the run and stamp its last use; later calls in the same run are free. */
    fun markUsed(id: String) {
        val first = synchronized(inUse) { inUse.add(id) }
        if (first) writeMeta(id, readMeta(id).apply { setProperty(LAST_USED, clock().toString()) })
    }

    /** Record the size of [id], once a fetch or a compression pass has changed it. */
    fun recordSize(id: String) {
        writeMeta(id, readMeta(id).apply { setProperty(BYTES, sizeOf(File(root, id)).toString()) })
    }

    /** Record the size of every collection holding files under [dir]: all of them when [dir] is the cache
     *  root, the enclosing one when [dir] lies inside a collection, and none for a directory elsewhere. */
    fun recordSizesUnder(dir: File) {
        val top = root.absoluteFile
        if (dir.absoluteFile == top) {
            collectionDirs().forEach { recordSize(it.name) }
            return
        }
        var cur: File? = dir.absoluteFile
        while (cur != null && cur.parentFile != top) cur = cur.parentFile
        if (cur != null && !cur.name.startsWith(".")) recordSize(cur.name)
    }

    /** Every collection directory in the cache, least recently used first. */
    fun collections(): List<Entry> {
        val pinned = synchronized(inUse) { inUse.toSet() }
        return collectionDirs().map { entry(it, pinned) }.sortedWith(compareBy({ it.lastUsedMillis }, { it.id }))
    }

    /** Evict least recently used collections until the cache fits [capBytes], skipping those in use. When
     *  only in-use collections remain the pass warns and stops over the cap rather than failing the run. */
    fun enforce(): Eviction {
        sweep()
        val entries = collections()
        var total = entries.sumOf { it.bytes }
        val cap = capBytes ?: return Eviction(emptyList(), total)
        val evicted = ArrayList<Entry>()
        for (victim in entries) {
            if (total <= cap) break
            if (victim.inUse) continue
            evict(victim.id)
            evicted += victim
            total -= victim.bytes
            log("evicted '${victim.id}' (${victim.bytes / MIB} MiB) to stay under the ${cap / MIB} MiB corpus cap")
        }
        if (total > cap) log("corpus cache holds ${total / MIB} MiB, over its ${cap / MIB} MiB cap, all in use")
        return Eviction(evicted, total)
    }

    private fun collectionDirs(): List<File> =
        root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().toList()

    private fun entry(dir: File, pinned: Set<String>): Entry {
        val meta = readMeta(dir.name)
        val bytes = meta.getProperty(BYTES)?.toLongOrNull() ?: sizeOf(dir).also {
            writeMeta(dir.name, meta.apply { setProperty(BYTES, it.toString()) })
        }
        val lastUsed = meta.getProperty(LAST_USED)?.toLongOrNull() ?: dir.lastModified()
        return Entry(dir.name, bytes, lastUsed, dir.name in pinned)
    }

    /** Rename the collection into the trash before deleting it, so an interrupted delete leaves nothing
     *  that [isPresent] reads as fetched; [sweep] finishes it on the next pass. */
    private fun evict(id: String) {
        trashDir.mkdirs()
        val trashed = File(trashDir, "$id.${System.nanoTime()}")
        Files.move(File(root, id).toPath(), trashed.toPath(), StandardCopyOption.ATOMIC_MOVE)
        metaFile(id).delete()
        trashed.deleteRecursively()
    }

    /** Finish interrupted evictions and drop the metadata of collections no longer in the cache. */
    private fun sweep() {
        trashDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
        val pinned = synchronized(inUse) { inUse.toSet() }
        metaDir.listFiles { f -> f.name.endsWith(META_SUFFIX) }.orEmpty()
            .filter { meta ->
                val id = meta.name.removeSuffix(META_SUFFIX)
                id !in pinned && !File(root, id).isDirectory
            }
            .forEach { it.delete() }
    }

    private fun metaFile(id: String) = File(metaDir, id + META_SUFFIX)

    private fun readMeta(id: String): Properties {
        val props = Properties()
        val file = metaFile(id)
        if (file.isFile) {
            try {
                file.inputStream().use { props.load(it) }
            } catch (_: IOException) {
                props.clear()
            }
        }
        return props
    }

    /** Write through a temp file and a rename, so a reader never sees a torn file. */
    private fun writeMeta(id: String, props: Properties) {
        metaDir.mkdirs()
        val tmp = File.createTempFile("$id.", ".tmp", metaDir)
        tmp.outputStream().use { props.store(it, null) }
        Files.move(tmp.toPath(), metaFile(id).toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun sizeOf(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        /** Default cap on the cache, in GB: leaves a small disk room for one large collection fetched on top. */
        const val DEFAULT_MAX_GB = 60.0

        private const val GB = 1_000_000_000.0

        private const val MIB = 1024L * 1024

        private const val META_DIR = ".meta"

        private const val TRASH_DIR = ".trash"

        private const val META_SUFFIX = ".properties"

        private const val BYTES = "bytes"

        private const val LAST_USED = "lastUsed"

        /** The configured cap in bytes from `-Dklause.bench.corpus.maxGb` or `KLAUSE_BENCH_CORPUS_MAX_GB`
         *  (default [DEFAULT_MAX_GB]), or null when it is `0` or `off`. */
        fun configuredCapBytes(): Long? {
            val raw = System.getProperty("klause.bench.corpus.maxGb")
                ?: System.getenv("KLAUSE_BENCH_CORPUS_MAX_GB")?.takeIf { it.isNotBlank() }
            return parseCapBytes(raw)
        }

        /** [raw] GB as bytes; null (no cap) for `0` or `off`, the default for an absent value. */
        fun parseCapBytes(raw: String?): Long? {
            val value = raw?.trim() ?: return (DEFAULT_MAX_GB * GB).toLong()
            if (value.equals("off", ignoreCase = true)) return null
            val gb = requireNotNull(value.toDoubleOrNull()) { "corpus cache cap '$value' is not a number of GB" }
            require(gb >= 0) { "corpus cache cap '$value' is negative" }
            return if (gb == 0.0) null else (gb * GB).toLong()
        }
    }
}
