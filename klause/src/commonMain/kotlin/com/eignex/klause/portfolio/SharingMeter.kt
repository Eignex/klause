package com.eignex.klause.portfolio

import com.eignex.klause.solver.incumbent.IncumbentSource
import com.eignex.klause.solver.result.ChannelTraffic
import com.eignex.klause.solver.result.SharingChannel
import com.eignex.klause.solver.result.SharingStats
import kotlin.time.TimeSource

/**
 * One worker's sharing traffic, recorded where it exchanges with the pools. A worker runs on one lane at a time,
 * so the meter needs no lock.
 */
internal class SharingMeter {
    internal var onImport: ((SharingChannel, Long, Int) -> Unit)? = null
    private val observed = arrayOfNulls<Long>(CHANNELS)

    internal fun observed(channel: SharingChannel, revision: Long) {
        val listener = onImport ?: return
        if (observed[channel.ordinal] == revision) return
        observed[channel.ordinal] = revision
        listener(channel, revision, 1)
    }
    private val nanos = LongArray(CHANNELS)
    private val exported = LongArray(CHANNELS)
    private val imported = LongArray(CHANNELS)
    private val duplicates = LongArray(CHANNELS)

    /** Run [block], charging the time it takes to [channel]. */
    fun <T> timed(channel: SharingChannel, block: () -> T): T {
        val start = TimeSource.Monotonic.markNow()
        try {
            return block()
        } finally {
            charge(channel, start.elapsedNow().inWholeNanoseconds)
        }
    }

    /** Charge [elapsed] nanoseconds to [channel]. */
    fun charge(channel: SharingChannel, elapsed: Long) {
        nanos[channel.ordinal] += elapsed
    }

    /** Count [count] entries published on [channel]. */
    fun exported(channel: SharingChannel, count: Int) {
        exported[channel.ordinal] += count.toLong()
    }

    /** Count [count] entries taken in from other arms on [channel]. */
    fun imported(channel: SharingChannel, count: Int, cursor: Long = 0L) {
        imported[channel.ordinal] += count.toLong()
        onImport?.invoke(channel, cursor, count)
    }

    /** Count [count] entries received on [channel] that were already seen. */
    fun duplicates(channel: SharingChannel, count: Int) {
        duplicates[channel.ordinal] += count.toLong()
    }

    /** The traffic so far, leaving out channels with none. */
    fun snapshot(): SharingStats = SharingStats(
        SharingChannel.entries.mapNotNull { channel ->
            val i = channel.ordinal
            val traffic = ChannelTraffic(nanos[i], exported[i], imported[i], duplicates[i])
            if (traffic == ChannelTraffic()) null else channel to traffic
        }.toMap(),
    )

    private companion object {
        val CHANNELS = SharingChannel.entries.size
    }
}

/** This source read through [meter], charging each read to [SharingChannel.Incumbents]. */
internal fun <A, V> IncumbentSource<A, V>.metered(meter: SharingMeter): IncumbentSource<A, V> =
    IncumbentSource {
        meter.timed(SharingChannel.Incumbents) {
            current().also { meter.observed(SharingChannel.Incumbents, it?.version ?: 0L) }
        }
    }
