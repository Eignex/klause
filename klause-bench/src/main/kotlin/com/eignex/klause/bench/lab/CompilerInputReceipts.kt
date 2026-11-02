package com.eignex.klause.bench.lab

import com.eignex.klause.bench.metric.sha256
import java.io.IOException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal class CompilerInputReceipts(
    private val directory: Path,
    private val hash: (Path) -> String = LabInputs::treeHash,
    private val reliable: (Path) -> Boolean = {
        Files.getFileStore(it).type().lowercase() in setOf("apfs", "ext4", "xfs", "btrfs", "overlay", "tmpfs", "zfs")
    },
) {
    fun matches(root: Path, expected: String): Boolean {
        val canonical = root.toRealPath()
        val before = try {
            if (reliable(canonical)) metadata(canonical) else null
        } catch (_: IOException) {
            null
        }
        if (before == null) return hash(canonical) == expected
        val identity = canonical.toString()
        val slot = sha256(identity.toByteArray()).take(2).toInt(16) % SLOTS
        val receipt = directory.resolve("$slot.bin")
        val saved = read(receipt)
        if (saved?.version == 1 && saved.root == identity && saved.digest == expected && saved.files == before) {
            return metadata(canonical) == before
        }
        val digest = hash(canonical)
        if (metadata(canonical) != before || digest != expected) return false
        save(receipt, Receipt(identity, digest, before))
        return true
    }

    private fun read(path: Path): Receipt? {
        return try {
            val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_BYTES + 1) }
            if (bytes.size > MAX_BYTES) null else DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readInt() != FORMAT) return null
                val root = input.readUTF()
                val digest = input.readUTF()
                val count = input.readInt()
                if (count !in 0..MAX_FILES) return null
                val files = List(count) {
                    Stamp(input.readUTF(), input.readLong(), input.readUTF(), input.readUTF(), input.readUTF())
                }
                if (input.read() != -1) null else Receipt(root, digest, files)
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun save(path: Path, receipt: Receipt) {
        try {
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { output ->
                output.writeInt(FORMAT)
                output.writeUTF(receipt.root)
                output.writeUTF(receipt.digest)
                output.writeInt(receipt.files.size)
                for (stamp in receipt.files) {
                    output.writeUTF(stamp.path)
                    output.writeLong(stamp.bytes)
                    output.writeUTF(stamp.modified)
                    output.writeUTF(stamp.changed)
                    output.writeUTF(stamp.key)
                    if (buffer.size() > MAX_BYTES) return
                }
            }
            Files.createDirectories(directory)
            val pending = Files.createTempFile(directory, ".compiler-", ".tmp")
            try {
                Files.write(pending, buffer.toByteArray())
                Files.move(pending, path, ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(pending)
            }
        } catch (_: IOException) {
            // Missing receipts only cost a new hash; they never establish input identity.
        }
    }

    private fun metadata(root: Path): List<Stamp>? {
        return try {
            val paths = Files.walk(root).use { it.sorted().limit(MAX_FILES + 1L).toList() }
            if (paths.size > MAX_FILES) null else paths.map { path ->
                val attrs = Files.readAttributes(
                    path, "unix:size,lastModifiedTime,ctime,fileKey,isRegularFile,isDirectory", NOFOLLOW_LINKS,
                )
                if (attrs["isRegularFile"] != true && attrs["isDirectory"] != true) return null
                val changed = attrs["ctime"]?.toString() ?: return null
                val key = attrs["fileKey"]?.toString() ?: return null
                Stamp(
                    root.relativize(path).toString(), attrs["size"] as Long,
                    attrs["lastModifiedTime"].toString(), changed, key,
                )
            }
        } catch (_: UnsupportedOperationException) {
            null
        }
    }

    private data class Stamp(
        val path: String,
        val bytes: Long,
        val modified: String,
        val changed: String,
        val key: String,
    )

    private data class Receipt(val root: String, val digest: String, val files: List<Stamp>, val version: Int = 1)

    private companion object {
        const val FORMAT = 0x43495231
        const val SLOTS = 64
        const val MAX_BYTES = 1024 * 1024
        const val MAX_FILES = 10000
    }
}
