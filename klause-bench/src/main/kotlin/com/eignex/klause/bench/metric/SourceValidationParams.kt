package com.eignex.klause.bench.metric

internal class SourceValidationParams(settings: SolverInvocation.Settings) {
    private val prefixes = setOf("source-validation=", "source-validation-gzip=")
    val requested: Boolean = flag(settings, "source-validation=")
    val gzip: Boolean = flag(settings, "source-validation-gzip=")
    val solverSettings: SolverInvocation.Settings = settings.copy(
        params = settings.params.filterNot { param -> prefixes.any { param.startsWith(it) } },
    )

    init {
        require(!gzip || requested) { "source-validation-gzip requires source-validation=true" }
    }

    private fun flag(settings: SolverInvocation.Settings, prefix: String): Boolean {
        val value = settings.params.lastOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
        return value?.toBooleanStrictOrNull() ?: run {
            require(value == null) { "${prefix.removeSuffix("=")} expects true or false, got `$value`" }
            false
        }
    }
}
