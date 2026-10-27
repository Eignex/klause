package com.eignex.klause.bench.source

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.ConcurrentHashMap

internal class CorpusStore(private val root: File, private val workspace: File = root) {
    fun ensure(id: String, fetch: (File) -> Unit): File {
        require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")))
        val key = File(root.canonicalFile, id).path
        return synchronized(locks.computeIfAbsent(key) { Any() }) {
            val lock = File(root, ".locks/$id.lock").toPath()
            Files.createDirectories(lock.parent)
            FileChannel.open(lock, CREATE, WRITE).use { channel ->
                channel.lock().use { cached(id, fetch) }
            }
        }
    }

    private fun cached(id: String, fetch: (File) -> Unit): File {
        val collection = File(root, id)
        if (!present(collection)) {
            val staging = File(root, ".fetch/$id").toPath()
            remove(staging)
            Files.createDirectories(staging)
            try {
                val content = staging.resolve("collection").toFile()
                fetch(content)
                check(present(content)) { "corpus collection '$id' is empty" }
                Files.move(content.toPath(), collection.toPath(), ATOMIC_MOVE)
            } finally {
                remove(staging)
            }
        }
        return if (root.canonicalFile == workspace.canonicalFile) collection else materialize(id, collection)
    }

    private fun materialize(id: String, collection: File): File {
        val target = File(workspace, id)
        if (present(target)) return target
        Files.createDirectories(workspace.toPath())
        val staging = Files.createTempDirectory(workspace.toPath(), ".copy-$id-")
        try {
            val source = collection.toPath()
            Files.walk(source).use { paths ->
                paths.forEach { file ->
                    val destination = staging.resolve(source.relativize(file))
                    when {
                        Files.isDirectory(file, NOFOLLOW_LINKS) -> Files.createDirectories(destination)
                        Files.isSymbolicLink(file) -> Files.copy(file, destination, NOFOLLOW_LINKS)
                        else -> try {
                            Files.createLink(destination, file)
                        } catch (_: IOException) {
                            Files.copy(file, destination, COPY_ATTRIBUTES)
                        } catch (_: UnsupportedOperationException) {
                            Files.copy(file, destination, COPY_ATTRIBUTES)
                        }
                    }
                }
            }
            Files.move(staging, target.toPath(), ATOMIC_MOVE)
        } finally {
            remove(staging)
        }
        return target
    }

    private fun present(directory: File): Boolean = directory.isDirectory && directory.list()?.isNotEmpty() == true

    private fun remove(path: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }

    private companion object {
        val locks = ConcurrentHashMap<String, Any>()
    }
}
