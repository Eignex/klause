package com.eignex.klause.bench.profile

import java.io.File
import java.nio.file.Files
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale

internal object CompilationStudyDistributions {
    private const val MAX_FILES = 10_000
    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024

    fun retain(source: File, destination: File): Map<String, CaptureFile> {
        require(source.isDirectory && !destination.exists())
        require(!destination.canonicalFile.toPath().startsWith(source.canonicalFile.toPath()))
        val before = inventory(source)
        check(destination.mkdirs())
        for (name in before.keys) {
            val original = File(source, name)
            val copy = File(destination, name)
            copy.parentFile.mkdirs()
            original.copyTo(copy)
            check(copy.setExecutable(original.canExecute(), false))
        }
        check(inventory(source) == before) { "Installed distribution changed while retaining original bytes" }
        check(inventory(destination) == before) { "Retained installed distribution differs" }
        return before
    }

    private fun inventory(root: File): Map<String, CaptureFile> {
        val files = linkedMapOf<String, CaptureFile>()
        var bytes = 0L
        root.walkTopDown().onEnter {
            require(!Files.isSymbolicLink(it.toPath())) { "Installed distribution contains a linked directory" }
            true
        }.forEach { file ->
            require(!Files.isSymbolicLink(file.toPath())) { "Installed distribution contains a linked file" }
            if (file.isFile) {
                require(files.size < MAX_FILES && file.length() <= MAX_BYTES - bytes) {
                    "Installed distribution retention exceeds bounds"
                }
                val identity = identity(file)
                require(identity.bytes == file.length() && identity.bytes <= MAX_BYTES - bytes)
                bytes += identity.bytes
                files[file.relativeTo(root).invariantSeparatorsPath] = identity
            } else {
                require(file.isDirectory) { "Installed distribution contains a special file" }
            }
        }
        require(files.isNotEmpty()) { "Installed distribution is empty" }
        return files
    }

    fun identity(file: File): CaptureFile {
        require(file.isFile && file.length() <= MAX_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        DigestInputStream(file.inputStream(), digest).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                count += read
                require(count <= MAX_BYTES) { "Installed file exceeds retention bounds" }
            }
        }
        return CaptureFile(count, digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) })
    }
}
