package com.eignex.klause.bench.source

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.zip.ZipFile

internal class CorpusZipCollection(
    private val uri: URI,
    private val extension: String,
    private val maxFileBytes: Long,
    private val downloads: CorpusDownload,
) {
    private data class Member(val path: String, val bytes: Long, val crc: Long)

    private val members: Map<String, Member> by lazy {
        archive { zip ->
            val result = LinkedHashMap<String, Member>()
            for (entry in zip.entries.asSequence()) {
                require(entry.name.split('/').none { it == ".." } && !entry.name.startsWith('/') && '\\' !in entry.name)
                if (entry.isDirectory) continue
                val plain = entry.name.removeSuffix(".gz")
                if (!plain.endsWith(".$extension") || entry.size !in 0..maxFileBytes) continue
                require(!entry.isUnixSymlink && zip.canReadEntryData(entry))
                val path = plain + CorpusFiles.SUFFIX
                require(result.put(path, Member(entry.name, entry.size, entry.crc)) == null) {
                    "ZIP contains ambiguous instance paths: $plain"
                }
            }
            require(result.isNotEmpty()) { "ZIP contains no eligible .$extension instances" }
            result
        }
    }

    val paths: List<String> get() = members.keys.toList()

    fun fetch(path: String, directory: File): File {
        val member = requireNotNull(members[path]) { "instance '$path' absent from ZIP collection" }
        val plain = directory.resolve(path.removeSuffix(CorpusFiles.SUFFIX))
        Files.createDirectories(plain.toPath().parent)
        try {
            archive { zip ->
                val entry = requireNotNull(zip.getEntry(member.path))
                require(
                    entry.size == member.bytes && entry.crc == member.crc &&
                        !entry.isUnixSymlink && zip.canReadEntryData(entry),
                ) {
                    "ZIP member changed after discovery"
                }
                val checksum = CRC32()
                CheckedInputStream(zip.getInputStream(entry), checksum).use { packed ->
                    val input = if (member.path.endsWith(".gz")) GZIPInputStream(packed) else packed
                    input.use { source -> plain.outputStream().use { source.copyTo(it) } }
                }
                require(checksum.value == member.crc) { "ZIP member checksum mismatch: ${member.path}" }
            }
            return CorpusFiles.compress(plain)
        } finally {
            plain.delete()
        }
    }

    private fun <T> archive(read: (ZipFile) -> T): T = CorpusRangeChannel(uri, downloads).use { channel ->
        ZipFile.builder().setSeekableByteChannel(channel).setIgnoreLocalFileHeader(true).get().use { zip ->
            read(zip).also { println("[corpus] ${channel.receipt()}") }
        }
    }
}
