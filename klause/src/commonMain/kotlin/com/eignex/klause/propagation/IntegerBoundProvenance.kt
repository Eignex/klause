package com.eignex.klause.propagation

import com.eignex.klause.util.subExact

private const val INTEGER_ENDPOINT_WALK = 64

internal fun PropagationState.rawIntegerEndpointSupported(
    variable: Int,
    lower: Boolean,
    requested: Long,
    trail: Int,
): Boolean {
    val live = intDomains[variable]
    if ((lower && requested < live.min) || (!lower && requested > live.max)) return false
    val prior = domainAt(variable, trail)
    if (prior.holeCount == 0L) return true
    // Keep clipped historical exclusions conservative without scanning an unbounded sparse tail.
    val width = if (lower) subExact(requested, prior.min) else subExact(prior.max, requested)
    if (width <= 0L) return true
    if (width > INTEGER_ENDPOINT_WALK) return false
    repeat(width.toInt()) { offset ->
        checkFiniteWork()
        val value = if (lower) prior.min + offset else prior.max - offset
        if (!inDomainAt(variable, value, trail, prior)) return false
    }
    return true
}
