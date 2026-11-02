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
        return locked(id) { cached(id, fetch) }
    }

    fun member(id: String, relative: String, fetch: (File) -> File): File = locked(id) {
        require(relative.split('/').none { it.isEmpty() || it == "." || it == ".." })
        require('\\' !in relative)
        val collection = File(root, id)
        if (!present(collection)) {
            collection.mkdirs()
            File(collection, SELECTIVE).writeText("")
        }
        val target = File(collection, relative).toPath()
        if (!Files.isRegularFile(target, NOFOLLOW_LINKS)) {
            val parent = File(root, ".fetch").toPath()
            Files.createDirectories(parent)
            val staging = Files.createTempDirectory(parent, "$id-member-")
            try {
                val source = fetch(staging.toFile()).toPath()
                require(
                    source.toRealPath().startsWith(staging.toRealPath()) && Files.isRegularFile(source, NOFOLLOW_LINKS),
                )
                Files.createDirectories(target.parent)
                require(target.parent.toRealPath().startsWith(collection.toPath().toRealPath()))
                Files.move(source, target, ATOMIC_MOVE)
            } finally {
                remove(staging)
            }
        }
        if (root.canonicalFile == workspace.canonicalFile) return@locked target.toFile()
        val view = File(workspace, id)
        if (!present(view)) {
            view.mkdirs()
            File(view, SELECTIVE).writeText("")
        }
        val copy = File(workspace, "$id/$relative").toPath()
        if (!Files.isRegularFile(copy, NOFOLLOW_LINKS)) {
            Files.createDirectories(copy.parent)
            require(copy.parent.toRealPath().startsWith(view.toPath().toRealPath()))
            val staging = Files.createTempFile(copy.parent, ".member-", ".partial")
            try {
                Files.delete(staging)
                try { Files.createLink(staging, target) }
                catch (_: IOException) { Files.copy(target, staging, COPY_ATTRIBUTES) }
                catch (_: UnsupportedOperationException) { Files.copy(target, staging, COPY_ATTRIBUTES) }
                Files.move(staging, copy, ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(staging)
            }
        }
        copy.toFile()
    }

    private fun <T> locked(id: String, action: () -> T): T {
        require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")))
        val key = File(root.canonicalFile, id).path
        return synchronized(locks.computeIfAbsent(key) { Any() }) {
            val lock = File(root, ".locks/$id.lock").toPath()
            Files.createDirectories(lock.parent)
            FileChannel.open(lock, CREATE, WRITE).use { channel ->
                channel.lock().use { action() }
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

    companion object {
        const val SELECTIVE = ".selected-members"
        val locks = ConcurrentHashMap<String, Any>()
    }
}
