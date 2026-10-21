package com.eignex.klause.bench.source

import java.io.InputStream
import java.security.MessageDigest

/**
 * The Global Benchmark Database's content hash of a DIMACS CNF (`gbdhash`): the MD5 of its clause literals with
 * comment and header lines dropped and each run of whitespace between tokens written as one space, ending in a
 * clause terminator. Two files that differ only in comments, header or layout share it, so it names an instance
 * whatever its file name or compression. Mirrors `cnf_hash` in GBD's `gbd_init/gbdhash.py`.
 */
internal object GbdHash {
    private const val BUFFER_BYTES = 1 shl 16
    private const val BYTE_MASK = 0xFF

    fun of(input: InputStream): String {
        val digest = Digest()
        val buffer = ByteArray(BUFFER_BYTES)
        var read = input.read(buffer)
        while (read >= 0) {
            for (i in 0 until read) digest.feed(buffer[i])
            read = input.read(buffer)
        }
        return digest.finish()
    }

    private class Digest {
        private val md5 = MessageDigest.getInstance("MD5")
        private var space = false
        private var skip = false
        private var start = true
        private var clauseEnded = true

        fun feed(byte: Byte) {
            val ch = byte.toInt() and BYTE_MASK
            when {
                !skip && (ch in '0'.code..'9'.code || ch == '-'.code) -> literal(byte, ch)
                ch <= ' '.code -> {
                    space = !start
                    skip = skip && ch != '\n'.code && ch != '\r'.code
                }

                else -> skip = true
            }
        }

        private fun literal(byte: Byte, ch: Int) {
            clauseEnded = ch == '0'.code && (space || start)
            start = false
            if (space) {
                space = false
                md5.update(' '.code.toByte())
            }
            md5.update(byte)
        }

        fun finish(): String {
            if (!clauseEnded) md5.update(" 0".toByteArray())
            return md5.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
