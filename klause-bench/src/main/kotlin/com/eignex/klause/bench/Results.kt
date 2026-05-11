package com.eignex.klause.bench

import kotlinx.serialization.Serializable

@Serializable
data class CellResult(
    val backend: String,
    val solveNsMedian: Long,
    val sampleNsMedian: Long,
    val enumNsMedian: Long,
)

@Serializable
data class EntryResult(
    val name: String,
    val expectedSat: Boolean,
    val backends: List<CellResult>,
)

@Serializable
data class BenchResults(
    val timestamp: String,
    val gitSha: String?,
    val entries: List<EntryResult>,
)
