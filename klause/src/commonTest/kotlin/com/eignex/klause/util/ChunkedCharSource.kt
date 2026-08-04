package com.eignex.klause.util

/** A [CharSource] over pre-split [chunks], for exercising a consumer across arbitrary chunk boundaries. */
class ChunkedCharSource(chunks: List<String>) : CharSource {
    private val chunk = chunks.iterator()
    override fun next(): String? = if (chunk.hasNext()) chunk.next() else null
}

/** A [CharSource] that hands out [text] one character at a time — the tightest chunking, which lands a
 *  boundary between every pair of characters. */
fun perCharSource(text: String): CharSource = ChunkedCharSource(text.map { it.toString() })
