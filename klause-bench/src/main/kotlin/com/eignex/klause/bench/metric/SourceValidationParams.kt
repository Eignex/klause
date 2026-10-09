package com.eignex.klause.bench.metric

internal class SourceValidationParams(settings: SolverInvocation.Settings) {
    private val prefix = "source-validation="
    private val value = settings.params.lastOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
    val requested: Boolean = value?.toBooleanStrictOrNull() ?: run {
        require(value == null) { "source-validation expects true or false, got `$value`" }
        false
    }
    val solverSettings: SolverInvocation.Settings = settings.copy(
        params = settings.params.filterNot { it.startsWith(prefix) },
    )
}
