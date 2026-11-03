package com.eignex.klause.bench.source

import java.io.File

internal enum class SourceStaging(val key: String) {
    CACHE("cache"),
    TEMPORARY("temporary"),
    CLI("cli"),
    CLI_JNI("cli-jni");

    val directory: File
        get() = when (this) {
            CACHE -> CorpusFiles.stagingRoot
            TEMPORARY -> File(System.getProperty("java.io.tmpdir"))
            CLI, CLI_JNI -> error("CLI delivery does not stage a temporary source")
        }

    companion object {
        fun parse(value: String): SourceStaging =
            requireNotNull(entries.find { it.key == value }) { "Unknown source staging: $value" }
    }
}
