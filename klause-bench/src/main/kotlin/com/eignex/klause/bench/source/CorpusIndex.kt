package com.eignex.klause.bench.source

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The inventory of one collection: each file's path relative to the collection root, its sha256 and its size.
 * Mirror indexes describe stored bytes; committed input indexes may name their materialized plain counterparts.
 * A lab plan that may declare input hashes (`LAB_DECLARED_INPUTS`) selects from this
 * index alone, so a fresh node no longer downloads and unpacks every pack a selection touches; the lab fetches each
 * case's own bytes by content hash and verifies them against these hashes before the case runs.
 *
 * Mirror indexes come from restored packs, winning pack publications or the mirror (see [CorpusFetcher.ensure]).
 * Committed input indexes bind the original bytes of those sources; materialization and publication verify them.
 */
@Serializable
internal data class CorpusIndex(val collection: String, val files: List<Entry>, val version: Int = VERSION) {
    @Serializable
    data class Entry(val path: String, val sha256: String, val bytes: Long)

    @Transient
    private val byPath: Map<String, Entry> = files.associateBy { it.path }

    init {
        require(version == VERSION) { "unsupported corpus index version $version" }
        require(collection.matches(ID)) { "invalid corpus index collection '$collection'" }
        require(byPath.size == files.size) { "corpus index lists a path twice" }
        for (entry in files) {
            require(entry.sha256.matches(SHA256) && entry.bytes >= 0) { "invalid corpus index entry ${entry.path}" }
            require(
                entry.path.isNotEmpty() && !entry.path.startsWith("/") &&
                    entry.path.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
                    entry.path.none { it == '\\' || it.isISOControl() },
            ) { "corpus index path escapes its collection: ${entry.path}" }
        }
    }

    fun entry(path: String): Entry? = byPath[path]

    companion object {
        const val VERSION = 1

        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
        private val SHA256 = Regex("[a-f0-9]{64}")
        private val json by lazy { Json { ignoreUnknownKeys = true } }

        fun encode(index: CorpusIndex): String = json.encodeToString(serializer(), index)

        fun decode(text: String): CorpusIndex = json.decodeFromString(serializer(), text)

        /** The index of the tree a pack of [collection] holds: the files [CorpusMirror] packs, in the same walk. */
        fun of(id: String, collection: File): CorpusIndex = CorpusIndex(
            id,
            collection.walkTopDown().onEnter { it.name != ".plain" }.filter { it.isFile }.sorted().map { file ->
                require(!Files.isSymbolicLink(file.toPath())) { "corpus collection '$id' holds a link: $file" }
                Entry(file.relativeTo(collection).invariantSeparatorsPath, sha256(file), file.length())
            }.toList(),
        )

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return HexFormat.of().formatHex(digest.digest())
        }
    }
}

/** A collection known only by its [index]. [root] is a listing skeleton of empty files, for discovery only. */
internal class IndexedCollection(val index: CorpusIndex, val root: File) {
    /** The index entry of [file], a path under [root]. */
    fun entry(file: File): CorpusIndex.Entry? =
        index.entry(root.toPath().relativize(file.toPath().normalize()).toString().replace('\\', '/'))
}

/**
 * Collection indexes committed with the source under `klause-bench/corpus-index/<id>.tsv.gz`: a header line, then
 * one `path<TAB>sha256<TAB>bytes` line per input. [fromSources] hashes original bytes rather than their compressed
 * encoding, so recompression preserves input identity. Planning reads these
 * instead of fetching an index or a collection, so a plan needs no network and costs no corpus transfer. A stale entry
 * cannot admit wrong bytes: the lab fetches each case's inputs by these hashes and verifies them before the case runs.
 */
internal object CommittedCorpusIndexes {
    const val DIRECTORY = "klause-bench/corpus-index"
    private const val HEADER = "path\tsha256\tbytes"

    fun file(workspace: File, id: String): File = File(workspace, "$DIRECTORY/$id.tsv.gz")

