package com.eignex.klause.bench.profile

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** One member of a [TarStream]. Its [content] is readable only until the next [TarStream.next]. */
internal class TarMember(val name: String, val type: Char, val size: Long, val content: InputStream) {
    val isRegular: Boolean get() = type == '0' || type == NUL || type == '7'
    val isDirectory: Boolean get() = type == '5'

    private companion object {
        const val NUL = '\u0000'
    }
}

/**
 * Forward-only reader of ustar, GNU and pax tar streams. Long names arrive through GNU `L` and pax `path`
 * records, and pax `size` overrides the header size, so an archive written by any common tar resolves to the
 * same member names. Reading stops at the first all-zero header block.
 */
internal class TarStream(private val input: InputStream) {
    private var current: MemberContent? = null
    private var finished = false

    fun next(): TarMember? {
        current?.let { skipFully(it.remaining + it.padding) }
        current = null
        if (finished) return null
        var longName: String? = null
        var paxPath: String? = null
        var paxSize: Long? = null
        while (true) {
            val header = readBlock()
            if (header == null || header.all { it == ZERO }) {
                finished = true
                return null
            }
            verifyChecksum(header)
            val type = (header[TYPE_OFFSET].toInt() and BYTE_MASK).toChar()
            val headerSize = number(header, SIZE_OFFSET, SIZE_LENGTH)
            when (type) {
                GNU_LONG_NAME -> longName = metadata(headerSize).decodeToString().trimEnd(NUL)
                GNU_LONG_LINK -> metadata(headerSize)
                PAX_GLOBAL -> metadata(headerSize)
                PAX_LOCAL -> parsePax(metadata(headerSize)).let { records ->
                    records["path"]?.let { paxPath = it }
                    records["size"]?.let { paxSize = it.toLong() }
                }
                else -> return member(type, paxPath ?: longName ?: ustarName(header), paxSize ?: headerSize)
            }
        }
    }

    private fun member(headerType: Char, rawName: String, size: Long): TarMember {
        require(size >= 0) { "negative tar member size" }
        // V7 tar writes a directory as an old-style regular entry ending in '/'.
        val type = if (headerType == NUL && rawName.endsWith('/')) '5' else headerType
        val name = if (type == '5') rawName.trimEnd('/') else rawName
        // Link, device, directory and FIFO headers carry no data whatever their size field says.
        val dataSize = if (type in '1'..'6') 0L else size
        val content = MemberContent(dataSize, (BLOCK - dataSize % BLOCK) % BLOCK)
        current = content
        return TarMember(name, type, size, content)
    }

