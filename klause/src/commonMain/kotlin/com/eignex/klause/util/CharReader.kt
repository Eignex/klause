package com.eignex.klause.util

/**
 * A forward-only buffered character reader over a [CharSource] — the primitive the char-scanning
 * front-ends (SMT-LIB, FlatZinc, XCSP3) build on. It gives a scanner indexed-`String`-style
 * access ([peek] / [advance] / [eof]) without ever holding the whole input: chunks are pulled from the
 * source on demand and the consumed prefix is dropped once the position grows, so memory stays bounded
 * by the live window rather than the file size.
 *
 * [peek] supports small lookahead (`offset` chars ahead), enough for the front-ends' two-character
 * escapes (e.g. SMT-LIB's `""` embedded quote).
 */
class CharReader(private val source: CharSource) {
    // The live window of pulled-but-not-yet-dropped characters; `pos` is the read cursor within it.
    // Consumed prefix is periodically dropped (see [compact]) so the buffer tracks the lookahead window,
    // not the whole input.
    private val buf = StringBuilder()
    private var pos = 0
    private var drained = false

    /** The character `offset` positions ahead of the cursor as an `Int`, or -1 at end of input. Pulls
     *  further chunks from the source as needed to satisfy the lookahead. */
    fun peek(offset: Int = 0): Int {
        val want = pos + offset
        while (buf.length <= want && !drained) {
            val chunk = source.next()
            if (chunk == null) drained = true else buf.append(chunk)
        }
        return if (want < buf.length) buf[want].code else -1
    }

    /** Consume one character, advancing the cursor. Compacts the buffer once the consumed prefix grows,
     *  so long inputs never accumulate. */
    fun advance() {
        pos++
        if (pos >= COMPACT_THRESHOLD) compact()
    }

    /** True once the cursor has reached end of input. */
    fun eof(): Boolean = peek() < 0

    // Drop the consumed prefix so the buffer holds only the live (unread) tail.
    private fun compact() {
        buf.deleteRange(0, pos)
        pos = 0
    }

    private companion object {
        // Drop the consumed prefix once it reaches this many characters. Large enough that compaction is
        // rare relative to token scanning, small enough that the buffer stays bounded on huge inputs.
        private const val COMPACT_THRESHOLD = 1 shl 16
    }
}
