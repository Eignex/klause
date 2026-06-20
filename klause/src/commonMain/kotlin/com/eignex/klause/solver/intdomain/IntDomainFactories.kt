package com.eignex.klause.solver.intdomain

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.util.Bits
import com.eignex.klause.util.IntArrayList

/**
 * Build a domain from a non-empty sorted-distinct survivor array. Picks the most compact rep:
 * gap-free ⇒ contiguous; span `<=` [KlauseConfig.bitsetThreshold] ⇒ bitset; otherwise the run list
 * when it is at least as compact as the survivor list (`2·runs <= survivors`), else the survivor
 * list. The array is adopted by-reference for the survivor rep, so callers must not mutate it after.
 */
internal fun intDomainFromSurvivors(sv: IntArray): IntDomain {
    val s = sv.size
    val newMin = sv[0]
    val newMax = sv[s - 1]
    val span = newMax - newMin + 1
    if (s == span) return ContiguousDomain(newMin, newMax)
    if (span <= KlauseConfig.current.bitsetThreshold) {
        val bits = LongArray((span + 63) ushr 6)
        for (i in 0 until s) Bits.set(bits, sv[i] - newMin)
        return BitsetDomain(newMin, newMax, bits, newMin)
    }
    var r = 1
    for (i in 1 until s) if (sv[i] != sv[i - 1] + 1) r++
    if (2 * r <= s) {
        val runs = IntArray(2 * r)
        var ri = 0
        var runLo = sv[0]
        for (i in 1 until s) {
            if (sv[i] != sv[i - 1] + 1) {
                runs[ri++] = runLo
                runs[ri++] = sv[i - 1]
                runLo = sv[i]
            }
        }
        runs[ri++] = runLo
        runs[ri] = sv[s - 1]
        return RunsDomain(newMin, newMax, runs)
    }
    return SurvivorsDomain(newMin, newMax, sv)
}

/**
 * Build a domain from a non-empty, normalized run list (flattened `lo,hi` pairs, sorted, disjoint
 * with strict gaps). Same selection rule as [intDomainFromSurvivors], expanding to a survivor list
 * only in the scattered (comb) case.
 */
internal fun intDomainFromRuns(runs: IntArrayList): IntDomain {
    val newMin = runs[0]
    val newMax = runs[runs.size - 1]
    if (runs.size == 2) return ContiguousDomain(newMin, newMax) // single run
    val span = newMax - newMin + 1
    val r = runs.size shr 1
    var s = 0
    var k = 0
    while (k < runs.size) {
        s += runs[k + 1] - runs[k] + 1
        k += 2
    }
    if (span <= KlauseConfig.current.bitsetThreshold) {
        val bits = LongArray((span + 63) ushr 6)
        k = 0
        while (k < runs.size) {
            Bits.fillRange(bits, runs[k] - newMin, runs[k + 1] - newMin + 1)
            k += 2
        }
        return BitsetDomain(newMin, newMax, bits, newMin)
    }
    if (2 * r <= s) return RunsDomain(newMin, newMax, runs.toIntArray())
    val sv = IntArray(s)
    var idx = 0
    k = 0
    while (k < runs.size) {
        var v = runs[k]
        val hi = runs[k + 1]
        while (v <= hi) {
            sv[idx++] = v
            v++
        }
        k += 2
    }
    return SurvivorsDomain(newMin, newMax, sv)
}
