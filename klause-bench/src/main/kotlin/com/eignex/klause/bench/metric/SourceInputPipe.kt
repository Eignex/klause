package com.eignex.klause.bench.metric

import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference

internal class SourceInputPipe(private val process: Process, produce: (OutputStream) -> Unit) : AutoCloseable {
    private val failed = AtomicReference<Throwable?>(null)
    val failure: Throwable? get() = failed.get()

    private val writer = Thread {
        runCatching { process.outputStream.use(produce) }.onFailure {
            failed.compareAndSet(null, it)
            stopProcess()
        }
    }.apply {
        name = "klause-source-pipe"
        isDaemon = true
        start()
    }

    private fun stopProcess() {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    override fun close() {
        var interrupted = Thread.currentThread().isInterrupted
        if (process.isAlive) {
            stopProcess()
            writer.interrupt()
        }
        while (writer.isAlive) {
            try {
                writer.join()
            } catch (failure: InterruptedException) {
                interrupted = true
                failed.compareAndSet(null, failure)
                stopProcess()
                writer.interrupt()
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
