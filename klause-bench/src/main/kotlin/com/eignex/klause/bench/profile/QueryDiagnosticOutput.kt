package com.eignex.klause.bench.profile

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class QueryDiagnosticOutput(private val maximum: Long) {
    private val written = AtomicLong()
    private val overflow = AtomicBoolean()
    val exceeded: Boolean get() = overflow.get()

    init {
        require(maximum >= 0L)
    }

    fun write(file: File, text: String) {
        val bytes = text.toByteArray()
        reserve(bytes.size)
        file.writeBytes(bytes)
    }

    fun wrap(destination: OutputStream): OutputStream = object : OutputStream() {
        override fun write(value: Int) {
            reserve(1)
            destination.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            reserve(length)
            destination.write(bytes, offset, length)
        }
        override fun flush() = destination.flush()
    }

    private fun reserve(bytes: Int) {
        require(bytes >= 0)
        while (true) {
            val previous = written.get()
            if (bytes > maximum - previous) {
                overflow.set(true)
                throw IOException("diagnostic aggregate output cap")
            }
            if (written.compareAndSet(previous, previous + bytes)) return
        }
    }
}
