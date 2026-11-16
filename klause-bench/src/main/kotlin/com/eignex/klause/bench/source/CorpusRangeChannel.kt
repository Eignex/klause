package com.eignex.klause.bench.source

import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel

internal class CorpusRangeChannel(
    private val uri: URI,
    private val downloads: CorpusDownload,
    private val limit: Long = 32L * 1024 * 1024,
) : SeekableByteChannel {
    init { require(limit >= 1) }

    private val identity = downloads.range(uri, 0, 1).identity
    private var offset = 0L
    private var requested = 1L
    private var open = true
    private val blocks = object : LinkedHashMap<Long, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = size > 8
    }

    override fun read(destination: ByteBuffer): Int {
        ensureOpen()
        if (!destination.hasRemaining()) return 0
        if (offset >= identity.size) return -1
        val start = offset / BLOCK_SIZE * BLOCK_SIZE
        val block = blocks.getOrPut(start) {
            val length = minOf(BLOCK_SIZE.toLong(), identity.size - start).toInt()
            require(requested <= limit - length) { "corpus ZIP range allowance exhausted" }
            requested += length
            downloads.range(uri, start, length, identity).bytes
        }
        val within = (offset - start).toInt()
        val count = minOf(destination.remaining(), block.size - within)
        destination.put(block, within, count)
        offset += count
        return count
    }

    override fun position(): Long {
        ensureOpen()
        return offset
    }

    override fun position(newPosition: Long): SeekableByteChannel {
        ensureOpen()
        require(newPosition >= 0)
        offset = newPosition
        return this
    }

    override fun size(): Long {
        ensureOpen()
        return identity.size
    }
    override fun write(source: ByteBuffer): Int = throw NonWritableChannelException()
    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
    override fun isOpen(): Boolean = open

    override fun close() {
        open = false
        blocks.clear()
    }

    fun receipt(): String =
        "ZIP ranges requested $requested bytes; archive size ${identity.size}; ETag ${identity.etag}"

    private fun ensureOpen() {
        if (!open) throw ClosedChannelException()
    }

    private companion object {
        const val BLOCK_SIZE = 65536
    }
}