    fun fromSources(id: String, collection: File): CorpusIndex {
        val files = collection.walkTopDown().onEnter { it.name != ".plain" }.filter { it.isFile }
            .sortedWith(compareBy(CorpusFiles.storedFirst) { it.relativeTo(collection).invariantSeparatorsPath })
            .distinctBy { CorpusFiles.plainPath(it.relativeTo(collection).invariantSeparatorsPath) }
            .map { file ->
                require(!Files.isSymbolicLink(file.toPath())) { "corpus collection '$id' holds a link: $file" }
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                CorpusFiles.open(file).use { input ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        bytes += count
                    }
                }
                CorpusIndex.Entry(
                    CorpusFiles.plainPath(file.relativeTo(collection).invariantSeparatorsPath),
                    HexFormat.of().formatHex(digest.digest()), bytes,
                )
            }.toList()
        return CorpusIndex(id, files)
    }

    fun load(id: String, workspace: File? = runCatching { CorpusFetcher.workspaceRoot() }.getOrNull()): CorpusIndex? {
        val file = workspace?.let { file(it, id) }?.takeIf { it.isFile } ?: return null
        val lines = GZIPInputStream(file.inputStream()).bufferedReader().use { it.readLines() }
        require(lines.firstOrNull() == HEADER) { "committed corpus index '$id' has no header" }
        return CorpusIndex(
            id,
            lines.drop(1).filter { it.isNotEmpty() }.map { line ->
                val fields = line.split('\t')
                require(fields.size == 3) { "committed corpus index '$id' has a malformed line" }
                CorpusIndex.Entry(fields[0], fields[1], fields[2].toLong())
            },
        )
    }

    fun write(index: CorpusIndex, workspace: File) {
        val target = file(workspace, index.collection)
        target.parentFile.mkdirs()
        val pending = Files.createTempFile(target.parentFile.toPath(), ".${index.collection}.", ".tmp")
        try {
            GZIPOutputStream(Files.newOutputStream(pending)).bufferedWriter().use { out ->
                out.write(HEADER)
                out.write("\n")
                for (entry in index.files.sortedBy { it.path }) {
                    out.write(
                        "${entry.path}\t${entry.sha256}\t${entry.bytes}\n",
                    )
                }
            }
            Files.move(pending, target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(pending)
        }
    }

    fun capture(cache: File, workspace: File, selection: File) {
        val rows = selection.readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
        require(rows.isNotEmpty()) { "source selection is empty" }
        val indexes = rows.map { it.getValue("collection").jsonPrimitive.content }.distinct().associateWith { id ->
            require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")))
            val tree = File(cache, id)
            require(tree.isDirectory && !File(tree, CorpusStore.SELECTIVE).exists()) {
                "corpus collection '$id' is not complete"
            }
            fromSources(id, tree).also { write(it, workspace) }
        }
        File(workspace, "selected-source-hashes.tsv").bufferedWriter().use { manifest ->
            for (row in rows) {
                val id = row.getValue("collection").jsonPrimitive.content
                val problem = row.getValue("problem").jsonPrimitive.content
                require(problem.none { it.isISOControl() })
                val source = File(row.getValue("model").jsonPrimitive.content).toPath().toRealPath()
                val tree = File(cache, id).toPath().toRealPath()
                require(source.startsWith(tree) && Files.isRegularFile(source)) { "source outside collection '$id'" }
                val relative = tree.relativize(source).toString().replace(File.separatorChar, '/')
                val entry = requireNotNull(indexes.getValue(id).entry(relative)) { "selected source is not indexed" }
                val artifact = "inputs/$id/$relative"
                val target = File(workspace, artifact)
                target.parentFile.mkdirs()
                Files.copy(source, target.toPath(), REPLACE_EXISTING)
                val digest = MessageDigest.getInstance("SHA-256")
                target.inputStream().use { input ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                require(target.length() == entry.bytes && HexFormat.of().formatHex(digest.digest()) == entry.sha256) {
                    "selected source changed during capture: $id/$relative"
                }
                manifest.write("$id\t$problem\t${entry.sha256}\t${entry.bytes}\t$artifact\n")
            }
        }
    }

    /** Writes the committed index of every complete collection tree under [cache], or of [ids] alone. */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 2) { "usage: CommittedCorpusIndexes CORPUS_CACHE WORKSPACE [COLLECTION...]" }
        val cache = File(args[0])
        val workspace = File(args[1])
        if (args.getOrNull(2) == "--selection") {
            require(args.size == 4) { "--selection requires a selection JSONL file" }
            capture(cache, workspace, File(args[3]))
            return
        }
        val ids = args.drop(2).ifEmpty {
            cache.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().map { it.name }.sorted()
        }
        for (id in ids) {
            val tree = File(cache, id)
            require(tree.isDirectory) { "corpus collection '$id' is not materialized" }
            if (File(tree, CorpusStore.SELECTIVE).exists()) {
                System.err.println("skip $id: partial tree")
                continue
            }
            write(fromSources(id, tree), workspace)
            System.err.println("indexed $id")
        }
    }
}

/**
 * Local copies of collection indexes and their listing skeletons, under `.index/` of the corpus cache. Dot directories
 * are never collections, so the cache cap, discovery and pack publication all pass over them.
 */
internal class CorpusIndexes(private val root: File) {
    private val directory: File get() = File(root, ".index")

    fun local(id: String): CorpusIndex? = File(directory, "$id.json").takeIf { it.isFile }
        ?.let { runCatching { CorpusIndex.decode(it.readText()) }.getOrNull() }
        ?.takeIf { it.collection == id }

    fun save(index: CorpusIndex) {
        directory.mkdirs()
        val pending = Files.createTempFile(directory.toPath(), "${index.collection}.", ".tmp")
        try {
            Files.writeString(pending, CorpusIndex.encode(index))
            Files.move(pending, File(directory, "${index.collection}.json").toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(pending)
        }
    }

    /**
     * A tree of empty files at the paths [index] lists, so the existing [CorpusSelection.Layout] discovery and
     * [CorpusFetcher.resolve] walk it exactly as they walk the real collection and select the same instances. Its
     * files carry no content: callers map a resolved file back to its [CorpusIndex.Entry] and never read it.
     */
    fun view(index: CorpusIndex): IndexedCollection {
        val key = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(CorpusIndex.encode(index).toByteArray()),
        )
        val views = File(directory, "views")
        val tree = File(views, "${index.collection}-$key")
        if (!tree.isDirectory) {
            views.mkdirs()
            val staging = Files.createTempDirectory(views.toPath(), ".${index.collection}-").toFile()
            try {
                for (entry in index.files) {
                    val file = File(staging, entry.path)
                    file.parentFile.mkdirs()
                    file.createNewFile()
                }
                try {
                    Files.move(staging.toPath(), tree.toPath(), ATOMIC_MOVE)
                } catch (failure: FileSystemException) {
                    // Atomic directory renames can report a concurrent winner as a generic filesystem error.
                    if (!tree.isDirectory) throw failure
                }
            } finally {
                staging.deleteRecursively()
            }
        }
        return IndexedCollection(index, tree)
    }
}
