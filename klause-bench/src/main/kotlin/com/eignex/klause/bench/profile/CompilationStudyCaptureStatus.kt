package com.eignex.klause.bench.profile

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
internal data class CompilationStudyCaptureStatus(
    val recordedCliExit: String?,
    val recordedCliTermination: String?,
    val recordedCliUnsupported: String?,
    val recordedStatsAreStringPairs: Boolean,
    val recordCommandIsError: Boolean,
    val recordedCliSucceeded: Boolean,
    val requestedJfrNonempty: Boolean?,
    val requestedCompilationLogNonempty: Boolean?,
    val requestedRecordingFilesNonempty: Boolean,
    val recordingContentsVerified: Boolean = false,
    val sourceOutcomeValidated: Boolean = false,
) {
    companion object {
        fun inspect(
            record: JsonElement?,
            profile: Boolean,
            recordingBytes: Long,
            compilationLogBytes: Long,
        ): CompilationStudyCaptureStatus {
            val objectRecord = record as? JsonObject
            val stats = objectRecord?.get("stats") as? JsonObject
            val stringPairs = stats != null && stats.values.all { it is JsonPrimitive && it.isString }
            fun stringValue(key: String): String? = (stats?.get(key) as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
            val exit = stringValue("exit")
            val termination = stringValue("killed")
            val unsupported = stringValue("unsupported")
            val commandError = objectRecord?.get("command") == JsonPrimitive("ERROR")
            val childSucceeded = stats != null && stringPairs && exit == "0" &&
                !stats.containsKey("killed") && !stats.containsKey("unsupported") && !commandError
            return CompilationStudyCaptureStatus(exit, termination, unsupported, stringPairs, commandError,
                childSucceeded,
                (recordingBytes > 0).takeIf { profile }, (compilationLogBytes > 0).takeIf { profile },
                !profile || (recordingBytes > 0 && compilationLogBytes > 0))
        }
    }
}
