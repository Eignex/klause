package com.eignex.klause.bench.metric

import java.io.Writer
import java.util.concurrent.TimeUnit

/**
 * Runs a reference solver shipped as a native binary (cvc5, kissat, HiGHS): its own [ProcessBuilder], the instance on
 * stdin when [input] is set, and a watchdog that force-kills a run that ignores its own time limit, so one solve can
 * never hang a sweep. Returns the solver's stdout and the wall-clock elapsed.
 */
internal object NativeReference {
    private const val EXIT_WAIT_MS = 5_000L
    private const val GRACE_MS = 20_000L

    /** Whether [binary] runs at all ([versionArg] exits 0). */
    fun available(binary: String, versionArg: String = "--version"): Boolean = runCatching {
        val p = ProcessBuilder(binary, versionArg)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        p.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS) && p.exitValue() == 0
    }.getOrElse { false }

    fun exec(cmd: List<String>, timeoutMillis: Long, input: ((Writer) -> Unit)? = null): Pair<String, Long> {
        val startNanos = System.nanoTime()
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        // Stdin on its own thread: a solver can print while it reads, and a full stdout pipe would block both.
        val writer = Thread {
            runCatching { proc.outputStream.bufferedWriter().use { out -> input?.invoke(out) } }
        }.apply {
            isDaemon = true
            start()
        }
        val watchdog = Thread {
            runCatching {
                if (!proc.waitFor(
                        timeoutMillis * 2 + GRACE_MS,
                        TimeUnit.MILLISECONDS,
                    )
                ) {
                    proc.destroyForcibly()
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        val stdout = proc.inputStream.bufferedReader().readText()
        proc.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)
        watchdog.interrupt()
        writer.interrupt()
        return stdout to (System.nanoTime() - startNanos) / NANOS_PER_MS
    }

    private const val NANOS_PER_MS = 1_000_000L
}
