package com.eignex.klause.io

/**
 * A forward-only source of text for a front-end parser. Backed either by an in-memory [String]
 * ([StringCharSource]) or by a streamed file / decompressor pipe (the CLI's `openFileSource`), so a
 * front-end can consume its input incrementally — never holding the whole file, nor an intermediate
 * DOM/AST over it — instead of parsing one materialized [String].
 *
 * The contract is deliberately small: [next] yields the next slice of characters, or `null` at end of
 * input. Chunk boundaries are arbitrary (a token or line may straddle two chunks), so scanners built on
 * a source buffer across boundaries themselves (see [lineSequence] and [readText]). A source is consumed
 * once; [next] is not restartable.
 */
interface CharSource {
    /** The next slice of characters, or `null` once the input is exhausted. Never returns an empty
     *  non-null slice — an empty result always means end of input. */
    fun next(): String?
}

/** An in-memory [CharSource] over [text] — the adapter that lets a front-end still parse a [String]
 *  (tests, the DSL, an already-decompressed blob) through the same path as a streamed file. */
class StringCharSource(text: String) : CharSource {
    private var remaining: String? = text.ifEmpty { null }

    override fun next(): String? = remaining.also { remaining = null }
}

/** Materialize the whole [CharSource] into a [String] — the bridge for a front-end not yet converted to
 *  incremental consumption. Streams the chunks into one builder rather than re-reading. */
fun CharSource.readText(): String {
    val first = next() ?: return ""
    val second = next() ?: return first // the common single-chunk (in-memory) case allocates nothing extra
    val sb = StringBuilder(first).append(second)
    while (true) sb.append(next() ?: return sb.toString())
}

/**
 * A forward-only view of the source as lines (terminators stripped), splitting across chunk boundaries —
 * the input shape the line-oriented front-ends (dimacs/wcnf, opb/wbo, mps) consume. Both `\n` and `\r\n`
 * terminate a line; a final unterminated line is yielded. Lazy: only as much of the source as the
 * consumer pulls is read.
 */
fun CharSource.lineSequence(): Sequence<String> = sequence {
    val carry = StringBuilder()
    while (true) {
        val chunk = next()
        if (chunk == null) {
            if (carry.isNotEmpty()) yield(stripCr(carry.toString()))
            return@sequence
        }
        var start = 0
        while (true) {
            val nl = chunk.indexOf('\n', start)
            if (nl < 0) {
                carry.append(chunk, start, chunk.length)
                break
            }
            val line = if (carry.isEmpty()) {
                chunk.substring(start, nl)
            } else {
                carry.append(chunk, start, nl)
                carry.toString().also { carry.setLength(0) }
            }
            yield(stripCr(line))
            start = nl + 1
        }
    }
}

private fun stripCr(line: String): String =
    if (line.isNotEmpty() && line[line.length - 1] == '\r') line.substring(0, line.length - 1) else line
