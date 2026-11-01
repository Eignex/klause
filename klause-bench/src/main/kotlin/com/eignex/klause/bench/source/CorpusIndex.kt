package com.eignex.klause.bench.source

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.HexFormat

/**
 * The inventory of one collection exactly as its mirror pack holds it: each file's path relative to the collection
 * root, its sha256 and its size. A lab plan that may declare input hashes (`LAB_DECLARED_INPUTS`) selects from this
 * index alone, so a fresh node no longer downloads and unpacks every pack a selection touches; the lab fetches each
 * case's own bytes by content hash and verifies them against these hashes before the case runs.
 *
 * An index is trusted only when it describes the stored pack: it is computed from a restored pack, or from a tree
 * whose pack publication this process won, or downloaded from the mirror (see [CorpusFetcher.ensure]).
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
        private val json = Json { ignoreUnknownKeys = true }

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
                } catch (_: FileAlreadyExistsException) {
                    // A concurrent planner built the same view.
                } catch (_: DirectoryNotEmptyException) {
                    // A concurrent planner built the same view.
                }
            } finally {
                staging.deleteRecursively()
            }
        }
        return IndexedCollection(index, tree)
    }
}
