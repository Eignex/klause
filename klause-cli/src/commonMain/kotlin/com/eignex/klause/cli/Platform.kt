package com.eignex.klause.cli

import com.eignex.klause.config.KlauseConfig
import kotlin.time.TimeSource

// Platform seams for the CLI: the complete list of what differs between the JVM
// distribution and the native executables. Everything else is common code.

/** Read a CLI tuning knob. JVM: system property, then the env var spelled as the dotted
 *  name uppercased with `.` mapped to `_` (e.g. `klause.fzn.engine` becomes
 *  `KLAUSE_FZN_ENGINE`). Native: the env var only. */
internal expect fun cliProp(name: String): String?

/** Load core [KlauseConfig] from the process environment and install it as the ambient
 *  config. JVM: `installKlauseConfigFromEnv` (system properties win over env vars).
 *  Native: env vars only. */
internal expect fun installCliConfig(): KlauseConfig

internal expect fun errPrintln(message: String)

internal expect fun exitCli(code: Int): Nothing

internal expect fun readTextFile(path: String): String

internal expect fun isDirectory(path: String): Boolean

/** All regular files under [root], recursively; just `[root]` when it is a regular file. */
internal expect fun walkFiles(root: String): List<String>

/** Bridge the suspend Portfolio API into the synchronous CLI (`runBlocking` exists on
 *  both JVM and native but is not in the common coroutines surface). */
internal expect fun <T> runBlockingBridge(block: suspend () -> T): T

private val timeOrigin = TimeSource.Monotonic.markNow()

/** Monotonic clock for deadlines — only ever compared against itself. */
internal fun nowMillis(): Long = timeOrigin.elapsedNow().inWholeMilliseconds

internal fun fileName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

internal fun fileExtension(path: String): String = fileName(path).substringAfterLast('.', "")
