package com.eignex.klause.cli

internal class CliDebugStats(private val compilationMillis: () -> Long?) {
    private val startupMs = compilationMillis()

    fun pairs(): List<Pair<String, String>> {
        val totalMs = compilationMillis()
        val supported = startupMs != null && totalMs != null
        return buildList {
            add("runtimeDebug" to "true")
            add("jvmCompilationSupported" to supported.toString())
            if (startupMs != null && totalMs != null) {
                add("jvmCompilationStartupMs" to startupMs.toString())
                add("jvmCompilationMs" to (totalMs - startupMs).toString())
            }
        }
    }
}

internal fun CommonOptions.startDebugStatistics(
    enabledByEnvironment: Boolean,
    create: () -> CliDebugStats = ::platformDebugStats,
) {
    if (!debug && !enabledByEnvironment) return
    statistics = true
    debugStats = create()
}
