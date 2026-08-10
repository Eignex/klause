package com.eignex.klause.cli

import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioExecutor
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.util.CharSource
import com.eignex.klause.util.StringCharSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fprintf
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.getenv
import platform.posix.pclose
import platform.posix.popen
import platform.posix.rewind
import platform.posix.stderr
import kotlin.system.exitProcess

@OptIn(ExperimentalForeignApi::class)
internal actual fun cliProp(name: String): String? = getenv(name.uppercase().replace('.', '_'))?.toKString()

@OptIn(ExperimentalForeignApi::class)
internal actual fun errPrintln(message: String) {
    fprintf(stderr, "%s\n", message)
}

internal actual fun exitCli(code: Int): Nothing = exitProcess(code)

// Native reads the whole file up front (a chunked reader would have to buffer partial UTF-8 sequences
// across chunk boundaries); the [CharSource] contract is met, and the incremental-IO win lands on the
// JVM distribution first. A dedicated native streaming reader is a follow-up.
internal actual fun openFileSource(path: String): CharSource = StringCharSource(readWholeFile(path))

@OptIn(ExperimentalForeignApi::class)
private fun readWholeFile(path: String): String {
    compressionExtension(path)?.let { ext ->
        // `<decompressor> -dc '<path>'` piped through the shell; single-quote the path (escaping any
        // embedded quote) so a path with spaces is one argument.
        val quoted = "'" + path.replace("'", "'\\''") + "'"
        return readCommandOutput(DECOMPRESSORS.getValue(ext).joinToString(" ") + " " + quoted)
    }
    val f = fopen(path, "rb") ?: error("cannot open $path")
    try {
        fseek(f, 0L, SEEK_END)
        val size = ftell(f)
        rewind(f)
        if (size <= 0L) return ""
        val bytes = ByteArray(size.toInt())
        val read = bytes.usePinned { fread(it.addressOf(0), 1u, size.toULong(), f) }
        return bytes.copyOf(read.toInt()).decodeToString()
    } finally {
        fclose(f)
    }
}

/** Read the full stdout of a shell [command] (used to pipe compressed instances through the system
 *  decompressor). Bytes are accumulated and decoded once so a multi-byte character is never split. */
@OptIn(ExperimentalForeignApi::class)
private fun readCommandOutput(command: String): String {
    val pipe = popen(command, "r") ?: error("cannot run '$command'")
    val chunks = ArrayList<ByteArray>()
    var total = 0
    try {
        while (true) {
            val buf = ByteArray(65536)
            val n = buf.usePinned { fread(it.addressOf(0), 1u, buf.size.toULong(), pipe) }.toInt()
            if (n <= 0) break
            chunks.add(buf.copyOf(n))
            total += n
        }
    } finally {
        require(pclose(pipe) == 0) { "decompression command '$command' failed" }
    }
    val all = ByteArray(total)
    var off = 0
    for (c in chunks) {
        c.copyInto(all, off)
        off += c.size
    }
    return all.decodeToString()
}

internal actual fun parallelPortfolio(workers: List<PortfolioWorker>): PortfolioExecutor = Portfolio(workers)

// Native has no heap accounting equivalent to the JVM's memory pools, so the dry-run reports no
// heap figures there rather than inventing one.
internal actual fun startHeapPeakSampler() = Unit

internal actual fun sampleHeap(): HeapSample? = null
