package com.eignex.klause.bench.lab

import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.IOException
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
        val receipt = directory.resolve("$slot.json")
        val saved = read(receipt)
        if (saved?.version == 1 && saved.root == identity && saved.digest == expected && saved.files == before) {
            return metadata(canonical) == before
        }
        val digest = hash(canonical)
        if (metadata(canonical) != before || digest != expected) return false
        save(receipt, Receipt(identity, digest, before))
        return true
    }

    private fun read(path: Path): Receipt? = try {
        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_BYTES + 1) }
        if (bytes.size > MAX_BYTES) null else Reports.json.decodeFromString<Receipt>(bytes.decodeToString())
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun save(path: Path, receipt: Receipt) {
        val bytes = Reports.json.encodeToString(receipt).toByteArray()
        if (bytes.size > MAX_BYTES) return
        try {
            Files.createDirectories(directory)
            val pending = Files.createTempFile(directory, ".compiler-", ".tmp")
            try {
                Files.write(pending, bytes)
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

    @Serializable
    private data class Stamp(
        val path: String,
        val bytes: Long,
        val modified: String,
        val changed: String,
        val key: String,
    )

    @Serializable
    private data class Receipt(val root: String, val digest: String, val files: List<Stamp>, val version: Int = 1)

    private companion object {
        const val SLOTS = 64
        const val MAX_BYTES = 1024 * 1024
        const val MAX_FILES = 10000
    }
}
