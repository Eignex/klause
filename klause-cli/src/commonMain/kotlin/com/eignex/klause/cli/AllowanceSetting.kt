package com.eignex.klause.cli

internal sealed interface AllowanceSetting {
    data object Automatic : AllowanceSetting
    data object Unlimited : AllowanceSetting
    data class Remaining(val units: Long) : AllowanceSetting
}

internal fun takeAllowanceSetting(params: MutableList<String>, key: String): AllowanceSetting {
    val entries = params.filter { it.startsWith("$key=") }
    val settings = entries.map { entry ->
        val raw = entry.substringAfter('=')
        when (raw.lowercase()) {
            "auto" -> AllowanceSetting.Automatic
            "unlimited" -> AllowanceSetting.Unlimited
            else -> {
                val units = raw.toLongOrNull()
                if (units == null || units < 0L) {
                    usageError("engine param `$key` expects auto, unlimited or a non-negative integer, got `$raw`")
                }
                AllowanceSetting.Remaining(units)
            }
        }
    }.distinct()
    if (settings.size > 1) usageError("engine param `$key` cannot be set to conflicting values")
    params.removeAll(entries)
    return settings.singleOrNull() ?: AllowanceSetting.Automatic
}
