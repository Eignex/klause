package com.eignex.klause.bench.source

import java.io.File

internal enum class SourceStaging(val key: String) {
    CACHE("cache"),
    TEMPORARY("temporary");

    val directory: File
        get() = when (this) {
            CACHE -> CorpusFiles.stagingRoot
            TEMPORARY -> File(System.getProperty("java.io.tmpdir"))
        }

    companion object {
        fun parse(value: String): SourceStaging =
            requireNotNull(entries.find { it.key == value }) { "Unknown source staging: $value" }
    }
}
