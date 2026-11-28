package com.eignex.klause.util

import com.eignex.util.io.CharSource

class ChunkedCharSource(chunks: List<String>) : CharSource {
    private val chunk = chunks.iterator()
    private var closed = false

    override fun next(): String? {
        if (closed) return null
        while (chunk.hasNext()) {
            val next = chunk.next()
            if (next.isNotEmpty()) return next
        }
        return null
    }

    override fun close() {
        closed = true
    }
}

// Lands a chunk boundary between every pair of characters.
fun perCharSource(text: String): CharSource = ChunkedCharSource(text.map { it.toString() })
