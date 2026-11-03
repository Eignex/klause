package com.eignex.klause.bench.metric

import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory

internal data class SourceHarnessCounters(
    val threadCpuNanos: Long? = null,
    val collectionCount: Long? = null,
    val collectionTimeMillis: Long? = null,
    val collectors: List<String>? = null,
    val threadId: Long? = null,
)

internal class SourceHarnessObservation(private val read: () -> SourceHarnessCounters) {
    private val initial = runCatching(read).getOrNull()

    fun finish(): Map<String, String> {
        val first = initial ?: return emptyMap()
        val last = runCatching(read).getOrNull() ?: return emptyMap()
        return buildMap {
            if (first.threadId != null && first.threadId == last.threadId) {
                delta(first.threadCpuNanos, last.threadCpuNanos)?.let {
                    put("sourceHarnessThreadCpuMs", (it / NANOS_PER_MILLI).toString())
                }
            }
            if (first.collectors != null && first.collectors == last.collectors) {
                delta(first.collectionCount, last.collectionCount)?.let {
                    put("sourceHarnessGcCount", it.toString())
                }
                delta(first.collectionTimeMillis, last.collectionTimeMillis)?.let {
                    put("sourceHarnessGcTimeMs", it.toString())
                }
            }
        }
    }

    companion object {
        private const val NANOS_PER_MILLI = 1_000_000L

        fun start(): SourceHarnessObservation {
            val thread = runCatching { ManagementFactory.getThreadMXBean() }.getOrNull()
            return SourceHarnessObservation {
                val cpu = runCatching {
                    thread?.takeIf { it.isCurrentThreadCpuTimeSupported && it.isThreadCpuTimeEnabled }
                        ?.currentThreadCpuTime?.takeIf { it >= 0L }
                }.getOrNull()
                val collectors = runCatching {
                    ManagementFactory.getGarbageCollectorMXBeans()
                        .takeIf { it.isNotEmpty() && it.all { bean -> bean.isValid } }
                }.getOrNull()
                SourceHarnessCounters(cpu, total(collectors) { it.collectionCount },
                    total(collectors) { it.collectionTime }, collectors?.map { it.name }?.sorted(),
                    Thread.currentThread().threadId())
            }
        }

        private fun total(collectors: List<GarbageCollectorMXBean>?, value: (GarbageCollectorMXBean) -> Long): Long? {
            val values = collectors?.map { bean -> runCatching { value(bean) }.getOrNull()?.takeIf { it >= 0L } }
                ?: return null
            if (values.any { it == null }) return null
            return values.sumOf { requireNotNull(it) }
        }

        private fun delta(first: Long?, last: Long?): Long? =
            if (first != null && last != null && first >= 0L && last >= first) last - first else null
    }
}
