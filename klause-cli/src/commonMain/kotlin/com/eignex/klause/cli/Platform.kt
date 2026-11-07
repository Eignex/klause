package com.eignex.klause.cli

import com.eignex.klause.config.KlauseConfig
import com.eignex.util.platform.compilationTimeMillis
import com.eignex.util.process.processProperty

internal fun platformDebugStats(): CliDebugStats = CliDebugStats(::compilationTimeMillis)

internal fun installCliConfig(): KlauseConfig =
    KlauseConfig.fromProps(lookup = ::processProperty).also { KlauseConfig.current = it }

internal expect fun nowMillis(): Long

internal fun fileName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

private val compressionExtensions = setOf("xz", "gz", "bz2", "zst")

internal fun compressionExtension(path: String): String? =
    fileName(path).substringAfterLast('.', "").lowercase().takeIf { it in compressionExtensions }

internal fun fileExtension(path: String): String {
    val name = fileName(path)
    val last = name.substringAfterLast('.', "")
    return if (last.lowercase() in compressionExtensions) {
        name.substringBeforeLast('.').substringAfterLast('.', "")
    } else {
        last
    }
}
