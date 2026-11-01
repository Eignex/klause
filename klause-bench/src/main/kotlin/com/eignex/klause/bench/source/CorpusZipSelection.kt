package com.eignex.klause.bench.source

import com.eignex.klause.bench.catalog.FetchMethod
import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.tukaani.xz.XZInputStream

internal class CorpusZipSelection(private val downloads: CorpusDownload) {
    fun fetch(uri: URI, member: FetchMethod.ZipMember, directory: File) {
        require(member.name.isNotEmpty() && member.name !in setOf(".", ".."))
        require('/' !in member.name && '\\' !in member.name)
        val plainName = member.name.removeSuffix(".xz")
        require(plainName.isNotEmpty() && plainName !in setOf(".", ".."))
        require(member.sha256.matches(Regex("[0-9a-f]{64}")))
        Files.createDirectories(directory.toPath())
        val partial = Files.createTempFile(directory.toPath(), ".member-", ".partial")
        try {
            CorpusRangeChannel(uri, downloads).use { channel ->
                // Local headers for unrelated members can span the entire multi-gigabyte archive.
                ZipFile.builder().setSeekableByteChannel(channel).setIgnoreLocalFileHeader(true).get().use { zip ->
                    val matches = zip.entries.asSequence().filter {
                        it.name.substringAfterLast('/') == member.name
                    }.take(2).toList()
                    require(matches.size == 1) { "ZIP must contain one member named ${member.name}" }
                    val entry = matches.single()
                    require(!entry.isDirectory && !entry.isUnixSymlink && zip.canReadEntryData(entry))
                    require(entry.size in 0..PACKED_LIMIT && entry.compressedSize in 0..PACKED_LIMIT)
                    val checksum = CRC32()
                    val digest = MessageDigest.getInstance("SHA-256")
                    CheckedInputStream(zip.getInputStream(entry), checksum).use { raw ->
                        val packed = bounded(raw, PACKED_LIMIT)
                        val input = if (member.name.endsWith(".xz")) XZInputStream(packed, 65536) else packed
                        input.use {
                            DigestOutputStream(Files.newOutputStream(partial), digest).use { output ->
                                bounded(it, PLAIN_LIMIT).copyTo(output)
                            }
                        }
                    }
                    require(checksum.value == entry.crc) { "ZIP member checksum mismatch: ${entry.name}" }
                    require(HexFormat.of().formatHex(digest.digest()) == member.sha256) {
                        "ZIP member original source hash mismatch: ${entry.name}"
                    }
                    println("[corpus] selected ZIP member ${entry.name}; original SHA256 ${member.sha256}")
                    println("[corpus] ${channel.receipt()}")
                }
            }
            val target = directory.resolve(plainName).toPath()
            Files.move(partial, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(partial)
        }
    }

    private fun bounded(input: InputStream, limit: Long): InputStream = object : InputStream() {
        private var remaining = limit

        override fun read(): Int {
            val value = input.read()
            if (value >= 0) {
                require(remaining > 0) { "ZIP member exceeds its byte allowance" }
                remaining--
            }
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val count = input.read(buffer, offset, minOf(length.toLong(), remaining + 1).toInt())
            require(count <= remaining) { "ZIP member exceeds its byte allowance" }
            if (count > 0) remaining -= count
            return count
        }

        override fun close() = input.close()
    }

    private companion object {
        const val PACKED_LIMIT = 16L * 1024 * 1024
        const val PLAIN_LIMIT = 64L * 1024 * 1024
    }
}
