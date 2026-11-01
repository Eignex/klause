package com.eignex.klause.bench.source

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.util.Base64

internal class CorpusMirror(private val root: URI, private val downloads: CorpusDownload = CorpusDownload()) {
    init {
        require(root.scheme == "https" && root.userInfo == null && root.rawQuery == null)
    }

    fun restore(id: String, destination: File): Boolean {
        val archive = Files.createTempFile(destination.parentFile.toPath(), ".mirror-", ".tar")
        try {
            try {
                downloads.fetch(address(id), archive.toFile())
            } catch (missing: CorpusDownload.HttpFailure) {
                if (missing.status == HttpURLConnection.HTTP_NOT_FOUND) return false
                throw missing
            }
            extract(id, archive.toFile(), destination)
            return true
        } finally {
            Files.deleteIfExists(archive)
        }
    }

    /** Publish [collection] as the pack of [id]. True when this call stored it; false when a pack was already
     *  there, whose bytes may differ from [collection] (a different upstream fetch). */
    fun publish(id: String, collection: File): Boolean {
        val archive = Files.createTempFile(collection.parentFile.toPath(), ".mirror-", ".tar")
        try {
            pack(id, collection, archive.toFile())
            return upload(id, archive)
        } finally {
            Files.deleteIfExists(archive)
        }
    }

    /**
     * The index of [id]'s pack, or null when none is published. It is stored as `<id>.index.tar` (a tar holding one
     * `index.json`) so it shares the pack key space the signer already grants, and is read without the pack.
     */
    fun index(id: String, scratch: File): CorpusIndex? {
        val archive = Files.createTempFile(scratch.toPath(), ".mirror-index-", ".tar")
        try {
            try {
                downloads.fetch(address(indexId(id)), archive.toFile())
            } catch (missing: CorpusDownload.HttpFailure) {
                if (missing.status == HttpURLConnection.HTTP_NOT_FOUND) return null
                throw missing
            }
            TarArchiveInputStream(Files.newInputStream(archive)).use { tar ->
                val entry = requireNotNull(tar.nextEntry) { "empty corpus index pack" }
                require(entry.isFile && entry.name == INDEX_ENTRY && entry.size in 0..MAX_INDEX_BYTES)
                val text = String(tar.readNBytes(entry.size.toInt()))
                require(tar.nextEntry == null) { "corpus index pack holds more than its index" }
                return CorpusIndex.decode(
                    text,
                ).also { require(it.collection == id) { "corpus index names another collection" } }
            }
        } finally {
            Files.deleteIfExists(archive)
        }
    }

    /** Publish [index], which must describe the pack stored for its collection. */
    fun publishIndex(index: CorpusIndex, scratch: File): Boolean {
        val archive = Files.createTempFile(scratch.toPath(), ".mirror-index-", ".tar")
        try {
            val bytes = CorpusIndex.encode(index).toByteArray()
            TarArchiveOutputStream(Files.newOutputStream(archive)).use { tar ->
                val entry = TarArchiveEntry(INDEX_ENTRY)
                entry.size = bytes.size.toLong()
                tar.putArchiveEntry(entry)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
            return upload(indexId(index.collection), archive)
        } finally {
            Files.deleteIfExists(archive)
        }
    }

    private fun indexId(id: String) = "$id.index"

    /** PUT [archive] as `<name>.tar` unless it already exists; true when this call stored it. */
    private fun upload(name: String, archive: Path): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(archive).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest()
        val publication = corpusMirrorPublication(name, hash, address(name))
        val connection = URI(publication.url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "PUT"
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            publication.headers.forEach { (header, value) -> connection.setRequestProperty(header, value) }
            connection.setFixedLengthStreamingMode(Files.size(archive))
            connection.setRequestProperty("If-None-Match", "*")
            val checksum = Base64.getEncoder().encodeToString(hash)
            connection.setRequestProperty("x-amz-checksum-sha256", checksum)
            val output = corpusTransport { connection.outputStream }
            try {
                Files.newInputStream(archive).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        corpusTransport { output.write(buffer, 0, count) }
                    }
                }
            } finally {
                corpusTransport { output.close() }
            }
            val status = corpusTransport { connection.responseCode }
            if (status !in setOf(200, 201, 204, 412)) {
                throw CorpusUnavailable(IOException("corpus mirror publication unavailable (HTTP $status)"))
            }
            return status != 412
        } finally {
            connection.disconnect()
        }
    }

    private fun address(id: String): URI {
        require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")))
        return URI(root.toString().trimEnd('/') + "/$id.tar")
    }

    internal fun extract(id: String, archive: File, destination: File) {
        var total = 0L
        val seen = mutableSetOf<String>()
        TarArchiveInputStream(archive.inputStream()).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val name = entry.name.removeSuffix("/")
                val relative = Path.of(name)
                require(!relative.isAbsolute && relative.normalize() == relative)
                require(relative.none { it.toString() == ".." })
                require(relative.first().toString() == id && (entry.isFile || entry.isDirectory))
                require(!entry.isLink && !entry.isSymbolicLink && seen.add(name))
                val target = if (relative.nameCount == 1) {
                    destination.toPath()
                } else {
                    destination.toPath().resolve(relative.subpath(1, relative.nameCount))
                }
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    require(entry.size in 0..MAX_BYTES - total)
                    total += entry.size
                    Files.createDirectories(target.parent)
                    val bytes = Files.newOutputStream(target, CREATE_NEW).use { tar.copyTo(it) }
                    require(bytes == entry.size)
                }
            }
        }
        check(destination.isDirectory && destination.list()?.isNotEmpty() == true) { "empty corpus mirror pack" }
    }

    private fun pack(id: String, collection: File, archive: File) {
        TarArchiveOutputStream(archive.outputStream()).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            collection.walkTopDown().onEnter { it.name != ".plain" }.filter { it.isFile }.sorted().forEach { file ->
                require(!Files.isSymbolicLink(file.toPath()))
                val entry = TarArchiveEntry(file, "$id/${file.relativeTo(collection).invariantSeparatorsPath}")
                tar.putArchiveEntry(entry)
                file.inputStream().use { it.copyTo(tar) }
                tar.closeArchiveEntry()
            }
        }
    }

    private companion object {
        const val MAX_BYTES = 32L * 1024 * 1024 * 1024
        const val MAX_INDEX_BYTES = 256L * 1024 * 1024
        const val INDEX_ENTRY = "index.json"
    }
}
