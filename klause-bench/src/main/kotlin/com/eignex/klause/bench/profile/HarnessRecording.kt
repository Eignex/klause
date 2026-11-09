package com.eignex.klause.bench.profile

import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordingFile
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant

internal object HarnessRecording {
    private val events = setOf(
        "klause.BenchPreparation", "jdk.FileRead", "jdk.FileWrite", "jdk.GarbageCollection", "jdk.GCPhasePause",
        "jdk.GCHeapSummary", "jdk.CPULoad", "jdk.ThreadCPULoad", "jdk.Compilation", "jdk.ExecutionSample",
        "jdk.NativeMethodSample", "jdk.ThreadPark", "jdk.JavaMonitorEnter", "jdk.ThreadSleep",
        "jdk.ActiveSetting", "jdk.DataLoss", "jdk.Shutdown",
    )

    fun extract(
        report: Path,
        recording: Path = report.resolve("harness.jfr"),
        scope: String = "ordinary harness JVM; excludes child CLI JVM and outer adapter extraction",
    ) {
        val start = System.nanoTime()
        var extractedEvents = 0L
        val failure = runCatching {
            check(Files.isRegularFile(recording)) { "Harness recording is unavailable" }
            Files.newBufferedWriter(report.resolve("harness-events.jsonl")).use { output ->
                RecordingFile(recording).use { input ->
                    output.appendLine(buildJsonObject {
                        put("scope", scope)
                        put("omissions", "recording thresholds, sampling and retention settings can omit observations")
                        put("overlapPolicy", "nested phases and JVM events overlap; durations are not additive")
                        put("failurePolicy", "aborted source-preparation may include cleanup before its phase closes")
                        put("stackDepthLimit", 32)
                        put("fieldPolicy",
                            "scalar fields only; nested objects remain in raw capture; timespans in nanoseconds; " +
                                "unrepresentable times are null and listed in unavailableTemporalFields")
                        put("ioPolicy", "IO call duration includes scheduling and GC; it does not prove device wait")
                        putJsonArray("eventTypes") {
                            input.readEventTypes().filter { it.name in events }.forEach { type ->
                                add(buildJsonObject { put("name", type.name); put("id", type.id) })
                            }
                        }
                    }.toString())
                    while (input.hasMoreEvents()) {
                        val event = input.readEvent()
                        if (event.eventType.name in events) {
                            output.appendLine(describe(event).toString())
                            extractedEvents++
                        }
                    }
                }
            }
        }.exceptionOrNull()
        if (failure != null) {
            Files.writeString(report.resolve("harness-analysis-error.txt"),
                "${failure.javaClass.name}: ${failure.message}\n")
        }
        Files.writeString(report.resolve("harness-analysis.json"), buildJsonObject {
            put("scope", "extraction outside recorded interval; inside current case/job ceiling")
            put("elapsedNanos", System.nanoTime() - start)
            put("complete", failure == null)
            put("extractedEvents", extractedEvents)
        }.toString())
    }

    private fun describe(event: RecordedEvent): JsonObject = buildJsonObject {
        val unavailable = mutableListOf<String>()
        put("event", event.eventType.name)
        put("eventTypeId", event.eventType.id)
        put("start", instantText { event.startTime }.also { if (it == null) unavailable.add("start") })
        put("durationNanos", durationNanos { event.duration }.also {
            if (it == null) unavailable.add("durationNanos")
        })
        val thread = if (event.hasField("sampledThread")) event.getThread("sampledThread") else event.thread
        put("thread", thread?.javaName)
        put("stackTruncated", event.stackTrace?.isTruncated)
        put("stackDepthLimited", (event.stackTrace?.frames?.size ?: 0) > 32)
        putJsonArray("stack") {
            event.stackTrace?.frames.orEmpty().take(32).forEach {
                add("${it.method.type.name}.${it.method.name}")
            }
        }
        putJsonObject("fields") {
            for (field in event.fields) {
                if (field.name in setOf("startTime", "duration", "eventThread", "stackTrace")) continue
                when (field.contentType) {
                    "jdk.jfr.Timespan" -> put(field.name, durationNanos { event.getDuration(field.name) }.also {
                        if (it == null) unavailable.add("fields.${field.name}")
                    })
                    "jdk.jfr.Timestamp" -> put(field.name, instantText { event.getInstant(field.name) }.also {
                        if (it == null) unavailable.add("fields.${field.name}")
                    })
                    else -> when (val value = event.getValue<Any?>(field.name)) {
                        null -> put(field.name, JsonNull)
                        is String -> put(field.name, value)
                        is Boolean -> put(field.name, value)
                        is Number -> put(field.name, value)
                    }
                }
            }
        }
        putJsonObject("fieldUnits") {
            for (field in event.fields) {
                field.contentType?.let {
                    put(field.name, if (it == "jdk.jfr.Timespan") "nanoseconds" else it)
                }
            }
        }
        putJsonArray("unavailableTemporalFields") { unavailable.forEach { add(it) } }
    }

    internal fun durationNanos(read: () -> Duration): Long? = try {
        read().toNanos()
    } catch (_: ArithmeticException) {
        null
    }

    internal fun instantText(read: () -> Instant): String? = try {
        read().toString()
    } catch (_: ArithmeticException) {
        null
    } catch (_: DateTimeException) {
        null
    }
}
