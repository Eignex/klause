package com.eignex.klause.bench.metric

import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Serializable
internal data class StderrCaptureStatus(
    val bytesRead: Long,
    val eof: Boolean,
    val recordTruncated: Boolean,
    val diagnosticsTruncated: Boolean,
    val processKilled: Boolean,
    val drainTimedOut: Boolean,
    val artifactRequested: Boolean,
    val artifactComplete: Boolean,
    val readFailure: String? = null,
    val artifactFailure: String? = null,
    val metadataFailure: String? = null,
) {
    val streamComplete: Boolean get() = eof && !processKilled && !drainTimedOut && readFailure == null
}

internal class StderrCapture(
    private val input: InputStream,
    private val openArtifact: (() -> OutputStream)? = null,
) {
    internal data class Result(
        val text: String,
        val refusal: String?,
        val quarantines: String?,
        val status: StderrCaptureStatus,
    ) {
        val complete: Boolean get() = status.streamComplete && !status.recordTruncated
    }

    private val diagnostics = StderrDiagnostics()
    private val stopped = AtomicBoolean(false)
    private val bytesRead = AtomicLong()
    @Volatile private var eof = false
    @Volatile private var readFailure: String? = null
    @Volatile private var artifactFailure: String? = null
    @Volatile private var artifactClosed = false
    private val thread = Thread(::drain, "klause-stderr").apply { isDaemon = true }

    fun start(): StderrCapture = apply { thread.start() }

    fun await(timeoutMs: Long, processKilled: Boolean): Result {
        require(timeoutMs > 0)
        thread.join(timeoutMs)
        val timedOut = thread.isAlive
        if (timedOut) {
            stopped.set(true)
            // Closing a process pipe can itself wait for its reader's lock.
            Thread({ runCatching { input.close() } }, "klause-stderr-close").apply {
                isDaemon = true
                start()
            }
        }
        return synchronized(diagnostics) {
            val status = StderrCaptureStatus(
                bytesRead = bytesRead.get(),
                eof = eof,
                recordTruncated = diagnostics.recordTruncated,
                diagnosticsTruncated = diagnostics.diagnosticsTruncated,
                processKilled = processKilled,
                drainTimedOut = timedOut,
                artifactRequested = openArtifact != null,
                artifactComplete = openArtifact != null && artifactClosed && artifactFailure == null &&
                    eof && readFailure == null && !processKilled && !timedOut,
                readFailure = readFailure,
                artifactFailure = artifactFailure,
            )
            Result(
                diagnostics.text, diagnostics.refusal.takeIf { status.streamComplete }, diagnostics.quarantines, status,
            )
        }
    }

    private fun drain() {
        var artifact = runCatching { openArtifact?.invoke() }
            .onFailure { artifactFailure = describe(it) }.getOrNull()
        val source = object : InputStream() {
            override fun read(): Int = error("bulk reads required")

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = input.read(buffer, offset, length)
                if (count > 0) {
                    bytesRead.addAndGet(count.toLong())
                    if (!stopped.get()) {
                        try {
                            artifact?.apply {
                                write(buffer, offset, count)
                                flush()
                            }
                        } catch (failure: IOException) {
                            artifactFailure = describe(failure)
                            runCatching { artifact?.close() }
                            artifact = null
                        }
                    }
                }
                return count
            }

            override fun close() = input.close()
        }
        try {
            source.reader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(CHUNK_SIZE)
                while (!stopped.get()) {
                    val count = reader.read(buffer)
                    if (count < 0) {
                        synchronized(diagnostics) { diagnostics.finish() }
                        eof = true
                        break
                    }
                    synchronized(diagnostics) { for (i in 0 until count) diagnostics.accept(buffer[i]) }
                }
            }
        } catch (failure: IOException) {
            readFailure = describe(failure)
        } finally {
            try {
                artifact?.close()
                artifactClosed = artifact != null
            } catch (failure: IOException) {
                artifactFailure = describe(failure)
            }
        }
    }

    companion object {
        const val RECORD_CAP = 16 * 1024
        const val DIAGNOSTIC_CAP = 2_000
        private const val CHUNK_SIZE = 4_096
        fun describe(failure: Throwable): String =
            "${failure.javaClass.simpleName}: ${failure.message.orEmpty().take(DIAGNOSTIC_CAP)}"
    }
}

internal class StderrDiagnostics {
    private val prefix = StringBuilder()
    private val line = StringBuilder()
    private val warnings = StringBuilder()
    private var firstRefusal: String? = null
    private var refusalSeen = false
    private var crash = false
    private var exceptionPosition = 0
    private var stackPosition = 0
    private var lineTruncated = false
    private var lineStarted = false
    private var afterCarriageReturn = false
    var recordTruncated = false
        private set
    var diagnosticsTruncated = false
        private set
    val text: String get() = prefix.toString()
    val refusal: String? get() = firstRefusal.takeUnless { crash }
    val quarantines: String? get() = warnings.toString().takeIf { it.isNotEmpty() }

    fun accept(char: Char) {
        when (char) {
            '\r' -> {
                endLine()
                afterCarriageReturn = true
            }
            '\n' -> {
                if (!afterCarriageReturn) endLine()
                afterCarriageReturn = false
            }
            else -> {
                afterCarriageReturn = false
                lineStarted = true
                retain(char)
                if (line.length < StderrCapture.DIAGNOSTIC_CAP) line.append(char) else lineTruncated = true
                exceptionPosition = if (char == EXCEPTION[exceptionPosition]) exceptionPosition + 1
                    else if (char == EXCEPTION[0]) 1 else 0
                if (exceptionPosition == EXCEPTION.length) {
                    crash = true
                    exceptionPosition = 0
                }
                if (stackPosition == 0 && char.isWhitespace()) return
                if (stackPosition >= 0) {
                    stackPosition = if (char == STACK[stackPosition]) stackPosition + 1 else -1
                    if (stackPosition == STACK.length) {
                        crash = true
                        stackPosition = -1
                    }
                }
            }
        }
    }

    fun finish() {
        if (lineStarted) endLine()
    }

    private fun retain(char: Char) {
        if (prefix.length < StderrCapture.RECORD_CAP) prefix.append(char) else recordTruncated = true
    }

    private fun endLine() {
        retain('\n')
        if (!refusalSeen && line.startsWith(REFUSAL_PREFIX)) {
            refusalSeen = true
            firstRefusal = line.substring(REFUSAL_PREFIX.length).trim().takeIf { it.isNotEmpty() }
            diagnosticsTruncated = diagnosticsTruncated || lineTruncated
        }
        if (line.startsWith(QUARANTINE_PREFIX)) {
            val warning = line.substring(QUARANTINE_PREFIX.length).trim()
            val separator = if (warnings.isEmpty()) "" else " | "
            val remaining = StderrCapture.DIAGNOSTIC_CAP - warnings.length
            val value = separator + warning
            warnings.append(value.take(remaining))
            diagnosticsTruncated = diagnosticsTruncated || lineTruncated || value.length > remaining
        }
        line.setLength(0)
        lineTruncated = false
        lineStarted = false
        exceptionPosition = 0
        stackPosition = 0
    }

    private companion object {
        const val EXCEPTION = "Exception"
        const val STACK = "at "
        const val REFUSAL_PREFIX = "klause "
        const val QUARANTINE_PREFIX = "% WARNING: portfolio arm "
    }
}
