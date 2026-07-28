package com.eignex.klause.solver.intdomain

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.util.Bits
import com.eignex.klause.util.LongArrayList

/**
 * Build a domain from a non-empty sorted-distinct survivor array. Picks the most compact rep:
 * gap-free ⇒ contiguous; span `<=` [KlauseConfig.bitsetThreshold] ⇒ bitset; the run list when
 * `2·runs <= survivors`; a bitset when no larger than the survivor list (`2·words <= survivors`), whose
 * O(1) `contains` beats the survivor list's binary search; else the survivor list. The array is adopted
 * by-reference for the survivor rep, so callers must not mutate it after.
 */
internal fun intDomainFromSurvivors(sv: LongArray): IntDomain {
    val s = sv.size
    val newMin = sv[0]
    val newMax = sv[s - 1]
    // A full-Long span overflows the subtraction; a negative "span" means huge, never compact.
    val span = newMax - newMin + 1
    val spanHuge = span <= 0
    if (!spanHuge && s.toLong() == span) return ContiguousDomain(newMin, newMax)
    if (!spanHuge && span <= KlauseConfig.current.bitsetThreshold) {
        val spanI = span.toInt()
        val bits = LongArray((spanI + 63) ushr 6)
        for (i in 0 until s) Bits.set(bits, (sv[i] - newMin).toInt())
        return BitsetDomain(newMin, newMax, bits, newMin)
    }
    var r = 1
    for (i in 1 until s) if (sv[i] != sv[i - 1] + 1) r++
    if (2 * r <= s) {
        val runs = LongArray(2 * r)
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
    // A scattered domain the run list can't compact still prefers a bitset when it is no larger than the
    // survivor array: O(1) `contains` in place of a binary search, at memory parity. A wide span makes
    // `words` huge, so `2·words <= s` is false and we fall through to the survivor list (span-independent).
    val words = (span + 63) ushr 6
    if (!spanHuge && 2 * words <= s) {
        val bits = LongArray(words.toInt())
        for (i in 0 until s) Bits.set(bits, (sv[i] - newMin).toInt())
        return BitsetDomain(newMin, newMax, bits, newMin)
    }
    return SurvivorsDomain(newMin, newMax, sv)
}

/**
 * Build a domain from a non-empty, normalized run list (flattened `lo,hi` pairs, sorted, disjoint
 * with strict gaps). Same selection rule as [intDomainFromSurvivors], expanding to a survivor list
 * only in the scattered (comb) case.
 */
internal fun intDomainFromRuns(runs: LongArrayList): IntDomain {
    val newMin = runs[0]
    val newMax = runs[runs.size - 1]
    if (runs.size == 2) return ContiguousDomain(newMin, newMax) // single run
    // A full-Long span overflows the subtraction; a negative "span" means huge, never compact.
    val span = newMax - newMin + 1
    val spanHuge = span <= 0
    val r = runs.size shr 1
    var s = 0L
    var k = 0
    while (k < runs.size) {
        s += runs[k + 1] - runs[k] + 1
        if (s < 0) return RunsDomain(newMin, newMax, runs.toLongArray()) // member count overflows: huge
        k += 2
    }
    if (!spanHuge && span <= KlauseConfig.current.bitsetThreshold) {
        val spanI = span.toInt()
        val bits = LongArray((spanI + 63) ushr 6)
        k = 0
        while (k < runs.size) {
            Bits.fillRange(bits, (runs[k] - newMin).toInt(), (runs[k + 1] - newMin + 1).toInt())
            k += 2
        }
        return BitsetDomain(newMin, newMax, bits, newMin)
    }
    if (2 * r <= s) return RunsDomain(newMin, newMax, runs.toLongArray())
    // Same memory-parity bitset preference as [intDomainFromSurvivors], filled straight from the runs.
    // A wide span makes `words` huge so this is skipped, and a huge run makes `2·r <= s` true above, so
    // the O(s) survivor materialisation below is only reached for a genuinely small scattered set.
    val words = (span + 63) ushr 6
    if (!spanHuge && 2 * words <= s) {
        val bits = LongArray(words.toInt())
        k = 0
        while (k < runs.size) {
            Bits.fillRange(bits, (runs[k] - newMin).toInt(), (runs[k + 1] - newMin + 1).toInt())
            k += 2
        }
        return BitsetDomain(newMin, newMax, bits, newMin)
    }
    val sv = LongArray(s.toInt())
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
