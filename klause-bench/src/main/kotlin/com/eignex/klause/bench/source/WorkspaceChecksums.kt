package com.eignex.klause.bench.source

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.HexFormat

internal class WorkspaceChecksums(
    root: Path,
    private val directory: Path? = directory(),
    private val hash: (Path) -> String = ::fileHash,
    reliable: (Path) -> Boolean = {
        Files.getFileStore(it).type().lowercase() in setOf("apfs", "ext4", "xfs", "btrfs", "overlay", "tmpfs", "zfs")
    },
) : AutoCloseable {
    private val identity = root.toRealPath().toString()
    private val device = try {
        if (directory != null && reliable(root)) device(root) else null
    } catch (_: IOException) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    }
    private val slot = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()))
        .take(2).toInt(16) % SLOTS
    private val entries = if (device == null) linkedMapOf() else read()
    private var dirty = false

    fun checksum(file: Path): String = canonicalChecksum(file.toRealPath())

    fun canonicalChecksum(canonical: Path): String {
        val before = if (device == null) null else stamp(canonical)
        val name = canonical.toString()
        entries[name]?.takeIf { before != null && it.stamp == before }?.let { return it.digest }
        val digest = hash(canonical)
        if (before != null) {
            require(stamp(canonical) == before) { "Input changed during checksum capture: $canonical" }
            if (entries.size >= MAX_FILES && name !in entries) entries.remove(entries.keys.first())
            entries[name] = Entry(before, digest)
            dirty = true
        }
        return digest
    }

    override fun close() {
        if (!dirty || directory == null) return
        try {
            val bytes = encode() ?: return
            Files.createDirectories(directory)
            FileChannel.open(directory.resolve("$slot.lock"), CREATE, WRITE).use { channel ->
                val owner = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                owner?.use {
                    val pending = directory.resolve("$slot.pending")
                    try {
                        Files.write(pending, bytes)
                        Files.move(pending, directory.resolve("$slot.bin"), ATOMIC_MOVE, REPLACE_EXISTING)
                    } finally {
                        Files.deleteIfExists(pending)
                    }
                }
            }
        } catch (_: IOException) {
            // Cache availability cannot establish an input identity or prevent byte verification.
        }
    }

    private fun stamp(file: Path): Stamp? {
        return try {
            val attributes = Files.readAttributes(file, "unix:dev,size,lastModifiedTime,ctime,fileKey,isRegularFile", NOFOLLOW_LINKS)
            if (attributes["isRegularFile"] != true || attributes["dev"] != device) return null
            val changed = attributes["ctime"]?.toString() ?: return null
            val key = attributes["fileKey"]?.toString() ?: return null
            Stamp(attributes["size"] as Long, attributes["lastModifiedTime"].toString(), changed, key)
        } catch (_: UnsupportedOperationException) {
            null
        }
    }

    private fun read(): LinkedHashMap<String, Entry> {
        return try {
            val file = requireNotNull(directory).resolve("$slot.bin")
            val bytes = Files.newInputStream(file).use { it.readNBytes(MAX_BYTES + 1) }
            if (bytes.size > MAX_BYTES) return linkedMapOf()
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readInt() != FORMAT || input.readUTF() != identity) return linkedMapOf()
                val count = input.readInt()
                if (count !in 0..MAX_FILES) return linkedMapOf()
                val saved = linkedMapOf<String, Entry>()
                repeat(count) {
                    val path = input.readUTF()
                    val stamp = Stamp(input.readLong(), input.readUTF(), input.readUTF(), input.readUTF())
                    val digest = input.readUTF()
                    if (stamp.bytes < 0 || !digest.matches(SHA256)) return linkedMapOf()
                    saved[path] = Entry(stamp, digest)
                }
                if (input.read() == -1) saved else linkedMapOf()
            }
        } catch (_: IOException) {
            linkedMapOf()
        }
    }

    private fun encode(): ByteArray? {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(FORMAT)
            output.writeUTF(identity)
            output.writeInt(entries.size)
            for ((path, entry) in entries) {
                output.writeUTF(path)
                output.writeLong(entry.stamp.bytes)
                output.writeUTF(entry.stamp.modified)
                output.writeUTF(entry.stamp.changed)
                output.writeUTF(entry.stamp.key)
                output.writeUTF(entry.digest)
                if (buffer.size() > MAX_BYTES) return null
            }
        }
        return buffer.toByteArray()
    }

    private data class Stamp(val bytes: Long, val modified: String, val changed: String, val key: String)
    private data class Entry(val stamp: Stamp, val digest: String)

    companion object {
        fun directory(environment: Map<String, String> = System.getenv()): Path? =
            environment["LAB_WORKSPACE_CACHE"]?.let { Path.of(it, "checksums-v1") }

        private fun device(path: Path): Any? = try {
            Files.getAttribute(path, "unix:dev", NOFOLLOW_LINKS)
        } catch (_: IOException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }

        private fun fileHash(file: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return HexFormat.of().formatHex(digest.digest())
        }

        private val SHA256 = Regex("[a-f0-9]{64}")
        private const val FORMAT = 0x57435331
        private const val SLOTS = 32
        private const val MAX_FILES = 4096
        private const val MAX_BYTES = 1024 * 1024
    }
}
