package com.eignex.klause.lp.bounding

import com.eignex.klause.solver.result.LpStatsSink

internal class RootHandoffDiagnostic(
    private val sink: LpStatsSink,
    private val seed: Long?,
    private val emphasis: String?,
    private val automatic: Boolean,
    private val planMask: Int = 0,
    private val enabled: Boolean = true,
) {
    private var sequence = 0
    private var epoch = 0L
    private var stopped = false

    fun reset(reason: String) {
        if (!enabled || stopped) return
        epoch++
        record("RESET", reason)
    }

    fun record(site: String, reason: String, fields: Map<String, String?> = emptyMap()) {
        if (!enabled || stopped) return
        val prefix = "ROOT_HANDOFF_TRACE_${seed ?: "unknown"}"
        if (sequence >= 127) {
            stopped = true
            sink.observePrimalPhase("${prefix}_CAP", "v1|reason:CAP", 0L)
            return
        }
        val values = linkedMapOf("seed" to seed?.toString(), "emphasis" to emphasis,
            "automatic" to automatic.toString(), "planMask" to planMask.toString(),
            "sequence" to sequence.toString(), "epoch" to epoch.toString(), "site" to site, "reason" to reason)
        if (fields.keys.any { it in values }) {
            stopped = true
            sink.observePrimalPhase("${prefix}_INVALID", "v1|reason:HEADER", 0L)
            return
        }
        values.putAll(fields)
        val payload = "v1|" + values.entries.joinToString("|") { (key, value) -> "$key:${value ?: "unknown"}" }
        if (values.size > 32 || payload.length > 2048 || payload.any { it == '=' || it.code !in 33..126 } ||
            values.keys.any { it.any { char -> char == ':' || char == '|' } } ||
            values.values.any { value -> value?.any { it == ':' || it == '|' } == true }
        ) {
            stopped = true
            sink.observePrimalPhase("${prefix}_INVALID", "v1|reason:PAYLOAD", 0L)
            return
        }
        sink.observePrimalPhase("${prefix}_${sequence++}", payload, 0L)
    }
}
