package com.eignex.klause.bench.source

import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CorpusRangeChannelTest {
    @Test
    fun `seeking reads bounded cached ranges without downloading intervening archive bytes`() {
        val source = ByteArray(200000) { it.toByte() }
        var transferred = 0
        val downloader = CorpusDownload(open = { uri ->
            object : HttpURLConnection(uri.toURL()) {
                private val interval get() = getRequestProperty("Range").removePrefix("bytes=").split('-')
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 206
                override fun getHeaderField(name: String): String? = when (name) {
                    "Content-Range" -> "bytes ${interval[0]}-${interval[1]}/${source.size}"
                    "ETag" -> "\"archive\""
                    else -> null
                }
                override fun getContentLengthLong() = -1L
                override fun getInputStream() = source.copyOfRange(
                    interval[0].toInt(), interval[1].toInt() + 1,
                ).also { transferred += it.size }.inputStream()
            }
        })

        CorpusRangeChannel(URI("https://corpus.invalid/archive"), downloader).use { channel ->
            val buffer = ByteBuffer.allocate(3)
            channel.position(131072)
            assertEquals(3, channel.read(buffer))
            assertContentEquals(source.copyOfRange(131072, 131075), buffer.array())
            channel.position(131072)
            buffer.clear()
            channel.read(buffer)
            channel.position(source.size.toLong())
            assertEquals(-1, channel.read(ByteBuffer.allocate(1)))
        }

        assertEquals(65537, transferred)
    }

    @Test
    fun `range allowance exhaustion stops before another body is requested`() {
        var requests = 0
        val downloader = CorpusDownload(open = { uri ->
            requests++
            object : HttpURLConnection(uri.toURL()) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 206
                override fun getHeaderField(name: String): String? = when (name) {
                    "Content-Range" -> "bytes 0-0/1000000"
                    "ETag" -> "\"archive\""
                    else -> null
                }
                override fun getContentLengthLong() = 1L
                override fun getInputStream() = byteArrayOf(0).inputStream()
            }
        })

        CorpusRangeChannel(URI("https://corpus.invalid/archive"), downloader, limit = 65536).use { channel ->
            assertFailsWith<IllegalArgumentException> { channel.read(ByteBuffer.allocate(1)) }
        }

        assertEquals(1, requests)
    }
}
