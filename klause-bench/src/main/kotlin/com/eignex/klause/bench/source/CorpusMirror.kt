package com.eignex.klause.bench.source

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.util.Base64
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream

internal class CorpusMirror(
    private val root: URI,
    private val downloads: CorpusDownload = CorpusDownload(),
) {
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

    fun publish(id: String, collection: File) {
        val archive = Files.createTempFile(collection.parentFile.toPath(), ".mirror-", ".tar")
        try {
            pack(id, collection, archive.toFile())
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
            val publication = corpusMirrorPublication(id, hash, address(id))
            val connection = URI(publication.url).toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "PUT"
                connection.connectTimeout = 10_000
                connection.readTimeout = 30_000
                connection.doOutput = true
                publication.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                connection.setFixedLengthStreamingMode(Files.size(archive))
                connection.setRequestProperty("If-None-Match", "*")
                val checksum = Base64.getEncoder().encodeToString(hash)
                connection.setRequestProperty("x-amz-checksum-sha256", checksum)
                connection.outputStream.use { output -> Files.newInputStream(archive).use { it.copyTo(output) } }
                check(connection.responseCode in setOf(200, 201, 204, 412)) { "corpus mirror publication failed" }
            } finally {
                connection.disconnect()
            }
        } finally {
            Files.deleteIfExists(archive)
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
    }
}
