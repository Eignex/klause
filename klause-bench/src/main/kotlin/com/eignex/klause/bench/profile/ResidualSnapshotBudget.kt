package com.eignex.klause.bench.profile

import com.eignex.klause.solver.objective.LinearObjective
import kotlinx.serialization.json.JsonObject

internal class ResidualCaptureContext(
    val objective: LinearObjective,
    val sourceIdentity: JsonObject,
    val preparationIdentity: JsonObject,
    val budget: ResidualSnapshotBudget,
)

internal class ResidualSnapshotBudget(private val maxCount: Int, private val maxBytes: Int) {
    var count: Int = 0
        private set
    var bytes: Int = 0
        private set

    init {
        require(maxCount in 0..16 && maxBytes in 0..ResidualSnapshots.MAX_BYTES)
    }

    fun retain(capture: (remainingBytes: Int) -> ResidualCapture): ResidualCapture {
        if (count >= maxCount) return ResidualCapture(null, "snapshot-count-limit")
        if (bytes >= maxBytes) return ResidualCapture(null, "snapshot-byte-limit")
        val result = capture(maxBytes - bytes)
        val payload = result.bytes ?: return result
        require(payload.size <= maxBytes - bytes) { "snapshot budget exceeded" }
        count++
        bytes += payload.size
        return result
    }
}
