package com.eignex.klause.bench.profile

import com.eignex.klause.cli.CliSourceInspection
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.util.Cancellation
import kotlin.time.TimeSource

internal object ArithmeticLearningSourcePreparation {
    fun prepare(
        bytes: ByteArray,
        path: String,
        cancellation: Cancellation,
        recipe: String,
        timeLimitMs: Long,
    ): CliSourceInspection.Prepared? {
        require(bytes.size <= 1_048_576 && recipe in setOf("automatic", "lp-default"))
        if (cancellation()) return null
        require(Mps.parse(bytes.decodeToString()).variables.size <= 4096) { "declared source column limit" }
        val deadline = requireNotNull(cancellation.deadline()) { "source inspection requires a containing deadline" }
        val remaining = (deadline - TimeSource.Monotonic.markNow()).inWholeMilliseconds.coerceIn(0L, timeLimitMs)
        val prepared = CliSourceInspection.prepareMps(path, timeLimitMs, remaining, recipe) ?: return null
        require(prepared.original.numIntVars + prepared.original.numBoolVars + prepared.original.numRealVars <= 4096) {
            "working source column limit"
        }
        return prepared.takeUnless { cancellation() }
    }
}
