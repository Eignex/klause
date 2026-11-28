package com.eignex.klause.ir.intdomain

import com.eignex.klause.ir.DomainStorageSettings
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.defaultDomainStorageSettings
import com.eignex.util.collections.LongArrayList
import com.eignex.util.collections.storage.PackedBits

// From a non-empty sorted-distinct survivor array, picking the most compact representation: gap-free ⇒
// contiguous; span within bitsetThreshold ⇒ bitset; 2·runs <= survivors ⇒ run list; 2·words <= survivors ⇒
// bitset; else the survivor list, which adopts the array by reference, so callers must not mutate it after.
internal fun intDomainFromSurvivors(
    sv: LongArray,
    storage: DomainStorageSettings = defaultDomainStorageSettings,
): IntDomain {
    val s = sv.size
    val newMin = sv[0]
    val newMax = sv[s - 1]
    // A full-Long span overflows the subtraction; a negative "span" means huge, never compact.
    val span = newMax - newMin + 1
    val spanHuge = span <= 0
    if (!spanHuge && s.toLong() == span) return ContiguousDomain(newMin, newMax, storage)
    if (!spanHuge && span <= storage.bitsetThreshold) {
        val spanI = span.toInt()
        val bits = LongArray((spanI + 63) ushr 6)
        for (i in 0 until s) PackedBits.set(bits, (sv[i] - newMin).toInt())
        return BitsetDomain(newMin, newMax, bits, newMin, storage)
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
        return RunsDomain(newMin, newMax, runs, storage)
    }
    // A scattered domain still prefers a bitset when it is no larger than the survivor array: O(1) contains in place
    // of a binary search. A wide span makes words huge, so it falls through to the span-independent survivor list.
    val words = (span + 63) ushr 6
    if (!spanHuge && 2 * words <= s) {
        val bits = LongArray(words.toInt())
        for (i in 0 until s) PackedBits.set(bits, (sv[i] - newMin).toInt())
        return BitsetDomain(newMin, newMax, bits, newMin, storage)
    }
    return SurvivorsDomain(newMin, newMax, sv, storage)
}

// From a non-empty normalized run list (flattened lo,hi pairs, sorted, strict gaps), with the same selection rule as
// intDomainFromSurvivors.
internal fun intDomainFromRuns(
    runs: LongArrayList,
    storage: DomainStorageSettings = defaultDomainStorageSettings,
): IntDomain {
    val newMin = runs[0]
    val newMax = runs[runs.size - 1]
    if (runs.size == 2) return ContiguousDomain(newMin, newMax, storage) // single run
    // A full-Long span overflows the subtraction; a negative "span" means huge, never compact.
    val span = newMax - newMin + 1
    val spanHuge = span <= 0
    val r = runs.size shr 1
    var s = 0L
    var k = 0
    while (k < runs.size) {
        s += runs[k + 1] - runs[k] + 1
        if (s < 0) return RunsDomain(newMin, newMax, runs.toLongArray(), storage) // member count overflows: huge
        k += 2
    }
    if (!spanHuge && span <= storage.bitsetThreshold) {
        val spanI = span.toInt()
        val bits = LongArray((spanI + 63) ushr 6)
        k = 0
        while (k < runs.size) {
            PackedBits.fillRange(bits, (runs[k] - newMin).toInt(), (runs[k + 1] - newMin + 1).toInt())
            k += 2
        }
        return BitsetDomain(newMin, newMax, bits, newMin, storage)
    }
    if (2 * r <= s) return RunsDomain(newMin, newMax, runs.toLongArray(), storage)
    // Same memory-parity bitset preference as intDomainFromSurvivors. A wide span makes words huge and a huge run
    // makes 2·r <= s true above, so the O(s) survivor list below is only reached for a small scattered set.
    val words = (span + 63) ushr 6
    if (!spanHuge && 2 * words <= s) {
        val bits = LongArray(words.toInt())
        k = 0
        while (k < runs.size) {
            PackedBits.fillRange(bits, (runs[k] - newMin).toInt(), (runs[k + 1] - newMin + 1).toInt())
            k += 2
        }
        return BitsetDomain(newMin, newMax, bits, newMin, storage)
    }
    val sv = LongArray(s.toInt())
    var idx = 0
    k = 0
    while (k < runs.size) {
        var v = runs[k]
        val hi = runs[k + 1]
        while (true) {
            sv[idx++] = v
            if (v == hi) break
            v++
        }
        k += 2
    }
    return SurvivorsDomain(newMin, newMax, sv, storage)
}

// The inverse of intDomainFromRuns. Contiguous and run-list representations answer directly, so a wide sparse
// domain costs its run count rather than its span.
internal fun IntDomain.runBounds(): LongArray = when (this) {
    is ContiguousDomain -> longArrayOf(min, max)
    is RunsDomain -> copyRuns()
    else -> {
        val values = requireNotNull(spanOrNull()) { "domain [$min, $max] holds too many values to list" }
        val runs = LongArrayList()
        values.forEach { value ->
            if (runs.size > 0 && runs.last() == value - 1) {
                runs[runs.size - 1] = value
            } else {
                runs.add(value)
                runs.add(value)
            }
        }
        runs.toLongArray()
    }
}
