package com.eignex.klause.localsearch

import com.eignex.klause.factor.global.Inverse
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation

internal fun inverseAssignmentView(problem: Problem, stop: Cancellation): Problem? {
    if (problem.numFactors > 4096 || stop()) return null
    val channels = ArrayList<Inverse>()
    for (factor in problem.factors) {
        if (stop()) return null
        stop.charge(1)
        if (factor is Inverse) channels += factor
    }
    if (channels.isEmpty() || channels.size > 16 || stop()) return null
    val claimed = HashSet<Int>()
    for (channel in channels) {
        if (channel.f.size > 64) return null
        for (variable in channel.f + channel.g) {
            if (stop() || variable !in 0 until problem.numIntVars || !claimed.add(variable)) return null
            stop.charge(1)
        }
    }
    val factors = problem.factors.copyOf()
    for (index in factors.indices) {
        if (stop()) return null
        stop.charge(1)
        val channel = factors[index] as? Inverse ?: continue
        factors[index] = Inverse(channel.g, channel.f, channel.gOffset, channel.fOffset)
    }
    return if (stop()) null else problem.withFactors(factors, problem.impliedFactorMask)
}