    private fun ustarName(header: ByteArray): String {
        val name = text(header, 0, NAME_LENGTH)
        val posix = (0 until POSIX_MAGIC.size).all { header[MAGIC_OFFSET + it] == POSIX_MAGIC[it] }
        val prefix = if (posix) text(header, PREFIX_OFFSET, PREFIX_LENGTH) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun metadata(size: Long): ByteArray {
        if (size !in 0..MAX_METADATA) throw IOException("tar metadata record exceeds $MAX_METADATA bytes")
        val bytes = ByteArray(size.toInt())
        readFully(bytes, bytes.size)
        skipFully((BLOCK - size % BLOCK) % BLOCK)
        return bytes
    }

    private fun parsePax(bytes: ByteArray): Map<String, String> {
        val records = HashMap<String, String>()
        var position = 0
        while (position < bytes.size) {
            val space = (position until bytes.size).firstOrNull { bytes[it] == SPACE }
                ?: throw IOException("malformed pax record")
            val length = bytes.copyOfRange(position, space).decodeToString().toIntOrNull()
            if (length == null || length <= space - position || position + length > bytes.size) {
                throw IOException("malformed pax record")
            }
            val record = bytes.copyOfRange(space + 1, position + length - 1).decodeToString()
            val separator = record.indexOf('=')
            if (separator < 0) throw IOException("malformed pax record")
            records[record.substring(0, separator)] = record.substring(separator + 1)
            position += length
        }
        return records
    }

    private fun verifyChecksum(header: ByteArray) {
        val stored = number(header, CHECKSUM_OFFSET, CHECKSUM_LENGTH)
        var unsigned = 0L
        var signed = 0L
        for ((index, byte) in header.withIndex()) {
            val inChecksum = index in CHECKSUM_OFFSET until CHECKSUM_OFFSET + CHECKSUM_LENGTH
            unsigned += if (inChecksum) SPACE.toLong() else (byte.toLong() and BYTE_MASK.toLong())
            signed += if (inChecksum) SPACE.toLong() else byte.toLong()
        }
        if (stored != unsigned && stored != signed) throw IOException("tar header checksum differs")
    }

    private fun number(header: ByteArray, offset: Int, length: Int): Long {
        val first = header[offset].toInt() and BYTE_MASK
        if (first == BASE256_POSITIVE) {
            var value = 0L
            for (index in offset + 1 until offset + length) {
                if (value ushr (Long.SIZE_BITS - Byte.SIZE_BITS - 1) != 0L) throw IOException("tar number overflows")
                value = (value shl Byte.SIZE_BITS) or (header[index].toLong() and BYTE_MASK.toLong())
            }
            return value
        }
        if (first and BASE256_POSITIVE != 0) throw IOException("negative tar number")
        val digits = text(header, offset, length).trim()
        if (digits.isEmpty()) return 0
        return digits.toLongOrNull(OCTAL) ?: throw IOException("invalid tar number")
    }

    private fun text(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && header[end] != ZERO) end++
        return header.copyOfRange(offset, end).decodeToString()
    }

    private fun readBlock(): ByteArray? {
        val block = ByteArray(BLOCK.toInt())
        val first = input.read(block, 0, block.size)
        if (first < 0) return null
        if (first < block.size) readFully(block, block.size, first)
        return block
    }

    private fun readFully(target: ByteArray, size: Int, start: Int = 0) {
        var filled = start
        while (filled < size) {
            val read = input.read(target, filled, size - filled)
            if (read < 0) throw EOFException("unexpected end of tar data")
            filled += read
        }
    }

    private fun skipFully(count: Long) {
        val buffer = ByteArray(SKIP_BUFFER)
        var remaining = count
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read < 0) throw EOFException("unexpected end of tar data")
            remaining -= read
        }
    }

    private inner class MemberContent(var remaining: Long, val padding: Long) : InputStream() {
        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and BYTE_MASK
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            check(current === this) { "tar member content read after advancing" }
            if (len == 0) return 0
            if (remaining == 0L) return -1
            val read = input.read(b, off, minOf(remaining, len.toLong()).toInt())
            if (read < 0) throw EOFException("unexpected end of tar data")
            remaining -= read
            return read
        }
    }

    private companion object {
        const val BLOCK = 512L
        const val SKIP_BUFFER = 65536
        const val MAX_METADATA = 1L shl 20
        const val NAME_LENGTH = 100
        const val SIZE_OFFSET = 124
        const val SIZE_LENGTH = 12
        const val CHECKSUM_OFFSET = 148
        const val CHECKSUM_LENGTH = 8
        const val TYPE_OFFSET = 156
        const val MAGIC_OFFSET = 257
        const val PREFIX_OFFSET = 345
        const val PREFIX_LENGTH = 155
        const val BYTE_MASK = 0xff
        const val BASE256_POSITIVE = 0x80
        const val OCTAL = 8
        const val ZERO: Byte = 0
        const val SPACE: Byte = 32
        const val NUL = '\u0000'
        const val GNU_LONG_NAME = 'L'
        const val GNU_LONG_LINK = 'K'
        const val PAX_LOCAL = 'x'
        const val PAX_GLOBAL = 'g'
        val POSIX_MAGIC = byteArrayOf(0x75, 0x73, 0x74, 0x61, 0x72, 0)
    }
}
