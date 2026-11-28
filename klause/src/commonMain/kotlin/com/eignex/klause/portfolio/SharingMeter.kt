package com.eignex.klause.portfolio

import com.eignex.klause.solver.incumbent.IncumbentOfferEvent
import com.eignex.klause.solver.incumbent.IncumbentSource
import com.eignex.klause.solver.result.ChannelTraffic
import com.eignex.klause.solver.result.SharingChannel
import com.eignex.klause.solver.result.SharingStats
import kotlin.time.TimeSource

// A worker runs on one lane at a time, so its meter needs no lock.
internal class SharingMeter {
    internal var onImport: ((SharingChannel, Long, Int) -> Unit)? = null
    private val observed = arrayOfNulls<Long>(CHANNELS)

    internal fun observed(channel: SharingChannel, revision: Long) {
        val listener = onImport ?: return
        if (observed[channel.ordinal] == revision) return
        observed[channel.ordinal] = revision
        listener(channel, revision, 1)
    }
    private val clauseCounts = HashMap<String, Long>()
    private val incumbentCounts = linkedMapOf<String, Long>()

    internal fun incumbentEvent(event: IncumbentOfferEvent) {
        if (incumbentCounts.isEmpty()) {
            for (kind in IncumbentOfferEvent.entries) {
                incumbentCounts[kind.name.replaceFirstChar { it.lowercaseChar() }] = 0L
            }
        }
        val name = event.name.replaceFirstChar { it.lowercaseChar() }
        val previous = incumbentCounts[name] ?: 0L
        if (previous < Long.MAX_VALUE) incumbentCounts[name] = previous + 1L
        if (event == IncumbentOfferEvent.Installed) exported(SharingChannel.Incumbents, 1)
    }
    private val clauseMemory = HashMap<String, Long>()
    private var sampleFields: Set<String> = emptySet()

    internal fun clauseMemorySample(values: Map<String, Long>) {
        for (name in sampleFields) clauseMemory.remove(name)
        sampleFields = values.keys.toSet()
        clauseMemory(values)
    }

    internal fun clauseCount(name: String, count: Long) {
        clauseCounts[name] = (clauseCounts[name] ?: 0L) + count
    }

    internal fun clauseMemory(values: Map<String, Long>) {
        for ((name, value) in values) {
            clauseMemory[name] = value
            clauseMemory["peak" + name.replaceFirstChar { it.uppercaseChar() }] =
                maxOf(clauseMemory["peak" + name.replaceFirstChar { it.uppercaseChar() }] ?: 0L, value)
        }
    }

    private val nanos = LongArray(CHANNELS)
    private val exported = LongArray(CHANNELS)
    private val imported = LongArray(CHANNELS)
    private val duplicates = LongArray(CHANNELS)

    fun <T> timed(channel: SharingChannel, block: () -> T): T {
        val start = TimeSource.Monotonic.markNow()
        try {
            return block()
        } finally {
            charge(channel, start.elapsedNow().inWholeNanoseconds)
        }
    }

    fun charge(channel: SharingChannel, elapsed: Long) {
        nanos[channel.ordinal] += elapsed
    }

    fun exported(channel: SharingChannel, count: Int) {
        exported[channel.ordinal] += count.toLong()
    }

    fun imported(channel: SharingChannel, count: Int, cursor: Long = 0L) {
        imported[channel.ordinal] += count.toLong()
        onImport?.invoke(channel, cursor, count)
    }

    fun duplicates(channel: SharingChannel, count: Int) {
        duplicates[channel.ordinal] += count.toLong()
    }

    fun snapshot(): SharingStats = SharingStats(
        SharingChannel.entries.mapNotNull { channel ->
            val i = channel.ordinal
            val traffic = ChannelTraffic(nanos[i], exported[i], imported[i], duplicates[i])
            if (traffic == ChannelTraffic()) null else channel to traffic
        }.toMap(),
        clauseCounts + clauseMemory,
        incumbentCounts.toMap(),
    )

    private companion object {
        val CHANNELS = SharingChannel.entries.size
    }
}

internal fun <A, V> IncumbentSource<A, V>.metered(meter: SharingMeter): IncumbentSource<A, V> =
    IncumbentSource {
        meter.timed(SharingChannel.Incumbents) {
            current().also { meter.observed(SharingChannel.Incumbents, it?.version ?: 0L) }
        }
    }
