package com.eignex.klause.bench.profile

import jdk.jfr.Event
import jdk.jfr.Label
import jdk.jfr.Name
import jdk.jfr.StackTrace

internal object SourcePreparationCapture {
    private val enabled = System.getProperty("klause.bench.source-events").toBoolean()
    private val disabled = AutoCloseable { }

    fun <T> measure(phase: String, block: () -> T): T = span(phase).use { block() }

    fun span(phase: String): AutoCloseable {
        if (!enabled) return disabled
        val event = PreparationEvent().apply {
            this.phase = phase
            begin()
        }
        return object : AutoCloseable {
            private var closed = false

            override fun close() {
                if (closed) return
                closed = true
                event.end()
                event.commit()
            }
        }
    }

    @Name("klause.BenchPreparation")
    @Label("Bench preparation phase")
    @StackTrace(false)
    internal class PreparationEvent : Event() {
        @Label("Phase")
        var phase: String = ""
    }
}
