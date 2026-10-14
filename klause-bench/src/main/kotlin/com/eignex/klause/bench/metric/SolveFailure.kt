package com.eignex.klause.bench.metric

import java.util.Collections
import java.util.IdentityHashMap

internal object SolveFailure {
    private const val MAX_CAUSES = 3
    private const val MAX_CLASS_CHARS = 256
    private const val MAX_MESSAGE_CHARS = 1024

    fun stats(failure: Throwable): Map<String, String> = buildMap {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = failure
        var depth = 0
        while (current != null) {
            if (!seen.add(current)) {
                put("errorCausesTruncated", "cycle")
                break
            }
            if (depth > MAX_CAUSES) {
                put("errorCausesTruncated", "depth")
                break
            }
            val prefix = if (depth == 0) "error" else "errorCause$depth"
            val name = current.javaClass.name
            put("${prefix}Class", name.take(MAX_CLASS_CHARS))
            if (name.length > MAX_CLASS_CHARS) put("${prefix}ClassTruncated", "true")
            current.message?.let { message ->
                put("${prefix}Message", message.take(MAX_MESSAGE_CHARS))
                if (message.length > MAX_MESSAGE_CHARS) put("${prefix}MessageTruncated", "true")
            }
            current = current.cause
            depth++
        }
    }
}
